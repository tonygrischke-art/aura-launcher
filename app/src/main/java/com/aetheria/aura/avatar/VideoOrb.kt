package com.aetheria.aura.avatar

import android.content.Context
import android.net.Uri
import android.os.Build
import android.view.TextureView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import com.aetheria.aura.R

@OptIn(UnstableApi::class)
@Composable
fun VideoOrb(
    state: AvatarState,
    modifier: Modifier = Modifier,
    lifecycleOwner: LifecycleOwner? = null
) {
    val context = LocalContext.current

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
    var currentState by remember { mutableStateOf(state) }
    var pendingState by remember { mutableStateOf<AvatarState?>(null) }
    var isCrossfading by remember { mutableStateOf(false) }
    var playerAIsCurrent by remember { mutableStateOf(true) }

    // Lifecycle handling
    val lifecycle = lifecycleOwner?.lifecycle ?: remember {
        object : LifecycleOwner {
            override val lifecycle = object : Lifecycle() {
                override fun getCurrentState() = Lifecycle.State.RESUMED
                override fun addObserver(observer: LifecycleEventObserver) {}
                override fun removeObserver(observer: LifecycleEventObserver) {}
            }
        }
    }

    // Release players on dispose
    DisposableEffect(Unit) {
        onDispose {
            playerA.release()
            playerB.release()
        }
    }

    // Pause/resume on lifecycle
    LaunchedEffect(lifecycle) {
        val observer = DefaultLifecycleObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    playerA.pause()
                    playerB.pause()
                }
                Lifecycle.Event.ON_START -> {
                    if (playerAIsCurrent) playerA.playWhenReady = true
                    if (!playerAIsCurrent) playerB.playWhenReady = true
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // Handle state changes
    LaunchedEffect(state) {
        if (state != currentState) {
            if (isCrossfading) {
                // Rapid state change: cancel pending and use latest
                pendingState = state
            } else {
                startCrossfade(state)
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
            currentState = pendingState ?: state
            isCrossfading = false

            // Check for rapid state changes during crossfade
            if (pendingState != null && pendingState != currentState) {
                startCrossfade(pendingState!!)
            }
        }
    }

    // Preload and start crossfade
    fun startCrossfade(newState: AvatarState) {
        pendingState = newState
        isCrossfading = true

        val nextPlayer = if (playerAIsCurrent) playerB else playerA
        val clipResId = stateToClipResId(newState)
        val mediaSource = createMediaSource(context, clipResId)

        nextPlayer.setMediaSource(mediaSource)
        nextPlayer.prepare()
        nextPlayer.seekTo(0)
        nextPlayer.playWhenReady = false
    }

    // Render two TextureViews stacked with cross-fade
    Box(
        modifier = modifier
            .size(280.dp)
            .align(Alignment.Center),
        contentAlignment = Alignment.Center
    ) {
        // Player A (bottom when crossfading out)
        AndroidTextureView(
            textureView = textureViewA,
            modifier = Modifier
                .fillMaxSize()
                .alpha(1f - crossfadeAlpha)
        )

        // Player B (top when crossfading in)
        AndroidTextureView(
            textureView = textureViewB,
            modifier = Modifier
                .fillMaxSize()
                .alpha(crossfadeAlpha)
        )
    }

    // Attach texture views to players
    LaunchedEffect(Unit) {
        playerA.setVideoTextureView(textureViewA)
        playerB.setVideoTextureView(textureViewB)

        // Start with idle
        val idleSource = createMediaSource(context, R.raw.aura_idle)
        playerA.setMediaSource(idleSource)
        playerA.prepare()
        playerA.playWhenReady = true
        playerA.repeatMode = ExoPlayer.REPEAT_MODE_ONE
        playerA.volume = 0f
    }
}

@OptIn(UnstableApi::class)
private fun createPlayer(context: Context): ExoPlayer {
    return ExoPlayer.Builder(context).build()
}

@OptIn(UnstableApi::class)
private fun createMediaSource(context: Context, @RawRes clipResId: Int): MediaSource {
    val uri = RawResourceDataSource.buildRawResourceUri(clipResId)
    val mediaItem = MediaItem.fromUri(uri)
    return ProgressiveMediaSource.Factory().createMediaSource(mediaItem)
}

private fun stateToClipResId(state: AvatarState): Int = when (state) {
    is AvatarState.Neutral -> R.raw.aura_idle
    is AvatarState.Listening -> R.raw.aura_listening
    is AvatarState.Thinking -> R.raw.aura_thinking
    is AvatarState.Acting -> R.raw.aura_acting
    is AvatarState.Speaking -> R.raw.aura_acting
    // Error and Confirm are procedural - should not reach here
    else -> R.raw.aura_idle
}

@Composable
private fun AndroidTextureView(
    textureView: TextureView,
    modifier: Modifier
) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { textureView },
        modifier = modifier,
        update = { it.setOpaque(false) }
    )
}