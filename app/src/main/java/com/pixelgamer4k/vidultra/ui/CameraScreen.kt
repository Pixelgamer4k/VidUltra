package com.pixelgamer4k.vidultra.ui

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
import com.pixelgamer4k.vidultra.camera.CameraController
import com.pixelgamer4k.vidultra.camera.VideoRecorder
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val cameraController = remember { CameraController(context) }
    val videoRecorder = remember { VideoRecorder(context) }
    
    var surfaceReady by remember { mutableStateOf(false) }
    var surfaceView: SurfaceView? by remember { mutableStateOf(null) }
    
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) }
    var recordingStartTime by remember { mutableStateOf(0L) }

    val permissionState = rememberMultiplePermissionsState(
        listOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
    )

    // Recording duration timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingStartTime = System.currentTimeMillis()
            while (isRecording) {
                recordingDuration = System.currentTimeMillis() - recordingStartTime
                delay(100)
            }
        } else {
            recordingDuration = 0L
        }
    }

    DisposableEffect(lifecycleOwner) {
        cameraController.startBackgroundThread()
        
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && permissionState.allPermissionsGranted) {
                if (surfaceReady && surfaceView != null) {
                    cameraController.openCamera(surfaceView!!.holder.surface)
                }
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                if (isRecording) {
                    // Stop recording
                    videoRecorder.stopRecording()
                    cameraController.stopRecordingSession {}
                    isRecording = false
                }
                cameraController.closeCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            cameraController.closeCamera()
            cameraController.stopBackgroundThread()
            videoRecorder.releaseRecorder()
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
                                cameraController.openCamera(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                surfaceReady = false
                                if (isRecording) {
                                    videoRecorder.stopRecording()
                                    isRecording = false
                                }
                                cameraController.closeCamera()
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
                        // Start recording
                        val recordingSurface = videoRecorder.prepareRecording(3840, 2160)
                        if (recordingSurface != null) {
                            cameraController.createRecordingSession(recordingSurface) {
                                // Session ready, now start MediaRecorder
                                if (videoRecorder.startRecording()) {
                                    isRecording = true
                                }
                            }
                        }
                    } else {
                        // Stop recording
                        videoRecorder.stopRecording()
                        cameraController.stopRecordingSession {
                            isRecording = false
                        }
                    }
                },
                onManualControlsChange = { iso, exposureTime, focus, wb ->
                    cameraController.updateManualControls(iso, exposureTime, focus, wb)
                }
            )
        }
    } else {
        LaunchedEffect(Unit) {
            permissionState.launchMultiplePermissionRequest()
        }
    }
}
