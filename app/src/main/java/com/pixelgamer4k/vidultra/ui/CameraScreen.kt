package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.core.Camera2Api

// Colors
                Text(toneModeName, fontSize = 13.sp, color = Gold, fontWeight = FontWeight.Bold)
            }
        }

        // --- RIGHT SIDE: Shutter Button ---
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
            Box(
                modifier = Modifier
                    .size(90.dp)
                    .border(3.dp, Gold, CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) RedRec else RedRec.copy(alpha = 0.8f))
                    .clickable { onRecord(isRecording) }
            )
        }

        // --- TOP RIGHT: Icons ---
        Row(
            modifier = Modifier.align(Alignment.TopEnd).alpha(uiAlpha),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gallery Icon (opens Google Photos)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .border(1.dp, Color.White.copy(0.2f), CircleShape)
                    .clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            type = "video/*"
                            setPackage("com.google.android.apps.photos")
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val fallbackIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                type = "video/*"
                            }
                            context.startActivity(fallbackIntent)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Draw custom gallery icon - minimalist grid
                Canvas(modifier = Modifier.size(24.dp)) {
                    val iconSize = size.width
                    val gridSize = 9.dp.toPx() // Size of each grid square
                    val gap = 2.dp.toPx() // Gap between squares
                    val cornerRadius = 1.5.dp.toPx()
                    
                    // 2x2 grid of rounded rectangles
                    for (row in 0..1) {
                        for (col in 0..1) {
                            val x = (iconSize - gridSize * 2 - gap) / 2 + col * (gridSize + gap)
                            val y = (iconSize - gridSize * 2 - gap) / 2 + row * (gridSize + gap)
                            
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(x, y),
                                size = androidx.compose.ui.geometry.Size(gridSize, gridSize),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius)
                            )
                        }
                    }
                }
            }
            CircleIcon("R")
            CircleIcon("S")
        }


        // --- BOTTOM CENTER: Slider/Picker Popup ---
        AnimatedVisibility(
            visible = activeControl != null,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp),
            enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) + 
                    androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        )
                    ) { it / 2 } +
                    androidx.compose.animation.scaleIn(
                        initialScale = 0.8f,
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        )
                    ),
            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(200)) + 
                   androidx.compose.animation.slideOutVertically { it / 2 } +
                   androidx.compose.animation.scaleOut(targetScale = 0.8f)
        ) {
            when (activeControl) {
                "ISO" -> {
                    // ISO Slider (starts at current auto value)
                    var sliderValue by remember { mutableFloatStateOf(400f) }
                    
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 40.dp)
                            .fillMaxWidth(0.8f)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                            .border(2.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("ISO: ${sliderValue.toInt()}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { 
                                sliderValue = it
                                onIsoChange(it)
                            },
                            valueRange = 100f..3200f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Gold,
                                activeTrackColor = Gold,
                                inactiveTrackColor = Color.White.copy(0.3f)
                            )
                        )
                    }
                }
                "S" -> {
                    // Shutter Speed Picker
                    val shutterSpeeds = listOf(
                        "1/30", "1/60", "1/125", "1/250", "1/500", "1/1000", "1/2000", "1/4000", "1/8000"
                    )
                    var selectedIndex by remember { mutableStateOf(2) }
                    
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 40.dp)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                            .border(2.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Shutter Speed", color = Gold, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(0.1f), CircleShape)
                                    .clickable { 
                                        if (selectedIndex > 0) {
                                            selectedIndex--
                                            val speed = shutterSpeeds[selectedIndex].substringAfter("/").toFloat()
                                            onShutterChange(1000000000f / speed)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("◄", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(32.dp))
                            Text("${shutterSpeeds[selectedIndex]}s", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(32.dp))
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.White.copy(0.1f), CircleShape)
                                    .clickable { 
                                        if (selectedIndex < shutterSpeeds.size - 1) {
                                            selectedIndex++
                                            val speed = shutterSpeeds[selectedIndex].substringAfter("/").toFloat()
                                            onShutterChange(1000000000f / speed)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("►", color = Gold, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                "F" -> {
                    // Focus Slider
                    var sliderValue by remember { mutableFloatStateOf(5f) }
                    
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 40.dp)
                            .fillMaxWidth(0.8f)
                            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(24.dp))
                            .border(2.dp, Gold.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Focus: ${"%.1f".format(sliderValue)}", color = Gold, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { 
                                sliderValue = it
                                onFocusChange(it)
                            },
                            valueRange = 0f..10f,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(
                                thumbColor = Gold,
                                activeTrackColor = Gold,
                                inactiveTrackColor = Color.White.copy(0.3f)
                            )
                        )
                    }
                }
            }
        }
        
        // --- BOTTOM CENTER: Control Dock (Truly Centered) ---
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .padding(vertical = 6.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ControlToggle("ISO", activeControl == "ISO", size = 36.dp) { 
                activeControl = if (activeControl == "ISO") null else "ISO" 
            }
            ControlToggle("S", activeControl == "S", size = 36.dp) { 
                activeControl = if (activeControl == "S") null else "S" 
            }
            ControlToggle("F", activeControl == "F", size = 36.dp) { 
                activeControl = if (activeControl == "F") null else "F" 
            }
        }
        
        // --- BOTTOM RIGHT: Format Indicator (Separate from dock) ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp)
                .background(Gold, RoundedCornerShape(10.dp))
                .border(1.dp, Color.Black.copy(0.2f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp)
        ) {
            Text("4K 30", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

@Composable
fun ControlToggle(text: String, isActive: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .background(if (isActive) Gold else Color.Black.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, if (isActive) Gold else Color.White.copy(0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, 
            color = if (isActive) Color.Black else Color.White, 
            fontSize = (size.value * 0.32).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingItem(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(0.05f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun CircleIcon(text: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            .border(1.dp, Color.White.copy(0.2f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun HistogramGraph() {
    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        val path = Path()
        path.moveTo(0f, size.height)
        // Mock curve
        path.cubicTo(
            size.width * 0.2f, size.height,
            size.width * 0.4f, size.height * 0.2f,
            size.width * 0.6f, size.height * 0.5f
        )
        path.cubicTo(
            size.width * 0.8f, size.height * 0.8f,
            size.width, size.height,
            size.width, size.height
        )
        path.lineTo(0f, size.height)
        path.close()
        
        drawPath(
            path = path,
            brush = Brush.verticalGradient(
                colors = listOf(Gold, Gold.copy(alpha = 0.1f))
            )
        )
        drawPath(
            path = path,
            color = Gold,
            style = Stroke(width = 2f)
        )
    }
}
