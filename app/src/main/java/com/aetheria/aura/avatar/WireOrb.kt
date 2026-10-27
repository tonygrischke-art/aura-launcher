package com.aetheria.aura.avatar

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

private val CYAN = Color(0xFF3DE8F5)
private val VIOLET = Color(0xFF8A3DFF)
private val VIOLET_HOT = Color(0xFFD08CFF)
private const val PULSE_SECONDS = 0.8f

private class WireMesh(val verts: FloatArray, val edges: IntArray)

private fun buildIcosphere(subdivisions: Int): WireMesh {
    val t = (1f + sqrt(5f)) / 2f
    val v = ArrayList<FloatArray>()
    fun add(x: Float, y: Float, z: Float): Int {
        val l = sqrt(x * x + y * y + z * z)
        v.add(floatArrayOf(x / l, y / l, z / l))
        return v.size - 1
    }
    add(-1f, t, 0f); add(1f, t, 0f); add(-1f, -t, 0f); add(1f, -t, 0f)
    add(0f, -1f, t); add(0f, 1f, t); add(0f, -1f, -t); add(0f, 1f, -t)
    add(t, 0f, -1f); add(t, 0f, 1f); add(-t, 0f, -1f); add(-t, 0f, 1f)
    var faces: List<IntArray> = listOf(
        intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
        intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
        intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1)
    )
    repeat(subdivisions) {
        val cache = HashMap<Long, Int>()
        fun mid(a: Int, b: Int): Int {
            val lo = minOf(a, b)
            val hi = maxOf(a, b)
            val key = lo.toLong() * 100000L + hi.toLong()
            val hit = cache[key]
            if (hit != null) return hit
            val idx = add(v[a][0] + v[b][0], v[a][1] + v[b][1], v[a][2] + v[b][2])
            cache[key] = idx
            return idx
        }
        val next = ArrayList<IntArray>()
        for (f in faces) {
            val a = mid(f[0], f[1])
            val b = mid(f[1], f[2])
            val c = mid(f[2], f[0])
            next.add(intArrayOf(f[0], a, c))
            next.add(intArrayOf(f[1], b, a))
            next.add(intArrayOf(f[2], c, b))
            next.add(intArrayOf(a, b, c))
        }
        faces = next
    }
    val edgeSet = LinkedHashSet<Long>()
    for (f in faces) {
        for (i in 0..2) {
            val a = f[i]
            val b = f[(i + 1) % 3]
            edgeSet.add(minOf(a, b).toLong() * 100000L + maxOf(a, b).toLong())
        }
    }
    val edges = IntArray(edgeSet.size * 2)
    var e = 0
    for (k in edgeSet) {
        edges[e++] = (k / 100000L).toInt()
        edges[e++] = (k % 100000L).toInt()
    }
    val verts = FloatArray(v.size * 3)
    for (j in v.indices) {
        verts[j * 3] = v[j][0]
        verts[j * 3 + 1] = v[j][1]
        verts[j * 3 + 2] = v[j][2]
    }
    return WireMesh(verts, edges)
}

private val MESH: WireMesh by lazy { buildIcosphere(2) }

private class OrbState(count: Int) {
    val sx = FloatArray(count)
    val sy = FloatArray(count)
    val sz = FloatArray(count)
    val bright = FloatArray(count)
    var yaw = 0.6f
    var pitch = 0.35f
    var yawVel = 0f
    var pitchVel = 0f
    var dragging = false
    var spin = 0.25f
    var cyan = 0.7f
    var violet = 0f
    var ripple = 0f
    var travel = 0f
    var time = 0f
    var pulseAge = -1f
    var px = 0f
    var py = 0f
    var pz = 1f
    var lastNanos = 0L
    var logTimer = 0f
    var intervalSum = 0f
    var frames = 0
    var drawNanos = 0L

    fun startPulse(vx: Float, vy: Float, vz: Float) {
        val cp = cos(pitch)
        val sp = sin(pitch)
        val cw = cos(yaw)
        val sw = sin(yaw)
        val y1 = vy * cp + vz * sp
        val z1 = -vy * sp + vz * cp
        px = vx * cw - z1 * sw
        py = y1
        pz = vx * sw + z1 * cw
        pulseAge = 0f
    }

