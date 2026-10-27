package com.aetheria.aura

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
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
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import com.aetheria.aura.actions.AuraAction

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val pm = packageManager
            val appIndex = AppIndex(this)
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
                val scope = rememberCoroutineScope()
                fun submit() {
                    val cmd = input.trim(); if (cmd.isEmpty()) return
                    input = ""; status = null
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
                                    val result = ActionExecutor.execute(action, pm, appIndex)
                                    Log.d("AuraRouter", "Routed OPEN_APP target='${route.target}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.CallContact -> {
                                    val action = AuraAction.CallContact(route.name)
                                    val result = ActionExecutor.execute(action, pm, appIndex)
                                    Log.d("AuraRouter", "Routed CALL_CONTACT name='${route.name}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.SendText -> {
                                    val action = AuraAction.SendText(route.name, route.body)
                                    val result = ActionExecutor.execute(action, pm, appIndex)
                                    Log.d("AuraRouter", "Routed SEND_TEXT name='${route.name}' body='${route.body}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.WebSearch -> {
                                    val action = AuraAction.WebSearch(route.query)
                                    val result = ActionExecutor.execute(action, pm, appIndex)
                                    Log.d("AuraRouter", "Routed WEB_SEARCH query='${route.query}' action=$result matchTimeMs=${System.currentTimeMillis() - startMs}")
                                }
                                is AuraActionRoute.AskHermes -> {
                                    Log.d("AuraRouter", "Routed ASK_HERMES input='${route.input}' matchTimeMs=${System.currentTimeMillis() - startMs}")
                                    val result = HermesClient.send(route.input)
                                    phase = AuraMood.ACTING
                                    val execResult = ActionExecutor.execute(result, pm, appIndex)
                                    delay(900)
                                }
                            }
                        } catch (e: Exception) {
                            status = "Error: ${e.message}"
                        } finally {
                            phase = AuraMood.IDLE
                        }
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