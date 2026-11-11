package com.aetheria.aura.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Client side of the Shizuku UserService shell executor.
 *
 * Binds [ShellUserService] (runs as uid 2000 shell) via Shizuku.bindUserService and
 * exposes exec() -> Result(stdout, stderr, exitCode) with a timeout. When Shizuku is
 * not READY, every call returns a clear SHIZUKU_NOT_READY error instead of crashing.
 */
object ShellExecutor {

    data class Result(val stdout: String, val stderr: String, val exitCode: Int)

    private const val TAG = "ShellExecutor"
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val BIND_POLL_MS = 100L

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.aetheria.aura", ShellUserService::class.java.name)
    ).daemon(false)
        .processNameSuffix("shell")
        .debuggable(false)
        .version(1)

    @Volatile
    private var service: IShellService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "user service connected: $name")
            service = if (binder != null && binder.pingBinder()) {
                IShellService.Stub.asInterface(binder)
            } else {
                null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "user service disconnected: $name")
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "user service binding died: $name")
            service = null
        }
    }

    fun isBound(): Boolean = service != null

    /** Starts the bind if Shizuku is READY. Returns whether a bind attempt was made. */
    fun ensureBound(): Boolean {
        service?.let { return true }
        if (ShizukuManager.state.value != ShizukuManager.State.READY) return false
        return try {
            Shizuku.bindUserService(userServiceArgs, connection)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "bindUserService failed", e)
            false
        }
    }

    private suspend fun awaitService(): IShellService? {
        service?.let { return it }
        if (!ensureBound()) return null
        var waited = 0L
        while (waited < BIND_TIMEOUT_MS) {
            service?.let { return it }
            delay(BIND_POLL_MS)
            waited += BIND_POLL_MS
        }
        return service
    }

    /** Run a shell command. Always returns; never throws. */
    suspend fun exec(command: String, timeoutSeconds: Int = 15): Result = withContext(Dispatchers.IO) {
        val svc = awaitService()
            ?: return@withContext Result("", notReadyError(), -1)
        try {
            decode(svc.exec(command, timeoutSeconds))
        } catch (e: Throwable) {
            Log.w(TAG, "exec failed: $command", e)
            Result("", "exec failed: ${e.message}", -1)
        }
    }

    /** Raw chunked file read (for pulling screencap PNGs off /data/local/tmp). */
    suspend fun readChunk(path: String, offset: Int, length: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val svc = awaitService() ?: return@withContext null
            try {
                svc.readChunk(path, offset, length)
            } catch (e: Throwable) {
                null
            }
        }

    /** uiautomator dump lines from the shell-uid process ("ERR: ..." on failure). */
    suspend fun uidump(maxNodes: Int = 150): String = withContext(Dispatchers.IO) {
        val svc = awaitService() ?: return@withContext "ERR: ${notReadyError()}"
        try {
            svc.uidump(maxNodes)
        } catch (e: Throwable) {
            "ERR: uidump failed: ${e.message}"
        }
    }

    fun notReadyError(): String = "SHIZUKU_NOT_READY (state=${ShizukuManager.state.value})"

    /** Kill the user service (calls destroy in the shell process). */
    fun release() {
        service = null
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Throwable) {
            Log.w(TAG, "unbindUserService failed", e)
        }
    }

    private fun decode(raw: String): Result {
        val parts = raw.split("\n", limit = 3)
        val exit = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: -1
        val stdout = parts.getOrNull(1)?.let { b64decode(it) } ?: ""
        val stderr = parts.getOrNull(2)?.let { b64decode(it) } ?: ""
        return Result(stdout, stderr, exit)
    }

    private fun b64decode(s: String): String = try {
        String(android.util.Base64.decode(s.trim(), android.util.Base64.DEFAULT), Charsets.UTF_8)
    } catch (e: Throwable) {
        ""
    }
}
