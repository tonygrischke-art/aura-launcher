package com.aetheria.aura.avatar

import android.os.SystemClock
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.Snapshot.Companion.sendApplyNotifications
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RadialGradient
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawCircle
import androidx.compose.ui.graphics.drawscope.drawPath
import androidx.compose.ui.graphics.drawscope.drawRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.px
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun OrbRenderer(
    state: AvatarState,
    mouthOpen: Float,
    modifier: Modifier = Modifier
) {
    val isVisible = remember { mutableStateOf(true) }
    
    DisposableEffect(Unit) {
        onDispose { isVisible.value = false }
    }

    // Background
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
    ) {
        val size = min(this.size.width, this.size.height)
        val centerX = this.size.width / 2f
        val centerY = this.size.height / 2f
        val baseRadius = size * 0.35f

        when (state) {
            is AvatarState.Neutral -> drawNeutral(
                centerX, centerY, baseRadius, state.breathPhase
            )
            is AvatarState.Listening -> drawListening(
                centerX, centerY, baseRadius, state.ripplePhase, state.scale
            )
            is AvatarState.Thinking -> drawThinking(
                centerX, centerY, baseRadius, state.rotationDegrees, state.violetShift
            )
            is AvatarState.Speaking -> drawSpeaking(
                centerX, centerY, baseRadius, state.mouthOpen, state.scale
            )
            is AvatarState.Error -> drawError(
                centerX, centerY, baseRadius, state.flashPhase, state.dimPhase, state.scale
            )
            is AvatarState.Confirm -> drawConfirm(
                centerX, centerY, baseRadius, state.bloomPhase, state.sparklePhase, state.scale
            )
        }
    }
}

@Composable
private fun drawNeutral(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    breathPhase: Float
) {
    val animatedRadius = baseRadius * (1f + 0.08f * sin(breathPhase * 2f * PI))
    val alpha = 0.6f + 0.2f * sin(breathPhase * 2f * PI)

    // Outer glow
    val outerRadius = animatedRadius + 48f
    val outerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = alpha * 0.6f),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = outerRadius
    )
    drawCircle(outerBrush, centerX, centerY, outerRadius)

    // Inner glow
    val innerRadius = animatedRadius + 12f
    val innerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = alpha * 0.95f),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = innerRadius
    )
    drawCircle(innerBrush, centerX, centerY, innerRadius)

    // Core orb
    val coreBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF),
            Color(0xFF8B5CF6)
        ),
        center = Offset(centerX, centerY),
        radius = animatedRadius
    )
    drawCircle(coreBrush, centerX, centerY, animatedRadius)
}

@Composable
private fun drawListening(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    ripplePhase: Float,
    scale: Float
) {
    val animatedRadius = baseRadius * scale
    val rippleRadius = animatedRadius + (ripplePhase * 60f)
    val rippleAlpha = (1f - ripplePhase) * 0.8f

    // Outer glow (static)
    val outerRadius = animatedRadius + 48f
    val outerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = 0.6f),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = outerRadius
    )
    drawCircle(outerBrush, centerX, centerY, outerRadius)

    // Inner glow
    val innerRadius = animatedRadius + 12f
    val innerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = 0.95f),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = innerRadius
    )
    drawCircle(innerBrush, centerX, centerY, innerRadius)

    // Ripple ring
    if (rippleAlpha > 0) {
        val rippleBrush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF00E5FF).copy(alpha = rippleAlpha),
                Color(0xFF00E5FF).copy(alpha = 0f)
            ),
            center = Offset(centerX, centerY),
            radius = rippleRadius
        )
        drawCircle(rippleBrush, centerX, centerY, rippleRadius)
    }

    // Core orb
    val coreBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF),
            Color(0xFF8B5CF6)
        ),
        center = Offset(centerX, centerY),
        radius = animatedRadius
    )
    drawCircle(coreBrush, centerX, centerY, animatedRadius)
}

