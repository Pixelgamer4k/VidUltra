package com.pixelgamer4k.vidultra.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Premium horizontal dial control with haptic feedback
 * Shows value in center with increment markers on sides
 */
@Composable
fun DialControl(
    label: String,
    currentValue: String,
    minValue: Int = 0,
    maxValue: Int = 100,
    step: Int = 1,
    onValueChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var internalValue by remember { mutableStateOf(50) }
    var dragOffset by remember { mutableStateOf(0f) }
    
    LaunchedEffect(dragOffset) {
        val newValue = (50 + (dragOffset / 20f).roundToInt()).coerceIn(minValue, maxValue)
        if (newValue != internalValue) {
            internalValue = newValue
            onValueChange(newValue)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Value display dial
        GlassPill(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(80.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = { dragOffset = 0f },
                        onHorizontalDrag = { _, delta ->
                            dragOffset += delta
                        }
                    )
                }
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back button
                IconButton(onClick = onDismiss) {
                    Text(
                        "<",
                        color = Color.White.copy(0.6f),
                        fontSize = 20.sp
                    )
                }
                
                // Increment markers
                Text("-2", color = Color.White.copy(0.4f), fontSize = 16.sp)
                Text("-1", color = Color.White.copy(0.4f), fontSize = 16.sp)
                
                // Current value (gold, prominent)
                Text(
                    currentValue,
                    color = Color(0xFFFFD700),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold
                )
                
                // Increment markers
                Text("+1", color = Color.White.copy(0.4f), fontSize = 16.sp)
                Text("+2", color = Color.White.copy(0.4f), fontSize = 16.sp)
            }
        }
        
        // Drag indicator
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(4.dp)
                .background(Color(0xFFFFD700), RoundedCornerShape(2.dp))
        )
    }
}
