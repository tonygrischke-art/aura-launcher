package com.aetheria.aura.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.aetheria.aura.actions.AuraAction.OpenApp
import com.aetheria.aura.net.HermesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes parsed AuraAction results.
 * For OpenApp: matches target against installed app labels (case-insensitive contains),
 * launches via PackageManager.getLaunchIntentForPackage, returns confirm status.
 * Unknown or no match returns error state.
 */
object ActionExecutor {

    /**
     * Executes the given AuraAction using the PackageManager to find and launch matching apps.
     * @param action The AuraAction to execute
     * @param context The Context to start activities
     * @return "confirm" if app was launched, "error" if no match or Unknown action
     */
    suspend fun execute(action: AuraAction, context: Context): String = withContext(Dispatchers.IO) {
        return@withContext when (action) {
            is OpenApp -> {
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                val targetLower = action.target.lowercase()
                var foundMatch = false

                for (pkg in packages) {
                    val label = pkg.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: continue
                    if (label.contains(targetLower)) {
                        val intent = pm.getLaunchIntentForPackage(pkg.packageName)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            foundMatch = true
                            break
                        }
                    }
                }

                if (foundMatch) "confirm" else "error"
            }
            else -> "error"
        }
    }
}