package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.camera.CameraEngine
import com.pixelgamer4k.vidultra.viewmodel.CameraViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    
    val cameraState by viewModel.cameraState.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    
    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )

    // Lifecycle to handle surface/camera
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (permissionState.allPermissionsGranted) {
                    // Surface creation handles open, but we might need to re-trigger if surface exists
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionState.allPermissionsGranted) {
            // Camera Preview Surface
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.onSurfaceReady(holder.surface, width, height)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                                // Resize if needed
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.onSurfaceDestroyed()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // UI Overlay
            ControlsOverlay(
                recordingState = recordingState,
                onRecordClick = { isRecording ->
                    if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
                },
                onIsoChange = { viewModel.setIso(it) },
                onShutterChange = { viewModel.setExposure(it) },
                onFocusChange = { viewModel.setFocus(it) },
                onAutoClick = { viewModel.setAuto() }
            )
            
            // Error/Status Overlay
            if (cameraState is CameraEngine.CameraState.Error) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                    Text("Error: ${(cameraState as CameraEngine.CameraState.Error).msg}", color = Color.Red)
                }
            }
            
        } else {
            // Permissions
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Camera Permissions Needed", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                    Text("Grant")
                }
            }
            LaunchedEffect(Unit) {
                permissionState.launchMultiplePermissionRequest()
            }
        }
    }
}

@Composable
fun ControlsOverlay(
    recordingState: CameraEngine.RecordingState,
    onRecordClick: (Boolean) -> Unit,
    onIsoChange: (Int) -> Unit,
    onShutterChange: (Long) -> Unit,
    onFocusChange: (Float) -> Unit,
    onAutoClick: () -> Unit
) {
    val isRecording = recordingState is CameraEngine.RecordingState.Recording
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Top Bar (Status)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isRecording) {
                val duration = (recordingState as CameraEngine.RecordingState.Recording).duration
                Text(
                    text = formatDuration(duration),
                    color = Color.Red,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                )
            }
        }

        // Right Side (Manual Controls)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp)
                .width(60.dp)
                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ControlKnob(label = "ISO", onClick = { /* Show ISO Slider */ })
            ControlKnob(label = "S", onClick = { /* Show Shutter Slider */ })
            ControlKnob(label = "F", onClick = { /* Show Focus Slider */ })
            ControlKnob(label = "A", onClick = onAutoClick)
        }

        // Bottom Bar (Shutter)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Shutter Button
            Button(
                onClick = { onRecordClick(isRecording) },
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.White
                )
            ) {}
        }
    }
}

@Composable
fun ControlKnob(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}

// Helper for clickable modifier
fun Modifier.clickable(onClick: () -> Unit): Modifier = androidx.compose.foundation.clickable(onClick = onClick)
