package com.aetheria.aura.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executes parsed AuraAction results.
 * For OpenApp: matches target against installed app labels (exact, startsWith, contains),
 * launches via PackageManager.getLaunchIntentForPackage with FLAG_ACTIVITY_NEW_TASK, returns confirm status.
 * For SendText: resolves contact name to phone number, launches SMS intent with FLAG_ACTIVITY_NEW_TASK.
 * Unknown or no match returns error state.
 */
object ActionExecutor {

    /**
     * Executes the given AuraAction using the PackageManager to find and launch matching apps.
     * Runs package scan on Dispatchers.Default.
     * @param action The AuraAction to execute
     * @param context Context for starting activities
     * @param pm The PackageManager instance
     * @return "confirm" if app was launched, "error" if no match or Unknown action,
     *         "permission_denied" if contacts permission denied,
     *         "no_contact:<name>" if no contact found,
     *         "ambiguous_contact:<name>" if multiple contacts match
     */
    suspend fun execute(action: AuraAction, context: Context, pm: PackageManager): String {
        return withContext(Dispatchers.IO) {
            when (action) {
                is AuraAction.OpenApp -> executeOpenApp(action, context, pm)
                is AuraAction.SendText -> executeSendText(action, context, pm)
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

    private fun executeSendText(action: AuraAction.SendText, context: Context, pm: PackageManager): String {
        val name = action.name.trim()
        if (name.isBlank()) {
            Log.w("AuraRouter", "SendText: blank name rejected")
            return "error"
        }

        // Check contacts permission
        val hasPermission = context.checkSelfPermission(
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w("AuraRouter", "SendText: READ_CONTACTS permission not granted")
            return "permission_denied"
        }

        // Resolve contact
        val matches = resolveContact(context, name)
        return when (matches.size) {
            0 -> {
                Log.w("AuraRouter", "SendText: no contact match for name='$name'")
                "no_contact:$name"
            }
            1 -> {
                val (_, number) = matches[0]
                sendSms(context, number, action.body)
            }
            else -> {
                // Multiple matches - return ambiguous for UI to handle
                val names = matches.map { it.first }.joinToString(", ")
                Log.w("AuraRouter", "SendText: ambiguous contact for name='$name', matches: $names")
                "ambiguous_contact:$name"
            }
        }
    }

    /** Resolve contact name to list of (displayName, phoneNumber) pairs.
     * Match order: exact -> startsWith -> contains (case-insensitive).
     * Runs on Dispatchers.IO (caller must ensure). */
    private fun resolveContact(context: Context, name: String): List<Pair<String, String>> {
        val normalizedName = name.lowercase().trim()
        if (normalizedName.isBlank()) return emptyList()

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$normalizedName%")

        var cursor: Cursor? = null
        val results = mutableListOf<Pair<String, String>>()
        try {
            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            if (cursor != null && cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (nameIndex >= 0 && numberIndex >= 0) {
                    do {
                        val displayName = cursor.getString(nameIndex) ?: ""
                        val number = cursor.getString(numberIndex) ?: ""
                        if (displayName.isNotBlank() && number.isNotBlank()) {
                            results.add(displayName to number)
                        }
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            Log.e("AuraRouter", "resolveContact failed name='$name'", e)
        } finally {
            cursor?.close()
        }

        // Sort by match quality: exact -> startsWith -> contains
        return results
            .map { it to matchQuality(it.first.lowercase(), normalizedName) }
            .sortedBy { it.second }
            .map { it.first }
    }

    /** Returns 0 for exact, 1 for startsWith, 2 for contains, 3 for no match */
    private fun matchQuality(displayName: String, target: String): Int {
        return when {
            displayName == target -> 0
            displayName.startsWith(target) -> 1
            displayName.contains(target) -> 2
            else -> 3
        }
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

    private fun sendSms(context: Context, number: String, body: String): String {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$number")
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Log.d("AuraRouter", "SendText: sending SMS to '$number' with body='${body.take(20)}'")
        return try {
            context.startActivity(intent)
            "confirm"
        } catch (e: Exception) {
            Log.e("AuraRouter", "SendText: startActivity failed for number='$number'", e)
            "error"
        }
    }
}