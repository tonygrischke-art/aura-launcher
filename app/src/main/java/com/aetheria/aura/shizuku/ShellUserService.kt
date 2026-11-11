package com.aetheria.aura.shizuku

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService: runs in its own process as uid 2000 (shell).
 * Instantiated by the Shizuku server (app_process), NOT via startService/onBind:
 * the class must extend the AIDL Stub and have a public no-arg constructor.
 * All methods are synchronous Binder calls; run nothing heavy on the caller's thread.
 */
class ShellUserService : IShellService.Stub() {

    override fun destroy() {
        Log.i(TAG, "destroy: exiting user service")
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun exec(command: String, timeoutSeconds: Int): String {
        val timeout = if (timeoutSeconds < 1) 1 else timeoutSeconds
        return try {
            val proc = ProcessBuilder("/bin/sh", "-c", command).start()
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            val tOut = Thread { proc.inputStream.use { it.copyTo(outBuf) } }
            val tErr = Thread { proc.errorStream.use { it.copyTo(errBuf) } }
            tOut.start()
            tErr.start()
            val finished = proc.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
            tOut.join(2000)
            tErr.join(2000)
            val exit = try {
                proc.exitValue()
            } catch (e: IllegalThreadStateException) {
                -1
            }
            var stderr = String(errBuf.toByteArray(), Charsets.UTF_8)
            if (!finished && stderr.isBlank()) stderr = "TIMEOUT after ${timeout}s"
            val stdout = String(outBuf.toByteArray(), Charsets.UTF_8)
            "$exit\n${b64(stdout)}\n${b64(stderr)}"
        } catch (e: Throwable) {
            "-1\n${b64("")}\n${b64("exec failed: ${e.message}")}"
        }
    }

    override fun readChunk(path: String, offset: Int, length: Int): ByteArray {
        val f = File(path)
        if (!f.isFile) return ByteArray(0)
        val raf = RandomAccessFile(f, "r")
        try {
            val len = length.coerceIn(0, MAX_CHUNK)
            if (offset < 0 || len == 0) return ByteArray(0)
            raf.seek(offset.toLong())
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = raf.read(buf, read, len - read)
                if (n < 0) break
                read += n
            }
            return if (read == len) buf else buf.copyOf(read)
        } finally {
            raf.close()
        }
    }

    override fun uidump(maxNodes: Int): String {
        val xmlPath = "$TMP/aura_uidump.xml"
        val result = exec("uiautomator dump $xmlPath", 25)
        val exit = result.lineSequence().firstOrNull()?.toIntOrNull() ?: -1
        if (exit != 0) {
            return "ERR: uiautomator dump failed (exit=$exit)"
        }
        val xml = try {
            File(xmlPath).readText()
        } catch (e: Throwable) {
            return "ERR: cannot read dump: ${e.message}"
        } finally {
            File(xmlPath).delete()
        }
        val lines = ArrayList<String>()
        for (m in NODE_REGEX.findAll(xml)) {
            if (lines.size >= maxNodes) break
            val attrs = m.groupValues[1]
            val text = attr(attrs, "text") ?: ""
            val id = attr(attrs, "resource-id") ?: ""
            if (text.isEmpty() && id.isEmpty()) continue
            val clickable = (attr(attrs, "clickable") ?: "false") == "true"
            val bounds = attr(attrs, "bounds") ?: ""
            lines.add("$text\t$id\t$clickable\t$bounds")
        }
        return lines.joinToString("\n")
    }

    private fun attr(attrs: String, name: String): String? =
        Regex("""\b${Regex.escape(name)}="([^"]*)\"""").find(attrs)?.groupValues?.get(1)

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

    companion object {
        private const val TAG = "ShellUserService"
        private const val TMP = "/data/local/tmp"
        private const val MAX_CHUNK = 512 * 1024
        private val NODE_REGEX = Regex("<node([^>]*?)/?>")
    }
}
