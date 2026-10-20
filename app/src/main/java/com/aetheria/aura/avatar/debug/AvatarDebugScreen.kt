package com.aetheria.aura.avatar.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetheria.aura.avatar.AvatarState

@Composable
fun AvatarDebugScreen(
    onClose: () -> Unit,
    currentState: AvatarState,
    onStateChange: (AvatarState) -> Unit,
    mouthOpen: Float,
    onMouthOpenChange: (Float) -> Unit
) {
    val states = listOf(
        "Neutral" to AvatarState.Neutral(),
        "Listening" to AvatarState.Listening(),
        "Thinking" to AvatarState.Thinking(),
        "Speaking" to AvatarState.Speaking(),
        "Error" to AvatarState.Error(),
        "Confirm" to AvatarState.Confirm()
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Color(0xCC000000))
            .padding(16.dp)
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Button(onClick = onClose) {
                    Text("✕ Close", fontSize = 14.sp, color = Color.White)
                }
            }

            Text(
                "Avatar Debug Controls",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            // State buttons
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                states.forEach { (name, state) ->
                    val isSelected = currentState::class == state::class
                    Button(
                        onClick = { onStateChange(state) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF333333),
                            contentColor = if (isSelected) Color.Black else Color.White
                        )
                    ) {
                        Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Mouth open slider
            Text(
                "Mouth Open: ${(mouthOpen * 100).toInt()}%",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Slider(
                value = mouthOpen,
                onValueChange = onMouthOpenChange,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF00E5FF),
                    trackColor = Color(0xFF00E5FF).copy(alpha = 0.3f),
                    activeTrackColor = Color(0xFF00E5FF),
                    inactiveTrackColor = Color(0xFF333333)
                )
            )
        }
    }
}