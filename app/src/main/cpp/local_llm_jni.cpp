#include <jni.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>
#include <llama.h>
#include <ggml-backend.h>
#include <common/sampling.h>
#include <common/common.h>
#include <mtmd.h>

#define LOG_TAG "AuraLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Global state. Every access except g_abort / g_last_error reads happens under g_mutex.
// ---------------------------------------------------------------------------
static llama_model     *g_model   = nullptr;
static llama_context   *g_ctx     = nullptr;
static common_sampler  *g_sampler = nullptr;
static mtmd_context    *g_mtmd    = nullptr;
static float            g_sampler_temp = 0.1f;
static std::mutex       g_mutex;
static std::atomic<bool> g_abort{false};
static std::string      g_last_error;       // guarded by g_mutex
static bool             g_backends_ready = false;

static const float DEFAULT_TEMP = 0.1f;     // low temperature for reliable tool calls

static std::string jstringToString(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *cstr = env->GetStringUTFChars(jstr, nullptr);
    if (!cstr) return "";
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

static void llama_log_to_android(ggml_log_level level, const char *text, void *) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = 5; // ANDROID_LOG_WARN
    __android_log_print(prio, "llama.cpp", "%s", text);
}

// Free everything in dependency order: sampler -> context -> mtmd -> model.
// mtmd holds a pointer to the text model, so it must go before the model.
// Caller must hold g_mutex. Safe to call with any subset already null (no double free).
static void free_all_locked() {
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);              g_ctx = nullptr; }
    if (g_mtmd)    { mtmd_free(g_mtmd);              g_mtmd = nullptr; }
    if (g_model)   { llama_model_free(g_model);      g_model = nullptr; }
}

static common_sampler *make_sampler_locked(float temp) {
    common_params_sampling sp;
    sp.temp  = temp;
    sp.top_p = 0.95f;
    sp.top_k = 40;
    return common_sampler_init(g_model, sp);
}

// Returns how many leading bytes of s end on a UTF-8 character boundary.
static size_t utf8_complete_prefix(const std::string &s) {
    const size_t n = s.size();
    size_t i = n;
    for (int back = 1; i > 0 && back <= 4; back++) {
        i--;
        unsigned char c = static_cast<unsigned char>(s[i]);
        if ((c & 0xC0) == 0x80) continue;                    // continuation byte, keep looking back
        size_t need = c < 0x80 ? 1 : (c >= 0xF0 ? 4 : (c >= 0xE0 ? 3 : (c >= 0xC0 ? 2 : 1)));
        return (n - i >= need) ? n : i;                       // last char complete? else cut before it
    }
    return n;
}

// Calls Java; returns false (and clears the exception) if Java threw.
static bool emit_bytes(JNIEnv *env, jobject cb, jmethodID onToken, const char *data, size_t len) {
    if (len == 0) return true;
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(len));
    if (!arr) { env->ExceptionClear(); return false; }
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(len), reinterpret_cast<const jbyte *>(data));
    env->CallVoidMethod(cb, onToken, arr);
    env->DeleteLocalRef(arr);
    if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
    return true;
}

