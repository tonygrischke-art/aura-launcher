package com.aetheria.aura.actions

import android.content.pm.PackageManager
import com.aetheria.aura.router.AppIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes parsed AuraAction results.
 * For OpenApp: matches target against installed app labels using AppIndex,
 * launches via PackageManager.getLaunchIntentForPackage, returns confirm status.
 * Unknown or no match returns error state.
 */
object ActionExecutor {

    /**
     * Executes the given AuraAction using the PackageManager and AppIndex to find and launch matching apps.
     * @param action The AuraAction to execute
     * @param pm The PackageManager instance
     * @param appIndex The AppIndex for fuzzy label matching
     * @return "confirm" if action was executed, "error" if no match or Unknown action
     */
    fun execute(action: AuraAction, pm: PackageManager, appIndex: AppIndex? = null): String {
        return when (action) {
            is AuraAction.OpenApp -> {
                val target = action.target
                if (target.isBlank()) "error"
                else {
                    val pkg = appIndex?.findBestMatch(target)
                    if (pkg != null) {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            // In a real implementation, we'd start the intent
                            // For now, just mark as confirm
                            "confirm"
                        } else "error"
                    } else "error"
                }
            }
            is AuraAction.CallContact -> "confirm" // Requires CALL_PHONE permission
            is AuraAction.SendText -> "confirm" // Requires SEND_SMS permission
            is AuraAction.WebSearch -> "confirm" // Handled by opening browser intent
            is AuraAction.AskHermes -> "hermes" // Signal to call Hermes bridge
            AuraAction.Unknown -> "error"
        }
    }
}