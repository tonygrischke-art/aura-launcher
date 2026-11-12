package com.aetheria.aura.model

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

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

    /** Pure function: checks if a file has GGUF magic bytes at the start. No Android types. */
    fun isValidGguf(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                read == 4 && magic.contentEquals("GGUF".toByteArray())
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Pure function: classifies a .gguf file by name. Returns "mmproj", "main", or "fast". */
    fun classifyGguf(fileName: String): String {
        val lower = fileName.lowercase()
        return if (lower.contains("mmproj")) "mmproj"
        else if (lower.contains("gemma") || lower.contains("270m") || lower.contains("small")) "fast"
        else "main"
    }

    /** Pure function: lists all .gguf files in modelsDir with classification and size in MB. */
    fun listGgufFiles(ctx: Context): List<ModelFileInfo> {
        val dir = modelsDir(ctx)
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles()?.filter { it.name.endsWith(".gguf") }
        if (files == null) return emptyList()
        val mapped = files.map { file ->
            val classification = classifyGguf(file.name)
            val sizeMb = file.length() / (1024 * 1024)
            ModelFileInfo(file.name, sizeMb, classification, file)
        }
        return mapped.sortedBy { info ->
            when (info.classification) {
                "fast" -> 0
                "main" -> 1
                "mmproj" -> 2
                else -> 3
            }
        }
    }

    /** Copies an input stream to modelsDir with progress callback. Returns the destination file. */
    fun copyModelFile(ctx: Context, input: java.io.InputStream, destName: String, progressCallback: (Long, Long) -> Unit): File {
        val dest = File(modelsDir(ctx), destName)
        dest.parentFile?.mkdirs()
        val tempFile = File(dest.parentFile, "$destName.part")
        var totalBytes = 0L
        val buffer = ByteArray(1024 * 1024) // 1MB chunks
        try {
            FileOutputStream(tempFile).use { output ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    totalBytes += read
                    progressCallback(totalBytes, -1) // total unknown for stream
                }
            }
            // Verify GGUF magic
            if (!isValidGguf(tempFile)) {
                tempFile.delete()
                throw IllegalArgumentException("File is not a valid GGUF (missing magic bytes)")
            }
            // Atomic rename
            if (!tempFile.renameTo(dest)) {
                throw IOException("Failed to move downloaded file to destination")
            }
            progressCallback(dest.length(), dest.length())
            return dest
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /** Deletes a model file by name. */
    fun deleteModelFile(ctx: Context, fileName: String): Boolean {
        return File(modelsDir(ctx), fileName).delete()
    }

    /** Data class for model file info - pure, no Android types. */
    data class ModelFileInfo(
        val fileName: String,
        val sizeMb: Long,
        val classification: String, // "main", "fast", "mmproj"
        val file: File? = null // only non-null when called from Android context
    )
}