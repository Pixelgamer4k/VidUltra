package com.vidultra.pro.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.vidultra.pro.camera.CameraSettings.ControlMode
import com.vidultra.pro.ui.HistogramView
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Camera2 controller handling preview, manual controls, and recording.
 */
class CameraController(private val context: Context) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val settingsManager = com.vidultra.pro.settings.SettingsManager(context)

    private var cameraId: String? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewBuilder: CaptureRequest.Builder? = null
    private var recorderBuilder: CaptureRequest.Builder? = null

    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var histogramReader: ImageReader? = null

    private val backgroundThread = HandlerThread("VidUltraCamera").apply { start() }
    private val backgroundHandler = Handler(backgroundThread.looper)

    private val cameraOpenCloseLock = Semaphore(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val videoRecorder = VideoRecorder(context)
    private var currentSettings = settingsManager.current

    private var availableSizes: Array<Size> = emptyArray()
    private var availableFpsRanges: Array<Range<Int>> = emptyArray()
    private var isoRange: Range<Int> = Range(100, 1600)
    private var exposureRange: Range<Long> = Range(1L, 33_333_333L)
    private var focusRange: Float = 0f
    private var recording = false
    private var histogramView: HistogramView? = null

    val isRecording: Boolean
        get() = recording

    // Focus bracketing state
    private var focusBracketJob: Job? = null
    private var focusBracketValues: List<Float> = emptyList()
    private var focusBracketIndex = 0

    var onError: ((String) -> Unit)? = null
    var onRecordingStateChanged: ((Boolean) -> Unit)? = null

    init {
        selectCamera(currentSettings.lensFacing)
    }

    fun setHistogramView(view: HistogramView?) {
        histogramView = view
    }

    fun getSettings(): CameraSettings = currentSettings

    fun updateSettings(block: CameraSettings.() -> CameraSettings) {
        currentSettings = currentSettings.block()
        settingsManager.current = currentSettings
        if (cameraDevice != null) {
            applySettingsToSession()
        }
    }

    private fun selectCamera(lensFacing: Int) {
        cameraId = cameraManager.cameraIdList.find { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == lensFacing
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    fun openCamera(textureView: TextureView, callback: () -> Unit) {
        val id = cameraId ?: return
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                onError?.invoke("Timeout opening camera")
                return
            }
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = device
                    loadCharacteristics()
                    createPreviewSession(textureView, callback)
                }

                override fun onDisconnected(device: CameraDevice) {
                    cameraOpenCloseLock.release()
                    closeCamera()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    onError?.invoke("Camera error: $error")
                    closeCamera()
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            onError?.invoke("Camera permission missing")
        } catch (e: Exception) {
            onError?.invoke("Camera open failed: ${e.message}")
        }
    }

    private fun loadCharacteristics() {
        val chars = getCharacteristics() ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        availableSizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        availableFpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 1600)
        exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1L, 33_333_333L)
        val focusMin = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        focusRange = focusMin
    }

    private fun createPreviewSession(textureView: TextureView, callback: () -> Unit) {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val size = chooseOptimalSize()
        texture.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(texture)

        // Histogram analysis stream
        val yuvSize = chooseOptimalYuvSize()
        histogramReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                processHistogram(image)
                image.close()
            }, backgroundHandler)
        }

        val outputs = mutableListOf(previewSurface!!, histogramReader!!.surface)

        device.createCaptureSession(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface!!)
                        if (currentSettings.histogramEnabled) {
                            addTarget(histogramReader!!.surface)
                        }
                    }
                    applySettingsToSession()
                    startPreview()
                    callback()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError?.invoke("Failed to configure camera session")
                }
            },
            backgroundHandler
        )
    }

    private fun createRecordingSession(textureView: TextureView, callback: () -> Unit) {
        val device = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        val size = chooseOptimalSize()
        texture.setDefaultBufferSize(size.width, size.height)
        previewSurface = Surface(texture)

        // Prepare MediaRecorder and get its surface
        recorderSurface = videoRecorder.prepare(currentSettings, size)
        if (recorderSurface == null) {
            onError?.invoke("Failed to prepare video recorder")
            return
        }

        val outputs = listOf(previewSurface!!, recorderSurface!!)
        device.createCaptureSession(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    recorderBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface!!)
                        addTarget(recorderSurface!!)
                    }
                    applySettingsToSession()
                    startRecordingCapture()
                    callback()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    onError?.invoke("Failed to configure recording session")
                }
            },
            backgroundHandler
        )
    }

    private fun chooseOptimalSize(): Size {
        val target = currentSettings.resolution
        return availableSizes.minByOrNull { size ->
            val ratioDiff = kotlin.math.abs(size.width.toFloat() / size.height - target.width.toFloat() / target.height)
            val areaDiff = kotlin.math.abs(size.width * size.height - target.width * target.height)
            ratioDiff * 1_000_000 + areaDiff
        } ?: target
    }

    private fun chooseOptimalYuvSize(): Size {
        val chars = getCharacteristics() ?: return Size(320, 180)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(320, 180)
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(320, 180)
        val target = Size(640, 360)
        return yuvSizes.minByOrNull { size ->
            val ratioDiff = kotlin.math.abs(size.width.toFloat() / size.height - target.width.toFloat() / target.height)
            val areaDiff = kotlin.math.abs(size.width * size.height - target.width * target.height)
            ratioDiff * 1_000_000 + areaDiff
        } ?: yuvSizes.firstOrNull() ?: Size(320, 180)
    }

    private fun applySettingsToSession() {
        val builder = if (recording) recorderBuilder else previewBuilder
        builder ?: return

        // AF / AE / AWB modes
        val afMode = when (currentSettings.focusMode) {
            ControlMode.AUTO -> CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            ControlMode.MANUAL -> CameraMetadata.CONTROL_AF_MODE_OFF
        }
        val aeMode = when (currentSettings.shutterMode to currentSettings.isoMode) {
            ControlMode.MANUAL to ControlMode.MANUAL -> CameraMetadata.CONTROL_AE_MODE_OFF
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        val awbMode = when (currentSettings.wbMode) {
            ControlMode.AUTO -> CameraMetadata.CONTROL_AWB_MODE_AUTO
            ControlMode.MANUAL -> CameraMetadata.CONTROL_AWB_MODE_OFF
        }

        builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        builder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode)

        if (currentSettings.isoMode == ControlMode.MANUAL) {
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, currentSettings.isoValue.coerceIn(isoRange.lower, isoRange.upper))
        }
        if (currentSettings.shutterMode == ControlMode.MANUAL) {
            val exposure = currentSettings.effectiveShutterNs().coerceIn(exposureRange.lower, exposureRange.upper)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
        }
        if (currentSettings.focusMode == ControlMode.MANUAL) {
            val distance = if (currentSettings.focusDistance <= 0f) 0f else 1f / currentSettings.focusDistance
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance.coerceAtMost(focusRange))
        }
        if (currentSettings.wbMode == ControlMode.MANUAL) {
            val rggb = kelvinToRggb(currentSettings.wbTemperature)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, rggb)
        }

        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, currentSettings.exposureCompensation)

        // Noise reduction OFF when disabled
        val nrMode = if (currentSettings.noiseReductionEnabled) {
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.NOISE_REDUCTION_MODE_OFF
        }
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, nrMode)

        // Sharpening OFF (EDGE_MODE_OFF)
        val edgeMode = if (currentSettings.sharpeningEnabled) {
            CameraMetadata.EDGE_MODE_HIGH_QUALITY
        } else {
            CameraMetadata.EDGE_MODE_OFF
        }
        builder.set(CaptureRequest.EDGE_MODE, edgeMode)

        // Stabilization
        val chars = getCharacteristics()
        val oisAvailable = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(
            CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
        ) == true
        if (oisAvailable) {
            builder.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (currentSettings.stabilizationEnabled) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
        }
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (currentSettings.stabilizationEnabled) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
            else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )

        // FPS range target
        val fpsRange = availableFpsRanges.find { it.lower <= currentSettings.frameRate && it.upper >= currentSettings.frameRate }
            ?: availableFpsRanges.maxByOrNull { it.upper }
        fpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        // Tone mapping / dynamic range hints (best effort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (currentSettings.tenBitEnabled || currentSettings.hdrEnabled) {
                // Prefer device default HDR/PQ pipeline when available
                builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }
        }

        repeatCaptureRequest(builder.build())
    }

    private fun startPreview() {
        val builder = previewBuilder ?: return
        captureSession?.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
    }

    private fun startRecordingCapture() {
        val builder = recorderBuilder ?: return
        captureSession?.setRepeatingRequest(builder.build(), captureCallback, backgroundHandler)
    }

    private fun repeatCaptureRequest(request: CaptureRequest) {
        captureSession?.setRepeatingRequest(request, captureCallback, backgroundHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(session, request, result)
        }
    }

    fun startRecording(textureView: TextureView): Boolean {
        if (recording) return false
        closeSession()
        createRecordingSession(textureView) {
            val started = videoRecorder.start()
            if (started) {
                recording = true
                onRecordingStateChanged?.invoke(true)
            } else {
                onError?.invoke("Failed to start recording")
                recording = false
                // Fall back to preview
                closeSession()
                createPreviewSession(textureView) {}
            }
        }
        return true
    }

    fun stopRecording(): File? {
        if (!recording) return null
        val file = videoRecorder.stop()
        recording = false
        onRecordingStateChanged?.invoke(false)
        return file
    }

    fun switchLens(lensFacing: Int, textureView: TextureView, callback: () -> Unit) {
        updateSettings { copy(lensFacing = lensFacing) }
        closeCamera()
        selectCamera(lensFacing)
        openCamera(textureView, callback)
    }

    fun startFocusBracket() {
        if (currentSettings.focusMode != ControlMode.MANUAL) {
            updateSettings { copy(focusMode = ControlMode.MANUAL) }
        }
        val steps = max(3, currentSettings.focusBracketSteps)
        val near = 0.1f
        val far = min(focusRange, 10f)
        focusBracketValues = (0 until steps).map { i ->
            near + (far - near) * (i / (steps - 1).toFloat())
        }
        focusBracketIndex = 0
        focusBracketJob?.cancel()
        focusBracketJob = scope.launch {
            while (isActive) {
                val value = focusBracketValues.getOrNull(focusBracketIndex) ?: break
                updateSettings { copy(focusDistance = 1f / value) }
                focusBracketIndex = (focusBracketIndex + 1) % focusBracketValues.size
                delay(1000L / currentSettings.frameRate.coerceAtLeast(1).toLong())
            }
        }
    }

    fun stopFocusBracket() {
        focusBracketJob?.cancel()
        focusBracketJob = null
    }

    private fun closeSession() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        previewBuilder = null
        recorderBuilder = null
        previewSurface = null
        recorderSurface = null
        try {
            histogramReader?.close()
        } catch (_: Exception) {
        }
        histogramReader = null
    }

    fun closeCamera() {
        stopFocusBracket()
        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        closeSession()
        cameraDevice?.close()
        cameraDevice = null
        videoRecorder.release()
    }

    fun release() {
        closeCamera()
        scope.cancel()
        backgroundThread.quitSafely()
    }

    private fun getCharacteristics(): CameraCharacteristics? {
        val id = cameraId ?: return null
        return try {
            cameraManager.getCameraCharacteristics(id)
        } catch (e: Exception) {
            null
        }
    }

    private fun processHistogram(image: Image) {
        val view = histogramView ?: return
        if (image.format != ImageFormat.YUV_420_888) {
            image.close()
            return
        }
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yArray = ByteArray(yBuffer.remaining())
        yBuffer.get(yArray)

        // Downsample for performance
        val luma = IntArray(256)
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)

        val width = image.width
        val height = image.height
        val sampleStep = 8

        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val yIndex = y * width + x
                val yVal = (yArray[yIndex].toInt() and 0xFF)
                luma[yVal]++
                // Approximate RGB from YUV is expensive; reuse luma for RGB placeholder
                red[yVal]++
                green[yVal]++
                blue[yVal]++
            }
        }

        view.post {
            view.update(luma, red, green, blue)
        }
    }

    private fun kelvinToRggb(kelvin: Int): RggbChannelVector {
        val temperature = kelvin / 100f
        val red: Float
        val green: Float
        val blue: Float

        if (temperature <= 66) {
            red = 255f
            green = if (temperature <= 19) 0f else {
                99.4708025861f * kotlin.math.ln(temperature - 10) - 161.1195681661f
            }
            blue = if (temperature <= 19) 0f else {
                138.5177312231f * kotlin.math.ln(temperature - 10) - 305.0447927307f
            }
        } else {
            red = (329.698727446 * Math.pow((temperature - 60).toDouble(), -0.1332047592)).toFloat()
            green = (288.1221695283 * Math.pow((temperature - 60).toDouble(), -0.0755148492)).toFloat()
            blue = 255f
        }

        val rGain = (red / green).coerceIn(0.1f, 10f)
        val bGain = (blue / green).coerceIn(0.1f, 10f)
        return RggbChannelVector(rGain, 1f, 1f, bGain)
    }
}
