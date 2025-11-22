package com.pixel

gamer4k.vidultra.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.camera.CameraManager
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val cameraManager = remember { CameraManager(context) }
    var surfaceReady by remember { mutableStateOf(false) }
    var surfaceView: SurfaceView? by remember { mutableStateOf(null) }
    
    val isRecording by cameraManager.isRecording.collectAsState()
    var recordingDuration by remember { mutableStateOf(0L) }
    var recordingStartTime by remember { mutableStateOf(0L) }

    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO
        )
    )

    // Recording duration timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingStartTime = System.currentTimeMillis()
            while (isRecording) {
                recordingDuration = System.currentTimeMillis() - recordingStartTime
                delay(100) // Update every 100ms
            }
        } else {
            recordingDuration = 0L
        }
    }

    DisposableEffect(lifecycleOwner) {
        cameraManager.startBackgroundThread()
        
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionState.allPermissionsGranted) {
                if (surfaceReady && surfaceView != null) {
                    cameraManager.openCamera(surfaceView!!.holder.surface, 3840, 2160)
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                if (isRecording) {
                    cameraManager.stopRecording()
                }
                cameraManager.closeCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            cameraManager.closeCamera()
            cameraManager.stopBackgroundThread()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (permissionState.allPermissionsGranted) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        surfaceView = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                surfaceReady = true
                                cameraManager.openCamera(holder.surface, 3840, 2160)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceReady = false
                                cameraManager.closeCamera()
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // Controls Overlay
            ControlsOverlay(
                modifier = Modifier.fillMaxSize(),
                isRecording = isRecording,
                recordingDuration = recordingDuration,
                onRecordClick = { shouldRecord ->
                    if (shouldRecord) {
                        cameraManager.startRecording(3840, 2160)
                    } else {
                        cameraManager.stopRecording()
                    }
                },
                onManualControlsChange = { iso, exposureTime, focus, wb ->
                    cameraManager.updateManualControls(iso, exposureTime, focus, wb)
                }
            )
        }
    } else {
        LaunchedEffect(Unit) {
            permissionState.launchMultiplePermissionRequest()
        }
    }
}
