#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <android/log.h>
#include <llama.h>
#include <common/sampling.h>
#include <common/common.h>
#include <mtmd.h>

#define LOG_TAG "HermesLlama"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// Global state
static llama_model *g_model = nullptr;
static llama_context *g_ctx = nullptr;
static common_sampler *g_sampler = nullptr;
static mtmd_context *g_mtmd = nullptr;
static std::mutex g_mutex;
static volatile bool g_abort = false;

// JNI helper: throw Java exception
static void throwException(JNIEnv *env, const char *className, const char *msg) {
    jclass cls = env->FindClass(className);
    if (cls) env->ThrowNew(cls, msg);
}

// JNI helper: get string from jstring
static std::string jstringToString(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *cstr = env->GetStringUTFChars(jstr, nullptr);
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

// UTF-8 validation: check if byte is a lead byte and return expected length
static int utf8_expected_len(unsigned char c) {
    if (c <= 0x7F) return 1;
    if (c >= 0xC2 && c <= 0xDF) return 2;
    if (c >= 0xE0 && c <= 0xEF) return 3;
    if (c >= 0xF0 && c <= 0xF4) return 4;
    return -1; // invalid lead byte
}

// Callback for streaming tokens to Kotlin
struct CallbackData {
    JNIEnv *env;
    jobject callback;
    jmethodID onToken;
    jmethodID onComplete;
    jmethodID onError;
    JavaVM *jvm;
    std::string buffer; // for UTF-8 completion
};

static void stream_callback(llama_token token, void *user_data) {
    CallbackData *data = static_cast<CallbackData *>(user_data);
    if (!data || !data->callback) return;

    JNIEnv *env = data->env;
    if (!env && data->jvm) {
        data->jvm->AttachCurrentThread(&env, nullptr);
    }
    if (!env) return;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (!vocab) return;

    // Convert token to piece
    char piece_buf[64];
    int n = llama_token_to_piece(vocab, token, piece_buf, sizeof(piece_buf), 0, true);
    if (n <= 0) return;

    // Append to buffer and validate UTF-8
    data->buffer.append(piece_buf, n);

    // Find complete UTF-8 sequences
    size_t i = 0;
    while (i < data->buffer.size()) {
        unsigned char c = static_cast<unsigned char>(data->buffer[i]);
        int expected = utf8_expected_len(c);
        if (expected == -1) {
            // Invalid byte, skip it
            data->buffer.erase(i, 1);
            continue;
        }
        if (i + expected > data->buffer.size()) {
            // Incomplete sequence, wait for more
            break;
        }
        // Valid complete sequence - send it
        std::string complete = data->buffer.substr(0, i + expected);
        data->buffer.erase(0, i + expected);

        jbyteArray jbytes = env->NewByteArray(complete.size());
        env->SetByteArrayRegion(jbytes, 0, complete.size(), reinterpret_cast<const jbyte *>(complete.data()));
        env->CallVoidMethod(data->callback, data->onToken, jbytes);
        env->DeleteLocalRef(jbytes);
        if (env->ExceptionCheck()) return;
        i = 0; // restart scan
    }
}

// Callback for abort check
static bool abort_callback(void *user_data) {
    return g_abort;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omarqaterge_hermesmobile_LocalLlmImpl_nativeInit(JNIEnv *env, jclass clazz, jstring libDir) {
    std::lock_guard<std::mutex> lock(g_mutex);
    std::string libDirStr = jstringToString(env, libDir);
    LOGI("nativeInit: libDir=%s", libDirStr.c_str());
    (void)libDirStr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_omarqaterge_hermesmobile_LocalLlmImpl_nativeLoad(JNIEnv *env, jclass clazz, jstring modelPath, jstring mmprojPath, jint nCtx) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }

    std::string path = jstringToString(env, modelPath);
    std::string mmproj = jstringToString(env, mmprojPath);
    LOGI("nativeLoad: model=%s, mmproj=%s, nCtx=%d", path.c_str(), mmproj.c_str(), nCtx);

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only

    g_model = llama_load_model_from_file(path.c_str(), model_params);
    if (!g_model) {
        LOGE("Failed to load model from %s", path.c_str());
        return JNI_FALSE;
    }

    // Load mmproj (multimodal projector) via libmtmd
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    g_mtmd = mtmd_init_from_file(mmproj.c_str(), g_model, mtmd_params);
    if (!g_mtmd) {
        LOGE("Failed to load mmproj from %s", mmproj.c_str());
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("mmproj loaded successfully");

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = nCtx;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    ctx_params.abort_callback = abort_callback;
    ctx_params.abort_callback_data = nullptr;

    g_ctx = llama_new_context_with_model(g_model, ctx_params);
    if (!g_ctx) {
        LOGE("Failed to create context");
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Initialize sampler with thinking disabled by default, temperature 0.1 for tool calls
    common_params_sampling sampler_params;
    sampler_params.temp = 0.1f;
    sampler_params.top_p = 0.95f;
    sampler_params.top_k = 40;
    g_sampler = common_sampler_init(g_model, sampler_params);
    if (!g_sampler) {
        LOGE("Failed to create sampler");
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
        llama_free(g_ctx);
        g_ctx = nullptr;
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully (thinking disabled, temp=0.1)");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_omarqaterge_hermesmobile_LocalLlmImpl_nativeUnload(JNIEnv *env, jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_abort = false;
    LOGI("nativeUnload: done");
}

extern "C" JNIEXPORT void JNICALL
Java_com_omarqaterge_hermesmobile_LocalLlmImpl_nativeAbort(JNIEnv *env, jclass clazz) {
    g_abort = true;
    LOGI("nativeAbort: set abort flag");
}

extern "C" JNIEXPORT void JNICALL
Java_com_omarqaterge_hermesmobile_LocalLlmImpl_nativeGenerate(
    JNIEnv *env, jclass clazz,
    jstring prompt, jint maxTokens, jfloat temperature,
    jobject callback) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_ctx || !g_model || !g_sampler) {
        LOGE("nativeGenerate: not initialized");
        return;
    }

    std::string promptStr = jstringToString(env, prompt);
    LOGI("nativeGenerate: prompt_len=%zu, maxTokens=%d, temp=%.2f", promptStr.size(), maxTokens, temperature);

    g_abort = false;

    // Get vocab
    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    if (!vocab) {
        LOGE("Failed to get vocab");
        return;
    }

    // Update sampler temperature
    // Note: common_sampler doesn't have a direct temp setter, would need to recreate
    // For now, use the temp from initialization

    // Tokenize prompt
    int n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token> tokens(n_vocab);
    int n_tokens = llama_tokenize(vocab, promptStr.c_str(), promptStr.size(), tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) {
        LOGE("Tokenize failed, need %d tokens", -n_tokens);
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(vocab, promptStr.c_str(), promptStr.size(), tokens.data(), tokens.size(), true, false);
        if (n_tokens < 0) {
            LOGE("Tokenize failed again");
            return;
        }
    }
    tokens.resize(n_tokens);
    LOGI("Tokenized to %d tokens", n_tokens);

    // Clear memory at start of generate
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Prepare callback data
    CallbackData cbData;
    cbData.callback = env->NewGlobalRef(callback);
    jclass cbClass = env->GetObjectClass(cbData.callback);
    cbData.onToken = env->GetMethodID(cbClass, "onToken", "([B)V");
    cbData.onComplete = env->GetMethodID(cbClass, "onComplete", "()V");
    cbData.onError = env->GetMethodID(cbClass, "onError", "(Ljava/lang/String;)V");
    env->GetJavaVM(&cbData.jvm);
    cbData.env = env;
    cbData.buffer.clear();

    if (!cbData.onToken || !cbData.onComplete || !cbData.onError) {
        LOGE("Failed to get callback method IDs");
        env->DeleteGlobalRef(cbData.callback);
        return;
    }

    // Chunk prompt decode by n_batch
    int n_batch = 512;
    for (int i = 0; i < n_tokens; i += n_batch) {
        if (g_abort) break;

        int chunk_size = std::min(n_batch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(&tokens[i], chunk_size);

        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during prompt processing at token %d", i);
            env->CallVoidMethod(cbData.callback, cbData.onError, env->NewStringUTF("Prompt decode failed"));
            env->DeleteGlobalRef(cbData.callback);
            return;
        }

        // Check abort in prompt loop
        if (g_abort) break;
    }

    if (g_abort) {
        env->CallVoidMethod(cbData.callback, cbData.onComplete);
        env->DeleteGlobalRef(cbData.callback);
        return;
    }

    // Generation loop
    int generated = 0;
    while (generated < maxTokens && !g_abort) {
        llama_token token = common_sampler_sample(g_sampler, g_ctx, -1, false);
        if (llama_vocab_is_eog(vocab, token)) break;

        common_sampler_accept(g_sampler, token, true);

        // Stream token to callback
        stream_callback(token, &cbData);

        // Decode the generated token
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }

        generated++;
    }

    // Flush any remaining UTF-8 bytes
    if (!cbData.buffer.empty() && !g_abort) {
        std::string valid;
        for (size_t i = 0; i < cbData.buffer.size(); ) {
            unsigned char c = static_cast<unsigned char>(cbData.buffer[i]);
            int expected = utf8_expected_len(c);
            if (expected == -1 || i + expected > cbData.buffer.size()) {
                valid.push_back('\xEF');
                valid.push_back('\xBF');
                valid.push_back('\xBD'); // U+FFFD
                i++;
            } else {
                valid.append(cbData.buffer.substr(i, expected));
                i += expected;
            }
        }
        if (!valid.empty()) {
            jbyteArray jbytes = env->NewByteArray(valid.size());
            env->SetByteArrayRegion(jbytes, 0, valid.size(), reinterpret_cast<const jbyte *>(valid.data()));
            env->CallVoidMethod(cbData.callback, cbData.onToken, jbytes);
            env->DeleteLocalRef(jbytes);
        }
    }

    // Call onComplete
    if (!g_abort) {
        env->CallVoidMethod(cbData.callback, cbData.onComplete);
    }

    env->DeleteGlobalRef(cbData.callback);
    LOGI("nativeGenerate: done, generated %d tokens", generated);
}