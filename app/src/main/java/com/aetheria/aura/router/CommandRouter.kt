package com.aetheria.aura.router

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process label -> package map built from launcher-resolvable activities.
 * Refreshes on package add/remove broadcasts.
 */
class AppIndex(private val context: Context) {

    private val labelToPackage = ConcurrentHashMap<String, String>()
    private val packageToLabel = ConcurrentHashMap<String, String>()
    private val normalizedLabels = ConcurrentHashMap<String, String>() // normalized -> original label
    private val buildTimeMs = AtomicReference<Long>(-1L)

    /**
     * Builds the index synchronously. Call on background thread.
     * @return pair of (appCount, buildTimeMs)
     */
    fun build(): Pair<Int, Long> {
        val start = System.currentTimeMillis()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivities(intent, 0)

        labelToPackage.clear()
        packageToLabel.clear()
        normalizedLabels.clear()

        for (info in activities) {
            val pkgName = info.activityInfo.packageName
            val label = info.loadLabel(pm).toString()
            if (label.isNotBlank()) {
                labelToPackage[label] = pkgName
                packageToLabel[pkgName] = label
                normalizedLabels[normalize(label)] = label
            }
        }

        val elapsed = System.currentTimeMillis() - start
        buildTimeMs.set(elapsed)
        Log.d("AuraRouter", "AppIndex built: ${labelToPackage.size} apps in ${elapsed}ms")
        return Pair(labelToPackage.size, elapsed)
    }

    /** Normalize for fuzzy matching: lowercase, strip non-alphanum, collapse spaces. */
    private fun normalize(s: String): String {
        return s.lowercase()
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
    }

    /** Find best match for target against app labels.
     * Returns package name if clear best match found (edit distance <= 2, unique), else null. */
    fun findBestMatch(target: String): String? {
        if (labelToPackage.isEmpty()) return null

        val normTarget = normalize(target)
        if (normTarget.isBlank()) return null

        // 1. Exact normalized match
        val exact = normalizedLabels[normTarget]
        if (exact != null) return labelToPackage[exact]

        // 2. Prefix match (normalized)
        val prefixMatches = mutableListOf<String>()
        for ((normLabel, origLabel) in normalizedLabels) {
            if (normLabel.startsWith(normTarget) || normTarget.startsWith(normLabel)) {
                prefixMatches.add(origLabel)
            }
        }
        if (prefixMatches.size == 1) return labelToPackage[prefixMatches[0]]

        // 3. Edit distance <= 2
        var bestLabel: String? = null
        var bestDist = 3
        var tie = false
        for ((normLabel, origLabel) in normalizedLabels) {
            val dist = levenshtein(normTarget, normLabel)
            if (dist <= 2) {
                if (dist < bestDist) {
                    bestDist = dist
                    bestLabel = origLabel
                    tie = false
                } else if (dist == bestDist) {
                    tie = true
                }
            }
        }
        if (!tie && bestLabel != null) return labelToPackage[bestLabel]

        return null
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = cur
            }
        }
        return dp[b.length]
    }

    fun getBuildTimeMs(): Long = buildTimeMs.get()
    fun getAppCount(): Int = labelToPackage.size
}

/**
 * Pure function command router. No I/O, no Android deps.
 * Input: raw user string. Output: AuraActionRoute.
 */
sealed class AuraActionRoute {
    data class OpenApp(val target: String) : AuraActionRoute()
    data class CallContact(val name: String) : AuraActionRoute()
    data class SendText(val name: String, val body: String) : AuraActionRoute()
    data class WebSearch(val query: String) : AuraActionRoute()
    data class AskHermes(val input: String) : AuraActionRoute()
}

object CommandRouter {

