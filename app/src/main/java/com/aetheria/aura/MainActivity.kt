package com.aetheria.aura

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import com.aetheria.aura.avatar.AvatarState
import com.aetheria.aura.avatar.OrbRenderer
import com.aetheria.aura.avatar.debug.AvatarDebugScreen
import com.aetheria.aura.avatar.rememberNeutralAnimation
import com.aetheria.aura.avatar.rememberListeningAnimation
import com.aetheria.aura.avatar.rememberThinkingAnimation
import com.aetheria.aura.avatar.rememberSpeakingAnimation
import com.aetheria.aura.avatar.rememberErrorAnimation
import com.aetheria.aura.avatar.rememberConfirmAnimation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuraTheme {
                MainScreen()
            }
        }
    }
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF00E5FF),
        secondary = Color(0xFF8B5CF6),
        surface = Color(0xFF000000),
        error = Color(0xFFFF4D1A),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onSurface = Color.White,
        onError = Color.White,
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        shapes = androidx.compose.material3.Shapes(),
        content = content
    )
}

@Composable
fun MainScreen() {
    var showDebug by remember { mutableStateOf(false) }
    var avatarState by remember { mutableStateOf<AvatarState>(AvatarState.Neutral()) }
    var mouthOpen by remember { mutableStateOf(0.0f) }

    // Animation values for each state
    val breathPhase = rememberNeutralAnimation()
    val ripplePhase = rememberListeningAnimation()
    val rotationDegrees = rememberThinkingAnimation()
    val smoothedMouthOpen = rememberSpeakingAnimation(mouthOpen)
    val (flashPhase, dimPhase) = rememberErrorAnimation()
    val (bloomPhase, sparklePhase) = rememberConfirmAnimation()

    // Update avatarState with animation values
    val currentState = avatarState
    val animatedState = remember(currentState, breathPhase, ripplePhase, rotationDegrees, smoothedMouthOpen, flashPhase, dimPhase, bloomPhase, sparklePhase) {
        when (currentState) {
            is AvatarState.Neutral -> avatarState.copy(breathPhase = breathPhase)
            is AvatarState.Listening -> avatarState.copy(ripplePhase = ripplePhase)
            is AvatarState.Thinking -> avatarState.copy(rotationDegrees = rotationDegrees, violetShift = (rotationDegrees % 360f) / 360f)
            is AvatarState.Speaking -> avatarState.copy(mouthOpen = smoothedMouthOpen, scale = 1f + smoothedMouthOpen * 0.3f)
            is AvatarState.Error -> avatarState.copy(flashPhase = flashPhase, dimPhase = dimPhase)
            is AvatarState.Confirm -> avatarState.copy(bloomPhase = bloomPhase, sparklePhase = sparklePhase)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Orb renderer fills the screen
        OrbRenderer(
            state = animatedState,
            mouthOpen = smoothedMouthOpen,
            modifier = Modifier.fillMaxSize()
        )

        // Debug screen overlay
        if (showDebug) {
            AvatarDebugScreen(
                onClose = { showDebug = false },
                currentState = avatarState,
                onStateChange = { avatarState = it },
                mouthOpen = mouthOpen,
                onMouthOpenChange = { mouthOpen = it }
            )
        }

        // Small toggle button in corner
        IconButton(
            onClick = { showDebug = true },
            modifier = Modifier
                .padding(16.dp)
                .align(Alignment.TopEnd)
        ) {
            androidx.compose.material.Icon(
                Settings,
                contentDescription = "Debug",
                tint = Color.White.copy(alpha = 0.7f),
                size = 28.dp
            )
        }
    }
}