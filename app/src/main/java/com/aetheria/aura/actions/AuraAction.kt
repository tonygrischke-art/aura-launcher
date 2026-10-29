package com.aetheria.aura.actions

import org.json.JSONObject

/**
 * Sealed class representing possible actions that Aura can execute.
 * Parsed from JSON format: {"action":"<name>","args":{...}}
 */
sealed class AuraAction {

    /** Action to open an app by matching the target against installed app labels */
    data class OpenApp(val target: String) : AuraAction()

    /** Action to send a text message to a contact */
    data class SendText(val name: String, val body: String) : AuraAction()

    /** Action for any unrecognized or unhandled command */
    object Unknown : AuraAction()

    /** Parse JSON into an AuraAction sealed class.
     *  Returns Unknown if parsing fails or the action is not recognized.
     */
    companion object {
        @JvmStatic
        fun parse(json: String): AuraAction {
            return try {
                val obj = JSONObject(json)
                val action = obj.getString("action")
                val args = obj.optJSONObject("args")

                return when (action) {
                    "open_app" -> {
                        val target = args?.getString("target") ?: ""
                        AuraAction.OpenApp(target)
                    }
                    "send_text" -> {
                        val name = args?.getString("name") ?: ""
                        val body = args?.getString("body") ?: ""
                        AuraAction.SendText(name, body)
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