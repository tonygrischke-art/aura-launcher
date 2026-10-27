package com.aetheria.aura.avatar

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
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

    val icosphere = remember { Icosphere(subdivisions = 2) }

    var yaw by remember { mutableStateOf(0.0f) }
    var pitch by remember { mutableStateOf(0.0f) }
    var yawVel by remember { mutableStateOf(0.0f) }
    var pitchVel by remember { mutableStateOf(0.0f) }

    val spinSpeed = remember {
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = when (mood) {
                AuraMood.IDLE -> 0.15f
                AuraMood.LISTENING -> 0.2f
                AuraMood.THINKING -> 0.5f
                AuraMood.ACTING -> 1.0f
            },
            animationSpec = tween(300)
        )
    }.value

    val cyanIntensity = remember {
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = when (mood) {
                AuraMood.IDLE -> 1.0f
                AuraMood.LISTENING -> 1.3f
                AuraMood.THINKING -> 0.8f
                AuraMood.ACTING -> 1.5f
            },
            animationSpec = tween(300)
        )
    }.value

    val violetShift = remember {
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = when (mood) {
                AuraMood.IDLE -> 0.0f
                AuraMood.LISTENING -> 0.0f
                AuraMood.THINKING -> 1.0f
                AuraMood.ACTING -> 0.5f
            },
            animationSpec = tween(300)
        )
    }.value

    val coreBreath = remember {
        androidx.compose.animation.core.animateFloatAsState(
            targetValue = when (mood) {
                AuraMood.IDLE -> 1.0f
                AuraMood.LISTENING -> 1.2f
                AuraMood.THINKING -> 0.8f
                AuraMood.ACTING -> 1.5f
            },
            animationSpec = tween(300)
        )
    }.value

    val icosphere = remember { Icosphere(subdivisions = 2) }

    var rippleTimer by remember { mutableStateOf(0.0f) }
    var ripplePhase by remember { mutableStateOf(0.0f) }
    var rippleOrigin by remember { mutableStateOf(icosphere.vertices[0]) }
    var rippleAccumulator by remember { mutableStateOf(0.0f) }
    var corePhase by remember { mutableStateOf(0.0f) }
    var edgePulsePhase by remember { mutableStateOf(0.0f) }
    var lastFrameTime by remember { mutableStateOf(System.nanoTime()) }
    var yawVel by remember { mutableStateOf(0.0f) }
    var pitchVel by remember { mutableStateOf(0.0f) }

    LaunchedEffect(lifecycleOwner) {
        repeatOnLifecycle(lifecycleOwner, Lifecycle.State.STARTED) {
            var frameCount = 0
            var lastLog = System.nanoTime()
            while (true) {
                val now = System.nanoTime()
                val dt = (now - lastFrameTime) / 1_000_000_000f
                lastFrameTime = now

                if (Math.abs(yawVel) < 0.001f && Math.abs(pitchVel) < 0.001f) {
                    yaw += spinSpeed * dt * 0.5f
                } else {
                    yawVel *= 0.95f
                    pitchVel *= 0.95f
                }

                if (rippleTimer > 0f) {
                    rippleTimer = max(0f, rippleTimer - dt)
                    ripplePhase = 1f - rippleTimer / 0.8f
                }

                if (mood == AuraMood.LISTENING) {
                    rippleAccumulator += dt
                    if (rippleAccumulator >= 1.2f) {
                        rippleAccumulator = 0f
                        rippleTimer = 0.8f
                        ripplePhase = 0f
                        val idx = ((yaw / (2f * PI) * icosphere.vertices.size) % icosphere.vertices.size).toInt()
                        rippleOrigin = icosphere.vertices[if (idx >= 0) idx else 0]
                    }
                }

                corePhase += dt * coreBreath * 0.5f

                val edgePulseSpeed = when (mood) {
                    AuraMood.ACTING -> 3.0f
                    else -> 0.0f
                }
                if (edgePulseSpeed > 0f) {
                    edgePulsePhase += dt * edgePulseSpeed
                    if (edgePulsePhase > 1f) edgePulsePhase -= 1f
                }

                corePhase += dt * coreBreath * 0.5f

                frameCount++
                if (System.nanoTime() - lastLog >= 5_000_000_000L) {
                    val avgMs = (System.nanoTime() - lastLog) / 1_000_000f / frameCount
                    Log.d("AuraWireOrb", "avg frame time: ${avgMs:.2f}ms over $frameCount frames")
                    frameCount = 0
                    lastLog = System.nanoTime()
                }

                kotlinx.coroutines.delay(16)
            }
        }
    }

    Box(
        modifier = modifier
            .size(280.dp)
            .background(Color.Black)
            .align(Alignment.Center)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { yawVel = 0f; pitchVel = 0f },
                    onDrag = { change, delta ->
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
                    rippleTimer = 0.8f
                    ripplePhase = 0f
                    val idx = ((yaw / (2f * PI) * icosphere.vertices.size) % icosphere.vertices.size).toInt()
                    rippleOrigin = icosphere.vertices[if (idx >= 0) idx else 0]
                })
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val size = min(this.size.width, this.size.height)
            val centerX = this.size.width / 2f
            val centerY = this.size.height / 2f
            val radius = size * 0.35f

            val cosPitch = cos(pitch)
            val sinPitch = sin(pitch)
            val cosYaw = cos(yaw)
            val sinYaw = sin(yaw)

            val projected = icosphere.vertices.map { v ->
                val x1 = v.first * cosPitch + v.third * sinPitch
                val y1 = v.second
                val z1 = -v.first * sinPitch + v.third * cosPitch

                val x2 = x1 * cosYaw + z1 * sinYaw
                val z2 = -x1 * sinYaw + z1 * cosYaw

                val px = centerX + x2 * radius
                val py = centerY + y1 * radius
                val pz = z1
                val screenR = radius * 0.015f
                ProjectedVertex(px, py, pz, screenR)
            }

            val rippleProgress = ripplePhase
            val projectedWithRipple = projected.mapIndexed { i, v ->
                val dist = angularDistance(rippleOrigin, icosphere.vertices[i])
                val influence = max(0f, 1f - dist / (PI / 3f)) * (1f - rippleProgress)
                if (influence > 0f) {
                    v.copy(rippleBoost = influence)
                } else v
            }

            for (edge in icosphere.edges) {
                val a = projectedWithRipple[edge.a]
                val b = projectedWithRipple[edge.b]
                val avgZ = (a.z + b.z) / 2f
                val isFront = avgZ > 0f
                val depthAlpha = (1f + avgZ) / 2f

                val baseCyan = lerp(Color(0xFF00E5FF), Color(0xFF00FFFF), cyanIntensity - 1f)
                val baseViolet = lerp(Color(0xFF8B5CF6), Color(0xFFBB86FC), violetShift)
                val color = if (violetShift > 0f) lerp(baseCyan, baseViolet, violetShift) else baseCyan

                var alpha = if (a.z > 0f) 0.85f * depthAlpha else 0.25f * depthAlpha

                val edgePulseSpeed = when (mood) {
                    AuraMood.ACTING -> 3.0f
                    else -> 0.0f
                }
                if (edgePulseSpeed > 0f) {
                    val pulsePos = (edgePulsePhase + (a.hashCode() % 100) / 100f) % 1f
                    alpha *= 0.5f + 0.5f * cos(pulsePos * 2f * PI)
                }

                drawLine(
                    color.copy(alpha = alpha),
                    Offset(a.x, a.y),
                    Offset(b.x, b.y),
                    1.5f
                )
            }

            for (v in projectedWithRipple) {
                val depthAlpha = (1f + v.z) / 2f
                val baseAlpha = 0.9f * depthAlpha
                val nodeCyan = Color(0xFF00E5FF).copy(alpha = baseAlpha * cyanIntensity * (1f + v.rippleBoost))
                val nodeViolet = Color(0xFF8B5CF6).copy(alpha = baseAlpha * violetShift * (1f + v.rippleBoost))
                val color = if (violetShift > 0f) lerp(nodeCyan, nodeViolet, violetShift * 0.7f) else nodeCyan
                drawCircle(color, radius = v.screenR, center = Offset(v.x, v.y))
            }

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

    DisposableEffect(Unit) {
        onDispose { }
    }
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
        val t = (1f + sqrt(5f)) / 2f
        val baseVerts = listOf(
            Triple(-1f, t, 0f), Triple(1f, t, 0f), Triple(-1f, -t, 0f), Triple(1f, -t, 0f),
            Triple(0f, -1f, t), Triple(0f, 1f, t), Triple(0f, -1f, -t), Triple(0f, 1f, -t),
            Triple(t, 0f, -1f), Triple(t, 0f, 1f), Triple(-t, 0f, -1f), Triple(-t, 0f, 1f)
        ).map { v ->
            val len = sqrt(v.first * v.first + v.second * v.second + v.third * v.third)
            Triple(v.first / len, v.second / len, v.third / len)
        )

        val faces = listOf(
            Triple(0, 11, 5), Triple(0, 5, 1), Triple(0, 1, 7), Triple(0, 7, 10), Triple(0, 10, 11),
            Triple(1, 5, 9), Triple(5, 11, 4), Triple(11, 10, 2), Triple(10, 7, 6), Triple(7, 1, 8),
            Triple(3, 9, 4), Triple(3, 4, 2), Triple(3, 2, 6), Triple(3, 6, 8), Triple(3, 8, 9),
            Triple(4, 9, 5), Triple(2, 4, 11), Triple(6, 2, 10), Triple(8, 6, 7), Triple(9, 8, 1)
        )

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

private data class ProjectedVertex(
    val x: Float,
    val y: Float,
    val z: Float,
    val screenR: Float,
    val rippleBoost: Float = 0f
)

private data class Edge(val a: Int, val b: Int)

private fun angularDistance(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Float {
    val dot = a.first * b.first + a.second * b.second + a.third * b.third
    return acos(max(-1f, min(1f, dot)))
}

private fun lerp(a: Color, b: Color, t: Float): Color {
    return Color.lerp(a, b, t.coerceIn(0f, 1f))
}

private var frameCount = 0
private var lastLog = System.nanoTime()
private var corePhase = 0.0f
private var edgePulsePhase = 0.0f
private var rippleTimer = 0.0f
private val rippleDuration = 0.8f
private var ripplePhase = 0.0f
private var rippleAccumulator = 0.0f
private var rippleOrigin = Triple(0f, 0f, 1f)
private var yawVel = 0.0f
private var pitchVel = 0.0f
private var yaw = 0.0f
private var pitch = 0.0f
private var spinSpeed = 0.15f
private var cyanIntensity = 1.0f
private var violetShift = 0.0f
private var coreBreath = 1.0f
private var coreColor = Color(0xFF8B5CF6).copy(alpha = 0.9f)