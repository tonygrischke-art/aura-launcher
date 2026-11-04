package com.aetheria.aura.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetheria.aura.avatar.AuraColors
import com.aetheria.aura.net.HermesClient

@Composable
fun SettingsScreen(
    context: Context,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var endpoint by remember { mutableStateOf(HermesClient.getStoredEndpoint(context)) }
    var warning by remember { mutableStateOf<String?>(null) }

    val isCleartextNonLocalhost = HermesClient.isCleartextNonLocalhost(endpoint)

    if (isCleartextNonLocalhost && warning == null) {
        warning = "Warning: Release builds block cleartext HTTP to non-localhost hosts. " +
            "Use https:// or set the endpoint to localhost/127.0.0.1 for development."
    } else if (!isCleartextNonLocalhost && warning != null) {
        warning = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .size(44.dp)
                .clickable { onBack() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "<",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Settings",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                .fillMaxWidth(0.85f)
                .align(Alignment.Start)
        )

        Text(
            text = "Hermes Endpoint",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(0.85f).align(Alignment.Start)
        )

        BasicTextField(
            value = endpoint,
            onValueChange = { newValue ->
                endpoint = newValue
                warning = null
            },
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF14181F))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(AuraColors.Cyan),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {}),
            decorationBox = { innerField ->
                Box(
                    modifier = Modifier.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    innerField()
                    if (endpoint.isNotBlank() &&
                        !endpoint.startsWith("http://", ignoreCase = true) &&
                        !endpoint.startsWith("https://", ignoreCase = true)) {
                        Text(
                            text = "Must start with http:// or https://",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            modifier = Modifier.align(Alignment.BottomEnd)
                        )
                    }
                }
            }
        )

        Spacer(Modifier.height(12.dp))

        if (warning != null) {
            val w = warning
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .background(Color(0xFF4D1A1A))
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = "!  $w",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (endpoint.isNotBlank()) {
                    HermesClient.setEndpoint(context, endpoint.trim())
                }
                onBack()
            },
            enabled = endpoint.isNotBlank(),
            modifier = Modifier.fillMaxWidth(0.85f),
            colors = ButtonDefaults.buttonColors(
                containerColor = AuraColors.Cyan,
                contentColor = Color.Black
            )
        ) {
            Text(text = "Save & Return", color = Color.Black, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Current: ${HermesClient.getStoredEndpoint(context)}",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Text(
            text = "First-run default: ${com.aetheria.aura.BuildConfig.HERMES_ENDPOINT}",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp)
        )
    }
}
