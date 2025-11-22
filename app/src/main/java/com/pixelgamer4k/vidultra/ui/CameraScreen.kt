package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.core.Camera2Api

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )
    
    val cameraState by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionState.allPermissionsGranted) {
                // SurfaceView handles its own lifecycle callbacks usually
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionState.allPermissionsGranted) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.onSurfaceReady(holder.surface)
                            }
                            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
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
                isRecording = cameraState is Camera2Api.CameraState.Recording,
                onRecord = { if (it) viewModel.stopRecording() else viewModel.startRecording() },
                onAuto = { viewModel.setAuto() }
            )
            
            // Error Overlay
            if (cameraState is Camera2Api.CameraState.Error) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)), contentAlignment = Alignment.Center) {
                    Text("Error: ${(cameraState as Camera2Api.CameraState.Error).msg}", color = Color.Red)
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Permissions Required", color = Color.White)
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                    Text("Grant")
                }
            }
            LaunchedEffect(Unit) { permissionState.launchMultiplePermissionRequest() }
        }
    }
}

@Composable
fun ControlsOverlay(isRecording: Boolean, onRecord: (Boolean) -> Unit, onAuto: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Right Controls (Frosted)
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(16.dp)
                .width(60.dp)
                .background(Color.Black.copy(0.3f), RoundedCornerShape(16.dp))
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Knob("ISO")
            Knob("S")
            Knob("F")
            Knob("A", onClick = onAuto)
        }

        // Bottom Shutter
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.8f)))),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color.Red else Color.White)
                    .clickable { onRecord(isRecording) }
            )
        }
    }
}

@Composable
fun Knob(text: String, onClick: () -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = Color.White, fontSize = 12.sp)
    }
}