    private val OPEN_VERBS = setOf("open", "launch", "start", "run")
    private val CALL_VERBS = setOf("call", "phone", "dial")
    private val TEXT_VERBS = setOf("text", "message", "send")
    // search|google|look up|find|search for <query> and exact phrases "what is the weather" / "weather"
    private val SEARCH_VERBS = setOf("search", "google", "look up", "lookup", "find")
    private val SEARCH_PHRASES = listOf("what is the weather", "weather")

        /**
         * Route a raw user command to an action route.
         * Pure function - no side effects.
         */
        fun route(input: String): AuraActionRoute {
            val trimmed = input.trim()
            val words = trimmed.split("\\s+".toRegex())
            val firstWord = words.firstOrNull()?.lowercase() ?: ""
            val firstTwoWords = if (words.size >= 2) words.take(2).joinToString(" ").lowercase() else ""
            if (trimmed.isBlank()) return AuraActionRoute.AskHermes(input)

            // Check multi-word search phrases first (exact phrases)
            if (firstTwoWords in SEARCH_PHRASES) {
                val query = words.drop(2).joinToString(" ")
                return if (query.isBlank()) AuraActionRoute.AskHermes(input)
                else AuraActionRoute.WebSearch(query)
            }

            return when {
                firstWord in OPEN_VERBS -> {
                    val target = words.drop(1).joinToString(" ")
                    if (target.isBlank()) AuraActionRoute.AskHermes(trimmed)
                    else AuraActionRoute.OpenApp(target)
                }
                firstWord in CALL_VERBS -> {
                    val name = words.drop(1).joinToString(" ")
                    if (name.isBlank()) AuraActionRoute.AskHermes(trimmed)
                    else AuraActionRoute.CallContact(name)
                }
                firstWord in TEXT_VERBS || (firstWord == "message" && words.size > 1) -> {
                    // "text Alisha I'm running late" -> name="Alisha", body="I'm running late"
                    val rest = words.drop(1)
                    val name = rest.firstOrNull() ?: ""
                    val body = rest.drop(1).joinToString(" ")
                    if (name.isBlank()) AuraActionRoute.AskHermes(trimmed)
                    else AuraActionRoute.SendText(name, body)
                }
                firstWord in SEARCH_VERBS -> {
                    val query = words.drop(1).joinToString(" ")
                    if (query.isBlank()) AuraActionRoute.AskHermes(trimmed)
                    else AuraActionRoute.WebSearch(query)
                }
                else -> AuraActionRoute.AskHermes(trimmed)
            }
        }
    }

/** Broadcast receiver to refresh AppIndex on package changes. */
class PackageChangeReceiver : android.content.BroadcastReceiver() {
    private var appIndex: AppIndex? = null

    fun setAppIndex(index: AppIndex) {
        appIndex = index
    }

    override fun onReceive(context: Context?, intent: android.content.Intent?) {
        if (intent?.action == Intent.ACTION_PACKAGE_ADDED ||
            intent?.action == Intent.ACTION_PACKAGE_REMOVED ||
            intent?.action == Intent.ACTION_PACKAGE_REPLACED) {
            appIndex?.build()
            Log.d("AuraRouter", "AppIndex refreshed due to ${intent.action}")
        }
    }
}

/** Helper to initialize AppIndex and register receiver. */
object AppIndexManager {
    @Volatile private var instance: AppIndex? = null
    private var receiver: PackageChangeReceiver? = null

    fun init(context: Context): AppIndex {
        if (instance == null) {
            synchronized(this) {
                if (instance == null) {
                    val idx = AppIndex(context.applicationContext)
                    instance = idx
                    // Build on background
                    CoroutineScope(Dispatchers.IO).launch { idx.build() }
                    // Register receiver
                    val rcv = PackageChangeReceiver().apply { setAppIndex(idx) }
                    receiver = rcv
                    val filter = android.content.IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REMOVED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addDataScheme("package")
                    }
                    context.applicationContext.registerReceiver(rcv, filter, Context.RECEIVER_EXPORTED)
                }
            }
        }
        return instance!!
    }

    fun get(): AppIndex? = instance
}