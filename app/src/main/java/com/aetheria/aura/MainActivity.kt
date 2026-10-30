package com.aetheria.aura

import android.os.Bundle
import androidx.activity.compose.setContent
import com.aetheria.aura.avatar.AuraMood
import com.aetheria.aura.avatar.AuraHomeScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.aetheria.aura.net.HermesClient
import com.aetheria.aura.actions.ActionExecutor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuraTheme {
                var input by remember { mutableStateOf("") }
                var focused by remember { mutableStateOf(false) }
                var phase by remember { mutableStateOf(AuraMood.IDLE) }
                var status by remember { mutableStateOf<String?>(null) }
                val scope = rememberCoroutineScope()
                fun submit() {
                    val cmd = input.trim(); if (cmd.isEmpty()) return
                    input = ""; status = null
                    scope.launch {
                        phase = AuraMood.THINKING
                        try {
                            val result = HermesClient.send(this@MainActivity, cmd)
                            phase = AuraMood.ACTING
                            val execResult = ActionExecutor.execute(result, this@MainActivity)
                            delay(900)
                        } catch (e: Exception) { status = "Hermes unreachable" } finally { phase = AuraMood.IDLE }
                    }
                }
                val shown = if (phase != AuraMood.IDLE) phase else if (focused) AuraMood.LISTENING else AuraMood.IDLE
                AuraHomeScreen(shown, input, { input = it }, { submit() }, { focused = it }, { /* mic: UI only */ }, status)
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
