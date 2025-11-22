package com.pixelgamer4k.vidultra.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * Simplified CameraManager - handles only Camera2 operations
 * Recording is handled by VideoRecorder class
 */
class CameraController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Manual Control State
    var iso: Int? = null
    var exposureTime: Long? = null
    var focusDistance: Float? = null
    var whiteBalance: Int? = null
    private var manualMode = false

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
    fun openCamera(surface: Surface) {
        previewSurface = surface
        try {
            val cameraId = cameraManager.cameraIdList[0]
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "openCamera: ", e)
        }
    }

    private fun createPreviewSession() {
        try {
            previewSurface?.let { surface ->
                val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                builder?.addTarget(surface)
                
                applySettings(builder, false)

                cameraDevice?.createCaptureSession(
                    listOf(surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            builder?.let {
                                try {
                                    session.setRepeatingRequest(it.build(), null, backgroundHandler)
                                    Log.d(TAG, "Preview session started")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error starting preview: ", e)
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Preview session configuration failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "createPreviewSession: ", e)
        }
    }

    /**
     * Creates recording session with preview + recording surfaces
     * Recording surface comes from VideoRecorder
     */
    fun createRecordingSession(recordingSurface: Surface, onReady: () -> Unit) {
        try {
            previewSurface?.let { preview ->
                // Close existing session first
                captureSession?.close()
                captureSession = null
                
                Thread.sleep(100) // Give time for session to close
                
                val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                builder?.addTarget(preview)
                builder?.addTarget(recordingSurface)
                
                applySettings(builder, true)

                cameraDevice?.createCaptureSession(
                    listOf(preview, recordingSurface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            builder?.let {
                                try {
                                    session.setRepeatingRequest(it.build(), null, backgroundHandler)
                                    Log.d(TAG, "Recording session started")
                                    onReady() // Signal ready to start recording
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error starting recording session: ", e)
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e(TAG, "Recording session configuration failed")
                        }
                    },
                    backgroundHandler
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "createRecordingSession: ", e)
            e.printStackTrace()
        }
    }

    /**
     * Stops recording session and returns to preview
     */
    fun stopRecordingSession(onStopped: () -> Unit) {
        try {
            captureSession?.close()
            captureSession = null
            Thread.sleep(100)
            createPreviewSession()
            onStopped()
        } catch (e: Exception) {
            Log.e(TAG, "stopRecordingSession: ", e)
        }
    }

    private fun applySettings(builder: CaptureRequest.Builder?, isRecording: Boolean) {
        builder?.apply {
            if (manualMode && iso != null && exposureTime != null) {
                // Manual mode
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, clampISO(iso!!))
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExposureTime(exposureTime!!))
                
                focusDistance?.let {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, clampFocusDistance(it))
                }
            } else {
                // Auto mode
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            }
            
            // Quality settings
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
            
            // Video stabilization for recording
            if (isRecording) {
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
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
        
        if (newIso != null || newExposureTime != null) {
            manualMode = true
        }
        
        if (updated) {
            updateCaptureRequest()
        }
    }

    private fun updateCaptureRequest() {
        try {
            captureSession?.let { session ->
                val builder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                previewSurface?.let { builder?.addTarget(it) }
                applySettings(builder, false)
                
                builder?.let {
                    session.setRepeatingRequest(it.build(), null, backgroundHandler)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateCaptureRequest: ", e)
        }
    }

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

    fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        previewSurface = null
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
