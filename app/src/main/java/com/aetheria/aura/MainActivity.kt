package com.aetheria.aura

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.material3.Button
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.aetheria.aura.net.HermesClient
import com.aetheria.aura.actions.AuraAction
import com.aetheria.aura.actions.ActionExecutor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AuraTheme {
                MainScreen(packageManager)
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
fun MainScreen(pm: android.content.pm.PackageManager) {
    var commandInput by remember { mutableStateOf("") }
    var responseText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Response area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .background(Color(0xFF1A1A1A))
        ) {
            androidx.compose.foundation.text.BasicText(
                text = androidx.compose.ui.text.AnnotatedString(
                    text = if (responseText.isNotBlank()) responseText else "Awaiting command...",
                    spanStyle = androidx.compose.ui.text.SpanStyle(
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                placeholder = { Text("Command", color = Color.White.copy(alpha = 0.4f)) },
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFF1A1A1A),
                    focusedContainerColor = Color(0xFF222222),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
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