package com.aetheria.aura.avatar

import android.hardware.HardwareBuffer
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.repeatOnLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun WireOrb(
    mood: AuraMood,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRunning = repeatOnLifecycle(lifecycleOwner, Lifecycle.State.STARTED) { true } ?: false

    // Precomputed icosphere (subdivision 2 = 162 vertices, ~480 edges)
    val icosphere = remember { Icosphere(subdivisions = 2) }

    // Animation state
    val yaw by remember { mutableStateOf(0.0f) }
    val pitch by remember { mutableStateOf(0.0f) }
    val yawVel by remember { mutableStateOf(0.0f) }
    val pitchVel by remember { mutableStateOf(0.0f) }

    // Mood parameters with smooth blending
    val spinSpeed by animateMoodFloat(mood) {
        when (it) {
            is AuraMood.IDLE -> 0.15f
            is AuraMood.LISTENING -> 0.2f
            is AuraMood.THINKING -> 0.5f
            is AuraMood.ACTING -> 1.0f
        }
    }
    val cyanIntensity by animateMoodFloat(mood) {
        when (it) {
            is AuraMood.IDLE -> 1.0f
            is AuraMood.LISTENING -> 1.3f
            is AuraMood.THINKING -> 0.8f
            is AuraMood.ACTING -> 1.5f
        }
    }
    val violetShift by animateMoodFloat(mood) {
        when (it) {
            is AuraMood.IDLE -> 0.0f
            is AuraMood.LISTENING -> 0.0f
            is AuraMood.THINKING -> 1.0f
            is AuraMood.ACTING -> 0.5f
        }
    }
    val edgePulseSpeed by animateMoodFloat(mood) {
        when (it) {
            is AuraMood.IDLE -> 0.0f
            is AuraMood.LISTENING -> 0.0f
            is AuraMood.THINKING -> 0.0f
            is AuraMood.ACTING -> 3.0f
        }
    }
    val coreBreath by animateMoodFloat(mood) {
        when (it) {
            is AuraMood.IDLE -> 1.0f
            is AuraMood.LISTENING -> 1.2f
            is AuraMood.THINKING -> 0.8f
            is AuraMood.ACTING -> 1.5f
        }
    }
    val rippleTimer by remember { mutableStateOf(0.0f) }
    val ripplePhase by remember { mutableStateOf(0.0f) }

    // Drag handling
    Box(
        modifier = modifier
            .size(280.dp)
            .background(Color.Black)
            .align(Alignment.Center)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { yawVel = 0f; pitchVel = 0f },
                    onDrag = { delta ->
                        yawVel = -delta.x * 0.01f
                        pitchVel = delta.y * 0.01f
                        yaw += yawVel
                        pitch = (pitch + pitchVel).coerceIn(-80f * PI / 180f, 80f * PI / 180f)
                    },
                    onDragEnd = { },
                    onDragCancel = { }
                )
                detectTapGestures(onTap = {
                    onTap()
                    triggerRipple()
                })
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val size = min(this.size.width, this.size.height)
            val centerX = this.size.width / 2f
            val centerY = this.size.height / 2f
            val radius = size * 0.35f // 70% of box

            // Update rotation (auto-rotate when not dragging)
            val now = System.nanoTime()
            val dt = (now - lastFrameTime) / 1_000_000_000f
            lastFrameTime = now
            if (abs(yawVel) < 0.001f && abs(pitchVel) < 0.001f) {
                yaw += spinSpeed * dt * 0.5f
            } else {
                yawVel *= 0.95f
                pitchVel *= 0.95f
            }

            // Update ripple
            if (rippleTimer > 0f) {
                rippleTimer = max(0f, rippleTimer - dt)
                ripplePhase = 1f - rippleTimer / rippleDuration
            }

            // Mood ripple timer (LISTENING)
            if (mood is AuraMood.LISTENING) {
                rippleAccumulator += dt
                if (rippleAccumulator >= 1.2f) {
                    rippleAccumulator = 0f
                    triggerRipple()
                }
            }

            // Core breathing
            corePhase += dt * coreBreath * 0.5f

            // Render edges (back faces dimmer, front faces brighter)
            val cosPitch = cos(pitch)
            val sinPitch = sin(pitch)
            val cosYaw = cos(yaw)
            val sinYaw = sin(yaw)

            // Project all vertices
            val projected = icosphere.vertices.map { v ->
                val (x, y, z) = rotateVertex(v.x, v.y, v.z, cosPitch, sinPitch, cosYaw, sinYaw)
                val px = centerX + x * radius
                val py = centerY + y * radius
                val pz = z // depth for back-face culling/alpha
                val screenR = radius * 0.015f // node radius
                ProjectedVertex(px, py, pz, screenR)
            }

            // Ripple propagation
            if (rippleTimer > 0f) {
                val rippleProgress = ripplePhase
                for (i in projected.indices) {
                    val dist = angularDistance(rippleOrigin, icosphere.vertices[i])
                    val influence = max(0f, 1f - dist / (PI / 3f)) * (1f - rippleProgress)
                    if (influence > 0f) {
                        projected[i] = projected[i].copy(rippleBoost = influence)
                    }
                }
            }

            // ACTING: edge pulses
            if (edgePulseSpeed > 0f) {
                edgePulsePhase += dt * edgePulseSpeed
                if (edgePulsePhase > 1f) edgePulsePhase -= 1f
            }

            // Draw edges
            val paint = Paint()
            paint.style = Paint.Style.Stroke
            paint.strokeWidth = 1.5f
            paint.strokeCap = StrokeCap.Round

            for (edge in icosphere.edges) {
                val a = projected[edge.a]
                val b = projected[b]
                val avgZ = (a.z + b.z) / 2f
                val isFront = avgZ > 0f
                val depthAlpha = (1f + avgZ) / 2f // -1..1 -> 0..1

                // Base color
                val baseCyan = lerp(Color(0xFF00E5FF), Color(0xFF00FFFF), cyanIntensity - 1f)
                val baseViolet = lerp(Color(0xFF8B5CF6), Color(0xFFBB86FC), violetShift)
                val color = if (violetShift > 0f) lerp(baseCyan, baseViolet, violetShift) else baseCyan

                // ACTING edge pulse
                var alpha = if (isFront) 0.85f * depthAlpha else 0.25f * depthAlpha
                if (edgePulseSpeed > 0f) {
                    val pulsePos = (edgePulsePhase + (a.hashCode() % 100) / 100f) % 1f
                    alpha *= 0.5f + 0.5f * cos(pulsePos * 2f * PI)
                }

                paint.color = color.copy(alpha = alpha)
                drawLine(color.copy(alpha = alpha), Offset(a.x, a.y), Offset(b.x, b.y), 1.5f)
            }

            // Draw vertices (nodes)
            val nodePaint = Paint()
            nodePaint.style = Paint.Style.Fill
            for (v in projected) {
                val depthAlpha = (1f + v.z) / 2f
                val baseAlpha = 0.9f * depthAlpha
                val nodeCyan = Color(0xFF00E5FF).copy(alpha = baseAlpha * cyanIntensity * (1f + v.rippleBoost))
                val nodeViolet = Color(0xFF8B5CF6).copy(alpha = baseAlpha * violetShift * (1f + v.rippleBoost))
                val color = if (violetShift > 0f) lerp(nodeCyan, nodeViolet, violetShift * 0.7f) else nodeCyan
                drawCircle(color, radius = v.screenR, center = Offset(v.x, v.y))
            }

            // Core: pulsing violet radial gradient
            val coreRadius = radius * 0.18f * coreBreath * (1f + 0.3f * sin(corePhase * 2f * PI))
            val coreColor = Color(0xFF8B5CF6).copy(alpha = 0.9f)
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(coreColor, Color.Transparent),
                    center = Offset(centerX, centerY),
                    radius = coreRadius
                ),
                radius = coreRadius,
                center = Offset(centerX, centerY)
            )

            // Two concentric violet rings
            for (i in 1..2) {
                val ringRadius = coreRadius * (1f + i * 0.25f)
                val ringAlpha = (0.4f - i * 0.15f) * (1f + 0.2f * sin(corePhase * 2f * PI + i))
                val ringPaint = Paint()
                ringPaint.style = Paint.Style.Stroke
                ringPaint.strokeWidth = 1.5f
                ringPaint.strokeCap = StrokeCap.Round
                ringPaint.color = Color(0xFF8B5CF6).copy(alpha = ringAlpha)
                drawCircle(
                    center = Offset(centerX, centerY),
                    radius = ringRadius,
                    paint = ringPaint
                )
            }
        }
    }

    // Frame time logging
    LaunchedEffect(isRunning) {
        if (!isRunning) return
        var frameCount = 0
        var lastLog = System.nanoTime()
        var frameStart = System.nanoTime()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) frameStart = System.nanoTime()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        while (true) {
            val now = System.nanoTime()
            frameCount++
            if (now - lastLog >= 5_000_000_000L) {
                val avgMs = (now - frameStart) / 1_000_000f / frameCount
                Log.d("AuraWireOrb", "avg frame time: ${avgMs:.2f}ms over $frameCount frames")
                frameCount = 0
                lastLog = now
                frameStart = now
            }
            kotlinx.coroutines.delay(16)
        }
    }

    fun triggerRipple() {
        rippleTimer = rippleDuration
        ripplePhase = 0f
        rippleOrigin = icosphere.vertices[(yaw / (2f * PI) * icosphere.vertices.size).toInt() % icosphere.vertices.size]
    }
}

