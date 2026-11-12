package com.aetheria.aura.model

import android.content.Context
import android.util.Log

/**
 * Simple test file to verify CI compilation works.
 */
object TestModelFileManager {

    private const val TAG = "TestModelFileManager"
    private const val VERSION = 1

    fun ensureModelsAvailable(context: Context): Boolean {
        Log.i(TAG, "TestModelFileManager called")
        return true
    }
}