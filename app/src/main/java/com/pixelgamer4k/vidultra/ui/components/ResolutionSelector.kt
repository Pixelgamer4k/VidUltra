package com.pixelgamer4k.vidultra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.pixelgamer4k.vidultra.core.Resolution

@Composable
fun ResolutionSelector(
    availableResolutions: List<Resolution>,
    selectedResolution: Resolution,
    onResolutionSelected: (Resolution) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(Color(0xFF1A1A1A), RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = \"Select Resolution\",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            availableResolutions.forEach { resolution ->
                ResolutionOption(
                    resolution = resolution,
                    isSelected = resolution == selectedResolution,
                    onClick = {
                        onResolutionSelected(resolution)
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
fun ResolutionOption(
    resolution: Resolution,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) VidUltraYellow.copy(0.2f) else Color(0xFF2A2A2A),
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = resolution.label,
                color = if (isSelected) VidUltraYellow else Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = \"${resolution.width} x ${resolution.height} @ ${resolution.fps}fps\",
                color = if (isSelected) VidUltraYellow.copy(0.8f) else Color.Gray,
                fontSize = 13.sp
            )
        }
    }
}
