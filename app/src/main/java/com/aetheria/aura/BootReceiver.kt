package com.aetheria.aura

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AuraBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i(TAG, "Boot completed, starting wake word service")
            val serviceIntent = Intent(context, WakeWordService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}