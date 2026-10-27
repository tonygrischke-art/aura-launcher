package com.aetheria.aura.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import com.aetheria.aura.router.AppIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes parsed AuraAction results.
 * For OpenApp: matches target against installed app labels using AppIndex,
 * launches via PackageManager.getLaunchIntentForPackage, returns confirm status.
 * For CallContact/SendText: resolves contact name to phone number via ContactsContract.
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
                    val phoneNumber = findPhoneNumberByName(context, name)
                    if (phoneNumber != null) {
                        val intent = Intent(Intent.ACTION_DIAL)
                        intent.data = Uri.parse("tel:$phoneNumber")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "confirm"
                    } else {
                        // Fallback: open dialer without number
                        val intent = Intent(Intent.ACTION_DIAL)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        "confirm"
                    }
                }
            }
            is AuraAction.SendText -> {
                val name = action.name
                val body = action.body
                if (name.isBlank()) "error"
                else {
                    val phoneNumber = findPhoneNumberByName(context, name)
                    val intent = Intent(Intent.ACTION_SENDTO)
                    if (phoneNumber != null) {
                        intent.data = Uri.parse("smsto:$phoneNumber")
                    } else {
                        intent.data = Uri.parse("smsto:")
                    }
                    intent.putExtra("sms_body", body)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent.resolveActivity(pm) != null) {
                        context.startActivity(intent)
                        "confirm"
                    } else "error"
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

    /**
     * Finds a phone number for a contact by name (fuzzy match).
     * Returns the first phone number found, or null if no match.
     */
    private fun findPhoneNumberByName(context: Context, name: String): String? {
        val normalizedName = name.lowercase().trim()
        if (normalizedName.isBlank()) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$normalizedName%")

        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numberIndex >= 0) {
                    cursor.getString(numberIndex)
                } else null
            } else null
        } catch (e: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }
}