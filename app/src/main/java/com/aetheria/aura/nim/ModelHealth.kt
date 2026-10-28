package com.aetheria.aura.nim

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Health state of a model
 */
enum class ModelState {
    UNKNOWN,    // Not yet checked
    HEALTHY,    // Responds quickly and correctly
    DEGRADED,   // Responds but slow or occasional errors
    DEAD        // Consistently fails or times out
}

/**
 * Health record for a single model
 */
data class ModelHealth(
    val modelId: String,
    var state: ModelState = ModelState.UNKNOWN,
    var lastCheck: Long = 0,
    var avgLatencyMs: Long = 0,
    var consecutiveFailures: Int = 0,
    var consecutiveSuccesses: Int = 0,
    var lastError: String? = null
) {
    fun recordSuccess(latencyMs: Long) {
        consecutiveSuccesses++
        consecutiveFailures = 0
        lastError = null
        // Exponential moving average for latency
        avgLatencyMs = if (avgLatencyMs == 0L) latencyMs else (avgLatencyMs * 3 + latencyMs) / 4
        if (consecutiveSuccesses >= 3) state = ModelState.HEALTHY
        else if (state == ModelState.DEAD) state = ModelState.DEGRADED
    }

    fun recordFailure(error: String) {
        consecutiveFailures++
        consecutiveSuccesses = 0
        lastError = error
        if (consecutiveFailures >= 3) state = ModelState.DEAD
        else if (state == ModelState.HEALTHY) state = ModelState.DEGRADED
    }

    fun isAvailable(): Boolean = state != ModelState.DEAD
}

/**
 * Circuit breaker for a single model
 */
class ModelCircuitBreaker(private val modelId: String) {
    enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var failureCount = 0
    private var successCount = 0
    private var lastFailureTime = 0L

    private val failureThreshold = 3
    private val successThreshold = 2
    private val baseBackoffMs = 30_000L // 30s
    private val maxBackoffMs = 5 * 60 * 1000L // 5 min

    fun canAttempt(): Boolean = when (state) {
        State.CLOSED -> true
        State.OPEN -> {
            val backoff = minOf(baseBackoffMs * (2L shl failureCount), maxBackoffMs)
            System.currentTimeMillis() - lastFailureTime > backoff
        }
        State.HALF_OPEN -> true
    }

    fun recordSuccess() {
        failureCount = 0
        successCount++
        if (state == State.HALF_OPEN && successCount >= 2) {
            state = State.CLOSED
            successCount = 0
        }
    }

    fun recordFailure() {
        failureCount++
        successCount = 0
        lastFailureTime = System.currentTimeMillis()
        if (state == State.HALF_OPEN) {
            state = State.OPEN
        } else if (state == State.CLOSED && failureCount >= 3) {
            state = State.OPEN
        }
    }

    fun getState(): State = state
    fun getFailureCount(): Int = failureCount
    fun getBackoffRemainingMs(): Long {
        if (state != State.OPEN) return 0
        val backoff = minOf(baseBackoffMs * (2L shl failureCount), maxBackoffMs)
        return maxOf(0, backoff - (System.currentTimeMillis() - lastFailureTime))
    }

    companion object {
        private val baseBackoffMs = 30_000L // 30s
        private val maxBackoffMs = 5 * 60 * 1000L // 5 min
    }
}

/**
 * SharedPreferences-backed model health persistence
 */
class ModelHealthStore(private val context: Context) {
    private val gson = Gson()
    private val healthMap = ConcurrentHashMap<String, ModelHealth>()
    private val modelListType: Type = object : TypeToken<java.util.Map<String, ModelHealth>>() {}.type
    private val prefsName = "model_health"
    private val healthJsonKey = "model_health_json"

    init {
        loadFromDisk()
    }

    private fun loadFromDisk() {
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val json = prefs.getString(healthJsonKey, null)
            if (json != null) {
                val map = gson.fromJson(json, modelListType)
                map?.forEach { (k, v) -> healthMap[k] = v }
            }
        } catch (e: Exception) {
            // Ignore, start fresh
        }
    }

    fun getHealth(modelId: String): ModelHealth = healthMap.getOrPut(modelId) { ModelHealth(modelId) }

    fun updateHealth(health: ModelHealth) {
        healthMap[health.modelId] = health
        persistAsync()
    }

    fun getAllHealth(): Map<String, ModelHealth> = healthMap.toMap()

    fun getHealthyModels(modelIds: List<String>): List<String> {
        return modelIds.filter { getHealth(it).isAvailable() }
    }

    private fun persistAsync() {
        val json = gson.toJson(healthMap)
        Thread {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                .edit()
                .putString(healthJsonKey, json)
                .apply()
        }.start()
    }
}