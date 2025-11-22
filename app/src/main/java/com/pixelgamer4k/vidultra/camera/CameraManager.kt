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

    private val cam

ameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var mediaRecorder: MediaRecorder? = null
    private var previewSurface: Surface? = null
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var currentVideoFile: File? = null

    // Manual Control State
    var iso: Int? = null
    var exposureTime: Long? = null
    var focusDistance: Float? = null
    var whiteBalance: Int? = null
    
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

    private fun createCameraPreviewSession(previewSurface: Surface) {
        try {
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder?.addTarget(previewSurface)

            // Apply Manual Controls
            applyManualControls(builder)

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
            val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder?.addTarget(previewSurface)
            builder?.addTarget(recordingSurface)

            // Apply Manual Controls
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
                            Log.d(TAG, "Recording started")
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
            // Manual mode
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            
            // ISO
            iso?.let { 
                set(CaptureRequest.SENSOR_SENSITIVITY, it)
            }
            
            // Shutter speed (exposure time)
            exposureTime?.let { 
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, it)
            }
            
            // Focus
            focusDistance?.let {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, it)
            }
            
            // White Balance
            whiteBalance?.let {
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                // Note: Manual WB with color temperature requires more complex implementation
            }
        }
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
        
        try {
            // Create video file
            currentVideoFile = FileManager.createVideoFile(
                context, 
                width, 
                height, 
                currentCodec.fileExtension
            )
            
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
            }
            
            // Create recording session with both surfaces
            val recordingSurface = mediaRecorder?.surface
            if (recordingSurface != null && previewSurface != null) {
                createRecordingSession(previewSurface!!, recordingSurface)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ", e)
            _isRecording.value = false
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    fun stopRecording() {
        try {
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
            previewSurface?.let { createCameraPreviewSession(it) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ", e)
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
