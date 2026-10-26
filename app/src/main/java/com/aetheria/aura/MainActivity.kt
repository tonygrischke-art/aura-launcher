package com.aetheria.aura

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.detectTapGestures
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.aetheria.aura.actions.ActionExecutor
import com.aetheria.aura.actions.AuraAction
import com.aetheria.aura.avatar.AvatarState
import com.aetheria.aura.avatar.VideoOrb
import com.aetheria.aura.net.HermesClient

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuraTheme {
                MainScreen(packageManager, lifecycle)
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

@Composable
fun MainScreen(pm: android.content.pm.PackageManager, lifecycle: Lifecycle) {
    var commandInput by remember { mutableStateOf("") }
    var responseText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var orbState by remember { mutableStateOf<AvatarState>(AvatarState.Neutral()) }
    var debugStateIndex by remember { mutableStateOf(0) }

    val debugStates = listOf(
        AvatarState.Neutral(),
        AvatarState.Listening(),
        AvatarState.Thinking(),
        AvatarState.Acting(),
    )

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Video Orb - centered, takes most of the screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .fillMaxHeight(0.7f)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        // Debug: cycle through states on tap
                        debugStateIndex = (debugStateIndex + 1) % debugStates.size
                        orbState = debugStates[debugStateIndex]
                        Log.d("AuraDebug", "Orb state: ${orbState::class.simpleName}")
                    })
                }
        ) {
            VideoOrb(state = orbState, lifecycleOwner = this@MainScreen as? androidx.lifecycle.LifecycleOwner)
        }

        // Response area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(16.dp)
                .background(Color(0xFF1A1A1A))
        ) {
            androidx.compose.foundation.text.BasicText(
                text = androidx.compose.ui.text.AnnotatedString(
                    text = if (responseText.isNotBlank()) responseText else "Awaiting command...",
                    style = androidx.compose.ui.text.SpanStyle(
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Normal
                    )
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            )
        }

        // Command input field
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            TextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                placeholder = { Text("Command", color = Color.White.copy(alpha = 0.4f)) },
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
                    containerColor = Color(0xFF1A1A1A),
                    focusedContainerColor = Color(0xFF222222),
                    textColor = Color.White,
                    placeholderTextColor = Color.White.copy(alpha = 0.4f),
                    cursorColor = Color(0xFF00E5FF),
                    disabledTextColor = Color.White.copy(alpha = 0.3f),
                    disabledContainerColor = Color(0xFF1A1A1A)
                )
            )

            Button(
                onClick = {
                    if (commandInput.isNotBlank() && !isProcessing) {
                        val cmd = commandInput
                        commandInput = ""
                        isProcessing = true
                        responseText = "Processing..."
                        scope.launch(Dispatchers.IO) {
                            val result = HermesClient.send(cmd)
                            val execResult = ActionExecutor.execute(result, pm)
                            scope.launch(Dispatchers.Main) {
                                responseText = when (result) {
                                    is AuraAction.OpenApp -> if (execResult == "confirm") "Opened $cmd" else "App not found: ${result.target}"
                                    else -> "Unknown command: $cmd"
                                }
                                isProcessing = false
                            }
                        }
                    }
                },
                modifier = Modifier
                    .width(80.dp)
                    .height(56.dp),
                enabled = !isProcessing,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (isProcessing) Color(0xFF333333) else Color(0xFF00E5FF),
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = if (isProcessing) "..." else "Send",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}