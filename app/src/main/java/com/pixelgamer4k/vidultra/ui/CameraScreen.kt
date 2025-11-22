package com.pixelgamer4k.vidultra.ui

import android.Manifest
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.pixelgamer4k.vidultra.camera.CameraManager
import com.pixelgamer4k.vidultra.ui.theme.Black

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    val cameraManager = remember { CameraManager(context) }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
        cameraManager.startBackgroundThread()
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.stopBackgroundThread()
            cameraManager.closeCamera()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
    ) {
        if (cameraPermissionState.status.isGranted) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                cameraManager.openCamera(holder.surface, width, height)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                // Handle resize if needed
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                cameraManager.closeCamera()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = "Camera Permission Required",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // UI Overlay
        ControlsOverlay(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            onRecordClick = { isRecording ->
                if (isRecording) {
                    cameraManager.startRecording(1920, 1080) // Placeholder resolution
                } else {
                    cameraManager.stopRecording()
                }
            }
        )
    }
}
