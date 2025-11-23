package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.opengl.GLSurfaceView
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
import com.pixelgamer4k.vidultra.core.renderer.FocusPeakingRenderer
import com.pixelgamer4k.vidultra.ui.components.*

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
    var peakingEnabled by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionState.allPermissionsGranted) {
            // Full Screen Preview
            val renderer = remember {
                FocusPeakingRenderer { surfaceTexture ->
                    val surface = Surface(surfaceTexture)
                    cameraViewModel.onSurfaceReady(surface)
                }
            }
            
            DisposableEffect(Unit) {
                onDispose {
                    cameraViewModel.onSurfaceDestroyed()
                }
            }

            AndroidView(
                factory = { ctx ->
                    GLSurfaceView(ctx).apply {
                        setEGLContextClientVersion(2)
                        setRenderer(renderer)
                        renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                        renderer.requestRender = { requestRender() }
                    }
                },
                update = {
                    renderer.isPeakingEnabled = peakingEnabled
                    it.requestRender()
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
                peakingEnabled = peakingEnabled,
                onPeakingToggle = { peakingEnabled = !peakingEnabled }
            )
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
    peakingEnabled: Boolean,
    onPeakingToggle: () -> Unit
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
                .align(Alignment.CenterStart)
                .padding(top = 100.dp), // Push down below histogram
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
            CircularIconButton(
                icon = Icons.Default.Visibility, 
                isActive = peakingEnabled,
                onClick = onPeakingToggle
            ) // Peaking
            CircularIconButton(icon = Icons.Default.Settings) // Settings
            CircularIconButton(icon = Icons.Default.Lock) // Lock
        }
        
        // --- BOTTOM CONTROLS ---
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            // Manual Controls Bar (Center)
            AnimatedVisibility(
                visible = activeControl == null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
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
            ) {
                FormatBadge(text = "4K 30")
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