@Composable
private fun drawThinking(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    rotationDegrees: Float,
    violetShift: Float
) {
    val animatedRadius = baseRadius
    val rotationRadians = rotationDegrees * PI / 180f

    // Outer glow with violet shift
    val outerRadius = animatedRadius + 48f
    val outerViolet = lerp(Color(0xFF00E5FF), Color(0xFF8B5CF6), violetShift)
    val outerBrush = Brush.radialGradient(
        colors = listOf(
            outerViolet.copy(alpha = 0.6f),
            outerViolet.copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = outerRadius
    )
    drawCircle(outerBrush, centerX, centerY, outerRadius)

    // Inner glow
    val innerRadius = animatedRadius + 12f
    val innerViolet = lerp(Color(0xFF00E5FF), Color(0xFF8B5CF6), violetShift * 0.8f)
    val innerBrush = Brush.radialGradient(
        colors = listOf(
            innerViolet.copy(alpha = 0.95f),
            innerViolet.copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = innerRadius
    )
    drawCircle(innerBrush, centerX, centerY, innerRadius)

    // Core orb with rotation indicator (three arcs)
    val coreBrush = Brush.radialGradient(
        colors = listOf(
            lerp(Color(0xFF00E5FF), Color(0xFF8B5CF6), violetShift),
            Color(0xFF8B5CF6)
        ),
        center = Offset(centerX, centerY),
        radius = animatedRadius
    )
    drawCircle(coreBrush, centerX, centerY, animatedRadius)

    // Three rotating arcs
    for (i in 0..2) {
        val arcStart = rotationRadians + (i * 2f * PI / 3f)
        val arcSweep = PI * 0.6f
        val arcPath = Path().apply {
            addArc(
                Rect(
                    centerX - animatedRadius * 1.2f,
                    centerY - animatedRadius * 1.2f,
                    centerX + animatedRadius * 1.2f,
                    centerY + animatedRadius * 1.2f
                ),
                (arcStart * 180f / PI) % 360f,
                arcSweep * 180f / PI
            )
        }
        drawPath(
            arcPath,
            Color(0xFF00E5FF).copy(alpha = 0.7f),
            style = PaintStyle.Stroke,
            strokeWidth = 4f
        )
    }
}

@Composable
private fun drawSpeaking(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    mouthOpen: Float,
    scale: Float
) {
    val animatedRadius = baseRadius * scale
    val mouthFactor = mouthOpen.coerceIn(0f, 1f)

    // Outer glow
    val outerRadius = animatedRadius + 48f
    val outerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = 0.6f),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = outerRadius
    )
    drawCircle(outerBrush, centerX, centerY, outerRadius)

    // Inner glow
    val innerRadius = animatedRadius + 12f
    val innerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = 0.95f),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = innerRadius
    )
    drawCircle(innerBrush, centerX, centerY, innerRadius)

    // Core orb - vertically stretched based on mouthOpen
    val coreBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF),
            Color(0xFF8B5CF6)
        ),
        center = Offset(centerX, centerY),
        radius = animatedRadius
    )

    // Draw as ellipse for mouth effect
    val verticalRadius = animatedRadius * (1f + mouthFactor * 0.5f)
    val horizontalRadius = animatedRadius * (1f - mouthFactor * 0.15f)

    val path = Path().apply {
        addOval(
            Rect(
                centerX - horizontalRadius,
                centerY - verticalRadius,
                centerX + horizontalRadius,
                centerY + verticalRadius
            )
        )
    }
    drawPath(path, coreBrush)
}

@Composable
private fun drawError(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    flashPhase: Float,
    dimPhase: Float,
    scale: Float
) {
    val animatedRadius = baseRadius * scale

    // Flash phase (first 200ms) - red flash
    val flashAlpha = if (flashPhase < 1f) {
        (1f - flashPhase) * 0.8f
    } else 0f

    // Dim phase (next 1000ms) - dim red
    val dimAlpha = if (flashPhase >= 1f && dimPhase < 1f) {
        0.4f * (1f - dimPhase)
    } else if (flashPhase >= 1f) 0f else 0f

    // Outer glow - red during flash
    val outerRadius = animatedRadius + 48f
    val outerColor = if (flashAlpha > 0) {
        Color(0xFFFF4D1A).copy(alpha = flashAlpha * 0.6f)
    } else {
        Color(0xFFFF4D1A).copy(alpha = dimAlpha * 0.6f)
    }
    val outerBrush = Brush.radialGradient(
        colors = listOf(outerColor, outerColor.copy(alpha = 0f)),
        center = Offset(centerX, centerY),
        radius = outerRadius
    )
    drawCircle(outerBrush, centerX, centerY, outerRadius)

    // Inner glow
    val innerRadius = animatedRadius + 12f
    val innerColor = if (flashAlpha > 0) {
        Color(0xFFFF4D1A).copy(alpha = flashAlpha * 0.95f)
    } else {
        Color(0xFFFF4D1A).copy(alpha = dimAlpha * 0.95f)
    }
    val innerBrush = Brush.radialGradient(
        colors = listOf(innerColor, innerColor.copy(alpha = 0f)),
        center = Offset(centerX, centerY),
        radius = innerRadius
    )
    drawCircle(innerBrush, centerX, centerY, innerRadius)

    // Core orb - red tinted
    val coreColor = if (flashAlpha > 0) {
        Color(0xFFFF4D1A)
    } else {
        Color(0xFFFF4D1A).copy(alpha = 0.6f + dimAlpha * 0.4f)
    }
    val coreBrush = Brush.radialGradient(
        colors = listOf(coreColor, coreColor),
        center = Offset(centerX, centerY),
        radius = animatedRadius
    )
    drawCircle(coreBrush, centerX, centerY, animatedRadius)
}

