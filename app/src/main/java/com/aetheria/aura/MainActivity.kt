package com.aetheria.aura

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aetheria.aura.avatar.AuraMood
import com.aetheria.aura.avatar.AuraHomeScreen
import com.aetheria.aura.nim.NimChatMessage
import com.aetheria.aura.nim.NimClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aetheria.aura.model.ModelFileManager2
import com.aetheria.aura.net.HermesClient
import com.aetheria.aura.actions.ActionExecutor
import com.aetheria.aura.router.AppIndex
import com.aetheria.aura.router.CommandRouter
import com.aetheria.aura.router.AuraActionRoute
import com.aetheria.aura.router.AppIndexManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import com.aetheria.aura.actions.AuraAction
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayList

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: Role,
    val content: String,
    val timestamp: Date = Date()
) {
    enum class Role { USER, AURA, SYSTEM }
}

class MainActivity : ComponentActivity() {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private lateinit var nimClient: NimClient
    private var currentPhase: AuraMood = AuraMood.IDLE

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // Permission granted, speech recognizer can be used
        }
    }

    // Single source of truth for messages - mutableStateOf accessible from anywhere
    private val messagesState = androidx.compose.runtime.mutableStateOf<java.util.ArrayList<ChatMessage>>(ArrayList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize AppIndex with auto-refresh on package changes
        AppIndexManager.init(this)

        // Ensure model files are available in app private storage
        lifecycleScope.launch(Dispatchers.IO) {
            val success = ModelFileManager2.ensureModelsAvailable(this@MainActivity)
            Log.i("ModelFileManager2", "Models available: $success")
        }

        // Initialize SpeechRecognizer
        initSpeechRecognizer()

        // Initialize NimClient
        nimClient = NimClient(this) { readNimKey() }

        setContent {
            val pm = packageManager
            val appIndex = AppIndexManager.get() ?: AppIndex(this)
            // Build AppIndex on background thread
            lifecycleScope.launch(Dispatchers.IO) {
                val (count, buildTimeMs) = appIndex.build()
                Log.d("AuraRouter", "AppIndex built: $count apps in ${buildTimeMs}ms")
            }

            AuraTheme {
                var input by remember { mutableStateOf("") }
                var focused by remember { mutableStateOf(false) }
                var phase by remember { mutableStateOf(currentPhase) }
                var status by remember { mutableStateOf<String?>(null) }
                val messages = messagesState
                val scope = rememberCoroutineScope()
                
                fun addMessage(role: ChatMessage.Role, content: String) {
                    val msg = ChatMessage(role = role, content = content)
                    messages.value.add(msg)
                }

                fun submit() {
                    val cmd = input.trim(); if (cmd.isEmpty()) return
                    input = ""; status = null
                    doSubmit(cmd)
                }

                fun startListening() {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        startSpeechRecognition()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }

                fun stopListening() {
                    speechRecognizer?.stopListening()
                    isListening = false
                }

                val shown = if (phase != AuraMood.IDLE) phase else if (focused) AuraMood.LISTENING else AuraMood.IDLE
                AuraHomeScreen(
                    mood = shown,
                    input = input,
                    onInputChange = { input = it },
                    onSubmit = { submit() },
                    onFocusChange = { focused = it },
                    onMic = {
                        if (isListening) stopListening() else startListening()
                    },
                    status = status,
                    messages = messages.value as List<ChatMessage>
                )
            }
        }
    }

    fun doSubmit(cmd: String) {
        val trimmed = cmd.trim()
        if (trimmed.isEmpty()) return
        
        addMessageInternal(ChatMessage.Role.USER, trimmed)
        Log.d("AuraRouter", "submit cmd='$trimmed'")
        
        // Run on background thread
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        scope.launch {
            currentPhase = AuraMood.THINKING
            runOnUiThread { currentPhase = AuraMood.THINKING }
            try {
                // Route locally first
                val route = CommandRouter.route(trimmed)
                Log.d("AuraRouter", "route=$route")
                val startMs = System.currentTimeMillis()
                // Exhaustive when on sealed class AuraActionRoute
                val activity = this@MainActivity
                val context = activity
                val pkgMgr = activity.packageManager
                val appIdx = AppIndexManager.get() ?: AppIndex(activity)
                when (route) {
                    is AuraActionRoute.OpenApp -> {
                        val action = AuraAction.OpenApp(route.target)
                        val result = ActionExecutor.execute(action, context, pkgMgr, appIdx)
                        Log.d("AuraRouter", "Routed OPEN_APP target='${route.target}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                        if (result == "error") addMessageInternal(ChatMessage.Role.SYSTEM, "Could not find app: ${route.target}")
                    }
                    is AuraActionRoute.CallContact -> {
                        val action = AuraAction.CallContact(route.name)
                        val result = ActionExecutor.execute(action, context, pkgMgr, appIdx)
                        Log.d("AuraRouter", "Routed CALL_CONTACT name='${route.name}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                    }
                    is AuraActionRoute.SendText -> {
                        val action = AuraAction.SendText(route.name, route.body)
                        val result = ActionExecutor.execute(action, context, pkgMgr, appIdx)
                        Log.d("AuraRouter", "Routed SEND_TEXT name='${route.name}' body='${route.body}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                    }
                    is AuraActionRoute.WebSearch -> {
                        val action = AuraAction.WebSearch(route.query)
                        val result = ActionExecutor.execute(action, context, pkgMgr, appIdx)
                        Log.d("AuraRouter", "Routed WEB_SEARCH query='${route.query}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                    }
                    is AuraActionRoute.NavigateWeb -> {
                        val action = AuraAction.NavigateWeb(route.url)
                        val result = ActionExecutor.execute(action, context, pkgMgr, appIdx)
                        Log.d("AuraRouter", "Routed NAVIGATE_WEB url='${route.url}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                    }
                    is AuraActionRoute.AskHermes -> {
                                            Log.d("AuraRouter", "Routed ASK_HERMES input='${route.input}' matchTimeMs=${System.currentTimeMillis() - startMs}")
                                            val startTime = System.currentTimeMillis()
                                            var firstToken = false
                                            var tokenCount = 0
                                            val messageId = System.currentTimeMillis()

                                            // Add placeholder message for streaming
                                            val placeholderMsg = ChatMessage(id = messageId, role = ChatMessage.Role.AURA, content = "")
                                            runOnUiThread { addMessageInternal(ChatMessage.Role.AURA, "") }

                                            // Convert local messages to NimClient messages
                                            val nimMessages = messagesState.value.map { NimChatMessage(it.role.name.lowercase(), it.content) }.toList()

                                            // Determine tier: VISION for image-related, THINKING for complex, INSTANT for quick
                                            val tier = when {
                                                route.input.contains("image") || route.input.contains("photo") || route.input.contains("picture") || route.input.contains("camera") || route.input.contains("screenshot") -> com.aetheria.aura.nim.NimClient.Tier.VISION
                                                route.input.length > 200 -> com.aetheria.aura.nim.NimClient.Tier.THINKING
                                                else -> com.aetheria.aura.nim.NimClient.Tier.INSTANT
                                            }

                                            nimClient.streamChat(
                                                messages = nimMessages,
                                                tier = tier,
                                                onToken = { token ->
                                                    if (!firstToken) {
                                                        val ttfb = System.currentTimeMillis() - startTime
                                                        Log.d("AuraRouter", "TTFB: ${ttfb}ms")
                                                        firstToken = true
                                                    }
                                                    tokenCount++
                                                    // Update the streaming message
                                                    runOnUiThread {
                                                        val idx = messagesState.value.indexOfFirst { it.id == messageId }
                                                        if (idx >= 0) {
                                                            val current = messagesState.value[idx]
                                                            messagesState.value[idx] = current.copy(content = current.content + token)
                                                        }
                                                    }
                                                },
                                                onComplete = { success ->
                                                    val totalTime = System.currentTimeMillis() - startTime
                                                    runOnUiThread {
                                                        val finalIdx = messagesState.value.indexOfFirst { it.id == messageId }
                                                        if (finalIdx >= 0) {
                                                            val current = messagesState.value[finalIdx]
                                                            if (current.content.isEmpty()) {
                                                                messagesState.value[finalIdx] = current.copy(content = "(no response)")
                                                            }
                                                        }
                                                    }
                                                    Log.d("AuraRouter", "NimClient stream complete: success=$success tokens=$tokenCount totalTime=${totalTime}ms tier=$tier")
                                                    if (!success) {
                                                        runOnUiThread {
                                                            val errIdx = messagesState.value.indexOfFirst { it.id == messageId }
                                                            if (errIdx >= 0) {
                                                                messagesState.value[errIdx] = messagesState.value[errIdx].copy(content = "NIM unreachable (stream failed)")
                                                            }
                                                        }
                                                    }
                                                },
                                                onError = { error ->
                                                    Log.e("AuraRouter", "NimClient error: $error")
                                                    runOnUiThread {
                                                        val errorIdx = messagesState.value.indexOfFirst { it.id == messageId }
                                                        if (errorIdx >= 0) {
                                                            messagesState.value[errorIdx] = messagesState.value[errorIdx].copy(content = "NIM unreachable ($error)")
                                                        }
                                                    }
                                                }
                                            )
                                        }
                }
            } catch (e: Exception) {
                Log.e("AuraRouter", "submit failed cmd='$trimmed'", e)
                runOnUiThread { addMessageInternal(ChatMessage.Role.SYSTEM, "Error: ${e.message}") }
            } finally {
                currentPhase = AuraMood.IDLE
                runOnUiThread { currentPhase = AuraMood.IDLE }
            }
        }
    }

    private fun addMessageInternal(role: ChatMessage.Role, content: String) {
        runOnUiThread {
            val msg = ChatMessage(role = role, content = content)
            messagesState.value.add(msg)
        }
    }

    private fun readNimKey(): String {
        return try {
            val file = File(filesDir, "nim.key")
            if (file.exists()) {
                file.readText().trim()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e("AuraRouter", "Failed to read NIM key", e)
            ""
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                isListening = true
                Log.d("AuraMic", "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d("AuraMic", "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray) {}

            override fun onEndOfSpeech() {
                isListening = false
                Log.d("AuraMic", "Speech ended")
            }

            override fun onError(error: Int) {
                isListening = false
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
                    else -> "Unknown error ($error)"
                }
                Log.e("AuraMic", "Speech error: $errorMsg")
            }

            override fun onResults(results: Bundle) {
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val recognizedText = matches[0]
                    Log.d("AuraMic", "Recognized: $recognizedText")
                    // Submit the recognized text
                    doSubmit(recognizedText)
                }
            }

            override fun onPartialResults(partialResults: Bundle) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e("AuraMic", "Failed to start listening", e)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}

@Composable
fun AuraTheme(content: @Composable () -> Unit) {
    val colors = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFF00E5FF),
        secondary = Color(0xFF8B5CF6),
        surface = Color(0xFF000000),
        error = Color(0xFFFF4D1A),
        onPrimary = Color.Black,
        onSecondary = Color.Black,
        onSurface = Color.White,
        onError = Color.White,
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        shapes = androidx.compose.material3.Shapes(),
        content = content
    )
}