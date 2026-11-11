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
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
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

    // sherpa-onnx objects (initialized in initSherpaOnnx)
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null

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
        try {
            recognizer?.release()
            stream?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing sherpa-onnx", e)
        }
        recognizer = null
        stream = null
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
        // sherpa-onnx v2.0.1 API (com.k2fsa.sherpa.onnx): data classes with defaulted
        // constructors. OnlineRecognizer(assetManager = null, config) loads model files
        // from absolute paths (assetManager path is for asset:// models).
        val encoderPath = File(modelsDir, "encoder.onnx").absolutePath
        val decoderPath = File(modelsDir, "decoder.onnx").absolutePath
        val joinerPath = File(modelsDir, "joiner.onnx").absolutePath
        val tokensPath = File(modelsDir, "tokens.txt").absolutePath

        val featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)

        val transducer = OnlineTransducerModelConfig(
            encoder = encoderPath,
            decoder = decoderPath,
            joiner = joinerPath,
        )

        val modelConfig = OnlineModelConfig(
            transducer = transducer,
            tokens = tokensPath,
            modelType = "zipformer2",
            numThreads = 1,
            debug = false,
        )

        val config = OnlineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            maxActivePaths = 4,
        )

        val rec = OnlineRecognizer(assetManager = null, config = config)
        recognizer = rec
        stream = rec.createStream()
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

        val rec = recognizer ?: run {
            Log.e(TAG, "Recognition loop: recognizer not initialized")
            return
        }
        val st = stream ?: run {
            Log.e(TAG, "Recognition loop: stream not initialized")
            return
        }

        while (isRunning) {
            val read = audioRecord!!.read(pcmBuffer, 0, FRAME_SIZE)
            if (read > 0) {
                for (i in 0 until read) {
                    buffer[i] = pcmBuffer[i] / 32768f
                }

                // Feed audio to sherpa-onnx (stream-side accept, then drain decodes)
                st.acceptWaveform(buffer.copyOf(read), SAMPLE_RATE)
                while (rec.isReady(st)) {
                    rec.decode(st)
                }

                // Check result text for the keyword
                val result = rec.getResult(st)
                val text = result?.text ?: ""
                if (text.isNotBlank()) {
                    Log.d(TAG, "Recognition result: $text")
                    if (isWakeWord(text)) {
                        Log.i(TAG, "Wake word detected: $text")
                        broadcastWakeWord()
                        // Reset stream after detection
                        rec.reset(st)
                    }
                }

                // Check endpoint
                if (rec.isEndpoint(st)) {
                    rec.reset(st)
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
