package com.aetheria.aura.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import java.io.File
import java.io.FileOutputStream

class ModelFileManagerTest {

    @Test
    fun `isValidGguf returns true for valid GGUF magic`() {
        val tempFile = File.createTempFile("test", ".gguf")
        try {
            FileOutputStream(tempFile).use { it.write("GGUF".toByteArray()) }
            assertTrue(ModelFileManager.isValidGguf(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `isValidGguf returns false for invalid magic`() {
        val tempFile = File.createTempFile("test", ".gguf")
        try {
            FileOutputStream(tempFile).use { it.write("FAKE".toByteArray()) }
            assertFalse(ModelFileManager.isValidGguf(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `isValidGguf returns false for empty file`() {
        val tempFile = File.createTempFile("test", ".gguf")
        try {
            assertFalse(ModelFileManager.isValidGguf(tempFile))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `classifyGguf returns mmproj for mmproj files`() {
        assertEquals("mmproj", ModelFileManager.classifyGguf("model-mmproj-Q8_0.gguf"))
        assertEquals("mmproj", ModelFileManager.classifyGguf("Qwen-mmproj.gguf"))
        assertEquals("mmproj", ModelFileManager.classifyGguf("MMPROJ.gguf"))
    }

    @Test
    fun `classifyGguf returns fast for gemma or small files`() {
        assertEquals("fast", ModelFileManager.classifyGguf("gemma-3-270m-it-Q4_K_M.gguf"))
        assertEquals("fast", ModelFileManager.classifyGguf("small-model.gguf"))
        assertEquals("fast", ModelFileManager.classifyGguf("GEMMA.gguf"))
        assertEquals("fast", ModelFileManager.classifyGguf("model-270m.gguf"))
    }

    @Test
    fun `classifyGguf returns main for other files`() {
        assertEquals("main", ModelFileManager.classifyGguf("Qwen-3-VL-2B-Instruct-heretic.i1-Q4_K_M.gguf"))
        assertEquals("main", ModelFileManager.classifyGguf("llama-7b.gguf"))
        assertEquals("main", ModelFileManager.classifyGguf("model.gguf"))
    }
}