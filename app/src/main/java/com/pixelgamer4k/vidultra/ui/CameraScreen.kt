package com.pixelgamer4k.vidultra.ui

import android.view.Surface
import android.view.TextureView
import android.graphics.SurfaceTexture
import android.graphics.Matrix
import android.graphics.RectF
import android.app.Activity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.core.Camera2Api
import com.pixelgamer4k.vidultra.ui.components.*

// Helper function to configure TextureView transform matrix
fun configureTransform(
    textureView: TextureView,
    viewWidth: Int,
    viewHeight: Int,
    previewWidth: Int,
    previewHeight: Int,
    activity: Activity?
) {
    activity ?: return
    
    val rotation = activity.windowManager.defaultDisplay.rotation
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
    val bufferRect = RectF(0f, 0f, previewHeight.toFloat(), previewWidth.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    
    when (rotation) {
        Surface.ROTATION_90, Surface.ROTATION_270 -> {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewHeight,
                viewWidth.toFloat() / previewWidth
            )
            matrix.postScale(scale, scale, centerX, centerY)
            matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
        }
        Surface.ROTATION_180 -> {
            matrix.postRotate(180f, centerX, centerY)
        }
    }
    
    textureView.setTransform(matrix)
}

@OptIn(ExperimentalPermissionsApi::class, ExperimentalAnimationApi::class)
@Composable
fun CameraScreen(cameraViewModel: CameraViewModel = viewModel()) {
    val permissionState = rememberMultiplePermissionsState(
        listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
    )
    val cameraState = cameraViewModel.state.collectAsState().value
    val bitDepth = cameraViewModel.bitDepth.collectAsState().value
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // 1:1 Premium UI Overlay
            ExactPremiumOverlay(
                isRecording = cameraState is Camera2Api.CameraState.Recording,
                bitDepth = bitDepth,
                supports10Bit = supports10Bit,
                activeControl = activeControl,
                onActiveControlChange = { activeControl = it },
                onRecord = { if (it) cameraViewModel.stopRecording() else cameraViewModel.startRecording() },
                onIsoChange = { cameraViewModel.setIso(it) },
                onShutterChange = { cameraViewModel.setExposure(it.toLong()) },
                onFocusChange = { cameraViewModel.setFocus(it.toFloat()) },
                onBitDepthChange = { cameraViewModel.setBitDepth(it) },
                selectedResolution = selectedResolution,
                onResolutionClick = { showResolutionSelector = true }
            )
            
            // Resolution Selector
            if (showResolutionSelector) {
                ResolutionSelector(
                    availableResolutions = availableResolutions,
                    selectedResolution = selectedResolution,
                    onResolutionSelected = { cameraViewModel.selectResolution(it) },
                    onDismiss = { showResolutionSelector = false }
                )
            }
        } else {
            LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ExactPremiumOverlay(
    isRecording: Boolean,
    bitDepth: Int,
    supports10Bit: Boolean,
    activeControl: String?,
    onActiveControlChange: (String?) -> Unit,
    onRecord: (Boolean) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onFocusChange: (Int) -> Unit,
    onBitDepthChange: (Int) -> Unit,
    selectedResolution: com.pixelgamer4k.vidultra.core.Resolution,
    onResolutionClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        
        // --- TOP LEFT SECTION ---
        Row(
            modifier = Modifier.align(Alignment.TopStart),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Histogram
            HistogramWidget()
            
            // Status Grid (Battery, Storage, etc.)
            StatusGrid()
        }
        
        // --- LEFT SIDE STACK ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 80.dp), // Push down below histogram (60dp + 20dp spacing)
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LargeInfoBadge(
                label = "BITRATE",
                value = if (bitDepth == 10) "150 Mbps" else "100 Mbps"
            )
            
            LargeInfoBadge(
                label = "CODEC",
                value = "HEVC"
            )
            
            LargeInfoBadge(
                label = "DEPTH",
                value = "$bitDepth-bit",
                showToggle = true,
                isToggled = bitDepth == 10,
                onToggle = { if (supports10Bit) onBitDepthChange(if (it) 10 else 8) }
            )
        }
        
        // --- TOP RIGHT ICONS ---
        Row(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularIconButton(icon = Icons.Default.Face) // Gallery
            CircularIconButton(icon = Icons.Default.List) // Grid
            CircularIconButton(icon = Icons.Default.Settings) // Settings
            CircularIconButton(icon = Icons.Default.Lock) // Lock
        }
        
        // --- BOTTOM CONTROLS ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 4.dp) // Pushed to edge
        ) {
            // Manual Controls Bar (Center)
            AnimatedVisibility(
                visible = activeControl == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(340.dp)
                        .height(72.dp)
                        .background(Color(0xFF0F0F0F).copy(alpha = 0.95f), RoundedCornerShape(36.dp))
                        .border(1.dp, Color.White.copy(0.1f), RoundedCornerShape(36.dp))
                        .padding(horizontal = 24.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ISO
                        ControlText(text = "ISO", onClick = { onActiveControlChange("ISO") })
                        
                        // Shutter
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onActiveControlChange("SHUTTER") }
                        ) {
                            Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("1/50", color = VidUltraYellow, fontSize = 11.sp)
                        }
                        
                        // Focus
                        ControlText(text = "F", onClick = { onActiveControlChange("FOCUS") })
                        
                        // Separator
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(24.dp)
                                .background(Color.White.copy(0.2f))
                        )
                        
                        // Aperture Icon
                        Icon(
                            imageVector = Icons.Default.Settings, // Using Settings as Aperture placeholder
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            // 4K Badge (Right of controls)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 40.dp, end = 100.dp)
                    .clickable(onClick = onResolutionClick)
            ) {
                FormatBadge(text = "${selectedResolution.label} ${selectedResolution.fps}")
            }
            
            // Record Button (Far Right)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 20.dp)
            ) {
                BigRecordButton(
                    isRecording = isRecording,
                    onClick = { onRecord(isRecording) }
                )
            }
        }
        
        // --- DIAL OVERLAYS (Top Center) ---
        // Reuse existing DialControl logic but position it correctly
        if (activeControl != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 120.dp) // Above the controls
            ) {
                when (activeControl) {
                    "ISO" -> DialControl(
                        label = "ISO",
                        currentValue = "400",
                        minValue = 100,
                        maxValue = 3200,
                        onValueChange = onIsoChange,
                        onDismiss = { onActiveControlChange(null) }
                    )
                    "SHUTTER" -> DialControl(
                        label = "SHUTTER",
                        currentValue = "1/50",
                        minValue = 1,
                        maxValue = 8000,
                        onValueChange = onShutterChange,
                        onDismiss = { onActiveControlChange(null) }
                    )
                    "FOCUS" -> DialControl(
                        label = "FOCUS",
                        currentValue = "∞",
                        minValue = 0,
                        maxValue = 100,
                        onValueChange = onFocusChange,
                        onDismiss = { onActiveControlChange(null) }
                    )
                }
            }
        }
    }
}

@Composable
fun CircularIconButton(
    icon: ImageVector, 
    isActive: Boolean = false,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                if (isActive) VidUltraYellow else Color(0xFF2A2A2A).copy(0.9f), 
                CircleShape
            )
            .border(1.dp, if (isActive) VidUltraYellow else Color.White.copy(0.1f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isActive) Color.Black else Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun ControlText(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
fun BigRecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Outer Ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(4.dp, Color.White.copy(0.3f), CircleShape)
        )
        
        // Inner Circle
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Color(0xFFFF3B30), if (isRecording) RoundedCornerShape(16.dp) else CircleShape)
        )
    }
}
