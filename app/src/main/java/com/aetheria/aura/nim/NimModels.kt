package com.aetheria.aura.nim

/**
 * NIM API data models - top-level for easy import
 */

data class NimChatMessage(
    val role: String,
    val content: String
)

data class NimChatRequest(
    val model: String,
    val messages: List<NimChatMessage>,
    val stream: Boolean = true,
    val max_tokens: Int = 512,
    val temperature: Float = 0.7f
)

data class NimChatResponse(
    val id: String,
    val choices: List<NimChoice>
)

data class NimChoice(
    val index: Int,
    val delta: NimDelta,
    val finish_reason: String?
)

data class NimDelta(
    val role: String?,
    val content: String?,
    val reasoning_content: String?
)