static void call_error(JNIEnv *env, jobject cb, jmethodID onError, const char *msg) {
    LOGE("%s", msg);
    jstring s = env->NewStringUTF(msg);
    env->CallVoidMethod(cb, onError, s);
    if (s) env->DeleteLocalRef(s);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// ---------------------------------------------------------------------------
// JNI
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_aura_llm_LocalLlm_nativeInit(JNIEnv *env, jclass clazz, jstring libDir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_backends_ready) return;
    std::string dir = jstringToString(env, libDir);
    LOGI("nativeInit: libDir=%s", dir.c_str());

    llama_log_set(llama_log_to_android, nullptr);
    llama_backend_init();

    // The build uses GGML_BACKEND_DL: the CPU backend is a set of libggml-cpu-*.so modules that
    // must be loaded explicitly, otherwise there is no backend and every model load fails.
    if (!dir.empty()) ggml_backend_load_all_from_path(dir.c_str());
    else              ggml_backend_load_all();

    size_t n = ggml_backend_reg_count();
    LOGI("nativeInit: %zu ggml backend(s) registered", n);
    if (n == 0) {
        g_last_error = "No ggml backends found in " + dir + " (libggml-cpu-*.so missing from the APK?)";
        LOGE("%s", g_last_error.c_str());
        return; // g_backends_ready stays false; nativeLoad will report the error
    }
    g_backends_ready = true;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aetheria_aura_llm_LocalLlm_nativeLoad(JNIEnv *env, jclass clazz,
                                                          jstring modelPath, jstring mmprojPath, jint nCtx) {
    g_abort = true;                          // let an in-flight generate() finish quickly
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;

    free_all_locked();                       // reload-safe, correct order
    g_last_error.clear();

    if (!g_backends_ready) {
        if (g_last_error.empty()) g_last_error = "nativeInit has not succeeded (no ggml backends)";
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    std::string path   = jstringToString(env, modelPath);
    std::string mmproj = jstringToString(env, mmprojPath);
    int ctxSize = nCtx > 0 ? nCtx : 4096;
    LOGI("nativeLoad: model=%s mmproj=%s nCtx=%d", path.c_str(), mmproj.empty() ? "(none)" : mmproj.c_str(), ctxSize);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only
    g_model = llama_model_load_from_file(path.c_str(), mp);
    if (!g_model) {
        g_last_error = "Failed to load model: " + path;
        LOGE("%s", g_last_error.c_str());
        return JNI_FALSE;
    }

    // Multimodal projector is optional: an empty path means text-only.
    if (!mmproj.empty()) {
        mtmd_context_params tp = mtmd_context_params_default();
        tp.use_gpu = false;
        g_mtmd = mtmd_init_from_file(mmproj.c_str(), g_model, tp);
        if (!g_mtmd) {
            g_last_error = "Failed to load mmproj (does not match this model, or corrupt): " + mmproj;
            LOGE("%s", g_last_error.c_str());
            free_all_locked();
            return JNI_FALSE;
        }
        LOGI("mmproj loaded (vision=%d)", mtmd_support_vision(g_mtmd) ? 1 : 0);
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx    = static_cast<uint32_t>(ctxSize);
    cp.n_batch  = 512;
    cp.n_ubatch = 512;
    cp.abort_callback = [](void *) -> bool { return g_abort.load(); };
    cp.abort_callback_data = nullptr;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        g_last_error = "Failed to create llama context (out of memory? try a smaller nCtx)";
        LOGE("%s", g_last_error.c_str());
        free_all_locked();
        return JNI_FALSE;
    }

    g_sampler_temp = DEFAULT_TEMP;
    g_sampler = make_sampler_locked(g_sampler_temp);
    if (!g_sampler) {
        g_last_error = "Failed to create sampler";
        LOGE("%s", g_last_error.c_str());
        free_all_locked();
        return JNI_FALSE;
    }

    LOGI("Model loaded (temp=%.2f, n_ctx=%u)", g_sampler_temp, llama_n_ctx(g_ctx));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_aura_llm_LocalLlm_nativeUnload(JNIEnv *env, jclass clazz) {
    g_abort = true;
    std::lock_guard<std::mutex> lock(g_mutex);
    free_all_locked();
    g_abort = false;
    LOGI("nativeUnload: done");
}

extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_aura_llm_LocalLlm_nativeAbort(JNIEnv *env, jclass clazz) {
    g_abort = true;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_aetheria_aura_llm_LocalLlm_nativeLastError(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

// Contract: exactly one terminal callback per call: onComplete (finished or aborted) or onError.
extern "C" JNIEXPORT void JNICALL
Java_com_aetheria_aura_llm_LocalLlm_nativeGenerate(
    JNIEnv *env, jclass clazz,
    jstring prompt, jint maxTokens, jfloat temperature,
    jobject callback) {

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onToken    = env->GetMethodID(cbClass, "onToken", "([B)V");
    jmethodID onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
    jmethodID onError    = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    if (!onToken || !onComplete || !onError) {
        env->ExceptionClear();
        LOGE("nativeGenerate: callback methods not found");
        return;
    }
    auto finish_ok = [&]() {
        env->CallVoidMethod(callback, onComplete);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };

    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort = false;

    if (!g_ctx || !g_model || !g_sampler) {
        call_error(env, callback, onError, "Model not loaded");
        return;
    }

    std::string promptStr = jstringToString(env, prompt);
    LOGI("nativeGenerate: prompt_bytes=%zu maxTokens=%d temp=%.2f", promptStr.size(), (int) maxTokens, (double) temperature);

    // Honor the requested temperature (previously ignored). <=0 keeps the current sampler.
    if (temperature > 0.0f && std::fabs(temperature - g_sampler_temp) > 1e-4f) {
        common_sampler *ns = make_sampler_locked(temperature);
        if (ns) {
            common_sampler_free(g_sampler);
            g_sampler = ns;
            g_sampler_temp = temperature;
        }
    }
    common_sampler_reset(g_sampler);          // do not carry penalty/grammar state across calls

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (!vocab) { call_error(env, callback, onError, "Failed to get vocab"); return; }

    // Tokenize: first call with no buffer returns -(needed count). Special tokens are parsed
    // so pre-templated prompts (<|im_start|> etc.) are tokenized as control tokens.
    int n_tokens = -llama_tokenize(vocab, promptStr.c_str(), (int32_t) promptStr.size(), nullptr, 0, true, true);
    if (n_tokens <= 0) { call_error(env, callback, onError, "Tokenize failed"); return; }
    std::vector<llama_token> tokens(n_tokens);
    n_tokens = llama_tokenize(vocab, promptStr.c_str(), (int32_t) promptStr.size(), tokens.data(), (int32_t) tokens.size(), true, true);
    if (n_tokens < 0) { call_error(env, callback, onError, "Tokenize failed"); return; }
    tokens.resize(n_tokens);

    const int n_ctx = (int) llama_n_ctx(g_ctx);
    if (n_tokens >= n_ctx) {
        call_error(env, callback, onError, "Prompt is longer than the context window");
        return;
    }
    int budget = std::min<int>(maxTokens, n_ctx - n_tokens);
    LOGI("Tokenized to %d tokens (n_ctx=%d, gen budget=%d)", n_tokens, n_ctx, budget);

    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Prompt decode in n_batch chunks
    const int n_batch = (int) llama_n_batch(g_ctx);
    for (int i = 0; i < n_tokens; i += n_batch) {
        if (g_abort) { finish_ok(); return; }
        int chunk = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(&tokens[i], chunk);
        int rc = llama_decode(g_ctx, batch);
        if (rc == 2) { finish_ok(); return; }   // aborted via abort_callback
        if (rc != 0) { call_error(env, callback, onError, "Prompt decode failed"); return; }
    }

    // Generation loop
    std::string pending;                      // bytes not yet forming a complete UTF-8 char
    int generated = 0;
    bool java_failed = false;
    while (generated < budget && !g_abort) {
        llama_token token = common_sampler_sample(g_sampler, g_ctx, -1, false);
        if (llama_vocab_is_eog(vocab, token)) break;
        common_sampler_accept(g_sampler, token, true);

        char piece[256];
        int n = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
        if (n > 0) {
            pending.append(piece, (size_t) n);
            size_t cut = utf8_complete_prefix(pending);
            if (cut > 0) {
                if (!emit_bytes(env, callback, onToken, pending.data(), cut)) { java_failed = true; break; }
                pending.erase(0, cut);
            }
        }

        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            if (!g_abort) LOGE("llama_decode failed during generation");
            break;
        }
        generated++;
    }

    if (java_failed) {                        // Java callback threw; stop quietly, nothing more to tell it
        LOGE("onToken callback threw; generation stopped");
        return;
    }
    if (!pending.empty() && !g_abort) {
        if (!emit_bytes(env, callback, onToken, pending.data(), pending.size())) return;
    }
    finish_ok();
    LOGI("nativeGenerate: done, generated %d tokens%s", generated, g_abort ? " (aborted)" : "");
}
