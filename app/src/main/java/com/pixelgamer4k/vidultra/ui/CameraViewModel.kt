package com.pixelgamer4k.vidultra.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelgamer4k.vidultra.core.Camera2Api
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    
    private val api = Camera2Api(application.applicationContext)
    val state: StateFlow<Camera2Api.CameraState> = api.state
    
    // Bit Depth State
    private val _bitDepth = MutableStateFlow(8)
    val bitDepth: StateFlow<Int> = _bitDepth.asStateFlow()
    
    private val _supports10Bit = MutableStateFlow(false)
    val supports10Bit: StateFlow<Boolean> = _supports10Bit.asStateFlow()
    
    init {
        api.start()
        _supports10Bit.value = api.supports10Bit
    }

    fun onSurfaceReady(surface: Surface) {
        api.openCamera(surface)
    }

    fun onSurfaceDestroyed() {
        api.stop()
        api.stop()
    }
}
