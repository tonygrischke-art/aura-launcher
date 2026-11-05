package com.omarqaterge.hermesmobile;

import android.content.Context;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local LLM implementation using llama.cpp via JNI.
 * Provides on-device inference for the Hermes assistant.
 */
public final class LocalLlmImpl {
    private static volatile String sLoadError = ""; // declared first: static initializers run in textual order
    private static final boolean sNativeOk;
    static {
        boolean ok = false;
        try {
            // Dependencies (libllama, libggml, libmtmd, ...) resolve from the APK's native lib dir by DT_NEEDED name.
            System.loadLibrary("hermes-llama");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            android.util.Log.e("HermesLlama", "native library failed to load: " + e.getMessage());
            sLoadError = e.getMessage();
        }
        sNativeOk = ok;
    }

    // Native methods (must match JNI signatures exactly)
    private static native void nativeInit(String libDir);
    private static native boolean nativeLoad(String modelPath, String mmprojPath, int nCtx);
    private static native void nativeUnload();
    private static native void nativeAbort();
    private static native String nativeLastError();
    private static native void nativeGenerate(String prompt, int maxTokens, float temperature, GenerateCallback callback);

    /** Callback interface for streaming generation. */
    public interface GenerateCallback {
        void onToken(byte[] utf8Bytes);
        void onComplete();
        void onError(String error);
    }

    private static volatile boolean sInitialized = false;
    private static volatile boolean sLoaded = false;
    private static final Object sLock = new Object();
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    /** Initialize the native library (call once at app startup). */
    public static void init(Context context) {
        synchronized (sLock) {
            if (sInitialized || !sNativeOk) return;
            String libDir = context.getApplicationInfo().nativeLibraryDir;
            nativeInit(libDir);
            sInitialized = true;
        }
    }

    /** Load a GGUF model file with its multimodal projector. Must be called before generate(). */
    public static boolean loadModel(File modelFile, File mmprojFile, int nCtx) {
        if (!sNativeOk || !sInitialized) return false;
        nativeAbort(); // an in-flight generate() holds the native lock; ask it to stop. Call off the main thread.
        synchronized (sLock) {
            if (!modelFile.exists()) {
                return false;
            }
            if (mmprojFile != null && !mmprojFile.exists()) {
                return false;
            }
            boolean ok = nativeLoad(modelFile.getAbsolutePath(),
                    mmprojFile != null ? mmprojFile.getAbsolutePath() : "",
                    nCtx);
            sLoaded = ok;
            return ok;
        }
    }

    /** Unload the current model and free resources. */
    public static void unload() {
        if (!sNativeOk) return;
        nativeAbort();
        synchronized (sLock) {
            if (sLoaded) {
                nativeUnload();
                sLoaded = false;
            }
        }
    }

    /** Abort the current generation. */
    public static void abort() {
        if (sNativeOk) nativeAbort();
    }

    /**
     * Generate text from a prompt, streaming tokens via callback.
     * Runs on a background thread; callbacks are invoked on that thread.
     */
    public static void generate(String prompt, int maxTokens, float temperature, GenerateCallback callback) {
        if (!sLoaded) {
            callback.onError("Model not loaded");
            return;
        }
        sExecutor.execute(() -> {
            nativeGenerate(prompt, maxTokens, temperature, new GenerateCallback() {
                @Override
                public void onToken(byte[] utf8Bytes) {
                    callback.onToken(utf8Bytes);
                }
                @Override
                public void onComplete() {
                    callback.onComplete();
                }
                @Override
                public void onError(String error) {
                    callback.onError(error);
                }
            });
        });
    }

    /** Why the last loadModel()/init failed (native error text, or the library load error). */
    public static String lastError() {
        if (!sNativeOk) return "native library not loaded: " + sLoadError;
        return nativeLastError();
    }

    public static boolean isNativeAvailable() {
        return sNativeOk;
    }

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static boolean isInitialized() {
        return sInitialized;
    }
}