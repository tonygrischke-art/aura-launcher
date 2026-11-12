package com.aetheria.aura

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aetheria.aura.wakeWord.WakeWordService

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AuraBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "BootReceiver.onReceive action=${intent.action}")
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // On Android 14+ (API 34+), microphone foreground services require the app
            // to be in "eligible state" (visible activity). Starting from boot receiver
            // before any activity is visible will crash with SecurityException.
            // Defer to MainActivity to start the service when app is in foreground.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.i(TAG, "Boot completed but deferring wake word service start (API 34+ requires foreground activity)")
            } else {
                Log.i(TAG, "Boot completed, starting wake word service")
                val serviceIntent = Intent(context, WakeWordService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}