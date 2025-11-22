package com.pixelgamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.viewmodel.CameraViewModel

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )

    // Lifecycle Observer
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (permissionState.allPermissionsGranted) {
                    viewModel.onResume()
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onPause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionState.allPermissionsGranted) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.onSurfaceCreated(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                // Handle resize if needed
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.onSurfaceDestroyed()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Controls Overlay
            ControlsOverlay(
                modifier = Modifier.fillMaxSize(),
                isRecording = viewModel.isRecording,
                recordingDuration = viewModel.recordingDuration,
                onRecordClick = { shouldRecord ->
                    if (shouldRecord) {
                        viewModel.startRecording()
                    } else {
                        viewModel.stopRecording()
                    }
                },
                onManualControlsChange = { iso, exposureTime, focus, wb ->
                    viewModel.updateManualControls(iso, exposureTime, focus, wb)
                }
            )
        } else {
            // Permission Request UI
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Permissions Required", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { permissionState.launchMultiplePermissionRequest() }) {
                    Text("Grant Permissions")
                }
            }
            
            LaunchedEffect(Unit) {
                permissionState.launchMultiplePermissionRequest()
            }
        }

        // Debug Overlay (Always Visible)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .background(Color.Black.copy(alpha = 0.5f))
                .zIndex(100f)
                .align(Alignment.TopCenter)
        ) {
            Text(
                text = viewModel.debugLogs,
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}
