package com.aetheria.aura.avatar

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

interface AvatarRenderer {
    @Composable
    fun render(
        state: AvatarState,
        mouthOpen: Float,
        modifier: Modifier = Modifier
    )
}