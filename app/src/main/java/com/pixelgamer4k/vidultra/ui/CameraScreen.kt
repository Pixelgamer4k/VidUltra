package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
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

val Gold = Color(0xFFFFD700)

@OptIn(ExperimentalPermissionsApi::class, ExperimentalAnimationApi::class)
@Composable
fun CameraScreen(cameraViewModel: CameraViewModel = viewModel()) {
    val permissionState = rememberMultiplePermissionsState(
        listOf(android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO)
    )
    val cameraState = cameraViewModel.state.collectAsState().value
    val bitDepth = cameraViewModel.bitDepth.collectAsState().value
    val supports10Bit = cameraViewModel.supports10Bit.collectAsState().value
    
    var activeControl by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionState.allPermissionsGranted) {
            // Full Screen Preview
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = cameraViewModel.onSurfaceReady(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                            override fun surfaceDestroyed(holder: SurfaceHolder) = cameraViewModel.onSurfaceDestroyed()
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Premium UI Overlay
            PremiumCameraOverlay(
                isRecording = cameraState is Camera2Api.CameraState.Recording,
                bitDepth = bitDepth,
                supports10Bit = supports10Bit,
                activeControl = activeControl,
                onActiveControlChange = { activeControl = it },
                onRecord = { if (it) cameraViewModel.stopRecording() else cameraViewModel.startRecording() },
                onIsoChange = { cameraViewModel.setIso(it) },
                onShutterChange = { cameraViewModel.setExposure(it.toLong()) },
                onFocusChange = { cameraViewModel.setFocus(it.toFloat()) },
                onBitDepthChange = { cameraViewModel.setBitDepth(it) }
            )
        } else {
            LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PremiumCameraOverlay(
    isRecording: Boolean,
    bitDepth: Int,
    supports10Bit: Boolean,
    activeControl: String?,
    onActiveControlChange: (String?) -> Unit,
    onRecord: (Boolean) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Int) -> Unit,
    onFocusChange: (Int) -> Unit,
    onBitDepthChange: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        
        // TOP LEFT: Info Badges
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Bitrate
            InfoBadge(
                label = "BITRATE",
                value = if (bitDepth == 10) "150 Mbps" else "100 Mbps"
            )
            
            // Codec
            InfoBadge(
                label = "CODEC",
                value = "HEVC"
            )
            
            // Bit depth
            InfoBadge(
                label = "DEPTH",
                value = "$bitDepth-bit"
            )
        }
        
        // TOP RIGHT: Icon Buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Gallery
            IconButton(
                onClick = { /* TODO */ },
                icon = Icons.Default.Image
            )
            
            // Grid
            IconButton(
                onClick = { /* TODO */ },
                icon = Icons.Default.GridOn
            )
            
            // Settings
            IconButton(
                onClick = { /* TODO */ },
                icon = Icons.Default.Settings
            )
            
            // Lock
            IconButton(
                onClick = { /* TODO */ },
                icon = Icons.Default.Lock
            )
        }
        
        // BOTTOM CENTER: Manual Controls Bar
        AnimatedVisibility(
            visible = activeControl == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp)
        ) {
            GlassPill(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(70.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ISO
                    ControlButton(
                        label = "ISO",
                        onClick = { onActiveControlChange("ISO") }
                    )
                    
                    // Shutter (S)
                    ControlButton(
                        label = "S",
                        subtitle = "1/50",
                        onClick = { onActiveControlChange("SHUTTER") }
                    )
                    
                    // Focus (F)
                    ControlButton(
                        label = "F",
                        onClick = { onActiveControlChange("FOCUS") }
                    )
                    
                    // Shutter icon
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.White, CircleShape)
                            .border(3.dp, Color.Black.copy(0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(Color.Black, CircleShape)
                        )
                    }
                }
            }
        }
        
        // DIAL OVERLAYS
        AnimatedVisibility(
            visible = activeControl == "ISO",
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 140.dp)
        ) {
            DialControl(
                label = "ISO",
                currentValue = "400",
                minValue = 100,
                maxValue = 3200,
                onValueChange = onIsoChange,
                onDismiss = { onActiveControlChange(null) }
            )
        }
        
        AnimatedVisibility(
            visible = activeControl == "SHUTTER",
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 140.dp)
        ) {
            DialControl(
                label = "SHUTTER",
                currentValue = "1/50",
                minValue = 1,
                maxValue = 8000,
                onValueChange = onShutterChange,
                onDismiss = { onActiveControlChange(null) }
            )
        }
        
        AnimatedVisibility(
            visible = activeControl == "FOCUS",
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 140.dp)
        ) {
            DialControl(
                label = "FOCUS",
                currentValue = "∞",
                minValue = 0,
                maxValue = 100,
                onValueChange = onFocusChange,
                onDismiss = { onActiveControlChange(null) }
            )
        }
        
        // BOTTOM: Record Button
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            RecordButton(
                isRecording = isRecording,
                onClick = { onRecord(isRecording) }
            )
        }
    }
}

@Composable
fun IconButton(
    onClick: () -> Unit,
    icon: ImageVector
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(
                Color(0xFF2A2A2A).copy(0.8f),
                CircleShape
            )
            .border(1.dp, Color.White.copy(0.1f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ControlButton(
    label: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            label,
            fontSize = 20.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 11.sp,
                color = Gold
            )
        }
    }
}

@Composable
fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (isRecording) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    Box(
        modifier = Modifier
            .size(76.dp)
            .scale(scale)
    ) {
        // Outer ring
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.White.copy(if (isRecording) 0.3f else 0.4f),
                    CircleShape
                )
                .border(3.dp, Color.White.copy(0.6f), CircleShape)
        )
        
        // Inner button
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .background(
                    if (isRecording) Color(0xFFFF3B30) else Color.White,
                    if (isRecording) RoundedCornerShape(8.dp) else CircleShape
                )
                .clickable(onClick = onClick)
        )
    }
}
