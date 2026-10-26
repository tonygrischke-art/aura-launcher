package com.aetheria.aura.avatar

sealed interface AvatarState {
    data class Neutral(
        val breathPhase: Float = 0f
    ) : AvatarState

    data class Listening(
        val ripplePhase: Float = 0f,
        val scale: Float = 1.1f
    ) : AvatarState

    data class Thinking(
        val rotationDegrees: Float = 0f,
        val violetShift: Float = 0f
    ) : AvatarState

    data class Speaking(
        val mouthOpen: Float = 0f,
        val scale: Float = 1f
    ) : AvatarState

    data class Error(
        val flashPhase: Float = 0f,
        val dimPhase: Float = 0f,
        val scale: Float = 0.8f
    ) : AvatarState

    data class Confirm(
        val bloomPhase: Float = 0f,
        val sparklePhase: Float = 0f,
        val scale: Float = 1.5f
    ) : AvatarState
}