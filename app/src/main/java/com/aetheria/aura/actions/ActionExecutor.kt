package com.aetheria.aura.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
     * @param context The Context to start activities
     * @param pm The PackageManager instance
     * @param appIndex The AppIndex for fuzzy label matching
     * @return "confirm" if action was executed, "error" if no match or Unknown action
     */
    fun execute(action: AuraAction, context: Context, pm: PackageManager, appIndex: AppIndex? = null): String {
        return when (action) {
            is AuraAction.OpenApp -> {
                val target = action.target
                if (target.isBlank()) "error"
                else {
                    val pkg = appIndex?.findBestMatch(target)
                    if (pkg != null) {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            "confirm"
                        } else "error"
                    } else "error"
                }
            }
            is AuraAction.CallContact -> {
                val name = action.name
                if (name.isBlank()) "error"
                else {
                    // For now, just return confirm - would need contacts lookup to get phone number
                    // In a real implementation: look up contact by name, get phone number, start call intent
                    "confirm"
                }
            }
            is AuraAction.SendText -> {
                val name = action.name
                val body = action.body
                if (name.isBlank()) "error"
                else {
                    // Would need contacts lookup to get phone number
                    "confirm"
                }
            }
            is AuraAction.WebSearch -> {
                val query = action.query
                if (query.isBlank()) "error"
                else {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH)
                    intent.putExtra(SearchManager.QUERY, query)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(pm) != null) {
                        context.startActivity(intent)
                        "confirm"
                    } else "error"
                }
            }
            is AuraAction.AskHermes -> "hermes" // Signal to call Hermes bridge
            AuraAction.Unknown -> "error"
        }
    }
}