package com.aetheria.aura.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Tracks Shizuku availability as a StateFlow and owns permission requests.
 *
 * States: NOT_INSTALLED -> NOT_RUNNING -> PERMISSION_NEEDED -> READY.
 * State is recomputed from Shizuku.pingBinder() / checkSelfPermission() on every
 * binder received / binder dead / permission result event.
 */
object ShizukuManager {

    enum class State { NOT_INSTALLED, NOT_RUNNING, PERMISSION_NEEDED, READY }

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE_PERMISSION = 4242
    private const val TAG = "ShizukuManager"

    private val _state = MutableStateFlow(State.NOT_RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var appContext: Context? = null
    private var initialized = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDeadListener = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    /** Idempotent. Call from Application.onCreate. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Throwable) {
            Log.w(TAG, "listener registration failed", e)
        }
        refresh()
    }

    /** Recompute state from live Shizuku calls. Safe to call from any thread. */
    fun refresh() {
        val ctx = appContext ?: return
        val next = try {
            when {
                !isInstalled(ctx) -> State.NOT_INSTALLED
                !Shizuku.pingBinder() -> State.NOT_RUNNING
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                    State.PERMISSION_NEEDED
                else -> State.READY
            }
        } catch (e: Throwable) {
            Log.w(TAG, "state computation failed", e)
            State.NOT_RUNNING
        }
        if (_state.value != next) {
            Log.i(TAG, "state: ${_state.value} -> $next")
            _state.value = next
        }
    }

    /** Package visibility for the check is granted by <queries> in AndroidManifest.xml. */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Shows the Shizuku permission dialog. No-op if the binder is not alive. */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.i(TAG, "requestPermission: binder not alive, nothing to request")
                return
            }
            Shizuku.requestPermission(REQUEST_CODE_PERMISSION)
        } catch (e: Throwable) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    /** Launch intent for the Shizuku app, or null when it is not installed. */
    fun openShizukuIntent(context: Context): Intent? {
        return try {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        } catch (e: Throwable) {
            Log.w(TAG, "getLaunchIntentForPackage failed", e)
            null
        }
    }

    /** "Get Shizuku" - official download page. */
    fun getShizukuIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
}
