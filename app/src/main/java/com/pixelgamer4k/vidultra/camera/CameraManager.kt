package com.pixelgamer4k.vidultra.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.pixelgamer4k.vidultra.utils.FileManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class CameraManager(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var currentVideoFile: File? = null

    // Manual Control State with device-aware ranges
    var iso: Int? = null
    var exposureTime: Long? = null
    var focusDistance: Float? = null
    var whiteBalance: Int? = null
    
    // Manual control enable flags
    private var manualMode = false
    
    // Recording settings
    var currentBitrate = VideoConfig.BitratePreset.MBPS_100
    var currentCodec = VideoConfig.Codec.H265

    fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = backgroundThread?.looper?.let { Handler(it) }
    }

    fun stopBackgroundThread() {
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
    fun openCamera(surface: Surface, width: Int, height: Int) {
        previewSurface = surface
        try {
            val cameraId = cameraManager.cameraIdList[0] // Back camera
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            // Log camera capabilities
            logCameraCapabilities()
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession(surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera: ", e)
        }
    }
    
    private fun logCameraCapabilities() {
        cameraCharacteristics?.let { chars ->
            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val focusRange = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            
            Log.d(TAG, "ISO Range: ${isoRange?.lower} - ${isoRange?.upper}")
            Log.d(TAG, "Exposure Range: ${exposureRange?.lower}ns - ${exposureRange?.upper}ns")
            Log.d(TAG, "Min Focus Distance: $focusRange")
        }
    }

    private fun createCameraPreviewSession(previewSurface: Surface) {
        try {
            // Use PREVIEW template for preview-only session
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder?.addTarget(previewSurface)

            // CRITICAL: Set proper control modes for manual control
            builder?.apply {
                // Start with auto mode until manual controls are adjusted
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                
                // Fix blue tint: set color correction mode
                set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                
                // Edge enhancement and noise reduction
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) // Zero sharpening
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
            }

            cameraDevice?.createCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            builder?.let {
                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
                            }
                        } catch (e: CameraAccessException) {
                            Log.e(TAG, "createCaptureSession: ", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createCameraPreviewSession: ", e)
        }
    }
    
    private fun createRecordingSession(previewSurface: Surface, recordingSurface: Surface) {
        try {
            // Use RECORD template for video recording
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder?.addTarget(previewSurface)
            builder?.addTarget(recordingSurface)

            // Apply manual controls
            applyManualControls(builder)

            cameraDevice?.createCaptureSession(
                listOf(previewSurface, recordingSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        try {
                            builder?.let {
                                session.setRepeatingRequest(it.build(), null, backgroundHandler)
                            }
                            // Start recording after session is configured
                            mediaRecorder?.start()
                            _isRecording.value = true
                            Log.d(TAG, "Recording started to: ${currentVideoFile?.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error starting recording: ", e)
                            _isRecording.value = false
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Recording session configuration failed")
                        _isRecording.value = false
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "createRecordingSession: ", e)
            _isRecording.value = false
        }
    }

    private fun applyManualControls(builder: CaptureRequest.Builder?) {
        builder?.apply {
            if (manualMode) {
                // Manual mode - full control
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                
                // Manual AE (ISO + Shutter)
                if (iso != null && exposureTime != null) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, clampISO(iso!!))
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposureTime(exposureTime!!))
                    Log.d(TAG, "Applied ISO: ${iso}, Exposure: ${exposureTime}ns")
                }
                
                // Manual Focus
                focusDistance?.let {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, clampFocusDistance(it))
                    Log.d(TAG, "Applied Focus Distance: $it")
                }
                
                // Manual White Balance
                whiteBalance?.let {
                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    // Note: Manual WB via color temperature is complex in Camera2
                    // For now, we'll use AWB_MODE_OFF and let sensor handle it
                    Log.d(TAG, "Applied WB: $it")
                }
            } else {
                // Auto mode
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            
            // Always apply these for quality
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) // No sharpening
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
        }
    }
    
    // Clamp values to device capabilities
    private fun clampISO(value: Int): Int {
        val range = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        return when {
            range == null -> value
            value < range.lower -> range.lower
            value > range.upper -> range.upper
            else -> value
        }
    }
    
    private fun clampExposureTime(value: Long): Long {
        val range = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        return when {
            range == null -> value
            value < range.lower -> range.lower
            value > range.upper -> range.upper
            else -> value
        }
    }
    
    private fun clampFocusDistance(value: Float): Float {
        val maxDistance = cameraCharacteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
        return value.coerceIn(0f, maxDistance)
    }

    fun updateManualControls(
        newIso: Int? = null, 
        newExposureTime: Long? = null, 
        newFocusDistance: Float? = null,
        newWhiteBalance: Int? = null
    ) {
        var updated = false
        
        if (newIso != null && newIso != iso) {
            iso = newIso
            updated = true
        }
        if (newExposureTime != null && newExposureTime != exposureTime) {
            exposureTime = newExposureTime
            updated = true
        }
        if (newFocusDistance != null && newFocusDistance != focusDistance) {
            focusDistance = newFocusDistance
            updated = true
        }
        if (newWhiteBalance != null && newWhiteBalance != whiteBalance) {
            whiteBalance = newWhiteBalance
            updated = true
        }
        
        // Enable manual mode when any control is set
        if (newIso != null || newExposureTime != null || newFocusDistance != null) {
            manualMode = true
        }
        
        // Update the repeating request if controls changed
        if (updated) {
            updateCaptureRequest()
        }
    }
    
    private fun updateCaptureRequest() {
        try {
            captureSession?.let { session ->
                val surfaces = mutableListOf<Surface>()
                previewSurface?.let { surfaces.add(it) }
                mediaRecorder?.surface?.let { surfaces.add(it) }
                
                val template = if (_isRecording.value) {
                    CameraDevice.TEMPLATE_RECORD
                } else {
                    CameraDevice.TEMPLATE_PREVIEW
                }
                
                val builder = cameraDevice?.createCaptureRequest(template)
                surfaces.forEach { builder?.addTarget(it) }
                applyManualControls(builder)
                
                builder?.let {
                    session.setRepeatingRequest(it.build(), null, backgroundHandler)
                    Log.d(TAG, "Capture request updated")
                }
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error updating capture request: ", e)
        }
    }

    fun startRecording(width: Int, height: Int) {
        if (cameraDevice == null || previewSurface == null) {
            Log.e(TAG, "Cannot start recording: camera not ready")
            return
        }
        
        if (_isRecording.value) {
            Log.w(TAG, "Already recording")
            return
        }
        
        try {
            // Create video file
            currentVideoFile = FileManager.createVideoFile(
                context, 
                width, 
                height, 
                currentCodec.fileExtension
            )
            
            Log.d(TAG, "Creating video file: ${currentVideoFile?.absolutePath}")
            
            // Setup MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                VideoConfig.setupMediaRecorder(
                    this,
                    currentVideoFile!!.absolutePath,
                    width,
                    height,
                    30, // FPS
                    currentBitrate.bitsPerSecond,
                    currentCodec
                )
                prepare()
                Log.d(TAG, "MediaRecorder prepared")
            }
            
            // Create recording session with both surfaces
            val recordingSurface = mediaRecorder?.surface
            if (recordingSurface != null && previewSurface != null) {
                // Close existing session
                captureSession?.close()
                captureSession = null
                
                createRecordingSession(previewSurface!!, recordingSurface)
            } else {
                throw IllegalStateException("Recording surface is null")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ", e)
            e.printStackTrace()
            _isRecording.value = false
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    fun stopRecording() {
        try {
            if (!_isRecording.value) {
                Log.w(TAG, "Not recording")
                return
            }
            
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            _isRecording.value = false
            
            // Add to MediaStore
            currentVideoFile?.let {
                FileManager.addVideoToMediaStore(context, it)
                Log.d(TAG, "Video saved: ${it.absolutePath}")
            }
            
            // Restart preview session
            previewSurface?.let { 
                captureSession?.close()
                captureSession = null
                createCameraPreviewSession(it) 
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ", e)
            e.printStackTrace()
            mediaRecorder?.release()
            mediaRecorder = null
            _isRecording.value = false
        }
    }

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        mediaRecorder?.release()
        mediaRecorder = null
        previewSurface = null
    }

    companion object {
        private const val TAG = "CameraManager"
    }
}
