package com.pixelgamer4k.vidultra.core

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
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
    
    // Recorder
    private var mediaRecorder: MediaRecorder? = null
    
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
            setupMediaRecorder()
            
            val surfaces = listOf(previewSurface!!, recorderSurface!!)
            
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            previewRequestBuilder!!.addTarget(previewSurface!!)
            previewRequestBuilder!!.addTarget(recorderSurface!!)
            
            applySettings()

            cameraDevice!!.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                        mediaRecorder?.start()
                        _state.value = CameraState.Recording
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        _state.value = CameraState.Error("Recording Config Failed")
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "startRecording: ", e)
            _state.value = CameraState.Error(e.message ?: "Recording Error")
        }
    }

    fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            startPreview()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecording: ", e)
        }
    }

    private fun setupMediaRecorder() {
        if (mediaRecorder == null) mediaRecorder = MediaRecorder()
        else mediaRecorder?.reset()
        
        val contentValues = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "VID_${System.currentTimeMillis()}.mp4")
            put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/VidUltra")
            }
        }
        
        val uri = context.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("Failed to create MediaStore entry")
            
        val fileDescriptor = context.contentResolver.openFileDescriptor(uri, "rw")?.fileDescriptor
            ?: throw Exception("Failed to open file descriptor")
        
        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(fileDescriptor)
            
            // Configure bitrate and profile based on bit depth
            if (bitDepth == 10 && supports10Bit) {
                setVideoEncodingBitRate(150_000_000) // Higher bitrate for 10-bit
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setVideoEncodingProfileLevel(
                        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51
                    )
                }
                Log.i(TAG, "Recording configured for 10-bit HEVC @ 150Mbps")
            } else {
                setVideoEncodingBitRate(100_000_000) // Standard bitrate for 8-bit
                Log.i(TAG, "Recording configured for 8-bit HEVC @ 100Mbps")
            }
            
            setVideoFrameRate(30)
            setVideoSize(3840, 2160)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
            recorderSurface = surface
        }
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
        _state.value = CameraState.Closed
    }
    
    // Manual Controls
    fun setManualIso(value: Int) { iso = value; applySettings(); updatePreview() }
    fun setManualExposure(value: Long) { exposure = value; applySettings(); updatePreview() }
    fun setManualFocus(value: Float) { focus = value; applySettings(); updatePreview() }
    fun setAuto() { iso = null; exposure = null; focus = null; applySettings(); updatePreview() }

    private fun applySettings() {
        val builder = previewRequestBuilder ?: return
        
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
