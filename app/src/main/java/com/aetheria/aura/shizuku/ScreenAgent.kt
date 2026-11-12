package com.aetheria.aura.shizuku

import android.content.Context
import android.util.Log
import com.aetheria.aura.llm.LocalLlm
import com.aetheria.aura.net.HermesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Minimal tool loop that lets the on-device Qwen (LocalLlm, llama.cpp via JNI) drive the
 * phone screen. Tools IN: uidump (screen text), screenshot (capture). Tools OUT:
 * tap/type/swipe/key. Everything runs locally through Shizuku (uid 2000 shell) -
 * nothing is routed through Hermes or the network.
 *
 * Protocol: the model emits one line per turn — "TOOL <name> <args>" or "ANSWER <text>".
 */
object ScreenAgent {

    private const val TAG = "ScreenAgent"
    private const val MAX_STEPS = 8
    private const val MAX_TOKENS = 512
    private const val SCREEN_SUMMARY_LIMIT = 4000

    private const val SYSTEM_PROMPT_BASE = "You are Aura, an on-device Android phone controller acting through one tool per turn."

    private fun buildSystemPrompt(context: Context): String {
        val userProfile = HermesClient.getStoredUserProfile(context)
        return if (userProfile.isNotBlank()) {
            """$SYSTEM_PROMPT_BASE
The user is a different person from you. If asked who the user is, say you don't know their name unless a USER_PROFILE line below provides it.
USER_PROFILE: $userProfile
Available tools (emit exactly one line per turn):
TOOL uidump - re-read the current screen text/bounds
TOOL screenshot - capture the screen
TOOL tap <x> <y> - tap at pixel coordinates
TOOL type <text> - type text into the focused field
TOOL swipe <x1> <y1> <x2> <y2> <ms> - swipe gesture
TOOL key <code> - key event (3=HOME, 4=BACK, 66=ENTER)
When the task is complete, reply: ANSWER <short result for the user>.
Pick coordinates from the screen dump: bounds="[x1,y1][x2,y2]"; tap the center."""
        } else {
            """$SYSTEM_PROMPT_BASE
The user is a different person from you. If asked who the user is, say you don't know their name unless a USER_PROFILE line below provides it.
Available tools (emit exactly one line per turn):
TOOL uidump - re-read the current screen text/bounds
TOOL screenshot - capture the screen
TOOL tap <x> <y> - tap at pixel coordinates
TOOL type <text> - type text into the focused field
TOOL swipe <x1> <y1> <x2> <y2> <ms> - swipe gesture
TOOL key <code> - key event (3=HOME, 4=BACK, 66=ENTER)
When the task is complete, reply: ANSWER <short result for the user>.
Pick coordinates from the screen dump: bounds="[x1,y1][x2,y2]"; tap the center."""
        }
    }

    /**
     * Run one agent task. Returns the final answer text, or a clear error string
     * (Shizuku not READY, local model unavailable, step limit).
     *
     * @param onStep progress callback with the tool line executed.
     */
    suspend fun runTask(
        context: Context,
        task: String,
        maxSteps: Int = MAX_STEPS,
        onStep: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        if (ShizukuManager.state.value != ShizukuManager.State.READY) {
            return@withContext "Error: Shizuku is ${ShizukuManager.state.value} - " +
                "screen tools are unavailable. Enable Shizuku and grant permission in Aura Settings."
        }
        val history = ArrayList<Pair<String, String>>()
        history.add("system" to buildSystemPrompt(context))
        history.add("user" to task)
        history.add("user" to "Current screen:\n${screenSummary()}")

        repeat(maxSteps) { step ->
            val sb = StringBuilder()
            val err = LocalLlm.chat(context.applicationContext, history.toList(), MAX_TOKENS) { piece -> sb.append(piece) }
            if (err != null) {
                return@withContext "Error: local model unavailable ($err)"
            }
            val reply = sb.toString()
            if (reply.isNullOrBlank()) {
                return@withContext "Error: local model returned no text"
            }
            val line = reply.lineSequence().map { it.trim() }
                .firstOrNull { it.startsWith("TOOL") || it.startsWith("ANSWER") }
                ?: reply.trim()
            onStep("step ${step + 1}: $line")

            when {
                line.startsWith("ANSWER") ->
                    return@withContext line.removePrefix("ANSWER").trim().ifEmpty { "done" }
                line.startsWith("TOOL") -> {
                    val obs = runTool(line)
                    history.add("assistant" to line)
                    history.add("user" to obs)
                }
                else -> history.add("assistant" to line)
            }
        }
        "Stopped after $maxSteps steps without a final answer."
    }

