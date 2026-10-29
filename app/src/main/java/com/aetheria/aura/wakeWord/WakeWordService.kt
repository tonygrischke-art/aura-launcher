package com.aetheria.aura.wakeWord

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Wake word detection service using Sherpa-ONNX KeywordSpotter.
 * Runs in foreground with microphone access, detects configured keyword
 * and broadcasts an intent to launch the main activity.
 * 
 * REQUIRES: Model files in assets/ directory:
 * - hey-aura.onnx (or other keyword spotter model)
 * - tokens.txt
 * 
 * Download from: https://github.com/k2-fsa/sherpa-onnx/releases
 * For custom wake word training: https://github.com/k2-fsa/sherpa-onnx/tree/master/kws
 */
class WakeWordService : Service() {

    private val TAG = "WakeWordService"
    private var keywordSpotter: KeywordSpotter? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private var modelLoaded = false

    companion object {
        const val ACTION_WAKE_WORD_DETECTED = "com.aetheria.aura.WAKE_WORD_DETECTED"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wake_word_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initKeywordSpotter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning && modelLoaded) {
            startForeground(NOTIFICATION_ID, buildNotification())
            startListening()
        } else if (!modelLoaded) {
            Log.w(TAG, "Model not loaded, wake word service idle. Place model files in assets/")
            // Still show notification so user knows service is running (but not detecting)
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        keywordSpotter?.close()
        keywordSpotter = null
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun initKeywordSpotter() {
        scope.launch {
            try {
                // Copy model from assets to files directory (Sherpa needs file path)
                val modelFile = copyModelFromAssets("hey-aura.onnx")
                val tokensFile = copyModelFromAssets("tokens.txt")

                if (!modelFile.exists() || !tokensFile.exists()) {
                    Log.w(TAG, "Model files not found in assets. Wake word detection disabled.")
                    Log.w(TAG, "Expected: ${modelFile.absolutePath} and ${tokensFile.absolutePath}")
                    Log.w(TAG, "Download from: https://github.com/k2-fsa/sherpa-onnx/releases")
                    modelLoaded = false
                    return@launch
                }

                val config = KeywordSpotterConfig(
                    model = modelFile.absolutePath,
                    tokens = tokensFile.absolutePath,
                    numThreads = 2,
                    maxActivePaths = 4,
                    keywordsThreshold = 0.5f,
                    keywordsScore = 1.0f,
                    keywordsFile = "" // Use built-in keywords from model
                )

                keywordSpotter = KeywordSpotter(config)
                modelLoaded = true
                Log.d(TAG, "KeywordSpotter initialized with model: ${modelFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize KeywordSpotter", e)
                modelLoaded = false
            }
        }
    }

    private fun copyModelFromAssets(modelName: String): File {
        val outFile = File(filesDir, modelName)
        if (outFile.exists()) return outFile

        return try {
            assets.open(modelName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy $modelName from assets: ${e.message}")
            outFile // Return file even if copy failed (will not exist)
        }
    }

    private fun startListening() {
        keywordSpotter?.let { spotter ->
            val sampleRate = 16000 // Sherpa expects 16kHz
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            ).also { it.startRecording() }

            isRunning = true
            job = scope.launch {
                val buffer = ShortArray(bufferSize)
                while (isRunning) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // Convert ShortArray to FloatArray (normalized)
                        val floatBuffer = FloatArray(read)
                        for (i in 0 until read) {
                            floatBuffer[i] = buffer[i] / 32768.0f
                        }

                        // Process audio chunk
                        val result = spotter.acceptWaveform(floatBuffer)
                        if (result != null && result.isNotEmpty()) {
                            Log.d(TAG, "Wake word detected: $result")
                            onWakeWordDetected(result)
                        }
                    }
                }
            }
            Log.d(TAG, "Started listening for wake word")
        } ?: Log.w(TAG, "KeywordSpotter not initialized, cannot start listening")
    }

    private fun stopListening() {
        isRunning = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Stopped listening")
    }

    private fun onWakeWordDetected(keyword: String) {
        // Broadcast intent to launch main activity
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            putExtra("keyword", keyword)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        sendBroadcast(intent)

        // Also start MainActivity directly
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("wake_word", keyword)
        }
        launchIntent?.let { startActivity(it) }

        Log.d(TAG, "Wake word '$keyword' detected, launching MainActivity")
    }

    private fun buildNotification() = android.app.NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Aura Wake Word")
        .setContentText(if (modelLoaded) "Listening for wake word..." else "Model not loaded - add model to assets/")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setPriority(android.app.NotificationCompat.PRIORITY_LOW)
        .setCategory(android.app.NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Wake Word Detection",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background wake word listening service"
                setSound(null, null)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}