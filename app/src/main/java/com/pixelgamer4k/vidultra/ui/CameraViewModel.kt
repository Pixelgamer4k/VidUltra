package com.pixelgamer4k.vidultra.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelgamer4k.vidultra.core.Camera2Api
import kotlinx.coroutines.flow.StateFlow

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    
    private val api = Camera2Api(application.applicationContext)
    val state: StateFlow<Camera2Api.CameraState> = api.state
    
    init {
        api.start()
    }

    fun onSurfaceReady(surface: Surface) {
        api.openCamera(surface)
    }

    fun onSurfaceDestroyed() {
        api.stop()
    }
    
    fun startRecording() = api.startRecording()
    fun stopRecording() = api.stopRecording()
    
    fun setIso(iso: Int) = api.setManualIso(iso)
    fun setExposure(exp: Long) = api.setManualExposure(exp)
    fun setFocus(focus: Float) = api.setManualFocus(focus)
    fun setAuto() = api.setAuto()

    override fun onCleared() {
        super.onCleared()
        api.stop()
    }
}
