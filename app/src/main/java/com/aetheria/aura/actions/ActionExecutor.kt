package com.aetheria.aura.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes parsed AuraAction results.
 * For OpenApp: matches target against installed app labels (exact, startsWith, contains),
 * launches via PackageManager.getLaunchIntentForPackage with FLAG_ACTIVITY_NEW_TASK, returns confirm status.
 * Unknown or no match returns error state.
 */
object ActionExecutor {

    /**
     * Executes the given AuraAction using the PackageManager to find and launch matching apps.
     * Runs package scan on Dispatchers.Default.
     * @param action The AuraAction to execute
     * @param context Context for starting activities
     * @param pm The PackageManager instance
     * @return "confirm" if app was launched, "error" if no match or Unknown action
     */
    suspend fun execute(action: AuraAction, context: Context, pm: PackageManager): String {
        return withContext(Dispatchers.Default) {
            when (action) {
                is AuraAction.OpenApp -> executeOpenApp(action, context, pm)
                else -> "error"
            }
        }
    }

    private fun executeOpenApp(action: AuraAction.OpenApp, context: Context, pm: PackageManager): String {
        val target = action.target.trim()
        if (target.isBlank()) {
            Log.w("AuraRouter", "OpenApp: blank target rejected")
            return "error"
        }

        val packages = pm.getInstalledPackages(0)
        val targetLower = target.lowercase()

        // 1. Exact match (case-insensitive)
        for (pkg in packages) {
            val label = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: continue
            if (label == targetLower) {
                return launchApp(context, pm, pkg.packageName, "exact")
            }
        }

        // 2. startsWith match
        for (pkg in packages) {
            val label = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: continue
            if (label.startsWith(targetLower)) {
                return launchApp(context, pm, pkg.packageName, "startsWith")
            }
        }

        // 3. contains match
        for (pkg in packages) {
            val label = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: continue
            if (label.contains(targetLower)) {
                return launchApp(context, pm, pkg.packageName, "contains")
            }
        }

        Log.w("AuraRouter", "OpenApp: no package match for target='$target'")
        return "error"
    }

    private fun launchApp(context: Context, pm: PackageManager, packageName: String, matchType: String): String {
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            Log.w("AuraRouter", "OpenApp: no launch intent for pkg='$packageName'")
            return "error"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.d("AuraRouter", "OpenApp: starting intent pkg='$packageName' match=$matchType")
        return try {
            context.startActivity(intent)
            "confirm"
        } catch (e: Exception) {
            Log.e("AuraRouter", "OpenApp: startActivity failed for pkg='$packageName'", e)
            "error"
        }
    }
}