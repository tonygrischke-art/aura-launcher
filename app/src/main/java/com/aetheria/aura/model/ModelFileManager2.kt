package com.aetheria.aura.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manages model files - copies from app-specific external storage to app private storage on first run.
 * Models are expected at getExternalFilesDir("models") - no READ_EXTERNAL_STORAGE needed.
 * Stage via rish: cp /sdcard/Download/aura_models/* /sdcard/Android/data/com.aetheria.aura/files/models/
 */
object ModelFileManager {

    private const val TAG = "ModelFileManager"
    private const val PREFS_KEY = "models_copied"
    private const val VERSION = 2

    private val MODEL_FILES = listOf(
        "Qwen-3-VL-2B-Instruct-heretic.i1-Q4_K_M.gguf" to 1107411168L,
        "Qwen-3-VL-2B-Instruct-heretic.mmproj-Q8_0.gguf" to 445053696L,
        "gemma-3-270m-it-Q4_K_M.gguf" to 253115424L
    )

    /** Checks if models exist in app private storage, copies from external files dir if needed. */
    fun ensureModelsAvailable(context: Context): Boolean {
        val prefs = context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREFS_KEY, false) && prefs.getInt("model_version", 0) == VERSION) {
            return verifyModels(context)
        }

        Log.i(TAG, "Models not copied yet or version changed, copying from external files dir...")
        return copyModels(context)
    }

    /** Copies model files from external files dir to app private storage. */
    private fun copyModels(context: Context): Boolean {
        val sourceDir = context.getExternalFilesDir("models")
        if (sourceDir == null || !sourceDir.exists() || !sourceDir.isDirectory) {
            Log.e(TAG, "Source directory not found: ${sourceDir?.absolutePath}")
            return false
        }

        val targetDir = File(context.filesDir, "models")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        var allSuccess = true
        for ((fileName, expectedSize) in MODEL_FILES) {
            val sourceFile = File(sourceDir, fileName)
            val targetFile = File(targetDir, fileName)

            if (!sourceFile.exists()) {
                Log.e(TAG, "Source file not found: $sourceFile")
                allSuccess = false
                continue
            }

            if (targetFile.exists() && targetFile.length() == expectedSize) {
                Log.i(TAG, "Model already exists with correct size: $fileName")
                continue
            }

            Log.i(TAG, "Copying $fileName (${sourceFile.length()} bytes)...")
            if (copyFile(sourceFile, targetFile)) {
                val actualSize = targetFile.length()
                if (actualSize == expectedSize) {
                    Log.i(TAG, "Successfully copied $fileName ($actualSize bytes)")
                } else {
                    Log.e(TAG, "Size mismatch for $fileName: expected $expectedSize, got $actualSize")
                    targetFile.delete()
                    allSuccess = false
                }
            } else {
                Log.e(TAG, "Failed to copy $fileName")
                allSuccess = false
            }
        }

        if (allSuccess) {
            context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREFS_KEY, true)
                .putInt("model_version", VERSION)
                .apply()
        }

        return allSuccess
    }

    /** Verifies all model files exist with correct sizes. */
    private fun verifyModels(context: Context): Boolean {
        val targetDir = File(context.filesDir, "models")
        for ((fileName, expectedSize) in MODEL_FILES) {
            val targetFile = File(targetDir, fileName)
            if (!targetFile.exists() || targetFile.length() != expectedSize) {
                Log.w(TAG, "Model verification failed for $fileName, will re-copy")
                return copyModels(context)
            }
        }
        return true
    }

    /** Copies a file using buffered streams. */
    private fun copyFile(source: File, target: File): Boolean {
        var input: FileInputStream? = null
        var output: FileOutputStream? = null
        return try {
            input = FileInputStream(source)
            output = FileOutputStream(target)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
            }
            output.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error copying ${source.name}: ${e.message}", e)
            false
        } finally {
            input?.close()
            output?.close()
        }
    }

    /** Returns the path to a model file in app private storage. */
    fun getModelPath(context: Context, fileName: String): String? {
        val file = File(File(context.filesDir, "models"), fileName)
        return if (file.exists()) file.absolutePath else null
    }

    /** Clears the copied flag (for testing). */
    fun resetCopiedFlag(context: Context) {
        context.getSharedPreferences("model_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_KEY)
            .apply()
    }
}