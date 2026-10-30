package com.aetheria.aura

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aetheria.aura.avatar.AuraMood
import com.aetheria.aura.avatar.AuraHomeScreen
import com.aetheria.aura.nim.NimChatMessage
import com.aetheria.aura.nim.NimClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aetheria.aura.model.TestModelFileManager
import com.aetheria.aura.net.HermesClient
import com.aetheria.aura.actions.ActionExecutor
import com.aetheria.aura.router.AppIndex
import com.aetheria.aura.router.CommandRouter
import com.aetheria.aura.router.AuraActionRoute
import com.aetheria.aura.router.AppIndexManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import com.aetheria.aura.actions.AuraAction
import com.aetheria.aura.accessibility.AuraAccessibilityService
import com.aetheria.aura.wakeWord.WakeWordService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import com.aetheria.aura.model.ModelFileManager

// Top-level PermissionItem for onboarding
data class PermissionItem(
    val permission: String,
    val title: String,
    val description: String,
    val isSpecial: Boolean = false,
    val settingsAction: String? = null
)

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

    // Permission onboarding state
    private val prefsName = "aura_prefs"
    private val prefsFirstRunKey = "first_run_complete"
    private val prefsAccessibilityEnabledKey = "accessibility_enabled"
    
    // Permission request launchers
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Permission result handled by onboarding UI
    }
    
    private val requestMultiplePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        // Handled by onboarding UI
    }

    // Single source of truth for messages - mutableStateOf accessible from anywhere
    private val messagesState = androidx.compose.runtime.mutableStateOf<java.util.ArrayList<ChatMessage>>(ArrayList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize AppIndex with auto-refresh on package changes
        AppIndexManager.init(this)

        // Ensure model files are available in app private storage
        lifecycleScope.launch(Dispatchers.IO) {
            val success = TestModelFileManager.ensureModelsAvailable(this@MainActivity)
            Log.i("TestModelFileManager", "Models available: $success")
        }

        // Initialize SpeechRecognizer
        initSpeechRecognizer()

        // Initialize NimClient
        nimClient = NimClient(this) { readNimKey() }

        // Start wake word service on resume (API 34+ requires activity in foreground for microphone FGS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                    val serviceIntent = Intent(this@MainActivity, WakeWordService::class.java)
                    startForegroundService(serviceIntent)
                    Log.i("AuraRouter", "Started WakeWordService from MainActivity.onResume (API 34+, RESUMED)")
                }
            }
        }

        setContent {
            val pm = packageManager
            val appIndex = AppIndexManager.get() ?: AppIndex(this)
            // Build AppIndex on background thread
            lifecycleScope.launch(Dispatchers.IO) {
                val (count, buildTimeMs) = appIndex.build()
                Log.d("AuraRouter", "AppIndex built: $count apps in ${buildTimeMs}ms")
            }

            // Check if first run onboarding is needed
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val firstRunComplete = prefs.getBoolean(prefsFirstRunKey, false)
            
            AuraTheme {
                var input by remember { mutableStateOf("") }
                var focused by remember { mutableStateOf(false) }
                var phase by remember { mutableStateOf(currentPhase) }
                var status by remember { mutableStateOf<String?>(null) }
                val messages = messagesState
                val scope = rememberCoroutineScope()
                
                // Settings screen state
                var showSettings by remember { mutableStateOf(false) }
                
                // Onboarding state
                var showOnboarding by remember { mutableStateOf(!firstRunComplete) }
                
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
                
                // Show onboarding overlay if needed
                if (showOnboarding) {
                    PermissionOnboardingScreen(
                        onComplete = { 
                            showOnboarding = false
                            getSharedPreferences(prefsName, MODE_PRIVATE)
                                .edit()
                                .putBoolean(prefsFirstRunKey, true)
                                .apply()
                        },
                        onSkip = {
                            showOnboarding = false
                            getSharedPreferences(prefsName, MODE_PRIVATE)
                                .edit()
                                .putBoolean(prefsFirstRunKey, true)
                                .apply()
                        },
                        requestPermissionLauncher = requestPermissionLauncher,
                        requestMultiplePermissionsLauncher = requestMultiplePermissionsLauncher
                    )
                } else if (showSettings) {
                    SettingsScreen(onClose = { showSettings = false })
                } else {
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
                        messages = messages.value as List<ChatMessage>,
                        onSettings = { showSettings = true }
                    )
                }
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
                                            
                                            // Check model source preference
                                            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                                            val modelSource = ModelSource.valueOf(
                                                prefs.getString("model_source", ModelSource.ONLINE.name) ?: ModelSource.ONLINE.name
                                            )
                                            
                                            // If OFFLINE or BOTH selected, try local first (placeholder for now)
                                            if (modelSource == ModelSource.OFFLINE || modelSource == ModelSource.BOTH) {
                                                // TODO: Implement local GGUF inference with llama.cpp
                                                // For now, show info and fall through to NIM if BOTH
                                                if (modelSource == ModelSource.OFFLINE) {
                                                    runOnUiThread { 
                                                        addMessageInternal(ChatMessage.Role.SYSTEM, "Offline mode selected but local inference not yet implemented. Falling back to online...") 
                                                    }
                                                } else {
                                                    runOnUiThread { 
                                                        addMessageInternal(ChatMessage.Role.SYSTEM, "Trying local model first (not yet implemented), will fallback to NIM...") 
                                                    }
                                                }
                                            }
                                            
                                            // Use NIM (online) for now
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
            val prefs = getSharedPreferences("aura_prefs", MODE_PRIVATE)
            prefs.getString("nim_key", "") ?: ""
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

/**
 * Permission onboarding screen shown on first run.
 * Guides user through granting all required permissions.
 */
@Composable
fun PermissionOnboardingScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    requestPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    requestMultiplePermissionsLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
) {
    val context = LocalContext.current
    val pm = context.packageManager
    
    val permissions = remember {
        listOf(
            PermissionItem(
                permission = Manifest.permission.RECORD_AUDIO,
                title = "Microphone",
                description = "Required for voice commands and wake word detection"
            ),
            PermissionItem(
                permission = Manifest.permission.READ_CONTACTS,
                title = "Contacts",
                description = "Needed to send texts and make calls by name"
            ),
            PermissionItem(
                permission = Manifest.permission.READ_SMS,
                title = "SMS",
                description = "Read and send text messages"
            ),
            PermissionItem(
                permission = Manifest.permission.SEND_SMS,
                title = "Send SMS",
                description = "Send text messages from Aura"
            ),
            PermissionItem(
                permission = Manifest.permission.CALL_PHONE,
                title = "Phone Calls",
                description = "Make phone calls by voice command"
            ),
            PermissionItem(
                permission = Manifest.permission.CAMERA,
                title = "Camera",
                description = "Future: vision features and QR codes"
            ),
            PermissionItem(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Location",
                description = "Location-based queries and services"
            ),
            PermissionItem(
                permission = Manifest.permission.READ_MEDIA_IMAGES,
                title = "Media & Photos",
                description = "Access photos for vision features"
            ),
            PermissionItem(
                permission = Manifest.permission.SYSTEM_ALERT_WINDOW,
                title = "Display Over Other Apps",
                description = "Show Aura overlay and floating UI",
                isSpecial = true,
                settingsAction = SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION
            ),
            PermissionItem(
                permission = Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
                title = "Accessibility Service",
                description = "Screen reading and UI automation for screen-act loop",
                isSpecial = true,
                settingsAction = SystemSettings.ACTION_ACCESSIBILITY_SETTINGS
            ),
            PermissionItem(
                permission = Manifest.permission.POST_NOTIFICATIONS,
                title = "Notifications",
                description = "Show Aura responses and alerts",
                isSpecial = true,
                settingsAction = SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS
            )
        )
    }
    
    // Track permission states
    val ctx = LocalContext.current
    val permissionStates = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            permissions.forEach { map[it.permission] = ContextCompat.checkSelfPermission(ctx, it.permission) == PackageManager.PERMISSION_GRANTED }
        }
    }
    
    val allGranted = remember { 
        mutableStateOf(permissionStates.values.all { it }) 
    }
    
    // Request permission handler
    fun requestPermission(perm: PermissionItem) {
        if (perm.isSpecial) {
            // Special permissions need settings intent
            val settingsIntent = Intent(perm.settingsAction).apply {
                if (perm.settingsAction == SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
                } else if (perm.settingsAction == SystemSettings.ACTION_MANAGE_OVERLAY_PERMISSION) {
                    data = android.net.Uri.parse("package:${context.packageName}")
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
        } else {
            // Regular permission - use launcher
            requestPermissionLauncher.launch(perm.permission)
        }
    }
    
    // Update allGranted when permissionStates change
    androidx.compose.runtime.LaunchedEffect(permissionStates) {
        allGranted.value = permissionStates.values.all { it }
    }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Background overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
        )
        
        // Onboarding card
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(24.dp)
                .heightIn(min = 400.dp, max = 700.dp)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Text(
                    text = "Welcome to Aura",
                    style = Typography().headlineMedium,
                    color = Color(0xFF00E5FF),
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = "Grant permissions to unlock all features",
                    style = Typography().bodyMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                
                // Progress indicator
                val grantedCount = permissionStates.values.count { it }
                val totalCount = permissions.size
                
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    progress = grantedCount.toFloat() / totalCount.toFloat()
                )
                
                Text(
                    text = "$grantedCount of $totalCount permissions granted",
                    style = Typography().bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )
                
                // Permission list
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    permissions.forEach { perm ->
                        val granted = permissionStates[perm.permission] ?: false
                        
                        PermissionRow(
                            permission = perm,
                            granted = granted,
                            onRequest = { requestPermission(perm) }
                        )
                    }
                }
                
                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Skip for now")
                    }
                    
                    Button(
                        onClick = {
                            if (allGranted.value) onComplete()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = allGranted.value,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (allGranted.value) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.1f),
                            contentColor = if (allGranted.value) Color.Black else Color.White.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(if (allGranted.value) "Continue" else "Grant all first")
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    permission: PermissionItem,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .background(
                if (granted) Color(0xFF00E5FF).copy(alpha = 0.1f) else Color.Transparent,
                shape = Shapes().small
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Icon(
            imageVector = if (granted) 
                Icons.Filled.CheckCircle 
            else if (permission.isSpecial) 
                Icons.Filled.Settings 
            else 
                Icons.Filled.HelpOutline,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(24.dp)
        )
        
        // Info
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = permission.title,
                style = Typography().bodyMedium,
                color = Color.White
            )
            Text(
                text = permission.description,
                style = Typography().bodySmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        
        // Action button
        if (!granted) {
            Button(
                onClick = onRequest,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (permission.isSpecial) Color.White.copy(alpha = 0.1f) else Color(0xFF00E5FF),
                    contentColor = if (permission.isSpecial) Color.White else Color.Black
                )
            ) {
                Text(if (permission.isSpecial) "Open Settings" else "Grant")
            }
        } else {
            Text(
                text = "✓ Granted",
                style = Typography().bodySmall,
                color = Color(0xFF00E5FF)
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)

    // Model source preference
    var modelSource by remember { mutableStateOf(
        ModelSource.valueOf(prefs.getString("model_source", ModelSource.ONLINE.name) ?: ModelSource.ONLINE.name)
    ) }

    // Show onboarding on next launch
    var showOnboardingOnNextLaunch by remember { mutableStateOf(
        prefs.getBoolean("show_onboarding_next_launch", false)
    ) }

    // NIM key - stored in SharedPreferences (not committed to git)
    var nimKey by remember { mutableStateOf(prefs.getString("nim_key", "") ?: "") }

    // NIM key status
    val nimKeyExists = nimKey.isNotBlank()

    // Local models status - use ModelFileManager's directory
    val localModelsDir = ModelFileManager.modelsDir(context)
    val localModelsExist = localModelsDir.exists() && localModelsDir.listFiles()?.isNotEmpty() == true

    // Availability: ONLINE needs key, OFFLINE needs models, BOTH needs BOTH
    val onlineAvailable = nimKeyExists
    val offlineAvailable = localModelsExist
    val bothAvailable = onlineAvailable && offlineAvailable

    // If selected source becomes unavailable, switch to first available
    LaunchedEffect(onlineAvailable, offlineAvailable, bothAvailable) {
        val currentAvailable = when (modelSource) {
            ModelSource.ONLINE -> onlineAvailable
            ModelSource.OFFLINE -> offlineAvailable
            ModelSource.BOTH -> bothAvailable
        }
        if (!currentAvailable) {
            val firstAvailable = when {
                onlineAvailable -> ModelSource.ONLINE
                offlineAvailable -> ModelSource.OFFLINE
                bothAvailable -> ModelSource.BOTH
                else -> ModelSource.ONLINE
            }
            modelSource = firstAvailable
            prefs.edit().putString("model_source", firstAvailable.name).apply()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Background overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
        )
        
        // Settings card
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(24.dp)
                .heightIn(min = 400.dp, max = 700.dp)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Settings",
                        style = Typography().headlineMedium,
                        color = Color(0xFF00E5FF)
                    )
                    
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close settings",
                            tint = Color.White
                        )
                    }
                }
                
                // Model Source Selection
                Text(
                    text = "AI Model Source",
                    style = Typography().titleMedium,
                    color = Color(0xFF00E5FF)
                )
                
                Text(
                    text = "Choose how Aura processes your requests",
                    style = Typography().bodySmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                
                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ModelSource.values().forEach { source ->
                                        val isSelected = (modelSource == source)
                                        val isAvailable = when (source) {
                                            ModelSource.ONLINE -> onlineAvailable
                                            ModelSource.OFFLINE -> offlineAvailable
                                            ModelSource.BOTH -> bothAvailable
                                        }

                                        // Keep selected item enabled even if unavailable, so user can see status/fix it
                                        // If ALL are unavailable, keep ONLINE enabled as default
                                        val allUnavailable = !onlineAvailable && !offlineAvailable && !bothAvailable
                                        val itemEnabled = isAvailable || isSelected || (allUnavailable && source == ModelSource.ONLINE)
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.15f)
                                    else if (!itemEnabled) Color.White.copy(alpha = 0.05f)
                                    else Color.Transparent
                                ),
                            onClick = { 
                                if (itemEnabled) {
                                    modelSource = source
                                    prefs.edit().putString("model_source", source.name).apply()
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { 
                                        if (itemEnabled) {
                                            modelSource = source
                                            prefs.edit().putString("model_source", source.name).apply()
                                        }
                                    },
                                    enabled = itemEnabled,
                                    modifier = Modifier.padding(end = 12.dp)
                                )
                                
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = source.displayName,
                                        style = Typography().bodyMedium,
                                        color = if (itemEnabled) Color.White else Color.White.copy(alpha = 0.4f)
                                    )
                                    Text(
                                        text = source.description,
                                        style = Typography().bodySmall,
                                        color = if (itemEnabled) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.3f)
                                    )
                                }
                                
                                if (!itemEnabled) {
                                    Text(
                                        text = "Unavailable",
                                        style = Typography().bodySmall,
                                        color = Color.White.copy(alpha = 0.4f),
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                
                // Divider
                Divider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    thickness = 1.dp
                )
                
                // Other Settings
                Text(
                    text = "Other Settings",
                    style = Typography().titleMedium,
                    color = Color(0xFF00E5FF)
                )
                
                // Show onboarding toggle
                var showOnboardingNextLaunch by remember { mutableStateOf(
                    context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)
                        .getBoolean("show_onboarding_next_launch", false)
                ) }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Show onboarding on next launch",
                            style = Typography().bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = "Re-run permission onboarding",
                            style = Typography().bodySmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    
                    Switch(
                                            checked = showOnboardingNextLaunch,
                                            onCheckedChange = { checked ->
                                                showOnboardingNextLaunch = checked
                                                context.getSharedPreferences("aura_prefs", android.content.Context.MODE_PRIVATE)
                                                    .edit()
                                                    .putBoolean("show_onboarding_next_launch", checked)
                                                    .apply()
                                            }
                                        )
                                    }

                                    // NIM API Key input
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "NIM API Key",
                                            style = Typography().titleMedium,
                                            color = Color(0xFF00E5FF)
                                        )
                                        Text(
                                            text = "Enter your NVIDIA NIM API key for online models",
                                            style = Typography().bodySmall,
                                            color = Color.White.copy(alpha = 0.6f)
                                        )
                                        var keyInput by remember { mutableStateOf(nimKey) }
                                        TextField(
                                            value = keyInput,
                                            onValueChange = { newVal ->
                                                keyInput = newVal
                                                nimKey = newVal
                                                prefs.edit().putString("nim_key", newVal).apply()
                                            },
                                            label = { Text("NIM API Key", style = Typography().bodyMedium, color = Color.White.copy(alpha = 0.6f)) },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth(),
                                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                            colors = TextFieldDefaults.textFieldColors(
                                                containerColor = Color.White.copy(alpha = 0.05f),
                                                unfocusedIndicatorColor = Color.White.copy(alpha = 0.3f),
                                                focusedIndicatorColor = Color(0xFF00E5FF)
                                            )
                                        )
                                        if (nimKeyExists) {
                                            Text(
                                                text = "Key saved ✓",
                                                style = Typography().bodySmall,
                                                color = Color(0xFF00E5FF)
                                            )
                                        }
                                    }

                                    // Divider
                                    Divider(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        color = Color.White.copy(alpha = 0.2f),
                                        thickness = 1.dp
                                    )

                                    // Local Models Status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Local Models (GGUF)",
                            style = Typography().bodyMedium,
                            color = Color.White
                        )
                        Text(
                            text = if (localModelsExist) "Available ✓" else "Not found - copy to external storage: /sdcard/Android/data/com.aetheria.aura/files/models/",
                            style = Typography().bodySmall,
                            color = if (localModelsExist) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.6f)
                        )
                    }
                    
                    if (!localModelsExist) {
                        OutlinedButton(
                            onClick = {
                                // Could open file manager or show instructions
                            },
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Manage")
                        }
                    }
                }
            }
        }
    }
}

enum class ModelSource {
    ONLINE("Online (NIM)", "NVIDIA NIM API - requires internet, fastest"),
    OFFLINE("Offline (Local)", "Local GGUF models - private, no internet needed"),
    BOTH("Auto (Offline → Online)", "Try local first, fallback to NIM");

    val displayName: String
    val description: String

    constructor(displayName: String, description: String) {
        this.displayName = displayName
        this.description = description
    }
}