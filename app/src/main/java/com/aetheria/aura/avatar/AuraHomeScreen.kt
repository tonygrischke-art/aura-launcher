package com.aetheria.aura.avatar

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Arrangement
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aetheria.aura.ChatMessage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.takeLast

enum class OrbStyle { WIRE, VIDEO }
const val ORB_STYLE = "WIRE"

@Composable
fun AuraHomeScreen(
    mood: AuraMood,
    input: String,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onMic: () -> Unit,
    status: String?,
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    val clock = remember(now) { SimpleDateFormat("h:mm", Locale.getDefault()).format(now) }
    val date = remember(now) { SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now) }

    Column(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(28.dp))
        Text(clock, color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Thin)
        Text(date, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            val debuggable = (LocalContext.current.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            var debugMood by remember { mutableStateOf(mood) }
            if (ORB_STYLE == "VIDEO") {
                VideoOrb(
                    mood = if (debuggable) debugMood else mood,
                    modifier = Modifier.size(side)
                        .pointerInput(Unit) {
                            if (debuggable) {
                                detectTapGestures(onTap = {
                                    debugMood = when (debugMood) {
                                        AuraMood.IDLE -> AuraMood.LISTENING
                                        AuraMood.LISTENING -> AuraMood.THINKING
                                        AuraMood.THINKING -> AuraMood.ACTING
                                        AuraMood.ACTING -> AuraMood.IDLE
                                    }
                                })
                            }
                        }
                )
            } else {
                WireOrb(
                    mood = if (debuggable) debugMood else mood,
                    onTap = {
                        if (debuggable) {
                            debugMood = when (debugMood) {
                                AuraMood.IDLE -> AuraMood.LISTENING
                                AuraMood.LISTENING -> AuraMood.THINKING
                                AuraMood.THINKING -> AuraMood.ACTING
                                AuraMood.ACTING -> AuraMood.IDLE
                            }
                        }
                    },
                    modifier = Modifier.size(side)
                )
            }
        }

        // Chat history above input
        if (messages.isNotEmpty()) {
            val displayMessages = messages.takeLast(10)
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .background(Color(0xFF14181F))
                            .clip(RoundedCornerShape(12.dp)),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        displayMessages.forEach { msg ->
                            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(msg.timestamp)
                            val (textColor, align) = when (msg.role) {
                                ChatMessage.Role.USER -> Color.White to Alignment.End
                                ChatMessage.Role.AURA -> Color.Cyan to Alignment.Start
                                ChatMessage.Role.SYSTEM -> Color.Gray to Alignment.CenterHorizontally
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = when (align) {
                                    Alignment.Start -> Arrangement.Start
                                    Alignment.End -> Arrangement.End
                                    else -> Arrangement.Center
                                }
                            ) {
                                Text(
                                    text = "${msg.content}",
                                    color = textColor,
                                    fontSize = 14.sp,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        if (status != null) {
            Text(status, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xFF14181F))
                .border(1.dp, AuraColors.Cyan.copy(alpha = 0.25f), RoundedCornerShape(28.dp))
                .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).onFocusChanged { onFocusChange(it.isFocused) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(AuraColors.Cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() }),
                decorationBox = { inner ->
                    Box(Modifier.padding(vertical = 10.dp)) {
                        if (input.isEmpty()) {
                            Text("Ask Aura...", color = Color.White.copy(alpha = 0.4f), fontSize = 16.sp)
                        }
                        inner()
                    }
                }
            )
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AuraColors.Cyan.copy(alpha = 0.18f))
                    .clickable { if (input.isBlank()) onMic() else onSubmit() },
                contentAlignment = Alignment.Center
            ) {
                CommandIcon(sendMode = input.isNotBlank())
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun CommandIcon(sendMode: Boolean) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val col = AuraColors.Cyan
        val sw = 2.dp.toPx()
        if (sendMode) {
            drawLine(col, Offset(w * 0.5f, h * 0.85f), Offset(w * 0.5f, h * 0.15f), sw, StrokeCap.Round)
            drawLine(col, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.22f, h * 0.43f), sw, StrokeCap.Round)
            drawLine(col, Offset(w * 0.5f, h * 0.15f), Offset(w * 0.78f, h * 0.43f), sw, StrokeCap.Round)
        } else {
            drawRoundRect(col, topLeft = Offset(w * 0.36f, h * 0.05f), size = Size(w * 0.28f, h * 0.5f),
                cornerRadius = CornerRadius(w * 0.14f, w * 0.14f))
            drawArc(col, 0f, 180f, false, topLeft = Offset(w * 0.2f, h * 0.25f), size = Size(w * 0.6f, h * 0.5f),
                style = Stroke(sw, cap = StrokeCap.Round))
            drawLine(col, Offset(w * 0.5f, h * 0.75f), Offset(w * 0.5f, h * 0.95f), sw, StrokeCap.Round)
        }
    }
}