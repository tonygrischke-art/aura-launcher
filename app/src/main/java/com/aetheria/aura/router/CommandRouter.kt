package com.aetheria.aura.router

import com.aetheria.aura.actions.AuraAction

/**
 * Local command router for parsing voice/text commands into AuraAction.
 * Runs before sending to Hermes server as a fallback for common commands.
 */
object CommandRouter {

    private val TEXT_VERBS = setOf("text", "message", "sms", "send")

    /**
     * Route a raw user command to an AuraAction.
     * Returns null if the command should be sent to Hermes server for parsing.
     */
    fun route(input: String): AuraAction? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        val lowerInput = trimmed.lowercase()
        val words = lowerInput.split("\\s+".toRegex())

        // Check for "text <name> [message]" pattern
        if (words.size >= 2 && words[0] in TEXT_VERBS) {
            val name = words[1]
            val body = if (words.size > 2) words.drop(2).joinToString(" ") else ""
            return AuraAction.SendText(name, body)
        }

        // Check for "message <name> [message]" pattern (alternative)
        if (words.size >= 2 && words[0] == "message") {
            val name = words[1]
            val body = if (words.size > 2) words.drop(2).joinToString(" ") else ""
            return AuraAction.SendText(name, body)
        }

        return null // Let Hermes handle it
    }
}