private fun rotateVertex(x: Float, y: Float, z: Float, cp: Float, sp: Float, cy: Float, sy: Float): Triple<Float, Float, Float> {
    // Rotate around X (pitch)
    val y1 = y * cp - z * sp
    val z1 = y * sp + z * cp
    // Rotate around Y (yaw)
    val x1 = x * cp + z1 * sp
    val z2 = -x * sp + z1 * cp
    // Wait, that's wrong - need proper yaw rotation around Y axis
    val x2 = x * cosYaw + z1 * sinYaw
    val z2 = -x * sinYaw + z1 * cosYaw
    return Triple(x2, y1, z2)
}

private data class ProjectedVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val screenR: Float,
    val rippleBoost: Float = 0f
)

private data class Edge(val a: Int, val b: Int)

private class Icosphere(val subdivisions: Int) {
    val vertices: List<Triple<Float, Float, Float>>
    val edges: List<Edge>

    init {
        // Icosphere: 12 base vertices of icosahedron
        val t = (1f + sqrt(5f)) / 2f // golden ratio
        val baseVerts = listOf(
            Triple(-1f, t, 0f), Triple(1f, t, 0f), Triple(-1f, -t, 0f), Triple(1f, -t, 0f),
            Triple(0f, -1f, t), Triple(0f, 1f, t), Triple(0f, -1f, -t), Triple(0f, 1f, -t),
            Triple(t, 0f, -1f), Triple(t, 0f, 1f), Triple(-t, 0f, -1f), Triple(-t, 0f, 1f)
        ).map { v ->
            val len = sqrt(v.first * v.first + v.second * v.second + v.third * v.third)
            Triple(v.first / len, v.second / len, v.third / len)
        }

        // 20 faces of icosahedron
        val faces = listOf(
            Triple(0, 11, 5), Triple(0, 5, 1), Triple(0, 1, 7), Triple(0, 7, 10), Triple(0, 10, 11),
            Triple(1, 5, 9), Triple(5, 11, 4), Triple(11, 10, 2), Triple(10, 7, 6), Triple(7, 1, 8),
            Triple(3, 9, 4), Triple(3, 4, 2), Triple(3, 2, 6), Triple(3, 6, 8), Triple(3, 8, 9),
            Triple(4, 9, 5), Triple(2, 4, 11), Triple(6, 2, 10), Triple(8, 6, 7), Triple(9, 8, 1)
        )

        // Subdivide
        var verts = baseVerts.toMutableList()
        var faceList = faces.toMutableList()
        val midCache = mutableMapOf<Pair<Int, Int>, Int>()

        fun getMid(a: Int, b: Int): Int {
            val key = if (a < b) Pair(a, b) else Pair(b, a)
            return midCache.getOrPut(key) {
                val va = verts[a]
                val vb = verts[b]
                val mx = (va.first + vb.first) / 2f
                val my = (va.second + vb.second) / 2f
                val mz = (va.third + vb.third) / 2f
                val len = sqrt(mx * mx + my * my + mz * mz)
                verts.add(Triple(mx / len, my / len, mz / len))
                verts.size - 1
            }
        }

        repeat(subdivisions) {
            val newFaces = mutableListOf<Triple<Int, Int, Int>>()
            for (f in faceList) {
                val a = f.first
                val b = f.second
                val c = f.third
                val ab = getMid(a, b)
                val bc = getMid(b, c)
                val ca = getMid(c, a)
                newFaces.add(Triple(a, ab, ca))
                newFaces.add(Triple(b, bc, ab))
                newFaces.add(Triple(c, ca, bc))
                newFaces.add(Triple(ab, bc, ca))
            }
            faceList = newFaces
        }

        this.vertices = verts.map { Triple(it.first, it.second, it.third) }

        // Build unique edges
        val edgeSet = mutableSetOf<Pair<Int, Int>>()
        for (f in faceList) {
            val edges = listOf(Pair(f.first, f.second), Pair(f.second, f.third), Pair(f.third, f.first))
            for (e in edges) {
                val ordered = if (e.first < e.second) e else Pair(e.second, e.first)
                edgeSet.add(ordered)
            }
        }
        this.edges = edgeSet.map { Edge(it.first, it.second) }.toList()
    }
}

private fun animateMoodFloat(mood: AuraMood, targetProvider: (AuraMood) -> Float): Float {
    val target = targetProvider(mood)
    return androidx.compose.animation.core.animateFloatAsState(
        targetValue = target,
        animationSpec = tween(300)
    ).value
}

private var lastFrameTime = System.nanoTime()
private var corePhase = 0.0f
private var edgePulsePhase = 0.0f
private var rippleTimer = 0.0f
private val rippleDuration = 0.8f
private var ripplePhase = 0.0f
private var rippleAccumulator = 0.0f
private var rippleOrigin = Triple(0f, 0f, 1f)

private fun angularDistance(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Float {
    val dot = a.first * b.first + a.second * b.second + a.third * b.third
    return acos(max(-1f, min(1f, dot)))
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    return Color.lerp(a, b, t.coerceIn(0f, 1f))
}

private val CosYaw = 1.0f
private val SinYaw = 0.0f