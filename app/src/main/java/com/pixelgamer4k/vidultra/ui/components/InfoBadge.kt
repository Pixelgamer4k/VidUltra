package com.pixelgamer4k.vidultra.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Info badge for displaying codec info, bitrate, etc.
 */
@Composable
fun InfoBadge(
    label: String,
    value: String,
    valueColor: Color = Color(0xFFFFD700), // Gold
    modifier: Modifier = Modifier
) {
    CompactGlassPill(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                label,
                fontSize = 9.sp,
                color = Color.White.copy(0.6f),
                fontWeight = FontWeight.Normal
            )
            Text(
                value,
                fontSize = 14.sp,
                color = valueColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Histogram-style badge (for waveform/histogram display)
 */
@Composable
fun HistogramBadge(
    modifier: Modifier = Modifier
) {
    CompactGlassPill(modifier = modifier.size(80.dp, 60.dp)) {
        // Placeholder for actual histogram
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "📊",
                fontSize = 24.sp
            )
        }
    }
}
