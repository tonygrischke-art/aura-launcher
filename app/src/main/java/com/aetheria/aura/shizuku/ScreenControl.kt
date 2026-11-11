package com.aetheria.aura.shizuku

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Screen control on top of the Shizuku shell executor: tap/swipe/type/key via `input`,
 * screenshot via `screencap -p` (chunked pull, avoids the ~1MB Binder limit), and
 * uiDump via `uiautomator dump` parsed into [UiNode]s.
 *
 * Every function degrades gracefully: when Shizuku is not READY the error names the state.
 */
object ScreenControl {

    private const val TAG = "ScreenControl"
    private const val CHUNK = 512 * 1024

    /** Pure bounds holder (no android.graphics, unit-testable). */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    data class UiNode(
        val text: String,
        val resourceId: String,
        val clickable: Boolean,
        val bounds: Bounds
    )

    class Screenshot(val bytes: ByteArray?, val error: String?) {
        override fun toString(): String =
            if (error != null) "Screenshot(error=$error)" else "Screenshot(bytes=${bytes?.size})"
    }

    /**
     * Escape text for `input text`: spaces as %s, % as %%, then single-quote for the shell.
     * e.g. "it's 50% off" -> 'it'\''s%s50%%%soff'
     */
    fun escapeForInputText(s: String): String {
        val inputTextForm = s.replace("%", "%%").replace(" ", "%s")
        return "'" + inputTextForm.replace("'", "'\\''") + "'"
    }

    suspend fun tap(x: Int, y: Int): ShellExecutor.Result =
        ShellExecutor.exec("input tap $x $y", 10)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): ShellExecutor.Result =
        ShellExecutor.exec("input swipe $x1 $y1 $x2 $y2 $durationMs", 15)

    suspend fun text(s: String): ShellExecutor.Result =
        ShellExecutor.exec("input text ${escapeForInputText(s)}", 10)

    suspend fun key(keyCode: Int): ShellExecutor.Result =
        ShellExecutor.exec("input keyevent $keyCode", 10)

    /**
     * screencap -p pulled through the shell process in <=512KB chunks.
     * Returns the raw PNG bytes, or an error string when capture/transfer failed.
     */
    suspend fun screenshot(): Screenshot = withContext(Dispatchers.IO) {
        if (ShizukuManager.state.value != ShizukuManager.State.READY) {
            return@withContext Screenshot(null, ShellExecutor.notReadyError())
        }
        val path = "/data/local/tmp/aura_shot_${System.currentTimeMillis()}.png"
        try {
            val r = ShellExecutor.exec("screencap -p $path", 15)
            if (r.exitCode != 0) {
                return@withContext Screenshot(null, "screencap failed exit=${r.exitCode} stderr=${r.stderr.take(500)}")
            }
            val sizeR = ShellExecutor.exec("stat -c %s $path", 5)
            val size = sizeR.stdout.trim().toIntOrNull() ?: 0
            if (size <= 0) {
                return@withContext Screenshot(null, "screencap produced an empty file: ${sizeR.stderr.take(300)}")
            }
            val buf = ByteArrayOutputStream(size)
            var offset = 0
            while (offset < size) {
                val len = minOf(CHUNK, size - offset)
                val chunk = ShellExecutor.readChunk(path, offset, len)
                if (chunk == null || chunk.isEmpty()) break
                buf.write(chunk)
                offset += chunk.size
            }
            val bytes = buf.toByteArray()
            if (bytes.size != size) {
                Screenshot(null, "chunked read incomplete: got ${bytes.size} of $size bytes")
            } else {
                Screenshot(bytes, null)
            }
        } finally {
            ShellExecutor.exec("rm -f $path", 5)
        }
    }

    /**
     * uiautomator dump parsed into nodes. Second value is an error string when the dump failed
     * (e.g. Shizuku not READY, or uiautomator itself failed) and the list is empty.
     */
    suspend fun uiDump(maxNodes: Int = 150): Pair<List<UiNode>, String?> =
        withContext(Dispatchers.IO) {
            val raw = ShellExecutor.uidump(maxNodes)
            if (raw.startsWith("ERR:")) {
                return@withContext Pair(emptyList(), raw.removePrefix("ERR:").trim())
            }
            val nodes = ArrayList<UiNode>()
            for (line in raw.split("\n")) {
                if (line.isBlank()) continue
                val node = parseNodeLine(line)
                if (node != null) nodes.add(node)
            }
            Pair(nodes, null)
        }

    /** Parse "text\tresource-id\tclickable\tbounds" produced by the user service. Pure. */
    internal fun parseNodeLine(line: String): UiNode? {
        val parts = line.split("\t")
        if (parts.size < 4) return null
        return UiNode(
            text = parts[0],
            resourceId = parts[1],
            clickable = parts[2] == "true",
            bounds = parseBounds(parts[3]) ?: return null
        )
    }

    /** Parse "[x1,y1][x2,y2]". Pure. */
    internal fun parseBounds(s: String): Bounds? {
        val m = BOUNDS_REGEX.find(s) ?: return null
        val (l, t, r, b) = m.destructured
        return try {
            Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        } catch (e: NumberFormatException) {
            Log.w(TAG, "bad bounds: $s", e)
            null
        }
    }

    private val BOUNDS_REGEX = Regex("""\[(\d+),(\d+)\]\[(\d+),(\d+)\]""")
}
