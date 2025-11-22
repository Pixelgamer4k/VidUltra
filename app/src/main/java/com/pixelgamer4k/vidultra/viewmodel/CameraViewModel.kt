package com.pixelgamer4k.vidultra.viewmodel

import android.app.Application
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelgamer4k.vidultra.camera.CameraController
import com.pixelgamer4k.vidultra.camera.VideoRecorder
import com.pixelgamer4k.vidultra.utils.LogServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    
    // Core Components
    val cameraController = CameraController(context)
    val videoRecorder = VideoRecorder(context)
    val logServer = LogServer()

    // UI State
    var isRecording by mutableStateOf(false)
        private set
        
    var recordingDuration by mutableStateOf(0L)
        private set
        
    var debugLogs by mutableStateOf("Logs (Port 9000):\n")
        private set
        
    var isSurfaceReady by mutableStateOf(false)
        private set

    private var recordingStartTime = 0L

    init {
        // Initialize Logging
        logServer.start()
        log("ViewModel Init")
        
        cameraController.onDebugLog = { msg ->
            appendLog(msg)
        }
    }

    fun onSurfaceCreated(surface: Surface) {
        log("Surface Created")
        isSurfaceReady = true
        cameraController.startBackgroundThread()
        cameraController.openCamera(surface)
    }

    fun onSurfaceDestroyed() {
        log("Surface Destroyed")
        isSurfaceReady = false
        if (isRecording) {
            stopRecording()
        }
        cameraController.closeCamera()
    }
    
    fun onResume() {
        log("On Resume")
        // If surface is already ready (e.g. returning from background), reopen camera
        if (isSurfaceReady) {
            // We might need to get the surface again if it was destroyed, 
            // but usually surfaceDestroyed is called on pause/stop.
            // If surface is valid, we try to open.
            // Note: SurfaceView usually destroys surface on Pause.
        }
    }

    fun onPause() {
        log("On Pause")
        if (isRecording) {
            stopRecording()
        }
        cameraController.closeCamera()
    }

    fun startRecording() {
        if (isRecording) return
        
        viewModelScope.launch {
            val recordingSurface = videoRecorder.prepareRecording(3840, 2160)
            if (recordingSurface != null) {
                cameraController.createRecordingSession(recordingSurface) {
                    if (videoRecorder.startRecording()) {
                        isRecording = true
                        recordingStartTime = System.currentTimeMillis()
                        startTimer()
                    }
                }
            } else {
                log("Error: Failed to prepare recording surface")
            }
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        
        videoRecorder.stopRecording()
        cameraController.stopRecordingSession {
            isRecording = false
        }
    }
    
    fun updateManualControls(iso: Int?, exposure: Long?, focus: Float?, wb: Int?) {
        cameraController.updateManualControls(iso, exposure, focus, wb)
    }

    private fun startTimer() {
        viewModelScope.launch {
            while (isRecording) {
                recordingDuration = System.currentTimeMillis() - recordingStartTime
                delay(100)
            }
            recordingDuration = 0L
        }
    }

    private fun log(msg: String) {
        appendLog(msg)
        logServer.log(msg)
    }
    
    private fun appendLog(msg: String) {
        // Keep log size manageable
        if (debugLogs.length > 5000) {
            debugLogs = debugLogs.takeLast(4000)
        }
        debugLogs += "$msg\n"
    }

    override fun onCleared() {
        super.onCleared()
        log("ViewModel Cleared")
        cameraController.stopBackgroundThread()
        logServer.stop()
    }
}
