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
    modifier: Modifier = Modifier
) {
    GlassPill(
        modifier = modifier
            .width(110.dp)
            .height(50.dp),
        cornerRadius = 16.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
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
                color = Color(0xFFFFD700),
                fontWeight = FontWeight.Bold
            )
        }
    }
}
