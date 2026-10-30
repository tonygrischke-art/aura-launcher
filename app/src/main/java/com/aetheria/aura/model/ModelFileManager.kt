package com.aetheria.aura.model

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages model file locations and verifies expected model files exist with correct sizes.
 * No hashing, no copying, no special permissions required.
 */
object ModelFileManager {

    private const val TAG = "AuraModels"

    // Expected model files with their sizes in bytes
    private val EXPECTED_MODELS = mapOf(
        "Qwen-3-VL-2B-Instruct-heretic.i1-Q4_K_M.gguf" to 1107411168L,
        "Qwen-3-VL-2B-Instruct-heretic.mmproj-Q8_0.gguf" to 445053696L,
        "gemma-3-270m-it-Q4_K_M.gguf" to 253115424L
    )

    /**
     * Returns the models directory in app-specific external storage.
     * No READ_EXTERNAL_STORAGE or READ_MEDIA_* permissions needed.
     */
    fun modelsDir(ctx: Context): File = ctx.getExternalFilesDir("models")!!

    /**
     * Checks each expected model file and logs its status.
     * Log format: "AuraModels: <name> exists=<bool> size=<n> expected=<n> ok=<bool>"
     */
    fun status(ctx: Context) {
        val dir = modelsDir(ctx)
        if (!dir.exists()) {
            Log.w(TAG, "Models directory does not exist: $dir")
            return
        }

        for ((fileName, expectedSize) in EXPECTED_MODELS) {
            val file = File(dir, fileName)
            val exists = file.exists()
            val actualSize = if (exists) file.length() else 0L
            val ok = exists && actualSize == expectedSize
            Log.i(TAG, "$fileName exists=$exists size=$actualSize expected=$expectedSize ok=$ok")
        }
    }
}