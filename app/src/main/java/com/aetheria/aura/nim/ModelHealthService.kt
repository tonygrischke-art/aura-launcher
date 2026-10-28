package com.aetheria.aura.nim

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Model health check service - runs daily health checks on all models
 */
class ModelHealthService(
    private val context: Context,
    private val healthStore: ModelHealthStore,
    private val apiKeyProvider: () -> String
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = com.google.gson.Gson()

    // Known free models per tier (curated from NIM)
    private val INSTANT_MODELS = listOf("nvidia/nemotron-3.5-lightning-30b-a3b")
    private val THINKING_MODELS = listOf("nvidia/nemotron-3-ultra-550b-a55b")
    private val CONTEMPLATING_MODELS = listOf("nvidia/nemotron-3-ultra-550b-a55b")
    private val VISION_MODELS = listOf(
        "meta/llama-3.2-11b-vision-instruct",
        "nvidia/neva-22b",
        "meta/llama-3.2-90b-vision-instruct"
    )

    private val ALL_MODELS = (INSTANT_MODELS + THINKING_MODELS + CONTEMPLATING_MODELS + VISION_MODELS).distinct()

    companion object {
        const val CHECK_TIMEOUT_MS = 5000L
        const val MAX_CONCURRENT_CHECKS = 3
        const val TAG = "ModelHealthService"
    }

    // Circuit breakers per model
    private val circuitBreakers = java.util.concurrent.ConcurrentHashMap<String, ModelCircuitBreaker>()

    fun getCircuitBreaker(modelId: String): ModelCircuitBreaker {
        return java.util.concurrent.ConcurrentHashMap<String, ModelCircuitBreaker>().getOrPut(modelId) { ModelCircuitBreaker(modelId) }
    }

    init {
        // Check if we need to run health checks (stale > 24h)
        scope.launch {
            checkIfStaleAndRun()
        }
    }

    fun runHealthCheckNow() {
        scope.launch { checkAllModels() }
    }

    private suspend fun checkIfStaleAndRun() {
        val prefs = context.getSharedPreferences("model_health", Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong("last_health_check", 0)
        val now = System.currentTimeMillis()
        if (now - lastCheck > 24 * 60 * 60 * 1000) { // 24 hours
            checkAllModels()
        }
    }

    fun checkAllModels() {
        Log.d(TAG, "Starting health check for ${ALL_MODELS.size} models")
        val startTime = System.currentTimeMillis()

        // Process in batches to avoid overwhelming the API
        ALL_MODELS.chunked(MAX_CONCURRENT_CHECKS).forEach { batch ->
            batch.map { modelId ->
                scope.launch { checkModel(modelId) }
            }.forEach { it.join() }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Health check complete in ${elapsed}ms")

        // Update last check timestamp
        context.getSharedPreferences("model_health", Context.MODE_PRIVATE)
            .edit()
            .putLong("last_health_check", System.currentTimeMillis())
            .apply()
    }

    private suspend fun checkModel(modelId: String) {
        val health = healthStore.getHealth(modelId)
        val startTime = System.currentTimeMillis()

        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(CHECK_TIMEOUT_MS) {
                doHealthCheck(modelId)
            } ?: run {
                Log.w(TAG, "Health check timeout for $modelId")
                false
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        if (result) {
            health.recordSuccess(elapsed)
            Log.d(TAG, "Health check OK: $modelId (${elapsed}ms)")
        } else {
            health.recordFailure("Timeout or error")
            Log.w(TAG, "Health check failed: $modelId (${elapsed}ms)")
        }
        healthStore.updateHealth(health)
    }

    private suspend fun doHealthCheck(modelId: String): Boolean {
        return try {
            val key = apiKeyProvider()
            val url = URL("https://integrate.api.nvidia.com/v1/chat/completions")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.connectTimeout = CHECK_TIMEOUT_MS.toInt()
            connection.readTimeout = CHECK_TIMEOUT_MS.toInt()
            connection.doOutput = true

            val requestBody = """{"model":"$modelId","messages":[{"role":"user","content":"hi"}],"max_tokens":1}"""
            val writer = OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8)
            writer.write(requestBody)
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                reader.readLine() // consume response
                reader.close()
                true
            } else {
                Log.w(TAG, "Health check HTTP $responseCode for $modelId")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Health check exception for $modelId: ${e.message}")
            false
        }
    }

    fun getTierModels(tier: Tier): List<String> = when (tier) {
        Tier.INSTANT -> INSTANT_MODELS
        Tier.THINKING -> THINKING_MODELS
        Tier.CONTEMPLATING -> CONTEMPLATING_MODELS
        Tier.VISION -> VISION_MODELS
    }

    fun getHealthyModelsForTier(tier: Tier): List<String> {
        return healthStore.getHealthyModels(getTierModels(tier))
    }

    enum class Tier {
        INSTANT, THINKING, CONTEMPLATING, VISION
    }
}