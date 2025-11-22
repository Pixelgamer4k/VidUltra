package com.pixelgamer4k.vidultra.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CameraEngine - A robust, low-level wrapper around Camera2 API.
 * Inspired by FreeDcam's architecture: Direct control, explicit state management.
 */
class CameraEngine(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    // State
    private val _cameraState = MutableStateFlow<CameraState>(CameraState.Closed)
    val cameraState = _cameraState.asStateFlow()
    
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState = _recordingState.asStateFlow()

    // Camera2 Objects
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    
    // Threading
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    // Surfaces
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    
    // MediaRecorder
    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoFile: File? = null
    
    // Manual Controls
    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var currentIso: Int? = null
    private var currentExposure: Long? = null
    private var currentFocus: Float? = null

    sealed class CameraState {
        object Closed : CameraState()
        object Opening : CameraState()
        object Preview : CameraState()
        data class Error(val msg: String) : CameraState()
    }
    
    sealed class RecordingState {
        object Idle : RecordingState()
        data class Recording(val duration: Long) : RecordingState()
    }

    fun start() {
        startBackgroundThread()
    }

    fun stop() {
        closeCamera()
        stopBackgroundThread()
    }

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("CameraEngineThread").also { it.start() }
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
    fun openCamera(surface: Surface, width: Int, height: Int) {
        if (_cameraState.value is CameraState.Opening) return
        _cameraState.value = CameraState.Opening
        
        previewSurface = surface
        
        try {
            val cameraId = getBestCameraId()
            if (cameraId == null) {
                _cameraState.value = CameraState.Error("No suitable camera found")
                return
            }

            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            isoRange = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureRange = cameraCharacteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _cameraState.value = CameraState.Closed
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _cameraState.value = CameraState.Error("Camera Error: $error")
                }
            }, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "openCamera: ", e)
            _cameraState.value = CameraState.Error(e.message ?: "Unknown error")
        }
    }

    private fun getBestCameraId(): String? {
        return try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun startPreview() {
        try {
            if (cameraDevice == null || previewSurface == null) return

            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            previewRequestBuilder!!.addTarget(previewSurface!!)
            
            // Auto-Focus & Auto-Exposure default
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)

            cameraDevice!!.createCaptureSession(
                listOf(previewSurface!!),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        updatePreview()
                        _cameraState.value = CameraState.Preview
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _cameraState.value = CameraState.Error("Preview configuration failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startPreview: ", e)
            _cameraState.value = CameraState.Error(e.message ?: "Preview error")
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
    
    // ============================================================================================
    // Recording Logic
    // ============================================================================================

    fun startRecording() {
        if (cameraDevice == null || !(_cameraState.value is CameraState.Preview)) return
        
        try {
            closePreviewSession()
            setupMediaRecorder()
            
            val surfaces = ArrayList<Surface>()
            surfaces.add(previewSurface!!)
            surfaces.add(recorderSurface!!)
            
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            previewRequestBuilder!!.addTarget(previewSurface!!)
            previewRequestBuilder!!.addTarget(recorderSurface!!)
            
            // Apply current manual settings if any
            applyManualSettings()

            cameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                        
                        mediaRecorder?.start()
                        _recordingState.value = RecordingState.Recording(0)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _cameraState.value = CameraState.Error("Recording config failed")
                    }
                },
                backgroundHandler
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: ", e)
            _cameraState.value = CameraState.Error("Failed to start recording: ${e.message}")
        }
    }

    fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            _recordingState.value = RecordingState.Idle
            
            // Restart preview
            startPreview()
            
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: ", e)
        }
    }

    private fun setupMediaRecorder() {
        if (mediaRecorder == null) {
            mediaRecorder = MediaRecorder()
        } else {
            mediaRecorder?.reset()
        }
        
        val file = getVideoFile()
        currentVideoFile = file
        
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncodingBitRate(100_000_000) // 100 Mbps
            setVideoFrameRate(30)
            setVideoSize(3840, 2160) // 4K
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
            recorderSurface = surface
        }
    }

    private fun getVideoFile(): File {
        val dir = File(context.getExternalFilesDir(null), "VidUltra")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "VID_$timestamp.mp4")
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun closeCamera() {
        closePreviewSession()
        cameraDevice?.close()
        cameraDevice = null
        mediaRecorder?.release()
        mediaRecorder = null
        _cameraState.value = CameraState.Closed
    }
    
    // ============================================================================================
    // Manual Controls
    // ============================================================================================
    
    fun setIso(iso: Int) {
        currentIso = iso
        applyManualSettings()
        updatePreview()
    }
    
    fun setExposure(exposure: Long) {
        currentExposure = exposure
        applyManualSettings()
        updatePreview()
    }
    
    fun setFocus(focus: Float) {
        currentFocus = focus
        applyManualSettings()
        updatePreview()
    }
    
    fun setAuto() {
        currentIso = null
        currentExposure = null
        currentFocus = null
        
        previewRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        previewRequestBuilder?.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        updatePreview()
    }

    private fun applyManualSettings() {
        if (previewRequestBuilder == null) return
        
        if (currentIso != null || currentExposure != null) {
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            
            currentIso?.let {
                previewRequestBuilder!!.set(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            currentExposure?.let {
                previewRequestBuilder!!.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
        }
        
        if (currentFocus != null) {
            previewRequestBuilder!!.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            previewRequestBuilder!!.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
        }
    }

    companion object {
        private const val TAG = "CameraEngine"
    }
}
