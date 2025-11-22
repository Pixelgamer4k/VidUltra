package com.pixelgamer4k.vidultra.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelgamer4k.vidultra.ui.theme.AccentGold
import com.pixelgamer4k.vidultra.ui.theme.GlassBackground
import com.pixelgamer4k.vidultra.ui.theme.GlassBorder
import com.pixelgamer4k.vidultra.ui.theme.TextPrimary
import com.pixelgamer4k.vidultra.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun ControlsOverlay(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    recordingDuration: Long,
    onRecordClick: (Boolean) -> Unit,
    onManualControlsChange: (iso: Int?, exposureTime: Long?, focusDistance: Float?, whiteBalance: Int?) -> Unit
) {
    var isProMode by remember { mutableStateOf(false) }
    
    // Manual control states
    var isoValue by remember { mutableStateOf(800f) }
    var shutterValue by remember { mutableStateOf(0.5f) }
    var focusValue by remember { mutableStateOf(0.5f) }
    var wbValue by remember { mutableStateOf(0.5f) }

    // Update manual controls when sliders change
    LaunchedEffect(isoValue, shutterValue, focusValue, wbValue) {
        if (isProMode) {
            val iso = isoValue.roundToInt()
            
            // Convert slider value to exposure time (nanoseconds)
            // Range: 1/8000s (125000ns) to 1s (1000000000ns)
            val exposureTime = (125000L + (shutterValue * 999875000L)).toLong()
            
            // Focus distance (0 = infinity, higher = closer)
            val focus = focusValue
            
            // White balance temperature (2000K - 10000K)
            val wb = (2000 + (wbValue * 8000)).roundToInt()
            
            onManualControlsChange(iso, exposureTime, focus, wb)
        }
    }

    Box(modifier = modifier) {
        // Top Left: Histogram
        Histogram(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(width = 120.dp, height = 80.dp)
        )
        
        // Top Center: Recording indicator & timer
        if (isRecording) {
            RecordingIndicator(
                duration = recordingDuration,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // Left Side: Settings Stack
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(top = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FrostedLabel(label = "BITRATE", value = "100 Mbps", icon = true)
            FrostedLabel(label = "CODEC", value = "HEVC", icon = true)
            FrostedLabel(label = "DEPTH", value = "10-bit", icon = true)
            FrostedLabel(label = "LOG", value = "OFF", icon = true)
        }

        // Right Side: Controls
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Settings / EIS Toggle
            IconButton(
                onClick = { /* Toggle EIS */ },
                modifier = Modifier
                    .size(48.dp)
                    .frostedGlass()
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
            }

            // Record Button with pulsing animation when recording
            RecordButton(
                isRecording = isRecording,
                onClick = { onRecordClick(!isRecording) }
            )

            // Pro Toggle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { isProMode = !isProMode }
                    .frostedGlass()
                    .padding(8.dp)
            ) {
                Text("PRO", color = if (isProMode) AccentGold else TextPrimary, fontWeight = FontWeight.Bold)
                Icon(
                    if (isProMode) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Toggle Pro",
                    tint = TextPrimary
                )
            }
        }

        // Bottom: Pro Controls (Expandable)
        AnimatedVisibility(
            visible = isProMode,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, start = 140.dp, end = 100.dp)
        ) {
            ProControls(
                isoValue = isoValue,
                shutterValue = shutterValue,
                wbValue = wbValue,
                focusValue = focusValue,
                onIsoChange = { isoValue = it },
                onShutterChange = { shutterValue = it },
                onWbChange = { wbValue = it },
                onFocusChange = { focusValue = it }
            )
        }
    }
}

@Composable
fun RecordingIndicator(duration: Long, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    
    Row(
        modifier = modifier
            .frostedGlass()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.Red.copy(alpha = alpha))
        )
        Text(
            text = formatDuration(duration),
            color = AccentGold,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / 60000) % 60
    val hours = millis / 3600000
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "record_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRecording) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(if (isRecording) scale else 1f)
            .border(4.dp, AccentGold, CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (isRecording) Color.Red else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (!isRecording) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Color.Red)
            )
        }
    }
}

@Composable
fun ProControls(
    isoValue: Float,
    shutterValue: Float,
    wbValue: Float,
    focusValue: Float,
    onIsoChange: (Float) -> Unit,
    onShutterChange: (Float) -> Unit,
    onWbChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProSlider(
            label = "ISO",
            value = isoValue,
            displayValue = ((100 + isoValue * 6300).roundToInt()).toString(),
            onValueChange = onIsoChange
        )
        ProSlider(
            label = "SHUTTER",
            value = shutterValue,
            displayValue = formatShutterSpeed(shutterValue),
            onValueChange = onShutterChange
        )
        ProSlider(
            label = "WB",
            value = wbValue,
            displayValue = "${(2000 + wbValue * 8000).roundToInt()}K",
            onValueChange = onWbChange
        )
        ProSlider(
            label = "FOCUS",
            value = focusValue,
            displayValue = if (focusValue < 0.1f) "∞" else "${(focusValue * 100).roundToInt()}cm",
            onValueChange = onFocusChange
        )
    }
}

fun formatShutterSpeed(value: Float): String {
    // Convert to exposure time
    val exposureNs = 125000L + (value * 999875000L)
    val exposureSec = exposureNs / 1_000_000_000.0
    
    return if (exposureSec >= 1.0) {
        "${exposureSec.roundToInt()}s"
    } else {
        val shutterSpeed = (1.0 / exposureSec).roundToInt()
        "1/$shutterSpeed"
    }
}

@Composable
fun ProSlider(label: String, value: Float, displayValue: String, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(displayValue, color = AccentGold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            colors = SliderDefaults.colors(
                thumbColor = AccentGold,
                activeTrackColor = AccentGold,
                inactiveTrackColor = GlassBorder
            ),
            modifier = Modifier.width(100.dp)
        )
    }
}

@Composable
fun FrostedLabel(label: String, value: String, icon: Boolean = false) {
    Row(
        modifier = Modifier
            .frostedGlass()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .width(140.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(label, color = TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = AccentGold, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        if (icon) {
            Box(modifier = Modifier.size(16.dp).background(Color.Gray, CircleShape))
        }
    }
}

@Composable
fun Histogram(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .frostedGlass()
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(AccentGold.copy(alpha = 0.5f), Color.Transparent)
                    ),
                    shape = RoundedCornerShape(4.dp)
                )
        )
        Text("HISTOGRAM", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.align(Alignment.TopStart).padding(2.dp))
    }
}

fun Modifier.frostedGlass(): Modifier = this
    .clip(RoundedCornerShape(12.dp))
    .background(GlassBackground)
    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
