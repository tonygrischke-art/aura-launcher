package com.aetheria.aura.avatar

import android.content.Context
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.aetheria.aura.R

@OptIn(UnstableApi::class)
@Composable
fun VideoOrb(
    mood: AuraMood,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Map mood to raw resource
    val clipResId = when (mood) {
        AuraMood.IDLE -> R.raw.aura_idle
        AuraMood.LISTENING -> R.raw.aura_listening
        AuraMood.THINKING -> R.raw.aura_thinking
        AuraMood.ACTING -> R.raw.aura_acting
    }

    // Player A (visible)
    val playerA = remember { createPlayer(context) }
    val textureViewA = remember { TextureView(context).apply { setOpaque(false) } }

    // Player B (hidden, preloading)
    val playerB = remember { createPlayer(context) }
    val textureViewB = remember { TextureView(context).apply { setOpaque(false) } }

    // Cross-fade alpha: 0 = playerA visible, 1 = playerB visible
    val crossfadeAlpha by animateFloatAsState(
        targetValue = 0f,
        animationSpec = tween(180)
    )

    // Current state tracking
    var currentMood by remember { mutableStateOf(mood) }
    var pendingMood by remember { mutableStateOf<AuraMood?>(null) }
    var isCrossfading by remember { mutableStateOf(false) }
    var playerAIsCurrent by remember { mutableStateOf(true) }

    // Release players on dispose
    DisposableEffect(Unit) {
        onDispose {
            playerA.release()
            playerB.release()
        }
    }

    // Lifecycle observer for pause/resume
    val observer = remember {
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                playerA.pause()
                playerB.pause()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (playerAIsCurrent) playerA.playWhenReady = true
                else playerB.playWhenReady = true
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle mood changes
    LaunchedEffect(mood) {
        if (mood != currentMood) {
            if (isCrossfading) {
                // Rapid mood change: cancel pending and use latest
                pendingMood = mood
            } else {
                startCrossfade(mood)
            }
        }
    }

    // Crossfade completion handling
    LaunchedEffect(crossfadeAlpha) {
        if (crossfadeAlpha >= 0.99f && isCrossfading) {
            // Swap players
            val oldPlayer = if (playerAIsCurrent) playerA else playerB
            val newPlayer = if (playerAIsCurrent) playerB else playerA

            oldPlayer.pause()
            oldPlayer.seekTo(0)
            oldPlayer.playWhenReady = false

            newPlayer.playWhenReady = true

            playerAIsCurrent = !playerAIsCurrent
            currentMood = pendingMood ?: mood
            isCrossfading = false

            // Check for rapid mood changes during crossfade
            if (pendingMood != null && pendingMood != currentMood) {
                startCrossfade(pendingMood!!)
            }
        }
    }

    // Preload and start crossfade
    fun startCrossfade(newMood: AuraMood) {
        pendingMood = newMood
        isCrossfading = true

        val nextPlayer = if (playerAIsCurrent) playerB else playerA
        val clipRes = when (newMood) {
            AuraMood.IDLE -> R.raw.aura_idle
            AuraMood.LISTENING -> R.raw.aura_listening
            AuraMood.THINKING -> R.raw.aura_thinking
            AuraMood.ACTING -> R.raw.aura_acting
        }
        val uri = RawResourceDataSource.buildRawResourceUri(clipRes)
        val mediaItem = MediaItem.fromUri(uri)

        nextPlayer.setMediaItem(mediaItem)
        nextPlayer.prepare()
        nextPlayer.seekTo(0)
        nextPlayer.playWhenReady = false
        nextPlayer.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        nextPlayer.volume = 0f
    }

    // Render two TextureViews stacked with cross-fade
    Box(
        modifier = modifier
            .size(280.dp)
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Player A (bottom when crossfading out)
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { textureViewA },
            modifier = Modifier
                .fillMaxSize()
                .alpha(1f - crossfadeAlpha)
        )

        // Player B (top when crossfading in)
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { textureViewB },
            modifier = Modifier
                .fillMaxSize()
                .alpha(crossfadeAlpha)
        )
    }

    // Attach texture views to players and start initial clip
    LaunchedEffect(Unit) {
        playerA.setVideoTextureView(textureViewA)
        playerB.setVideoTextureView(textureViewB)

        // Start with idle clip on player A
        val uri = RawResourceDataSource.buildRawResourceUri(clipResId)
        val mediaItem = MediaItem.fromUri(uri)
        playerA.setMediaItem(mediaItem)
        playerA.prepare()
        playerA.playWhenReady = true
        playerA.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        playerA.volume = 0f
    }
}

private fun createPlayer(context: Context): ExoPlayer {
    return ExoPlayer.Builder(context).build()
}