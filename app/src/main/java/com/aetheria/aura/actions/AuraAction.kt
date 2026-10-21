package com.aetheria.aura.actions

import org.json.JSONObject

/**
 * Sealed class representing possible actions that Aura can execute.
 * Parsed from JSON format: {"action":"<name>","args":{...}}
 */
sealed class AuraAction {

    /** Action to open an app by matching the target against installed app labels */
    data class OpenApp(val target: String) : AuraAction()

    /** Action for any unrecognized or unhandled command */
    object Unknown : AuraAction()

    /** Parse JSON into an AuraAction sealed class.
     *  Returns Unknown if parsing fails or the action is not recognized.
     */
    companion object {
        @JvmStatic
        fun parse(json: String): AuraAction {
            try {
                val obj = JSONObject(json)
                val action = obj.getString("action")
                val args = obj.optJSONObject("args")

                return when (action) {
                    "open_app" -> {
                        val target = args?.getString("target") ?: ""
                        AuraAction.OpenApp(target)
                    }
                    else -> AuraAction.Unknown
                }
            } catch (e: Exception) {
                // Parsing failure returns Unknown
                AuraAction.Unknown
            }
        }
    }
}