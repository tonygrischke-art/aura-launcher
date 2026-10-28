package com.aetheria.aura.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
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

    /** Executes the given AuraAction using the PackageManager and AppIndex to find and launch matching apps. */
    fun execute(action: AuraAction, context: Context, pm: PackageManager, appIndex: AppIndex? = null): String {
        Log.d("AuraRouter", "ActionExecutor.execute action=${action::class.simpleName} $action")
        return when (action) {
            is AuraAction.OpenApp -> executeOpenApp(action, context, pm, appIndex)
            is AuraAction.CallContact -> executeCallContact(action, context, pm)
            is AuraAction.SendText -> executeSendText(action, context, pm)
            is AuraAction.WebSearch -> executeWebSearch(action, context, pm)
            is AuraAction.AskHermes -> "hermes"
            AuraAction.Unknown -> "error"
        }
    }

    private fun executeOpenApp(action: AuraAction.OpenApp, context: Context, pm: PackageManager, appIndex: AppIndex?): String {
        val target = action.target
        if (target.isBlank()) return "error"
        val pkg = appIndex?.findBestMatch(target)
        if (pkg == null) {
            Log.w("AuraRouter", "OpenApp: no package match for target='$target'")
            return "error"
        }
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Log.w("AuraRouter", "OpenApp: no launch intent for pkg='$pkg'")
            return "error"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.d("AuraRouter", "OpenApp: starting intent action=${intent.action} pkg=$pkg target='$target'")
        return try {
            context.startActivity(intent)
            "confirm"
        } catch (e: Exception) {
            Log.e("AuraRouter", "OpenApp: startActivity failed for pkg='$pkg'", e)
            "error"
        }
    }

    private fun executeCallContact(action: AuraAction.CallContact, context: Context, pm: PackageManager): String {
        val name = action.name
        if (name.isBlank()) return "error"
        val phoneNumber = findPhoneNumberByName(context, name)
        val intent = Intent(Intent.ACTION_DIAL)
        if (phoneNumber != null) {
            intent.data = Uri.parse("tel:$phoneNumber")
            Log.d("AuraRouter", "CallContact: dialing name='$name' phoneNumber='$phoneNumber'")
        } else {
            Log.w("AuraRouter", "CallContact: no phone number for name='$name', opening dialer without number")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "confirm"
        } catch (e: Exception) {
            Log.e("AuraRouter", "CallContact: startActivity failed name='$name'", e)
            "error"
        }
    }

    private fun executeSendText(action: AuraAction.SendText, context: Context, pm: PackageManager): String {
        val name = action.name
        val body = action.body
        if (name.isBlank()) return "error"
        val phoneNumber = findPhoneNumberByName(context, name)
        val intent = Intent(Intent.ACTION_SENDTO)
        if (phoneNumber != null) {
            intent.data = Uri.parse("smsto:$phoneNumber")
            Log.d("AuraRouter", "SendText: to name='$name' phoneNumber='$phoneNumber' body='$body'")
        } else {
            intent.data = Uri.parse("smsto:")
            Log.w("AuraRouter", "SendText: no phone number for name='$name', opening SMS composer without recipient")
        }
        intent.putExtra("sms_body", body)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(pm) == null) {
            Log.w("AuraRouter", "SendText: no SMS app found")
            return "error"
        }
        return try {
            context.startActivity(intent)
            "confirm"
        } catch (e: Exception) {
            Log.e("AuraRouter", "SendText: startActivity failed name='$name'", e)
            "error"
        }
    }

    private fun executeWebSearch(action: AuraAction.WebSearch, context: Context, pm: PackageManager): String {
        val query = action.query
        if (query.isBlank()) return "error"
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
        intent.putExtra(SearchManager.QUERY, query)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        Log.d("AuraRouter", "WebSearch: query='$query'")
        if (intent.resolveActivity(pm) == null) {
            Log.w("AuraRouter", "WebSearch: no browser/search app found")
            return "error"
        }
        return try {
            context.startActivity(intent)
            "confirm"
        } catch (e: Exception) {
            Log.e("AuraRouter", "WebSearch: startActivity failed query='$query'", e)
            "error"
        }
    }

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
            Log.e("AuraRouter", "findPhoneNumberByName failed name='$name'", e)
            null
        } finally {
            cursor?.close()
        }
    }
}