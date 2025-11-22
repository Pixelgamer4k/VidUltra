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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.pixelgamer4k.vidultra.camera.CameraController
import com.pixelgamer4k.vidultra.camera.VideoRecorder
import com.pixelgamer4k.vidultra.utils.LogServer
import kotlinx.coroutines.delay

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val cameraController = remember { CameraController(context) }
    val videoRecorder = remember { VideoRecorder(context) }
    val logServer = remember { LogServer() }
    
    var surfaceReady by remember { mutableStateOf(false) }
    var surfaceView: SurfaceView? by remember { mutableStateOf(null) }
    
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0L) }
    var recordingStartTime by remember { mutableStateOf(0L) }
    
    // Debug Logs State
    var debugLogs by remember { mutableStateOf("Logs (Port 9000):\n") }
    
    // Connect logger
    LaunchedEffect(Unit) {
        logServer.start()
        logServer.log("App Started")
        
        cameraController.onDebugLog = { msg ->
            debugLogs += "$msg\n"
            logServer.log(msg)
        }
    }
    
    DisposableEffect(Unit) {
        onDispose {
            logServer.stop()
        }
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        if (permissionState.allPermissionsGranted) {
            // Camera Preview
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        surfaceView = this
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                val msg = "Surface Created"
                                debugLogs += "$msg\n"
                                logServer.log(msg)
                                surfaceReady = true
                                cameraController.startBackgroundThread()
                                cameraController.openCamera(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                val msg = "Surface Changed: ${width}x${height}"
                                debugLogs += "$msg\n"
                                logServer.log(msg)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                val msg = "Surface Destroyed"
                                debugLogs += "$msg\n"
                                logServer.log(msg)
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
                        } else {
                            val msg = "Error: Recording surface null"
                            debugLogs += "$msg\n"
                            logServer.log(msg)
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
                .zIndex(100f) // Ensure on top
                .align(Alignment.TopCenter)
        ) {
            Text(
                text = debugLogs,
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