    fun step(dt: Float, mood: AuraMood) {
        time += dt
        var tSpin = 0.25f
        var tCyan = 0.7f
        var tViolet = 0f
        var tRipple = 0f
        var tTravel = 0f
        when (mood) {
            AuraMood.LISTENING -> { tSpin = 0.35f; tCyan = 0.95f; tViolet = 0.15f; tRipple = 1f }
            AuraMood.THINKING -> { tSpin = 1.1f; tCyan = 0.8f; tViolet = 0.8f }
            AuraMood.ACTING -> { tSpin = 1.6f; tCyan = 1f; tViolet = 0.5f; tTravel = 1f }
            else -> Unit
        }
        val k = 1f - exp(-dt / 0.1f)
        spin += (tSpin - spin) * k
        cyan += (tCyan - cyan) * k
        violet += (tViolet - violet) * k
        ripple += (tRipple - ripple) * k
        travel += (tTravel - travel) * k

        if (!dragging) {
            yaw += (spin + yawVel) * dt
            pitch += pitchVel * dt
            val decay = exp(-dt * 3f)
            yawVel *= decay
            pitchVel *= decay
            pitch += (0.35f - pitch) * (1f - exp(-dt * 0.4f))
        }
        pitch = pitch.coerceIn(-1.4f, 1.4f)

        if (pulseAge >= 0f) {
            pulseAge += dt
            if (pulseAge > PULSE_SECONDS) pulseAge = -1f
        }

        frames++
        intervalSum += dt
        logTimer += dt
        if (logTimer >= 5f) {
            val n = frames.coerceAtLeast(1)
            Log.d(
                "AuraWireOrb",
                "avg frame interval %.1f ms, avg draw %.2f ms over %d frames".format(
                    intervalSum / n * 1000f, drawNanos / n / 1_000_000f, n
                )
            )
            logTimer = 0f
            intervalSum = 0f
            frames = 0
            drawNanos = 0L
        }
    }

    fun project(mesh: WireMesh, cx: Float, cy: Float, r: Float) {
        val cw = cos(yaw)
        val sw = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)
        val n = mesh.verts.size / 3
        for (i in 0 until n) {
            val x = mesh.verts[i * 3]
            val y = mesh.verts[i * 3 + 1]
            val z = mesh.verts[i * 3 + 2]
            val x1 = x * cw + z * sw
            val z1 = -x * sw + z * cw
            val y2 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            sx[i] = cx + x1 * r
            sy[i] = cy - y2 * r
            sz[i] = z2
            var b = 0f
            if (travel > 0.01f) {
                val w = sin(y * 4f + x * 2f - time * 5f)
                if (w > 0f) {
                    val w2 = w * w
                    b += travel * w2 * w2 * 0.9f
                }
            }
            if (pulseAge >= 0f) {
                val dot = (x * px + y * py + z * pz).coerceIn(-1f, 1f)
                val ang = acos(dot)
                val front = pulseAge / PULSE_SECONDS * 3.2f
                val d = (ang - front) / 0.35f
                b += exp(-d * d) * (1f - pulseAge / PULSE_SECONDS)
            }
            bright[i] = b
        }
    }
}

