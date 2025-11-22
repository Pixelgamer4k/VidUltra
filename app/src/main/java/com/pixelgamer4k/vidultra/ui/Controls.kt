package com.pixelgamer4k.vidultra.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun ControlsOverlay(
    modifier: Modifier = Modifier,
    onRecordClick: (Boolean) -> Unit
) {
    var isProMode by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Top Left: Histogram
        Histogram(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(width = 120.dp, height = 80.dp)
        )

        // Left Side: Settings Stack
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(top = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FrostedLabel(label = "BITRATE", value = "800 Mbps", icon = true)
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

            // Record Button
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, AccentGold, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.Red else Color.Transparent)
                    .clickable { 
                        isRecording = !isRecording
                        onRecordClick(isRecording)
                    },
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
            ProControls()
        }
    }
}

@Composable
fun ProControls() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .frostedGlass()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProSlider(label = "ISO", value = "800")
        ProSlider(label = "SHUTTER", value = "1/50")
        ProSlider(label = "WB", value = "5600K")
        ProSlider(label = "FOCUS", value = "Manual")
    }
}

@Composable
fun ProSlider(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = AccentGold, fontWeight = FontWeight.Bold)
        Slider(
            value = 0.5f,
            onValueChange = {},
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
