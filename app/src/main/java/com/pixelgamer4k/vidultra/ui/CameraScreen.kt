package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.R
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
                onIsoChange = { cameraViewModel.setIso(it.toInt()) },
                onShutterChange = { cameraViewModel.setExposure(it.toLong()) },
                onFocusChange = { cameraViewModel.setFocus(it) },
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
    onIsoChange: (Float) -> Unit,
    onShutterChange: (Float) -> Unit,
    onFocusChange: (Float) -> Unit,
    onBitDepthChange: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        
        // TOP LEFT: Info Badges
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Histogram placeholder
            HistogramBadge()
            
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
            
            // Bit depth with toggle
            Row(
                modifier = Modifier.clickable(enabled = supports10Bit) {
                    onBitDepthChange(if (bitDepth == 8) 10 else 8)
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                InfoBadge(
                    label = "DEPTH",
                    value = "$bitDepth-bit"
                )
                
                // Toggle indicator
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(20.dp)
                        .background(
                            if (bitDepth == 10) Gold.copy(0.3f) else Color.White.copy(0.2f),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = if (bitDepth == 10) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (bitDepth == 10) Gold else Color.White,
                                CircleShape
                            )
                    )
                }
            }
        }
        
        // TOP RIGHT: Icon Buttons
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Gallery
            GlassPillIconButton(
                onClick = { /* TODO */ },
                icon = "🖼️"
            )
            
            // Grid
            GlassPillIconButton(
                onClick = { /* TODO */ },
                icon = "⊞"
            )
            
            // Settings
            GlassPillIconButton(
                onClick = { /* TODO */ },
                icon = "⚙️"
            )
            
            // Lock
            GlassPillIconButton(
                onClick = { /* TODO */ },
                icon = "🔒"
            )
        }
        
        // BOTTOM CENTER: Manual Controls Bar
        AnimatedVisibility(
            visible = activeControl == null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
        ) {
            GlassPill(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .height(70.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
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
                    
                    // Shutter icon (aesthetic)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("◉", fontSize = 24.sp, color = Color.Black)
                    }
                }
            }
        }
        
        // DIAL OVERLAYS
        AnimatedVisibility(
            visible = activeControl == "ISO",
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp)
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
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp)
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
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 120.dp)
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
                .padding(bottom = 24.dp)
        ) {
            RecordButton(
                isRecording = isRecording,
                onClick = { onRecord(isRecording) }
            )
        }
    }
}

@Composable
fun GlassPillIconButton(
    onClick: () -> Unit,
    icon: String
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                Color.Black.copy(0.6f),
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(icon, fontSize = 20.sp)
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
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            fontSize = 18.sp,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 10.sp,
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
        targetValue = if (isRecording) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    )
    
    Box(
        modifier = Modifier
            .size(80.dp)
            .scale(scale)
            .background(
                if (isRecording) Color.Red else Color.White,
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(Color.White, RoundedCornerShape(4.dp))
            )
        }
    }
}