@Composable
private fun drawConfirm(
    centerX: Float,
    centerY: Float,
    baseRadius: Float,
    bloomPhase: Float,
    sparklePhase: Float,
    scale: Float
) {
    val animatedRadius = baseRadius * scale
    val bloomRadius = animatedRadius * (1f + bloomPhase * 0.5f)

    // Bloom expansion
    val outerRadius = bloomRadius + 48f
    val outerAlpha = (1f - bloomPhase) * 0.6f
    val outerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = outerAlpha),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = outerRadius
    )
    drawCircle(outerBrush, centerX, centerY, outerRadius)

    // Inner glow
    val innerRadius = bloomRadius + 12f
    val innerAlpha = (1f - bloomPhase) * 0.95f
    val innerBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF).copy(alpha = innerAlpha),
            Color(0xFF00E5FF).copy(alpha = 0f)
        ),
        center = Offset(centerX, centerY),
        radius = innerRadius
    )
    drawCircle(innerBrush, centerX, centerY, innerRadius)

    // Core orb
    val coreBrush = Brush.radialGradient(
        colors = listOf(
            Color(0xFF00E5FF),
            Color(0xFF8B5CF6)
        ),
        center = Offset(centerX, centerY),
        radius = bloomRadius
    )
    drawCircle(coreBrush, centerX, centerY, bloomRadius)

    // Sparkle particles (one-shot)
    if (sparklePhase < 1f) {
        val sparkleAlpha = (1f - sparklePhase) * 0.9f
        for (i in 0..7) {
            val angle = (i * 2f * PI / 8f) + (sparklePhase * PI * 0.5f)
            val distance = bloomRadius + (sparklePhase * 40f)
            val x = centerX + cos(angle) * distance
            val y = centerY + sin(angle) * distance
            val sparkleSize = (1f - sparklePhase) * 6f + 2f
            drawCircle(
                Color.White.copy(alpha = sparkleAlpha),
                x, y, sparkleSize
            )
        }
    }
}

// Animation controllers
@Composable
fun rememberNeutralAnimation(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "neutral")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = Easing.InOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    ).value
}

@Composable
fun rememberListeningAnimation(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "listening")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = Easing.Linear),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple"
    ).value
}

@Composable
fun rememberThinkingAnimation(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = Easing.Linear),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    ).value
}

@Composable
fun rememberSpeakingAnimation(targetMouthOpen: Float): Float {
    return animateFloatAsState(
        targetValue = targetMouthOpen,
        animationSpec = spring(
            dampingRatio = 0.8f,
            stiffness = 200f
        ),
        label = "mouth"
    ).value
}

@Composable
fun rememberErrorAnimation(): Pair<Float, Float> {
    var flashPhase by remember { mutableStateOf(0f) }
    var dimPhase by remember { mutableStateOf(0f) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            // Flash phase: 200ms
            val startTime = SystemClock.uptimeMillis()
            while (flashPhase < 1f) {
                val elapsed = (SystemClock.uptimeMillis() - startTime) / 1000f
                flashPhase = min(elapsed / 0.2f, 1f)
                sendApplyNotifications()
                kotlinx.coroutines.delay(16)
            }
            // Dim phase: 1000ms
            val dimStart = SystemClock.uptimeMillis()
            while (dimPhase < 1f) {
                val elapsed = (SystemClock.uptimeMillis() - dimStart) / 1000f
                dimPhase = min(elapsed / 1.0f, 1f)
                sendApplyNotifications()
                kotlinx.coroutines.delay(16)
            }
        }
    }

    return Pair(flashPhase, dimPhase)
}

@Composable
fun rememberConfirmAnimation(): Pair<Float, Float> {
    var bloomPhase by remember { mutableStateOf(0f) }
    var sparklePhase by remember { mutableStateOf(0f) }
    var started by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            // Bloom: 600ms
            val startTime = SystemClock.uptimeMillis()
            while (bloomPhase < 1f) {
                val elapsed = (SystemClock.uptimeMillis() - startTime) / 1000f
                bloomPhase = min(elapsed / 0.6f, 1f)
                sendApplyNotifications()
                kotlinx.coroutines.delay(16)
            }
            // Sparkle: 200ms
            val sparkleStart = SystemClock.uptimeMillis()
            while (sparklePhase < 1f) {
                val elapsed = (SystemClock.uptimeMillis() - sparkleStart) / 1000f
                sparklePhase = min(elapsed / 0.2f, 1f)
                sendApplyNotifications()
                kotlinx.coroutines.delay(16)
            }
        }
    }

    return Pair(bloomPhase, sparklePhase)
}