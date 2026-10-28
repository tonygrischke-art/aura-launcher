package com.aetheria.aura.nim

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker for daily model health checks
 */
class DailyHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            withContext(Dispatchers.IO) {
                val healthStore = ModelHealthStore(applicationContext)
                val apiKey = getApiKey()
                
                val healthService = ModelHealthService(
                    applicationContext,
                    healthStore,
                    { apiKey }
                )
                
                healthService.checkAllModels()
                
                Log.d("DailyHealthCheckWorker", "Daily health check completed successfully")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e("DailyHealthCheckWorker", "Health check failed", e)
            Result.retry()
        }
    }

    private fun getApiKey(): String {
        return try {
            val file = java.io.File(applicationContext.filesDir, "nim.key")
            if (file.exists()) {
                file.readText().trim()
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * Initialize WorkManager periodic job for daily health checks
 */
object HealthCheckScheduler {
    private const val TAG = "HealthCheckScheduler"
    private const val WORK_NAME = "daily_model_health_check"

    fun scheduleDailyHealthCheck(context: Context) {
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
            .build()

        val request = androidx.work.PeriodicWorkRequestBuilder<DailyHealthCheckWorker>(
            24, java.util.concurrent.TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .addTag(WORK_NAME)
            .build()

        val workManager = androidx.work.WorkManager.getInstance(context)
        
        // Cancel existing and schedule new
        workManager.cancelAllWorkByTag(WORK_NAME)
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.REPLACE,
            request
        )
        
        Log.d(TAG, "Scheduled daily health check")
    }

    fun cancelDailyHealthCheck(context: Context) {
        val workManager = androidx.work.WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(WORK_NAME)
        Log.d(TAG, "Cancelled daily health check")
    }
}