package com.pixelgamer4k.vidultra.viewmodel

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelgamer4k.vidultra.camera.CameraEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = CameraEngine(application.applicationContext)
    
    val cameraState = engine.cameraState
    val recordingState = engine.recordingState
    
    init {
        engine.start()
    }

    fun onSurfaceReady(surface: Surface, width: Int, height: Int) {
        engine.openCamera(surface, width, height)
    }

    fun onSurfaceDestroyed() {
        engine.stop()
    }
    
    fun startRecording() {
        engine.startRecording()
    }
    
    fun stopRecording() {
        engine.stopRecording()
    }
    
    fun setIso(iso: Int) = engine.setIso(iso)
    fun setExposure(exp: Long) = engine.setExposure(exp)
    fun setFocus(focus: Float) = engine.setFocus(focus)
    fun setAuto() = engine.setAuto()

    override fun onCleared() {
        super.onCleared()
        engine.stop()
    }
}
