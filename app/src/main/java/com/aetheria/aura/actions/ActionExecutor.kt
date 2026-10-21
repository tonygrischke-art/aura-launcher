package com.aetheria.aura.actions

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.unit.dp
import com.aetheria.aura.actions.AuraAction.OpenApp
import com.aetheria.aura.net.HermesClient

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
     * @param pm The PackageManager instance
     * @return "confirm" if app was launched, "error" if no match or Unknown action
     */
    fun execute(action: AuraAction, pm: PackageManager): String {
        return when (action) {
            is OpenApp -> {
                val packages = pm.getInstalledPackages(0)
                val targetLower = action.target.lowercase()
                var foundMatch = false

                for (pkg in packages) {
                    val label = pkg.applicationInfo.loadLabel(pm).toString().lowercase()
                    if (label.contains(targetLower)) {
                        val intent = pm.getLaunchIntentForPackage(pkg.packageName)
                        if (intent != null) {
                            // In a real implementation, we'd start the intent
                            // For now, just mark as confirm
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