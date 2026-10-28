package com.aetheria.aura.nim

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * NIM API Client with OkHttp SSE streaming and tiered model fallback
 */
class NimClient(
    private val context: Context,
    private val healthService: ModelHealthService,
    private val apiKeyProvider: () -> String
) {

    private val gson = com.google.gson.Gson()
    private val parser = com.google.gson.JsonParser()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val MEDIA_TYPE = "application/json".toMediaType()
    private val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"

    // Circuit breakers per model
    private val circuitBreakers = java.util.concurrent.ConcurrentHashMap<String, ModelCircuitBreaker>()

    private fun getCircuitBreaker(modelId: String): ModelCircuitBreaker {
        return circuitBreakers.getOrPut(modelId) { ModelCircuitBreaker(modelId) }
    }

    companion object {
        const val TAG = "NimClient"
        const val STREAM_TIMEOUT_MS = 60_000L
    }

    /**
     * Send a streaming chat request with tiered fallback
     */
    fun streamChat(
        messages: List<ChatMessage>,
        tier: Tier,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val models = healthService.getHealthyModels(tier)
        if (models.isEmpty()) {
            onError("No healthy models available for tier $tier")
            return
        }

        Log.d(TAG, "Starting stream for tier $tier with models: $models")
        attemptModels(models.toMutableList(), messages, onToken, onComplete, onError, 0)
    }

    private fun attemptModels(
        remainingModels: MutableList<String>,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit,
        attemptIndex: Int
    ) {
        if (remainingModels.isEmpty()) {
            onError("All models in tier exhausted")
            return
        }

        val model = remainingModels.removeAt(0)
        val circuit = getCircuitBreaker(model)

        if (!circuit.canAttempt()) {
            Log.w(TAG, "Circuit breaker OPEN for $model, skipping")
            attemptModels(remainingModels, messages, onToken, onComplete, onError, attemptIndex + 1)
            return
        }

        Log.d(TAG, "Attempting model: $model (attempt ${attemptIndex + 1})")
        val startTime = System.currentTimeMillis()

        val requestBody = buildRequestBody(model, messages)
        val request = okhttp3.Request.Builder()
            .url(ENDPOINT)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${apiKeyProvider()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        val streamStartTime = System.currentTimeMillis()

        val eventListener = object : okhttp3.sse.EventSourceListener() {
            private val tokenBuffer = StringBuilder()
            private var tokenCount = 0
            private var ttfbLogged = false

            override fun onOpen(eventSource: okhttp3.sse.EventSource, response: okhttp3.Response) {
                Log.d(TAG, "SSE connected for $model")
            }

            override fun onEvent(eventSource: okhttp3.sse.EventSource, id: String?, eventType: String?, data: String) {
                if (data == "[DONE]") return

                try {
                    val json = com.google.gson.JsonParser.parseString(data).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size > 0) {
                        val delta = choices[0].asJsonObject.get("delta")
                        if (delta != null && delta.isJsonObject) {
                            val content = delta.asJsonObject.get("content")
                            if (content != null && content.isJsonPrimitive) {
                                val token = content.asJsonPrimitive.content
                                if (token.isNotEmpty()) {
                                    if (!ttfbLogged) {
                                        val ttfb = System.currentTimeMillis() - streamStartTime
                                        Log.d(TAG, "TTFB for $model: ${ttfb}ms")
                                        ttfbLogged = true
                                    }
                                    tokenBuffer.append(token)
                                    tokenCount++
                                    onToken(token)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE chunk: $e")
                    }
                }

            override fun onClosed(eventSource: okhttp3.sse.EventSource) {
                val totalTime = System.currentTimeMillis() - streamStartTime
                Log.d(TAG, "SSE closed for $model: ${tokenCount} tokens in ${totalTime}ms")
                onComplete(true)
            }

            override fun onFailure(eventSource: okhttp3.sse.EventSource, t: Throwable, response: okhttp3.Response?) {
                val errorMsg = t.message ?: "Stream failed"
                Log.e(TAG, "SSE failure for $model: $errorMsg", t)
                getCircuitBreaker(model).recordFailure()
                onModelFailure(model, remainingModels, messages, onToken, onComplete, onError, errorMsg)
            }
        }

        val streamStartTime = System.currentTimeMillis()
        val eventSource = okhttp3.sse.EventSources.createFactory(client).newEventSource(request, eventListener)

        // Timeout guard
        Thread {
            Thread.sleep(STREAM_TIMEOUT_MS)
            eventSource.cancel()
        }.start()
    }

    private fun buildRequest(messages: List<ChatMessage>, model: String): String {
        val json = com.google.gson.JsonObject()
        json.addProperty("model", model)
        json.addProperty("stream", true)
        json.addProperty("max_tokens", 2048)
        json.addProperty("temperature", 0.7)

        val messagesArray = com.google.gson.JsonArray()
        for (msg in messages) {
            val msgObj = com.google.gson.JsonObject()
            msgObj.addProperty("role", msg.role)
            msgObj.addProperty("content", msg.content)
            messagesArray.add(msgObj)
        }
        json.add("messages", messagesArray)

        return com.google.gson.Gson().toJson(json)
    }

    private fun buildRequestBody(model: String, messages: List<ChatMessage>): okhttp3.RequestBody {
        val json = buildRequest(model, messages)
        return json.toRequestBody("application/json".toMediaType())
    }

    private fun onModelFailure(
        failedModel: String,
        remainingModels: MutableList<String>,
        messages: List<ChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit,
        error: String
    ) {
        Log.w(TAG, "Model $failedModel failed: $error, trying next...")
        getCircuitBreaker(failedModel).recordFailure()

        if (remainingModels.isNotEmpty()) {
            attemptModels(remainingModels, messages, onToken, onComplete, onError, 0)
        } else {
            onError("All models exhausted: last error: $error")
        }
    }

    /**
     * Get healthy models for a tier
     */
    fun getHealthyModels(tier: Tier): List<String> {
        return when (tier) {
            Tier.INSTANT -> healthService.getHealthyModelsForTier(ModelHealthService.Tier.INSTANT)
            Tier.THINKING -> healthService.getHealthyModelsForTier(ModelHealthService.Tier.THINKING)
            Tier.CONTEMPLATING -> healthService.getHealthyModelsForTier(ModelHealthService.Tier.CONTEMPLATING)
            Tier.VISION -> healthService.getHealthyModelsForTier(ModelHealthService.Tier.VISION)
        }
    }

    enum class Tier {
        INSTANT, THINKING, CONTEMPLATING, VISION
    }

    data class ChatMessage(
        val role: String,
        val content: String
    )
}