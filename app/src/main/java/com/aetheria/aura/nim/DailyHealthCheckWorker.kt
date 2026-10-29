package com.aetheria.aura.nim

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * WorkManager worker for daily model health checks.
 * Runs every 24h with network constraint, checks models in batches of 3 with 5s timeout each.
 */
class DailyHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d("DailyHealthCheck", "Starting daily model health checks")

            // Get NimClient instance - we need to create one with a dummy key provider
            // In production, this would use the actual key from the app
            val apiKey = getApiKey() ?: return@withContext Result.retry()

            val nimClient = NimClient(applicationContext) { apiKey }
            val allHealth = nimClient.getAllModelHealth()

            if (allHealth.isEmpty()) {
                Log.d("DailyHealthCheck", "No models to check")
                return@withContext Result.success()
            }

            // Check models in batches of 3
            val modelsToCheck = allHealth.map { it.model }.distinct()
            val batchSize = 3
            var checkedCount = 0
            var healthyCount = 0

            for (i in 0 until modelsToCheck.size step batchSize) {
                val batch = modelsToCheck.subList(i, minOf(i + batchSize, modelsToCheck.size))
                Log.d("DailyHealthCheck", "Checking batch: $batch")

                // Run health checks for this batch in parallel using coroutineScope + async
                val results: List<Boolean> = coroutineScope {
                    batch.map { model ->
                        async(Dispatchers.IO) {
                            checkModelHealth(nimClient, model)
                        }
                    }.awaitAll()
                }

                for (index in batch.indices) {
                    val model = batch[index]
                    val isHealthy = results[index]
                    checkedCount++
                    if (isHealthy) healthyCount++
                    Log.d("DailyHealthCheck", "Health check $model: ${if (isHealthy) "HEALTHY" else "UNHEALTHY"}")
                }

                // Small delay between batches to avoid rate limiting
                if (i + batchSize < modelsToCheck.size) {
                    Thread.sleep(1000)
                }
            }

            Log.d("DailyHealthCheck", "Completed: $healthyCount/$checkedCount models healthy")
            Result.success()

        } catch (e: Exception) {
            Log.e("DailyHealthCheck", "Health check failed", e)
            Result.retry()
        }
    }

    private suspend fun checkModelHealth(nimClient: NimClient, model: String): Boolean {
        return withContext(Dispatchers.IO) {
            val result = CompletableDeferred<Boolean>()
            nimClient.healthCheck(model) { isHealthy ->
                result.complete(isHealthy)
            }
            // Wait with timeout
            try {
                withTimeout(NimClient.HEALTH_CHECK_TIMEOUT_MS + 1000) {
                    result.await()
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun getApiKey(): String? {
        // Read from app's private files (debuggable APK only)
        val file = java.io.File(applicationContext.filesDir, "nim.key")
        return if (file.exists()) {
            file.readText().trim().takeIf { it.isNotBlank() }
        } else null
    }
}