    private suspend fun runTool(line: String): String {
        val body = line.removePrefix("TOOL").trim()
        val name = body.split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return "no tool name"
        return try {
            when (name) {
                "uidump" -> screenSummary()
                "screenshot" -> {
                    val s = ScreenControl.screenshot()
                    s.error ?: ("screenshot captured (${s.bytes?.size ?: 0} bytes to /data/local/tmp, then deleted). " +
                        "Note: this build runs the local model text-only (VL projector disabled), " +
                        "so use the text screen dump above to decide actions.")
                }
                "tap" -> {
                    val p = body.split(Regex("\\s+"))
                    val x = p.getOrNull(1)?.toIntOrNull()
                    val y = p.getOrNull(2)?.toIntOrNull()
                    if (x == null || y == null) "ERROR: tap needs x y"
                    else obs(ScreenControl.tap(x, y))
                }
                "type" -> obs(ScreenControl.text(body.drop(name.length).trim()))
                "swipe" -> {
                    val p = body.split(Regex("\\s+"))
                    val x1 = p.getOrNull(1)?.toIntOrNull(); val y1 = p.getOrNull(2)?.toIntOrNull()
                    val x2 = p.getOrNull(3)?.toIntOrNull(); val y2 = p.getOrNull(4)?.toIntOrNull()
                    val ms = p.getOrNull(5)?.toIntOrNull() ?: 300
                    if (x1 == null || y1 == null || x2 == null || y2 == null) "ERROR: swipe needs x1 y1 x2 y2 [ms]"
                    else obs(ScreenControl.swipe(x1, y1, x2, y2, ms))
                }
                "key" -> {
                    val code = body.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
                    if (code == null) "ERROR: key needs a key code" else obs(ScreenControl.key(code))
                }
                else -> "ERROR: unknown tool '$name'"
            }
        } catch (e: Throwable) {
            Log.w(TAG, "tool failed: $body", e)
            "ERROR: ${e.message}"
        }
    }

    private fun obs(r: ShellExecutor.Result): String = buildString {
        append("exit=").append(r.exitCode)
        if (r.stdout.isNotBlank()) append(" stdout=").append(r.stdout.take(300))
        if (r.stderr.isNotBlank()) append(" stderr=").append(r.stderr.take(300))
    }

    private suspend fun screenSummary(): String {
        val (nodes, err) = ScreenControl.uiDump()
        if (err != null) return "uiDump error: $err"
        val sb = StringBuilder()
        for (n in nodes) {
            if (sb.length >= SCREEN_SUMMARY_LIMIT) {
                sb.append("...(truncated)")
                break
            }
            val label = if (n.text.isNotEmpty()) n.text else n.resourceId.ifEmpty { "(unlabeled)" }
            sb.append('"').append(label).append('"')
                .append(" [").append(n.bounds.left).append(',').append(n.bounds.top)
                .append(" -> ").append(n.bounds.centerX).append(',').append(n.bounds.centerY).append(']')
                .append(if (n.clickable) " clickable" else "")
                .append('\n')
        }
        return sb.toString().ifEmpty { "(empty screen dump)" }
    }
}
