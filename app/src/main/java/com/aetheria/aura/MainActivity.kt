package com.aetheria.aura

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.aetheria.aura.avatar.AuraMood
import com.aetheria.aura.avatar.AuraHomeScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.aetheria.aura.net.HermesClient
import com.aetheria.aura.actions.ActionExecutor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    private val requestContactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            // Permission denied - will be handled on next submit attempt
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val pm = packageManager
            AuraTheme {
                var input by remember { mutableStateOf("") }
                var focused by remember { mutableStateOf(false) }
                var phase by remember { mutableStateOf(AuraMood.IDLE) }
                var status by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()

                fun submit() {
                    val cmd = input.trim()
                    if (cmd.isEmpty()) return
                    // Disable submit while phase != IDLE
                    if (phase != AuraMood.IDLE) return

                    input = ""
                    status = null
                    scope.launch {
                        phase = AuraMood.THINKING
                        try {
                            val result = HermesClient.send(cmd)
                            phase = AuraMood.ACTING
                            // Pass context (this@MainActivity) to ActionExecutor
                            val execResult = ActionExecutor.execute(result, this@MainActivity, pm)
                            handleExecResult(execResult)
                            // Small delay to show ACTING phase
                            kotlinx.coroutines.delay(900)
                        } catch (e: Exception) {
                            status = "Hermes unreachable"
                        } finally {
                            phase = AuraMood.IDLE
                        }
                    }
                }

                fun handleExecResult(result: String) {
                    when {
                        result == "confirm" -> {
                            // Success, no status needed
                        }
                        result == "permission_denied" -> {
                            status = "Contacts permission required"
                            // Request permission for next time
                            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
                        }
                        result.startsWith("no_contact:") -> {
                            val name = result.substringAfter("no_contact:")
                            status = "No contact named '$name'"
                        }
                        result.startsWith("ambiguous_contact:") -> {
                            val name = result.substringAfter("ambiguous_contact:")
                            status = "Multiple contacts match '$name'. Be more specific."
                        }
                        else -> {
                            status = "Action failed: $result"
                        }
                    }
                }

                val shown = if (phase != AuraMood.IDLE) phase else if (focused) AuraMood.LISTENING else AuraMood.IDLE
                AuraHomeScreen(
                    mood = shown,
                    input = input,
                    onInputChange = { input = it },
                    onSubmit = { submit() },
                    onFocusChange = { focused = it },
                    onMic = { /* mic: UI only */ },
                    status = status
                )
            }
        }
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