@Composable
fun WireOrb(mood: AuraMood, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val s = remember { OrbState(MESH.verts.size / 3) }
    val currentMood by rememberUpdatedState(mood)
    val currentOnTap by rememberUpdatedState(onTap)
    val haptic = LocalHapticFeedback.current
    var tick by remember { mutableLongStateOf(0L) }

    // withFrameNanos stops delivering frames when the app is in the background,
    // so this loop pauses on its own.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                val last = s.lastNanos
                s.lastNanos = now
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    s.step(dt, currentMood)
                }
                tick = now
            }
        }
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(onTap = { pos ->
                    val r = minOf(size.width, size.height) * 0.35f
                    var dx = (pos.x - size.width / 2f) / r
                    var dy = (pos.y - size.height / 2f) / r
                    val d2 = dx * dx + dy * dy
                    val dz: Float
                    if (d2 > 1f) {
                        val k = 1f / sqrt(d2)
                        dx *= k
                        dy *= k
                        dz = 0f
                    } else {
                        dz = sqrt(1f - d2)
                    }
                    s.startPulse(dx, -dy, dz)
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnTap()
                })
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { s.dragging = true },
                    onDragEnd = { s.dragging = false },
                    onDragCancel = { s.dragging = false }
                ) { change, drag ->
                    change.consume()
                    s.yaw += drag.x * 0.01f
                    s.pitch = (s.pitch + drag.y * 0.01f).coerceIn(-1.4f, 1.4f)
                    s.yawVel = (s.yawVel * 0.5f + drag.x * 0.6f).coerceIn(-15f, 15f)
                    s.pitchVel = (s.pitchVel * 0.5f + drag.y * 0.6f).coerceIn(-15f, 15f)
                }
            }
    ) {
        val t0 = System.nanoTime()
        val frame = tick
        if (frame < 0L) return@Canvas
        val cx = size.width / 2f
        val cy = size.height / 2f
        val center = Offset(cx, cy)
        val r = minOf(size.width, size.height) * 0.35f
        val mesh = MESH
        s.project(mesh, cx, cy, r)

        val lineColor = lerp(CYAN, VIOLET_HOT, s.violet * 0.45f)

        // outer halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, CYAN.copy(alpha = 0.07f * s.cyan), Color.Transparent),
                center = center,
                radius = r * 1.25f
            ),
            radius = r * 1.25f,
            center = center
        )

        // violet core
        val breath = 1f + (0.07f + 0.08f * s.violet) * sin(s.time * (1.6f + s.violet * 3f))
        val coreR = r * 0.5f * breath
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    VIOLET_HOT.copy(alpha = 0.85f),
                    VIOLET.copy(alpha = 0.35f),
                    Color.Transparent
                ),
                center = center,
                radius = coreR
            ),
            radius = coreR,
            center = center
        )

        // two thin rotating rings around the core
        val ringStroke = Stroke(width = maxOf(1f, r * 0.01f))
        val ringColor = VIOLET.copy(alpha = 0.45f + 0.3f * s.violet)
        val r1 = r * 0.22f
        val r2 = r * 0.32f
        drawArc(
            color = ringColor,
            startAngle = s.time * (30f + s.spin * 50f),
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(cx - r1, cy - r1),
            size = Size(r1 * 2f, r1 * 2f),
            style = ringStroke
        )
        drawArc(
            color = ringColor,
            startAngle = 90f - s.time * (20f + s.spin * 40f),
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(cx - r2, cy - r2),
            size = Size(r2 * 2f, r2 * 2f),
            style = ringStroke
        )

        // listening ripples
        if (s.ripple > 0.02f) {
            for (k in 0..2) {
                val phase = (s.time * 0.8f + k / 3f) % 1f
                drawCircle(
                    color = lineColor.copy(alpha = (1f - phase) * s.ripple * 0.5f),
                    radius = r * (0.2f + 0.75f * phase),
                    center = center,
                    style = Stroke(width = 1.5f)
                )
            }
        }

        // mesh edges
        val lineW = maxOf(1.2f, r * 0.006f)
        val edges = mesh.edges
        var e = 0
        while (e < edges.size) {
            val a = edges[e]
            val b = edges[e + 1]
            e += 2
            val depth = (s.sz[a] + s.sz[b]) * 0.5f
            val f = 0.5f + 0.5f * depth
            val bb = (s.bright[a] + s.bright[b]) * 0.5f
            val alpha = ((0.10f + 0.50f * f) * s.cyan + bb * 0.6f).coerceIn(0f, 1f)
            val c = if (bb > 0.05f) lerp(lineColor, Color.White, bb.coerceAtMost(1f) * 0.5f) else lineColor
            drawLine(
                color = c.copy(alpha = alpha),
                start = Offset(s.sx[a], s.sy[a]),
                end = Offset(s.sx[b], s.sy[b]),
                strokeWidth = lineW
            )
        }

        // vertex nodes
        val nodeR = maxOf(1.5f, r * 0.011f)
        val n = mesh.verts.size / 3
        for (i in 0 until n) {
            val f = 0.5f + 0.5f * s.sz[i]
            val bi = s.bright[i].coerceAtMost(1f)
            drawCircle(
                color = lerp(CYAN, Color.White, bi * 0.6f).copy(alpha = ((0.2f + 0.8f * f) * s.cyan + bi * 0.4f).coerceIn(0f, 1f)),
                radius = nodeR + bi * r * 0.02f,
                center = Offset(s.sx[i], s.sy[i])
            )
        }

        s.drawNanos += System.nanoTime() - t0
    }
}