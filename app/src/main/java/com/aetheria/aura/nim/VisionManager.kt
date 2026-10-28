package com.aetheria.aura.nim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Vision Manager for gesture detection using camera
 * Privacy-first: only activates when user explicitly enables gesture mode
 */
class VisionManager(
    private val context: Context,
    private val nimClient: NimClient,
    private val lifecycle: Lifecycle
) : LifecycleObserver {

    private val TAG = "VisionManager"
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val imageAnalysisExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: androidx.camera.core.ProcessCameraProvider? = null
    private var imageReader: ImageReader? = null
    private var isActive = false
    private var gestureCallback: ((String) -> Unit)? = null
    private var lastFrameTime = 0L
    private val minFrameIntervalMs = 200 // Max 5 FPS to save battery

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        stopGestureMode()
        cameraExecutor.shutdown()
        imageAnalysisExecutor.shutdown()
    }

    /**
     * Start gesture detection mode
     * Only activates if user granted camera permission
     */
    fun startGestureMode(onGesture: (String) -> Unit): Boolean {
        if (isActive) return true

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted")
            return false
        }

        isActive = true
        gestureCallback = onGesture
        startCamera()
        Log.d(TAG, "Gesture mode activated")
        return true
    }

    fun stopGestureMode() {
        if (!isActive) return
        isActive = false
        gestureCallback = null
        stopCamera()
        Log.d(TAG, "Gesture mode deactivated")
    }

    fun isGestureModeActive(): Boolean = isActive

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
            }
        }, cameraExecutor)
    }

    private fun bindCameraUseCases() {
        cameraProvider?.let { provider ->
            provider.unbindAll()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // Preview (optional, for visual feedback)
            // val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

            // Image Analysis for gesture detection
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(imageAnalysisExecutor, ::analyzeImage) }

            try {
                provider.bindToLifecycle(lifecycle, cameraSelector, imageAnalysis)
                Log.d(TAG, "Camera bound for gesture detection")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageReader?.close()
        imageReader = null
    }

    private fun analyzeImage(imageProxy: androidx.camera.core.ImageProxy) {
        if (!isActive) {
            imageProxy.close()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastFrameTime < minFrameIntervalMs) {
            imageProxy.close()
            return
        }
        lastFrameTime = now

        // Convert to base64 JPEG
        val image = imageProxy.image ?: return
        val bitmap = yuvToBitmap(image)
        val base64 = bitmapToBase64(bitmap)
        imageProxy.close()
        bitmap.recycle()

        // Send to vision model
        CoroutineScope(Dispatchers.IO).launch {
            val messages = listOf(
                NimClient.ChatMessage("user", "Analyze this frame for hand gestures. Reply with gesture name only (e.g. 'swipe_left', 'pinch', 'tap', 'none')."),
                NimClient.ChatMessage("user", "data:image/jpeg;base64,$base64")
            )

            nimClient.streamChat(
                messages = messages,
                tier = NimClient.Tier.VISION,
                onToken = { token ->
                    // Accumulate tokens
                },
                onComplete = { success ->
                    if (success) {
                        // Parse gesture from accumulated response
                        // This would need the full response - simplified for now
                    }
                },
                onError = { error ->
                    Log.w(TAG, "Vision model error: $error")
                }
            )
        }
    }

    private fun yuvToBitmap(image: Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val yData = ByteArray(ySize)
        val uData = ByteArray(uSize)
        val vData = ByteArray(vSize)

        yBuffer.get(yData)
        uBuffer.get(uData)
        vBuffer.get(vData)

        val width = image.width
        val height = image.height

        val rgba = IntArray(width * height)
        var yIndex = 0
        var uvIndex = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val yVal = (yData[yIndex] + 256) % 256
                val uVal = (uData[uvIndex] + 256) % 256
                val vVal = (vData[uvIndex] + 256) % 256

                var r = (yVal + 1.402 * (vVal - 128)).toInt()
                var g = (yVal - 0.34414 * (uVal - 128) - 0.71414 * (vVal - 128)).toInt()
                var b = (yVal + 1.772 * (uVal - 128)).toInt()

                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)

                rgba[y * width + x] = 0xFF000000 or (r shl 16) or (g shl 8) or b

                yIndex++
                if (x % 2 == 1 && y % 2 == 1) uvIndex += 2
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(rgba, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}