package com.aetheria.aura.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.StringWriter
import java.util.concurrent.TimeUnit

/**
 * AuraAccessibilityService - provides screen reading and UI tree dump capabilities.
 * Used for Stage 6: screen-act loop (<30ms tap, <150ms UI tree).
 */
class AuraAccessibilityService : AccessibilityService() {

    private val TAG = "AuraAccessibilityService"
    private var isEnabled = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Log window state changes for debugging
        event?.let {
            Log.d(TAG, "Event: ${event.eventType}, package: ${event.packageName}, class: ${event.className}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
        isEnabled = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isEnabled = true
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        isEnabled = false
        Log.i(TAG, "Accessibility service destroyed")
        super.onDestroy()
    }

    /** Checks if the service is currently enabled and connected. */
    fun isServiceReady(): Boolean = isEnabled && rootInActiveWindow != null

    /**
     * Dumps the active window's UI tree to a string.
     * Returns the tree as formatted text, or error message if not available.
     */
    fun getUiTree(): String {
        val root = rootInActiveWindow
        if (root == null) {
            return "ERROR: No root node (service not enabled or no active window)"
        }

        val writer = StringWriter()
        dumpNode(root, writer, 0)
        return writer.toString()
    }

    private fun dumpNode(node: AccessibilityNodeInfo, writer: StringWriter, depth: Int) {
        val indent = "  ".repeat(depth)
        val className = node.className ?: "null"
        val text = node.text ?: ""
        val contentDesc = node.contentDescription ?: ""
        val boundsRect = node.getBoundsInScreen()
        val bounds = boundsRect?.let { it.toShortString() } ?: "no-bounds"
        val clickable = node.isClickable
        val focusable = node.isFocusable
        val enabled = node.isEnabled
        val visible = node.isVisibleToUser
        val id = node.viewIdResourceName ?: ""

        writer.write("$indent$className")
        if (id.isNotEmpty()) writer.write(" @$id")
        if (text.isNotEmpty()) writer.write(" text=\"$text\"")
        if (contentDesc.isNotEmpty()) writer.write(" desc=\"$contentDesc\"")
        writer.write(" bounds=$bounds")
        writer.write(" clickable=$clickable focusable=$focusable enabled=$enabled visible=$visible")
        writer.write("\n")

        // Recurse children
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                dumpNode(child, writer, depth + 1)
                child.recycle()
            }
        }
    }

    /** Gets the root node without dumping (for external use). */
    fun getRootNode(): AccessibilityNodeInfo? = rootInActiveWindow

    /** Finds nodes by text content (partial match). */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findByText(root, text, results)
        return results
    }

    private fun findByText(node: AccessibilityNodeInfo, text: String, results: MutableList<AccessibilityNodeInfo>) {
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true)) {
            results.add(node)
        } else {
            node.recycle()
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findByText(it, text, results) }
        }
    }

    /** Finds a clickable node by text and performs click. */
    fun clickByText(text: String): Boolean {
        val nodes = findNodesByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) {
                    Log.d(TAG, "Clicked node with text: $text")
                    return true
                }
            }
            node.recycle()
        }
        return false
    }

    /** Performs a tap at screen coordinates (requires canRequestTouchExplorationMode). */
    fun tapAt(x: Int, y: Int): Boolean {
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                    0, 10
                )
            )
            .build()
        return dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
    }
}

private fun android.graphics.Rect.toShortString(): String {
    return "[$left,$top][$right,$bottom]"
}