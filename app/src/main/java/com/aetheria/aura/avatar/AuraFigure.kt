package com.aetheria.aura.avatar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aetheria.aura.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun AuraFigure(mood: AuraMood, modifier: Modifier = Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.aura_figure)

    val haloSpeed by animateFloatAsState(
        when (mood) { AuraMood.IDLE -> 9f; AuraMood.LISTENING -> 20f; AuraMood.THINKING -> 60f; AuraMood.ACTING -> 120f },
        tween(400), label = "haloSpeed")
    val glowBase by animateFloatAsState(
        when (mood) { AuraMood.IDLE -> 0.25f; AuraMood.LISTENING -> 0.55f; AuraMood.THINKING -> 0.40f; AuraMood.ACTING -> 0.85f },
        tween(400), label = "glowBase")
    val glowAmp by animateFloatAsState(
        when (mood) { AuraMood.IDLE -> 0.15f; AuraMood.LISTENING -> 0.20f; AuraMood.THINKING -> 0.30f; AuraMood.ACTING -> 0.05f },
        tween(400), label = "glowAmp")
    val pulseHz by animateFloatAsState(
        when (mood) { AuraMood.IDLE -> 0.25f; AuraMood.LISTENING -> 0.8f; AuraMood.THINKING -> 1.25f; AuraMood.ACTING -> 0.5f },
        tween(400), label = "pulseHz")
    val moodScale by animateFloatAsState(if (mood == AuraMood.LISTENING) 1.03f else 1f, tween(400), label = "moodScale")
    val rippleAlpha by animateFloatAsState(if (mood == AuraMood.LISTENING) 1f else 0f, tween(400), label = "ripple")
    val glowColor by animateColorAsState(
        if (mood == AuraMood.THINKING) AuraColors.Violet else AuraColors.Cyan, tween(400), label = "glowColor")

    val pop = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (mood == AuraMood.ACTING) {
            pop.animateTo(1.04f, tween(120))
            pop.animateTo(1f, tween(260))
        }
    }

    var time by remember { mutableFloatStateOf(0f) }
    var haloAngle by remember { mutableFloatStateOf(0f) }
    var pulsePhase by remember { mutableFloatStateOf(0f) }
    var ripple by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    time = (time + dt) % 24f
                    haloAngle = (haloAngle + haloSpeed * dt) % 360f
                    pulsePhase = (pulsePhase + pulseHz * dt) % 1f
                    ripple = (ripple + dt / 1.5f) % 1f
                }
                last = now
            }
        }
    }

    Canvas(modifier) {
        val twoPi = (2.0 * PI).toFloat()
        val c = center
        val r = size.minDimension / 2f
        val glow = (glowBase + glowAmp * (0.5f + 0.5f * sin(twoPi * pulsePhase))).coerceIn(0f, 1f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor.copy(alpha = glow * 0.55f), Color.Transparent),
                center = c, radius = r),
            radius = r, center = c)

        val haloR = r * 0.68f
        val haloTopLeft = Offset(c.x - haloR, c.y - haloR)
        val haloSize = Size(haloR * 2f, haloR * 2f)
        rotate(haloAngle, c) {
            drawArc(color = glowColor.copy(alpha = 0.65f), startAngle = 0f, sweepAngle = 70f,
                useCenter = false, topLeft = haloTopLeft, size = haloSize,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawArc(color = glowColor.copy(alpha = 0.45f), startAngle = 180f, sweepAngle = 40f,
                useCenter = false, topLeft = haloTopLeft, size = haloSize,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }

        if (rippleAlpha > 0.01f) {
            drawCircle(
                color = glowColor.copy(alpha = (1f - ripple) * 0.35f * rippleAlpha),
                radius = haloR * (1f + 0.25f * ripple), center = c,
                style = Stroke(width = 1.5.dp.toPx()))
        }

        val figScale = (1f + 0.0075f * (1f - cos(twoPi * time / 8f))) * moodScale * pop.value
        val dy = (-3f * (1f - cos(twoPi * time / 12f))).dp.toPx()
        withTransform({
            translate(top = dy)
            scale(figScale, figScale, pivot = c)
        }) {
            drawImage(
                image = bitmap,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                blendMode = BlendMode.Screen)
        }
    }
}
