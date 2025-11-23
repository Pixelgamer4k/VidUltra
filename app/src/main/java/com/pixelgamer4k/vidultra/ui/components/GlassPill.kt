package com.pixelgamer4k.vidultra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Premium frosted glass pill component
 * Inspired by modern iOS/Material Design with multi-layer blur effects
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color.Black.copy(alpha = 0.3f)
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF1A1A1A).copy(alpha = 0.8f), // Darker top
                        Color(0xFF0D0D0D).copy(alpha = 0.9f)  // Darker bottom
                    )
                ),
                shape = RoundedCornerShape(cornerRadius)
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f), // Brighter top border
                        Color.White.copy(alpha = 0.05f)  // Subtle bottom border
                    )
                ),
                shape = RoundedCornerShape(cornerRadius)
            )
    ) {
        content()
    }
}

/**
 * Compact glass pill for smaller elements
 */
@Composable
fun CompactGlassPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    GlassPill(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        cornerRadius = 16.dp,
        content = content
    )
}
