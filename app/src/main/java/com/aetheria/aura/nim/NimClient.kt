package com.aetheria.aura.nim

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * NIM API client with tiered fallback: Instant -> Thinking -> Vision
 * Includes circuit breaker pattern and model health tracking.
 * Models from https://integrate.api.nvidia.com/v1/models
 */
class NimClient(
    private val context: Context,
    private val apiKeyProvider: () -> String
) {

    private val gson = Gson()
    private val parser = JsonParser()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("Authorization", "Bearer ${apiKeyProvider()}")
                .build()
            chain.proceed(request)
        }
        .build()

    private val MEDIA_TYPE = "application/json".toMediaType()
    private val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"

    // Model health store for circuit breaker
    private val healthStore = ModelHealthStore(context)

    // Tiered models (ordered by priority within tier)
    // Instant: 0.30s TTB, Thinking: 2.34s TTB, Vision: ~3s TTB
    enum class Tier { INSTANT, THINKING, VISION }

    private val INSTANT_MODELS = listOf(
        "nvidia/nemotron-3.5-lightning-30b-a3b",    // 0.30s TTB
        "nvidia/nemotron-3.5-bolt-e4b"              // fallback
    )
    private val THINKING_MODELS = listOf(
        "nvidia/nemotron-3-ultra-550b-a55b",        // 2.34s TTB
        "nvidia/nemotron-4-340b-instruct"           // fallback
    )
    private val VISION_MODELS = listOf(
        "meta/llama-3.2-11b-vision-instruct",       // vision primary
        "microsoft/phi-3-vision-128k-instruct",     // vision fallback
        "nvidia/neva-22b"                           // vision fallback
    )

    companion object {
        const val TAG = "NimClient"
        const val STREAM_TIMEOUT_MS = 60_000L
        const val HEALTH_CHECK_TIMEOUT_MS = 5_000L
    }

    /**
     * Send a streaming chat request with tiered fallback and circuit breaker
     * @param messages Chat history
     * @param tier Tier to use (INSTANT, THINKING, VISION)
     * @param onToken Callback for each token received
     * @param onComplete Callback when stream ends (success or failure)
     * @param onError Callback for errors
     */
    fun streamChat(
        messages: List<NimChatMessage>,
        tier: Tier = Tier.INSTANT,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val models = when (tier) {
            Tier.INSTANT -> INSTANT_MODELS
            Tier.THINKING -> THINKING_MODELS
            Tier.VISION -> VISION_MODELS
        }
        
        // Filter out models with open circuit
        val availableModels = healthStore.getAvailableModels(models)
        if (availableModels.isEmpty()) {
            Log.w(TAG, "All models in ${tier.name} tier have open circuits, falling back to next tier")
            fallbackToNextTier(tier, messages, onToken, onComplete, onError)
            return
        }
        
        Log.d(TAG, "streamChat: tier=$tier availableModels=$availableModels")
        tryModels(availableModels, messages, tier, 0, System.currentTimeMillis(), onToken, onComplete, onError)
    }

    /** Fallback to next tier when current tier is exhausted or all circuits open */
    private fun fallbackToNextTier(
        currentTier: Tier,
        messages: List<NimChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val nextTier = when (currentTier) {
            Tier.INSTANT -> Tier.THINKING
            Tier.THINKING -> Tier.VISION
            Tier.VISION -> null
        }
        if (nextTier != null) {
            Log.d(TAG, "Falling back to ${nextTier.name} tier")
            streamChat(messages, nextTier, onToken, onComplete, onError)
        } else {
            onError("All tiers exhausted or circuits open")
        }
    }

    private fun tryModels(
        models: List<String>,
        messages: List<NimChatMessage>,
        tier: Tier,
        index: Int,
        overallStartTime: Long,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "tryModels: tier=$tier models=$models index=$index")
        if (index >= models.size) {
            Log.e(TAG, "All ${tier.name} models exhausted")
            onError("All ${tier.name} models failed")
            fallbackToNextTier(tier, messages, onToken, onComplete, onError)
            return
        }

        val model = models[index]
        val startTime = System.currentTimeMillis()

        val requestBody = buildRequestBody(model, messages)
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${apiKeyProvider()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        // Debug: log request headers
        Log.d(TAG, "Request headers: ${request.headers}")

        val streamStartTime = System.currentTimeMillis()
        val eventListener = NimEventListener(
            model = model,
            streamStartTime = streamStartTime,
            tier = tier,
            remainingModels = models.drop(index + 1),
            onToken = onToken,
            onComplete = { success ->
                val totalTime = System.currentTimeMillis() - overallStartTime
                val health = healthStore.get(model)
                if (success) {
                    health.recordSuccess(totalTime)
                } else {
                    health.recordFailure()
                }
                healthStore.update(health)
                Log.d(TAG, "NimClient stream complete: success=$success tier=$tier model=$model totalTime=${totalTime}ms")
                onComplete(success)
            },
            onError = { error ->
                val totalTime = System.currentTimeMillis() - overallStartTime
                val health = healthStore.get(model)
                health.recordFailure()
                healthStore.update(health)
                Log.w(TAG, "Model $model failed after ${totalTime}ms: $error")
                // Try next model in same tier
                tryModels(models, messages, tier, index + 1, overallStartTime, onToken, onComplete, onError)
            }
        )

        val eventSource = EventSources.createFactory(client).newEventSource(request, eventListener)

        // Timeout guard
        Thread {
            Thread.sleep(STREAM_TIMEOUT_MS)
            eventSource.cancel()
        }.start()
    }

    private fun buildRequestBody(model: String, messages: List<NimChatMessage>): okhttp3.RequestBody {
        val request = NimChatRequest(
            model = model,
            messages = messages,
            stream = true
        )
        val json = gson.toJson(request)
        return json.toRequestBody(MEDIA_TYPE)
    }

    /**
     * Health check for a single model - sends a minimal request with 5s timeout
     * @param model Model to check
     * @param callback true if healthy, false if failed
     */
    fun healthCheck(model: String, callback: (Boolean) -> Unit) {
        Log.d(TAG, "Health check for $model")
        val requestBody = buildRequestBody(model, listOf(NimChatMessage("user", "hi")))
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${apiKeyProvider()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        val eventListener = object : EventSourceListener() {
            private var gotResponse = false

            override fun onOpen(eventSource: EventSource, response: Response) {
                if (response.code == 200) {
                    gotResponse = true
                }
            }

            override fun onEvent(eventSource: EventSource, id: String?, eventType: String?, data: String) {
                if (data != "[DONE]") {
                    gotResponse = true
                    eventSource.cancel()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                val health = healthStore.get(model)
                if (gotResponse) {
                    health.recordHealthCheckPass()
                } else {
                    health.recordHealthCheckFail()
                }
                healthStore.update(health)
                callback(gotResponse)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val health = healthStore.get(model)
                health.recordHealthCheckFail()
                healthStore.update(health)
                Log.w(TAG, "Health check failed for $model: ${t?.message ?: "unknown"}")
                callback(false)
            }
        }

        val eventSource = EventSources.createFactory(client).newEventSource(request, eventListener)

        // 5s timeout for health check
        Thread {
            Thread.sleep(HEALTH_CHECK_TIMEOUT_MS)
            eventSource.cancel()
        }.start()
    }

    /**
     * Get health status for all models
     */
    fun getAllModelHealth(): List<ModelHealth> = healthStore.getAll()

    /**
     * Reset circuit breaker for a model (e.g., after manual intervention)
     */
    fun resetModelHealth(model: String) {
        healthStore.reset(model)
    }

    /**
     * EventSourceListener as a normal class - no local functions with modifiers
     */
    private inner class NimEventListener(
        private val model: String,
        private val streamStartTime: Long,
        private val tier: Tier,
        private val remainingModels: List<String>,
        private val onToken: (String) -> Unit,
        private val onComplete: (Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : EventSourceListener() {

        private var tokenCount = 0
        private var ttfbLogged = false

        override fun onOpen(eventSource: EventSource, response: Response) {
            Log.d(TAG, "SSE connected for $model (tier=$tier) responseCode=${response.code}")
        }

        override fun onEvent(eventSource: EventSource, id: String?, eventType: String?, data: String) {
            if (data == "[DONE]") return

            try {
                val json = JsonParser.parseString(data).asJsonObject
                val choices = json.getAsJsonArray("choices")
                if (choices != null && choices.size() > 0) {
                    val delta = choices[0].asJsonObject.get("delta")
                    if (delta != null && delta.isJsonObject) {
                        val deltaObj = delta.asJsonObject
                        // NIM returns reasoning_content for thinking models, content for regular
                        var token = ""
                        val reasoningContent = deltaObj.get("reasoning_content")
                        if (reasoningContent != null && reasoningContent.isJsonPrimitive) {
                            token = reasoningContent.asJsonPrimitive.getAsString()
                        } else {
                            val content = deltaObj.get("content")
                            if (content != null && content.isJsonPrimitive) {
                                token = content.asJsonPrimitive.getAsString()
                            }
                        }
                        if (token.isNotEmpty()) {
                            if (!ttfbLogged) {
                                val ttfb = System.currentTimeMillis() - streamStartTime
                                Log.d(TAG, "TTFB for $model: ${ttfb}ms")
                                ttfbLogged = true
                            }
                            tokenCount++
                            onToken(token)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse SSE chunk: $e")
            }
        }

        override fun onClosed(eventSource: EventSource) {
            val totalTime = System.currentTimeMillis() - streamStartTime
            Log.d(TAG, "SSE closed for $model: ${tokenCount} tokens in ${totalTime}ms")
            onComplete(true)
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            val errorMsg = t?.message ?: "Stream failed"
            val responseCode = response?.code ?: 0
            val body = try { response?.body?.string() } catch (e: Exception) { "" }
            Log.e(TAG, "SSE failure for $model: errorMsg=$errorMsg, httpCode=$responseCode, body=$body, throwable=$t", t)
            // Don't call onError here - tryModels handles fallback
            onError("$errorMsg (http=$responseCode)")
        }
    }

    // Use top-level data classes from NimModels.kt
// NimChatMessage, NimChatRequest, NimChatResponse, NimChoice, NimDelta
}