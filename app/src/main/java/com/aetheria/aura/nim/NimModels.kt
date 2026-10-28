package com.aetheria.aura.nim

import com.google.gson.annotations.SerializedName

/**
 * NIM API request/response models matching OpenAI-compatible format
 */
data class NimChatRequest(
    @SerializedName("model") val model: String,
    @SerializedName("messages") val messages: List<NimChatMessage>,
    @SerializedName("stream") val stream: Boolean = true,
    @SerializedName("max_tokens") val maxTokens: Int = 2048,
    @SerializedName("temperature") val temperature: Double = 0.7
)

data class NimChatMessage(
    @SerializedName("role") val role: String,
    @SerializedName("content") val content: String
)

/**
 * SSE streaming response chunk
 */
data class NimStreamChunk(
    @SerializedName("id") val id: String?,
    @SerializedName("object") val `object`: String?,
    @SerializedName("created") val created: Long?,
    @SerializedName("model") val model: String?,
    @SerializedName("choices") val choices: List<NimStreamChoice>
)

data class NimStreamChoice(
    @SerializedName("index") val index: Int,
    @SerializedName("delta") val delta: NimDelta,
    @SerializedName("finish_reason") val finishReason: String?
)

data class NimDelta(
    @SerializedName("role") val role: String?,
    @SerializedName("content") val content: String?
)

/**
 * Non-streaming response (for health checks)
 */
data class NimChatResponse(
    @SerializedName("id") val id: String,
    @SerializedName("object") val `object`: String,
    @SerializedName("created") val created: Long,
    @SerializedName("model") val model: String,
    @SerializedName("choices") val choices: List<NimChoice>,
    @SerializedName("usage") val usage: NimUsage?
)

data class NimChoice(
    @SerializedName("index") val index: Int,
    @SerializedName("message") val message: NimChatMessage,
    @SerializedName("finish_reason") val finishReason: String
)

data class NimUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int,
    @SerializedName("completion_tokens") val completionTokens: Int,
    @SerializedName("total_tokens") val totalTokens: Int
)

/**
 * Models list response
 */
data class NimModelsResponse(
    @SerializedName("object") val `object`: String,
    @SerializedName("data") val data: List<NimModel>
)

data class NimModel(
    @SerializedName("id") val id: String,
    @SerializedName("object") val `object`: String,
    @SerializedName("created") val created: Long,
    @SerializedName("owned_by") val ownedBy: String
)