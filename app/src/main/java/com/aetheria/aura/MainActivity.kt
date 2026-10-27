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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aetheria.aura.net.HermesClient
import com.aetheria.aura.actions.ActionExecutor
import com.aetheria.aura.router.AppIndex
import com.aetheria.aura.router.CommandRouter
import com.aetheria.aura.router.AuraActionRoute
import com.aetheria.aura.router.AppIndexManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import com.aetheria.aura.actions.AuraAction
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

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            // Permission granted, speech recognizer can be used
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize AppIndex with auto-refresh on package changes
        AppIndexManager.init(this)
        
        // Initialize SpeechRecognizer
        initSpeechRecognizer()
        
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
                var phase by remember { mutableStateOf(AuraMood.IDLE) }
                var status by remember { mutableStateOf<String?>(null) }
                val messages = remember { mutableStateOf<java.util.ArrayList<ChatMessage>>(ArrayList()) }
                val scope = rememberCoroutineScope()
                
                fun addMessage(role: ChatMessage.Role, content: String) {
                    messages.value.add(ChatMessage(role = role, content = content))
                }

                fun submit() {
                    val cmd = input.trim(); if (cmd.isEmpty()) return
                    input = ""; status = null
                    addMessage(ChatMessage.Role.USER, cmd)
                    scope.launch {
                        phase = AuraMood.THINKING
                        try {
                            // Route locally first
                            val route = CommandRouter.route(cmd)
                            val startMs = System.currentTimeMillis()
                            // Exhaustive when on sealed class AuraActionRoute
                            when (route) {
                                is AuraActionRoute.OpenApp -> {
                                    val action = AuraAction.OpenApp(route.target)
                                    val result = ActionExecutor.execute(action, this@MainActivity, pm, appIndex)
                                    Log.d("AuraRouter", "Routed OPEN_APP target='${route.target}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                    if (result == "error") addMessage(ChatMessage.Role.SYSTEM, "Could not find app: ${route.target}")
                                }
                                is AuraActionRoute.CallContact -> {
                                    val action = AuraAction.CallContact(route.name)
                                    val result = ActionExecutor.execute(action, this@MainActivity, pm, appIndex)
                                    Log.d("AuraRouter", "Routed CALL_CONTACT name='${route.name}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.SendText -> {
                                    val action = AuraAction.SendText(route.name, route.body)
                                    val result = ActionExecutor.execute(action, this@MainActivity, pm, appIndex)
                                    Log.d("AuraRouter", "Routed SEND_TEXT name='${route.name}' body='${route.body}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.WebSearch -> {
                                    val action = AuraAction.WebSearch(route.query)
                                    val result = ActionExecutor.execute(action, this@MainActivity, pm, appIndex)
                                    Log.d("AuraRouter", "Routed WEB_SEARCH query='${route.query}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.AskHermes -> {
                                    Log.d("AuraRouter", "Routed ASK_HERMES input='${route.input}' matchTimeMs=${System.currentTimeMillis() - startMs}")
                                    val result = HermesClient.send(route.input)
                                    phase = AuraMood.ACTING
                                    val execResult = ActionExecutor.execute(result, this@MainActivity, pm, appIndex)
                                    // Display Hermes response in chat
                                    when (result) {
                                        is AuraAction.AskHermes -> addMessage(ChatMessage.Role.AURA, result.input)
                                        is AuraAction.OpenApp -> addMessage(ChatMessage.Role.SYSTEM, "Opening ${result.target}...")
                                        is AuraAction.CallContact -> addMessage(ChatMessage.Role.SYSTEM, "Calling ${result.name}...")
                                        is AuraAction.SendText -> addMessage(ChatMessage.Role.SYSTEM, "Sending message to ${result.name}...")
                                        is AuraAction.WebSearch -> addMessage(ChatMessage.Role.SYSTEM, "Searching for ${result.query}...")
                                        else -> addMessage(ChatMessage.Role.AURA, "Done")
                                    }
                                    delay(900)
                                }
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                            addMessage(ChatMessage.Role.SYSTEM, "Error: ${e.message}")
                        } finally {
                            phase = AuraMood.IDLE
                        }
                    }
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
                    messages = messages.value
                )
            }
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