package com.pixelgamer4k.vidultra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val VidUltraYellow = Color(0xFFFFD700)
val GlassDark = Color(0xFF1A1A1A).copy(alpha = 0.9f)
val GlassBorder = Color.White.copy(alpha = 0.1f)

@Composable
fun HistogramWidget(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(120.dp, 60.dp)
            .background(GlassDark, RoundedCornerShape(12.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val path = Path().apply {
                moveTo(0f, height)
                // Simulate a histogram curve
                cubicTo(width * 0.2f, height, width * 0.3f, height * 0.2f, width * 0.5f, height * 0.1f)
                cubicTo(width * 0.7f, 0f, width * 0.8f, height * 0.6f, width, height * 0.8f)
                lineTo(width, height)
                close()
            }
            
            // Fill
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(VidUltraYellow.copy(alpha = 0.5f), Color.Transparent)
                )
            )
            
            // Stroke
            drawPath(
                path = path,
                color = VidUltraYellow,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
fun StatusGrid(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(icon = Icons.Default.ThumbUp, text = "84%", textColor = Color(0xFF4CAF50)) // Battery
            StatusPill(icon = Icons.Default.Menu, text = "128G") // Storage
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(icon = Icons.Default.Share, text = "5G") // Wifi/5G
            StatusPill(icon = Icons.Default.DateRange, text = "TC 00:04") // Timecode
        }
    }
}

@Composable
fun StatusPill(
    icon: ImageVector,
    text: String,
    textColor: Color = Color.White
) {
    Row(
        modifier = Modifier
            .height(28.dp)
            .background(GlassDark, RoundedCornerShape(6.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.Gray,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun LargeInfoBadge(
    label: String,
    value: String,
    showToggle: Boolean = false,
    isToggled: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .width(160.dp)
            .height(70.dp)
            .background(GlassDark, RoundedCornerShape(16.dp))
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = Color.Gray,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                text = value,
                color = if (label == "DEPTH" && !isToggled) Color.Gray else VidUltraYellow,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        
        if (showToggle && onToggle != null) {
            Switch(
                checked = isToggled,
                onCheckedChange = onToggle,
                modifier = Modifier.align(Alignment.CenterEnd).scale(0.8f),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = VidUltraYellow,
                    uncheckedThumbColor = Color.Gray,
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedBorderColor = Color.Gray
                )
            )
        }
    }
}

@Composable
fun FormatBadge(text: String) {
    Box(
        modifier = Modifier
            .background(VidUltraYellow, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}
