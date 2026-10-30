package com.aetheria.aura.wakeWord

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Wake word detection service - STUB IMPLEMENTATION
 * 
 * This is a placeholder for Sherpa-ONNX KeywordSpotter integration.
 * The actual implementation requires:
 * 1. Sherpa-ONNX Android library (com.k2fsa:sherpa-onnx) - not publicly available on Maven Central
 * 2. Model files in assets/: hey-aura.onnx + tokens.txt
 * 3. GitHub Packages authentication for dependency resolution
 * 
 * Current status: Service runs but logs warning that model is not available.
 * To enable: Add Sherpa-ONNX dependency and model files, then implement KeywordSpotter logic.
 * 
 * See: https://github.com/k2-fsa/sherpa-onnx
 * Model training: https://github.com/k2-fsa/sherpa-onnx/tree/master/kws
 */
class WakeWordService : Service() {

    private val TAG = "WakeWordService"
    private var isRunning = false

    companion object {
        const val ACTION_WAKE_WORD_DETECTED = "com.aetheria.aura.WAKE_WORD_DETECTED"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wake_word_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.w(TAG, "WakeWordService created - STUB MODE: Sherpa-ONNX library not available")
        Log.w(TAG, "To enable wake word detection: add com.k2fsa:sherpa-onnx dependency + model files")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            val notification = buildNotification()
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isRunning = true
                Log.d(TAG, "WakeWordService started (stub mode)")
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot start microphone foreground service: ${e.message}. Stopping service.")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        Log.d(TAG, "WakeWordService stopped")
        super.onDestroy()
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Aura Wake Word")
        .setContentText("Wake word detection unavailable - add Sherpa-ONNX library")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Wake Word Detection",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background wake word listening service (stub)"
                setSound(null, null)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}