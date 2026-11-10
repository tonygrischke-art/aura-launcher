package com.aetheria.aura.wakeWord

import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wake word detection service using sherpa-onnx streaming KeywordSpotter.
 *
 * Model files (encoder.onnx, decoder.onnx, joiner.onnx, tokens.txt, keywords.txt)
 * are bundled in assets/wake/ and copied to the app's files directory on first start.
 *
 * The service:
 * 1. Copies model assets to files/wake/ on create
 * 2. Initializes sherpa-onnx OnlineRecognizer with the streaming model
 * 3. Starts AudioRecord at 16 kHz mono
 * 4. Feeds audio to the recognizer in a background thread
 * 5. On keyword match, broadcasts ACTION_WAKE_WORD_DETECTED
 * 6. Notification shows "Listening for wake word" when active,
 *    "Wake word detection unavailable" only if init fails
 */
class WakeWordService : Service() {

    private val TAG = "WakeWordService"
    private var isRunning = false
    private var recognitionThread: HandlerThread? = null
    private var audioRecord: AudioRecord? = null
    private var wakeWordInitialized = false
    private var initError: String? = null

    // sherpa-onnx objects (initialized in initSherpa)
    private var recognizer: Any? = null
    private var stream: Any? = null

    companion object {
        const val ACTION_WAKE_WORD_DETECTED = "com.aetheria.aura.WAKE_WORD_DETECTED"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "wake_word_channel"
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE = 1024 // 64ms at 16kHz
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Copy model assets to app files dir (sherpa-onnx needs file paths, not asset://)
        val modelsDir = File(filesDir, "wake")
        modelsDir.mkdirs()
        val requiredFiles = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")
        var allPresent = true
        for (name in requiredFiles) {
            val dest = File(modelsDir, name)
            if (!dest.exists()) {
                try {
                    copyAsset("wake/$name", dest)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy asset $name", e)
                    allPresent = false
                }
            }
        }

        if (!allPresent) {
            initError = "Missing model files in assets/wake/"
            Log.e(TAG, initError ?: "unknown")
            showNotification("Wake word detection unavailable - ${initError ?: "unknown"}")
            return
        }

        // Initialize sherpa-onnx recognizer
        try {
            initSherpaOnnx(modelsDir)
            wakeWordInitialized = true
            Log.i(TAG, "WakeWordService initialized - sherpa-onnx KWS ready")
            showNotification("Listening for wake word")
        } catch (e: Exception) {
            initError = "sherpa-onnx init failed: ${e.message}"
            Log.e(TAG, initError, e)
            showNotification("Wake word detection unavailable - ${initError ?: "unknown"}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            val notification = buildNotification()
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification.build())
                }
                isRunning = true
                Log.d(TAG, "WakeWordService started")

                if (wakeWordInitialized) {
                    startRecognitionLoop()
                } else {
                    Log.e(TAG, "Cannot start recognition - sherpa-onnx not initialized: $initError")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot start microphone foreground service: ${e.message}. Stopping service.")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        recognitionThread?.quit()
        recognitionThread = null
        Log.d(TAG, "WakeWordService stopped")
        super.onDestroy()
    }

    private fun copyAsset(assetName: String, dest: File) {
        val input: InputStream = assets.open(assetName)
        FileOutputStream(dest).use { output ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
            }
        }
    }

    private fun initSherpaOnnx(modelsDir: File) {
        // Load sherpa-onnx classes reflectively to avoid hard compile dependency
        // when the AAR is not available (CI will download it)
        val recognizerClass = Class.forName("io.github.k2fsa.sherpa.onnx.OnlineRecognizer")
        val configClass = Class.forName("io.github.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
        val streamClass = Class.forName("io.github.k2fsa.sherpa.onnx.OnlineStream")

        val encoderPath = File(modelsDir, "encoder.onnx").absolutePath
        val decoderPath = File(modelsDir, "decoder.onnx").absolutePath
        val joinerPath = File(modelsDir, "joiner.onnx").absolutePath
        val tokensPath = File(modelsDir, "tokens.txt").absolutePath

        // Build OnlineRecognizerConfig
        val config = configClass.getDeclaredConstructor().newInstance()

        // Set config fields (sherpa-onnx v2.0.1 uses public fields)
        configClass.getField("modelConfig").set(config, buildModelConfig(encoderPath, decoderPath, joinerPath, tokensPath))
        configClass.getField("num_threads").set(config, 1)
        configClass.getField("debug").set(config, false)
        configClass.getField("endpoint_rule").set(config, "")
        configClass.getField("max_active_paths").set(config, 4)

        // Create recognizer
        val createMethod = recognizerClass.getMethod("createFromFile", configClass)
        recognizer = createMethod.invoke(null, config)

        // Create stream
        val createStreamMethod = recognizerClass.getMethod("createStream")
        stream = createStreamMethod.invoke(recognizer)
    }

    private fun buildModelConfig(encoder: String, decoder: String, joiner: String, tokens: String): Any {
        val modelConfigClass = Class.forName("io.github.k2fsa.sherpa.onnx.OnlineModelConfig")
        val config = modelConfigClass.getDeclaredConstructor().newInstance()

        modelConfigClass.getField("model_type").set(config, "zipformer")
        modelConfigClass.getField("encoder").set(config, encoder)
        modelConfigClass.getField("decoder").set(config, decoder)
        modelConfigClass.getField("joiner").set(config, joiner)
        modelConfigClass.getField("tokens").set(config, tokens)
        modelConfigClass.getField("num_threads").set(config, 1)
        modelConfigClass.getField("debug").set(config, false)

        return config
    }

    private fun startRecognitionLoop() {
        recognitionThread = HandlerThread("WakeWordRecognition").apply { start() }
        val handler = Handler(recognitionThread!!.looper)
        handler.post {
            runRecognition()
        }
    }

    private fun runRecognition() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val buffer = FloatArray(FRAME_SIZE)
        val pcmBuffer = ShortArray(FRAME_SIZE)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        ).apply { startRecording() }

        val readMethod = recognizer?.javaClass?.getMethod("acceptWaveform", stream?.javaClass, FloatArray::class.java, Int::class.java, Int::class.java)
        val decodeMethod = recognizer?.javaClass?.getMethod("decodeStream", stream?.javaClass)
        val getResultMethod = recognizer?.javaClass?.getMethod("getResult", stream?.javaClass)
        val isEndpointMethod = recognizer?.javaClass?.getMethod("isEndpoint", stream?.javaClass)
        val resetMethod = recognizer?.javaClass?.getMethod("reset", stream?.javaClass)

        while (isRunning) {
            val read = audioRecord!!.read(pcmBuffer, 0, FRAME_SIZE)
            if (read > 0) {
                for (i in 0 until read) {
                    buffer[i] = pcmBuffer[i] / 32768f
                }

                // Feed audio to sherpa-onnx
                readMethod?.invoke(recognizer, stream, buffer, 0, read)

                // Decode and check result
                decodeMethod?.invoke(recognizer, stream)
                val result = getResultMethod?.invoke(recognizer, stream) as? String
                if (result != null && result.isNotBlank()) {
                    Log.d(TAG, "Recognition result: $result")
                    if (isWakeWord(result)) {
                        Log.i(TAG, "Wake word detected: $result")
                        broadcastWakeWord()
                        // Reset stream after detection
                        resetMethod?.invoke(recognizer, stream)
                    }
                }

                // Check endpoint
                val isEnd = isEndpointMethod?.invoke(recognizer, stream) as? Boolean ?: false
                if (isEnd) {
                    resetMethod?.invoke(recognizer, stream)
                }
            }
        }
    }

    private fun isWakeWord(text: String): Boolean {
        val keywordsFile = File(filesDir, "wake/keywords.txt")
        if (!keywordsFile.exists()) return false
        val keywords = keywordsFile.readText().lowercase().split("\n").filter { it.isNotBlank() }
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    private fun broadcastWakeWord() {
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).setPackage(packageName)
        sendBroadcast(intent)
        Log.i(TAG, "Broadcast WAKE_WORD_DETECTED")
    }

    private fun showNotification(text: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura Wake Word")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val text = if (wakeWordInitialized) "Listening for wake word" else "Wake word detection unavailable - $initError"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aura Wake Word")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
    }

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
