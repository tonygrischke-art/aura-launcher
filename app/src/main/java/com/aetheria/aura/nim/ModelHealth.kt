package com.aetheria.aura.nim

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Model health state tracking with circuit breaker pattern.
 * Persists to SharedPreferences for survival across app restarts.
 */
data class ModelHealth(
    val model: String,
    var state: ModelState = ModelState.HEALTHY,
    var consecutiveFailures: Int = 0,
    var lastSuccessTime: Long = 0,
    var lastFailureTime: Long = 0,
    var lastCheckTime: Long = 0,
    var totalRequests: Int = 0,
    var totalSuccesses: Int = 0,
    var avgLatencyMs: Double = 0.0
) {
    /** Returns true if model is available for requests (not circuit open) */
    fun isAvailable(): Boolean = state != ModelState.CIRCUIT_OPEN

    /** Called on successful request */
    fun recordSuccess(latencyMs: Long) {
        totalRequests++
        totalSuccesses++
        consecutiveFailures = 0
        lastSuccessTime = System.currentTimeMillis()
        // Exponential moving average for latency
        avgLatencyMs = if (totalSuccesses == 1) latencyMs.toDouble()
        else avgLatencyMs * 0.9 + latencyMs * 0.1
    }

    /** Called on failed request */
    fun recordFailure() {
        totalRequests++
        consecutiveFailures++
        lastFailureTime = System.currentTimeMillis()
        // Open circuit after 3 consecutive failures
        if (consecutiveFailures >= 3) {
            state = ModelState.CIRCUIT_OPEN
        }
    }

    /** Called when health check passes - can half-open circuit */
    fun recordHealthCheckPass() {
        if (state == ModelState.CIRCUIT_OPEN) {
            state = ModelState.HALF_OPEN
        } else {
            state = ModelState.HEALTHY
        }
        consecutiveFailures = 0
        lastCheckTime = System.currentTimeMillis()
    }

    /** Called when health check fails */
    fun recordHealthCheckFail() {
        consecutiveFailures++
        lastCheckTime = System.currentTimeMillis()
        if (consecutiveFailures >= 3) {
            state = ModelState.CIRCUIT_OPEN
        }
    }
}

enum class ModelState {
    HEALTHY,      // Normal operation
    DEGRADED,     // Some failures but still usable
    CIRCUIT_OPEN, // Too many failures, blocked
    HALF_OPEN     // Testing recovery after circuit open
}

/** Persistent storage for model health data */
class ModelHealthStore(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("model_health", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val typeToken = object : TypeToken<ConcurrentHashMap<String, ModelHealth>>() {}.type

    /** In-memory cache for fast access */
    private val healthMap = ConcurrentHashMap<String, ModelHealth>()

    init {
        load()
    }

    /** Load health data from SharedPreferences */
    private fun load() {
        val json = prefs.getString("health_map", "{}")
        try {
            val map = gson.fromJson<ConcurrentHashMap<String, Any>>(json, typeToken)
            map.forEach { (model, data) ->
                val health = gson.fromJson(gson.toJson(data), ModelHealth::class.java)
                healthMap[model] = health
            }
        } catch (e: Exception) {
            // Ignore parse errors, start fresh
        }
    }

    /** Save health data to SharedPreferences */
    private fun save() {
        val json = gson.toJson(healthMap)
        prefs.edit().putString("health_map", json).apply()
    }

    /** Get health for a model, creating if not exists */
    fun get(model: String): ModelHealth {
        return healthMap.computeIfAbsent(model) { ModelHealth(it) }.also { save() }
    }

    /** Update health for a model */
    fun update(health: ModelHealth) {
        healthMap[health.model] = health
        save()
    }

    /** Get all model health entries */
    fun getAll(): List<ModelHealth> = healthMap.values.toList()

    /** Reset health for a model (e.g., after manual intervention) */
    fun reset(model: String) {
        healthMap[model] = ModelHealth(model)
        save()
    }

    /** Get models available for a tier (not circuit open) */
    fun getAvailableModels(models: List<String>): List<String> {
        return models.filter { get(it).isAvailable() }
    }
}