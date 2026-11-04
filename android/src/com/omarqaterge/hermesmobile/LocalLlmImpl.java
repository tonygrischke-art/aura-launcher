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
    static {
        System.loadLibrary("hermes-llama");
    }

    // Native methods (must match JNI signatures exactly)
    private static native void nativeInit(String libDir);
    private static native boolean nativeLoad(String modelPath, int nCtx);
    private static native void nativeUnload();
    private static native void nativeAbort();
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
            if (sInitialized) return;
            String libDir = context.getApplicationInfo().nativeLibraryDir;
            nativeInit(libDir);
            sInitialized = true;
        }
    }

    /** Load a GGUF model file. Must be called before generate(). */
    public static boolean loadModel(File modelFile, int nCtx) {
        synchronized (sLock) {
            if (!modelFile.exists()) {
                return false;
            }
            boolean ok = nativeLoad(modelFile.getAbsolutePath(), nCtx);
            sLoaded = ok;
            return ok;
        }
    }

    /** Unload the current model and free resources. */
    public static void unload() {
        synchronized (sLock) {
            if (sLoaded) {
                nativeUnload();
                sLoaded = false;
            }
        }
    }

    /** Abort the current generation. */
    public static void abort() {
        nativeAbort();
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

    public static boolean isLoaded() {
        return sLoaded;
    }

    public static boolean isInitialized() {
        return sInitialized;
    }
}