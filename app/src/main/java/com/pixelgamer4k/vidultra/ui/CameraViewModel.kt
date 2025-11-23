package com.pixelgamer4k.vidultra.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelgamer4k.vidultra.core.Camera2Api
import com.pixelgamer4k.vidultra.core.Resolution
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
    
    // Resolution State
    private val _availableResolutions = MutableStateFlow<List<Resolution>>(emptyList())
    val availableResolutions: StateFlow<List<Resolution>> = _availableResolutions.asStateFlow()
    
    private val _selectedResolution = MutableStateFlow<Resolution>(Resolution.PRESET_4K_16_9)
    val selectedResolution: StateFlow<Resolution> = _selectedResolution.asStateFlow()
    
    init {
        api.start()
        _supports10Bit.value = api.supports10Bit
        _availableResolutions.value = api.getSupportedResolutions()
        _selectedResolution.value = api.getSelectedResolution()
    }
    
    fun selectResolution(resolution: Resolution) {
        api.setResolution(resolution)
        _selectedResolution.value = resolution
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
    
    // Bit Depth Control
    fun setBitDepth(depth: Int) {
        if (depth == 10 && !_supports10Bit.value) return
        api.setBitDepth(depth)
        _bitDepth.value = depth
    }

    // Tone Mapping State
    private val _toneMapMode = MutableStateFlow(5) // Default REC2020
    val toneMapMode: StateFlow<Int> = _toneMapMode.asStateFlow()
    
    fun cycleToneMapMode() {
        val newMode = (_toneMapMode.value + 1) % 6
        _toneMapMode.value = newMode
        api.setToneMapMode(newMode)
    }

    override fun onCleared() {
        super.onCleared()
        api.stop()
    }
}
