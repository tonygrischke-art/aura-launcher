package com.aetheria.aura.llm

import android.content.Context
import android.util.Log
import com.aetheria.aura.model.ModelFileManager
import com.aetheria.aura.settings.UserProfileStore
import java.io.File

/**
 * On-device inference (llama.cpp via JNI). Text chat works now. The multimodal projector can be
 * loaded (LOAD_MMPROJ) but there is no image-encoding path yet, so it stays off to save ~450 MB RAM.
 */
object LocalLlm {
    private const val TAG = "AuraLocalLlm"
    const val LOAD_MMPROJ = false
    private const val N_CTX = 4096
    private const val SYSTEM_PROMPT_BASE = "You are Aura, an on-device phone assistant."

    private var nativeOk = false
    private var nativeLoadError = ""
    private var inited = false
    private var loaded = false

    init {
        try {
            System.loadLibrary("aura-llama")
            nativeOk = true
        } catch (e: UnsatisfiedLinkError) {
            nativeLoadError = e.message ?: "unknown"
            Log.e(TAG, "native library failed to load: $nativeLoadError")
        }
    }

    interface GenerateCallback {
        fun onToken(utf8Bytes: ByteArray)
        fun onComplete()
        fun onError(error: String)
    }

    @JvmStatic private external fun nativeInit(libDir: String)
    @JvmStatic private external fun nativeLoad(modelPath: String, mmprojPath: String, nCtx: Int): Boolean
    @JvmStatic private external fun nativeUnload()
    @JvmStatic private external fun nativeAbort()
    @JvmStatic private external fun nativeLastError(): String
    @JvmStatic private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, cb: GenerateCallback)

    fun abort() { if (nativeOk) nativeAbort() }

    @Synchronized
    fun unload() {
        if (nativeOk && loaded) { nativeUnload(); loaded = false }
    }

    /** Returns null when a model is loaded and ready, otherwise a human-readable error. Blocking: call off the main thread. */
    @Synchronized
    fun ensureLoaded(ctx: Context): String? {
        if (!nativeOk) return "native library not loaded: $nativeLoadError"
        if (loaded) return null
        if (!inited) {
            nativeInit(ctx.applicationInfo.nativeLibraryDir)
            inited = true
        }
        val dir = ModelFileManager.modelsDir(ctx)
        val ggufs = dir.listFiles { f -> f.name.endsWith(".gguf") && ModelFileManager.isValidGguf(f) }?.toList().orEmpty()
        val main: File = ggufs.firstOrNull { ModelFileManager.classifyGguf(it.name) == "main" }
            ?: return "No main .gguf model in ${dir.absolutePath}"
        val mmproj: File? = if (LOAD_MMPROJ) ggufs.firstOrNull { ModelFileManager.classifyGguf(it.name) == "mmproj" } else null
        Log.i(TAG, "loading ${main.name} mmproj=${mmproj?.name ?: "none"}")
        if (!nativeLoad(main.absolutePath, mmproj?.absolutePath ?: "", N_CTX)) {
            return nativeLastError().ifBlank { "model load failed" }
        }
        loaded = true
        return null
    }

    /** ChatML prompt (Qwen format), newest turns kept within a rough size budget. */
    fun buildPrompt(context: Context, history: List<Pair<String, String>>): String {
        val kept = ArrayList<Pair<String, String>>()
        var chars = 0
        for (turn in history.asReversed()) {
            chars += turn.second.length
            if (kept.size >= 12 || chars > 6000) break
            kept.add(turn)
        }
        val userProfile = UserProfileStore.getProfileForPrompt(context)
        val systemPrompt = if (userProfile.isNotBlank()) {
            "$SYSTEM_PROMPT_BASE\nThe user is a different person from you. If asked who the user is, say you don't know their name unless a USER_PROFILE line below provides it.\nUSER_PROFILE: $userProfile"
        } else {
            "$SYSTEM_PROMPT_BASE\nThe user is a different person from you. If asked who the user is, say you don't know their name unless a USER_PROFILE line below provides it."
        }
        val sb = StringBuilder("""<|system|>\n$systemPrompt<|endoftext|>""")
        for ((role, text) in kept.asReversed()) sb.append("<|").append(role).append("|>\n").append(text).append("<|endoftext|>\n")
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    /**
     * Runs one chat turn on the calling thread, streaming decoded text to onText.
     * history = (role "user"|"assistant", text) oldest first. Returns null on success, else an error message.
     */
    fun chat(ctx: Context, history: List<Pair<String, String>>, maxTokens: Int, temperature: Float = 0.7f,
             onText: (String) -> Unit): String? {
        ensureLoaded(ctx)?.let { return it }
        var error: String? = null
        var produced = false
        nativeGenerate(buildPrompt(ctx, history), maxTokens, temperature, object : GenerateCallback {
            override fun onToken(utf8Bytes: ByteArray) { produced = true; onText(String(utf8Bytes, Charsets.UTF_8)) }
            override fun onComplete() {}
            override fun onError(error: String) { Log.e(TAG, error); this@LocalLlm.lastGenError = error }
        })
        error = lastGenError
        lastGenError = null
        if (error == null && !produced) error = "model produced no output"
        return error
    }

    @Volatile private var lastGenError: String? = null
}
