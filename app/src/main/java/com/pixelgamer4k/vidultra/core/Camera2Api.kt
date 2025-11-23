package com.pixelgamer4k.vidultra.core

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Camera2Api - The Core Engine.
 * Implements robust Camera2 handling patterns found in FreeDcam.
 */
class Camera2Api(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    // State
    private val _state = MutableStateFlow<CameraState>(CameraState.Closed)
    val state = _state.asStateFlow()
    
    // Threading
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Camera Objects
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    
    // Surfaces
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    
    // Video Encoder
    private var videoEncoder: VideoEncoder? = null
    private var currentColorProfile: ColorProfile = ColorProfiles.getById(5) // REC2020 default
    
    // Manual Settings
    var iso: Int? = null
    var exposure: Long? = null
    var focus: Float? = null
    
    // Bit Depth
    private var bitDepth: Int = 8
    private var _supports10Bit: Boolean? = null
    
    val supports10Bit: Boolean
        get() {
            if (_supports10Bit == null) {
                _supports10Bit = check10BitSupport()
            }
            return _supports10Bit ?: false
        }

    sealed class CameraState {
        object Closed : CameraState()
        object Opening : CameraState()
        object Preview : CameraState()
        object Recording : CameraState()
        data class Error(val msg: String) : CameraState()
    }

    fun start() {
        startBackgroundThread()
        LogWriter.init(context)
    }

    fun stop() {
        closeCamera()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("Camera2ApiThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "stopBackgroundThread: ", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun openCamera(surface: Surface) {
        if (_state.value is CameraState.Opening) return
        _state.value = CameraState.Opening
        previewSurface = surface

        try {
            val cameraId = getBackCameraId() ?: throw Exception("No Back Camera Found")
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _state.value = CameraState.Closed
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _state.value = CameraState.Error("Camera Error: $error")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera: ", e)
            _state.value = CameraState.Error(e.message ?: "Unknown Error")
        }
    }

    private fun getBackCameraId(): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    private fun startPreview() {
        try {
            if (cameraDevice == null || previewSurface == null) return

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(previewSurface!!)
            
            applySettings()

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface!!),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        updatePreview()
                        _state.value = CameraState.Preview
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _state.value = CameraState.Error("Preview Config Failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: ", e)
            _state.value = CameraState.Error(e.message ?: "Preview Error")
        }
    }

    private fun updatePreview() {
        if (cameraDevice == null) return
        try {
            captureSession?.setRepeatingRequest(
                previewRequestBuilder!!.build(),
                null,
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "updatePreview: ", e)
        }
    }

    fun startRecording() {
        if (cameraDevice == null || previewSurface == null) return
        
        try {
            closePreviewSession()
            setupVideoEncoder()
            
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            previewRequestBuilder!!.addTarget(previewSurface!!)
            previewRequestBuilder!!.addTarget(recorderSurface!!)
            
            applySettings()

            // Use OutputConfiguration with DynamicRangeProfile for BT.2020 support (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && currentColorProfile.id == 5) {
                // REC2020 mode - use HLG10 dynamic range profile
                val previewOutputConfig = android.hardware.camera2.params.OutputConfiguration(previewSurface!!)
                val recorderOutputConfig = android.hardware.camera2.params.OutputConfiguration(recorderSurface!!)
                
                // Set HLG10 dynamic range profile on recorder surface
                try {
                    recorderOutputConfig.setDynamicRangeProfile(android.hardware.camera2.params.DynamicRangeProfiles.HLG10)
                    Log.i(TAG, "✅ Set DynamicRangeProfile.HLG10 for BT.2020 recording")
                    LogWriter.writeLog("DynamicRangeProfile: HLG10 (enables BT.2020 metadata)")
                } catch (e: Exception) {
                    Log.w(TAG, "Device doesn't support HLG10, falling back to SDR", e)
                    LogWriter.writeLog("WARNING: HLG10 not supported, using SDR fallback")
                }
                
                // Create session configuration
                val sessionConfig = android.hardware.camera2.params.SessionConfiguration(
                    android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR,
                    listOf(previewOutputConfig, recorderOutputConfig),
                    context.mainExecutor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            updatePreview()
                            videoEncoder?.start()
                            _state.value = CameraState.Recording
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _state.value = CameraState.Error("Recording Config Failed")
                        }
                    }
                )
                
                cameraDevice!!.createCaptureSession(sessionConfig)
            } else {
                // Legacy mode or non-REC2020 profile
                val surfaces = listOf(previewSurface!!, recorderSurface!!)
                cameraDevice!!.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            updatePreview()
                            videoEncoder?.start()
                            _state.value = CameraState.Recording
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            _state.value = CameraState.Error("Recording Config Failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: ", e)
            LogWriter.writeLog("ERROR: startRecording failed: ${e.message}")
            _state.value = CameraState.Error(e.message ?: "Recording Error")
        }
    }

    fun stopRecording() {
        try {
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: ", e)
        }
    }

    private fun setupVideoEncoder() {
        // Create output folder
        val outputDir = File(context.getExternalFilesDir(null), "VidUltra")
        if (!outputDir.exists()) outputDir.mkdirs()
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFile = File(outputDir, "VID_${timestamp}.mp4")
        
        // Create encoder
        videoEncoder = VideoEncoder(
            outputFile = outputFile,
            width = 3840,
            height = 2160,
            frameRate = 30,
            bitDepth = bitDepth
        ).apply {
            // Configure color space from current profile
            colorStandard = currentColorProfile.colorStandard
            colorTransfer = currentColorProfile.colorTransfer
            colorRange = currentColorProfile.colorRange
            
            // Prepare encoder
            prepare()
            
            // Get encoder surface
            recorderSurface = inputSurface
        }
        
        Log.i(TAG, "VideoEncoder configured with profile: ${currentColorProfile.displayName}")
        Log.i(TAG, "Output: ${outputFile.absolutePath}")
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun closeCamera() {
        closePreviewSession()
        cameraDevice?.close()
        cameraDevice = null
        videoEncoder?.release()
        videoEncoder = null
        _state.value = CameraState.Closed
    }
    
    // Manual Controls
    fun setManualIso(value: Int) { iso = value; applySettings(); updatePreview() }
    fun setManualExposure(value: Long) { exposure = value; applySettings(); updatePreview() }
    fun setManualFocus(value: Float) { focus = value; applySettings(); updatePreview() }
    fun setAuto() { iso = null; exposure = null; focus = null; applySettings(); updatePreview() }

    // Color Profile Management
    private var toneMapMode = 5 // Default to REC2020

    fun setToneMapMode(mode: Int) {
        toneMapMode = mode
        currentColorProfile = ColorProfiles.getById(mode)
        applySettings()
        updatePreview()
        Log.i(TAG, "Color profile changed to: ${currentColorProfile.displayName}")
    }
    
    fun getToneMapMode() = toneMapMode

    private fun applySettings() {
        val builder = previewRequestBuilder ?: return
        val profile = currentColorProfile
        
        // Force MANUAL capture intent to disable "smart" stock processing (HDR, scene optimization)
        builder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL)
        
        // Apply tone mapping from ColorProfile
        builder.set(CaptureRequest.TONEMAP_MODE, profile.tonemapMode)
        
        // Apply profile-specific settings
        when {
            profile.tonemapCurve != null -> {
                builder.set(CaptureRequest.TONEMAP_CURVE, profile.tonemapCurve)
            }
            profile.tonemapGamma != null -> {
                builder.set(CaptureRequest.TONEMAP_GAMMA, profile.tonemapGamma)
            }
            profile.tonemapPresetCurve != null -> {
                builder.set(CaptureRequest.TONEMAP_PRESET_CURVE, profile.tonemapPresetCurve)
            }
        }
        
        // Disable enhancements for soft, cinematic look
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        
        // Manual controls
        if (iso != null || exposure != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            exposure?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        }
        
        if (focus != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    // 10-bit Support
    fun setBitDepth(depth: Int) {
        if (depth == 10 && !supports10Bit) {
            Log.w(TAG, "10-bit not supported, staying at 8-bit")
            return
        }
        bitDepth = depth
    }
    
    fun getBitDepth(): Int = bitDepth
    
    private fun check10BitSupport(): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.codecInfos.any { codecInfo ->
                codecInfo.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC) &&
                codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                    .profileLevels.any { 
                        it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking 10-bit support", e)
            false
        }
    }

    companion object {
        private const val TAG = "Camera2Api"
    }
}
