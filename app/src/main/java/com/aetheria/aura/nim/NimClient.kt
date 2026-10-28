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
 * Minimal NIM API client - single model, SSE streaming, no tiers/health checks
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
        .build()

    private val MEDIA_TYPE = "application/json".toMediaType()
    private val ENDPOINT = "https://integrate.api.nvidia.com/v1/chat/completions"
    private val MODEL = "nvidia/nemotron-3.5-lightning-30b-a3b"

    companion object {
        const val TAG = "NimClient"
        const val STREAM_TIMEOUT_MS = 60_000L
    }

    /**
     * Send a streaming chat request
     * @param messages Chat history
     * @param onToken Callback for each token received
     * @param onComplete Callback when stream ends (success or failure)
     * @param onError Callback for errors
     */
    fun streamChat(
        messages: List<NimChatMessage>,
        onToken: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        val startTime = System.currentTimeMillis()

        val requestBody = buildRequestBody(messages)
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(requestBody)
            .addHeader("Authorization", "Bearer ${apiKeyProvider()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .build()

        val streamStartTime = System.currentTimeMillis()
        val eventListener = NimEventListener(
            model = MODEL,
            streamStartTime = streamStartTime,
            onToken = onToken,
            onComplete = { success ->
                val totalTime = System.currentTimeMillis() - startTime
                Log.d(TAG, "NimClient stream complete: success=$success totalTime=${totalTime}ms")
                onComplete(success)
            },
            onError = { error ->
                val totalTime = System.currentTimeMillis() - startTime
                Log.e(TAG, "NimClient error after ${totalTime}ms: $error")
                onError(error)
            }
        )

        val eventSource = EventSources.createFactory(client).newEventSource(request, eventListener)

        // Timeout guard
        Thread {
            Thread.sleep(STREAM_TIMEOUT_MS)
            eventSource.cancel()
        }.start()
    }

    /**
     * Non-streaming health check (returns true if model responds)
     */
    suspend fun healthCheck(): Boolean {
        return try {
            val key = apiKeyProvider()
            val url = java.net.URL("https://integrate.api.nvidia.com/v1/chat/completions")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doOutput = true

            val requestBody = """{"model":"$MODEL","messages":[{"role":"user","content":"hi"}],"max_tokens":1,"stream":false}"""
            val writer = java.io.OutputStreamWriter(connection.outputStream, java.nio.charset.StandardCharsets.UTF_8)
            writer.write(requestBody)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                reader.readLine()
                reader.close()
                true
            } else {
                Log.w(TAG, "Health check HTTP $responseCode for $MODEL")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check exception for $MODEL: ${e.message}")
            false
        }
    }

    private fun buildRequestBody(messages: List<NimChatMessage>): okhttp3.RequestBody {
        val request = NimChatRequest(
            model = MODEL,
            messages = messages
        )
        val json = gson.toJson(request)
        return json.toRequestBody(MEDIA_TYPE)
    }

    /**
     * EventSourceListener as a normal class - no local functions with modifiers
     */
    private inner class NimEventListener(
        private val model: String,
        private val streamStartTime: Long,
        private val onToken: (String) -> Unit,
        private val onComplete: (Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : EventSourceListener() {

        private var tokenCount = 0
        private var ttfbLogged = false

        override fun onOpen(eventSource: EventSource, response: Response) {
            Log.d(TAG, "SSE connected for $model")
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
            Log.e(TAG, "SSE failure for $model: $errorMsg, httpCode=$responseCode, body=$body", t)
            onError("$errorMsg (http=$responseCode)")
        }
    }
}