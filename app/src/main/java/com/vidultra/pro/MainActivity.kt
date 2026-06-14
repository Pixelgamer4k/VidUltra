package com.vidultra.pro

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vidultra.pro.camera.CameraController
import com.vidultra.pro.camera.CameraSettings
import com.vidultra.pro.databinding.ActivityMainBinding
import com.vidultra.pro.utils.LogProfile
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraController: CameraController
    private val handler = Handler(Looper.getMainLooper())
    private var recordingStartTime = 0L
    private var activeSlider: SliderType? = null

    private enum class SliderType { ISO, SHUTTER, FOCUS, WB, EV, FPS, BITRATE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupFullscreen()

        cameraController = CameraController(this)
        cameraController.onError = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }
        cameraController.onRecordingStateChanged = { recording ->
            runOnUiThread { updateRecordingUi(recording) }
        }
        cameraController.setHistogramView(binding.histogramView)

        binding.btnRecord.setOnClickListener { toggleRecord() }
        setupControlButtons()
        setupSlider()

        if (hasPermissions()) {
            startCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::cameraController.isInitialized && hasPermissions() && binding.previewView.isAvailable) {
            cameraController.openCamera(binding.previewView) {}
        }
    }

    override fun onPause() {
        super.onPause()
        if (::cameraController.isInitialized) {
            cameraController.closeCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraController.isInitialized) {
            cameraController.release()
        }
    }

    private fun setupFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    private fun startCamera() {
        if (binding.previewView.isAvailable) {
            cameraController.openCamera(binding.previewView) { refreshUiFromSettings() }
        } else {
            binding.previewView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    cameraController.openCamera(binding.previewView) { refreshUiFromSettings() }
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = false
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
            }
        }
    }

    private fun setupControlButtons() {
        binding.btnLens.setOnClickListener { showLensDialog() }
        binding.btnLog.setOnClickListener { showLogDialog() }
        binding.btnHdr.setOnClickListener { toggleHdr() }
        binding.btnTenbit.setOnClickListener { toggleTenBit() }
        binding.btnBitrate.setOnClickListener { showSlider(SliderType.BITRATE) }
        binding.btnStabilization.setOnClickListener { toggleStabilization() }
        binding.btnHistogram.setOnClickListener { toggleHistogram() }
        binding.btnFocusBracket.setOnClickListener { toggleFocusBracket() }
        binding.btnNoiseReduction.setOnClickListener { toggleNoiseReduction() }
        binding.btnSharpening.setOnClickListener { toggleSharpening() }

        binding.btnIso.setOnClickListener { showSlider(SliderType.ISO) }
        binding.btnShutter.setOnClickListener { showShutterDialog() }
        binding.btnFocus.setOnClickListener { showSlider(SliderType.FOCUS) }
        binding.btnWb.setOnClickListener { showSlider(SliderType.WB) }
        binding.btnEv.setOnClickListener { showSlider(SliderType.EV) }
        binding.btnFpsSelect.setOnClickListener { showSlider(SliderType.FPS) }

        binding.btnResolution.setOnClickListener { showResolutionDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun setupSlider() {
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applySliderValue(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun showSlider(type: SliderType) {
        activeSlider = type
        binding.sliderContainer.visibility = View.VISIBLE
        val settings = cameraController.getSettings()
        when (type) {
            SliderType.ISO -> {
                binding.tvSliderTitle.text = "ISO"
                binding.seekBar.max = 6400
                binding.seekBar.progress = settings.isoValue
            }
            SliderType.SHUTTER -> {
                binding.tvSliderTitle.text = "Shutter Angle"
                binding.seekBar.max = 360
                binding.seekBar.progress = settings.shutterAngle.toInt()
            }
            SliderType.FOCUS -> {
                binding.tvSliderTitle.text = "Focus Distance (m)"
                binding.seekBar.max = 1000
                binding.seekBar.progress = ((settings.focusDistance * 100).toInt())
            }
            SliderType.WB -> {
                binding.tvSliderTitle.text = "White Balance (K)"
                binding.seekBar.max = 9000
                binding.seekBar.progress = settings.wbTemperature
            }
            SliderType.EV -> {
                binding.tvSliderTitle.text = "Exposure Compensation"
                binding.seekBar.max = 60
                binding.seekBar.progress = settings.exposureCompensation + 30
            }
            SliderType.FPS -> {
                binding.tvSliderTitle.text = "Frame Rate"
                binding.seekBar.max = 120
                binding.seekBar.progress = settings.frameRate
            }
            SliderType.BITRATE -> {
                binding.tvSliderTitle.text = "Video Bitrate (Mbps)"
                binding.seekBar.max = 400
                binding.seekBar.progress = settings.videoBitrate / 1_000_000
            }
        }
        applySliderValue(binding.seekBar.progress)
    }

    private fun applySliderValue(progress: Int) {
        val settings = cameraController.getSettings()
        val newSettings = when (activeSlider) {
            SliderType.ISO -> settings.copy(isoMode = CameraSettings.ControlMode.MANUAL, isoValue = progress.coerceAtLeast(50))
            SliderType.SHUTTER -> settings.copy(shutterMode = CameraSettings.ControlMode.MANUAL, shutterAngle = progress.toFloat())
            SliderType.FOCUS -> settings.copy(focusMode = CameraSettings.ControlMode.MANUAL, focusDistance = progress / 100f)
            SliderType.WB -> settings.copy(wbMode = CameraSettings.ControlMode.MANUAL, wbTemperature = progress.coerceAtLeast(2000))
            SliderType.EV -> settings.copy(exposureCompensation = progress - 30)
            SliderType.FPS -> settings.copy(frameRate = progress.coerceAtLeast(1))
            SliderType.BITRATE -> settings.copy(videoBitrate = progress * 1_000_000)
            null -> return
        }
        cameraController.updateSettings { newSettings }
        updateSliderLabel(newSettings)
        refreshUiFromSettings()
    }

    private fun updateSliderLabel(settings: CameraSettings) {
        binding.tvSliderValue.text = when (activeSlider) {
            SliderType.ISO -> settings.isoValue.toString()
            SliderType.SHUTTER -> "${settings.shutterAngle.toInt()}°"
            SliderType.FOCUS -> "${settings.focusDistance}m"
            SliderType.WB -> "${settings.wbTemperature}K"
            SliderType.EV -> settings.exposureCompensation.toString()
            SliderType.FPS -> "${settings.frameRate} FPS"
            SliderType.BITRATE -> "${settings.videoBitrate / 1_000_000} Mbps"
            null -> ""
        }
    }

    private fun toggleRecord() {
        if (cameraController.isRecording) {
            cameraController.stopRecording()
            stopRecordTimer()
        } else {
            cameraController.startRecording(binding.previewView)
            startRecordTimer()
        }
    }

    private fun startRecordTimer() {
        recordingStartTime = SystemClock.elapsedRealtime()
        binding.tvRecordTime.visibility = View.VISIBLE
        binding.recordingDot.visibility = View.VISIBLE
        handler.post(object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - recordingStartTime
                val hrs = TimeUnit.MILLISECONDS.toHours(elapsed)
                val min = TimeUnit.MILLISECONDS.toMinutes(elapsed) % 60
                val sec = TimeUnit.MILLISECONDS.toSeconds(elapsed) % 60
                binding.tvRecordTime.text = String.format("%02d:%02d:%02d", hrs, min, sec)
                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun stopRecordTimer() {
        handler.removeCallbacksAndMessages(null)
        binding.tvRecordTime.visibility = View.INVISIBLE
        binding.recordingDot.visibility = View.INVISIBLE
    }

    private fun updateRecordingUi(recording: Boolean) {
        if (recording) {
            binding.btnRecord.background = ContextCompat.getDrawable(this, R.drawable.bg_record_button_recording)
        } else {
            binding.btnRecord.background = ContextCompat.getDrawable(this, R.drawable.bg_record_button)
            stopRecordTimer()
        }
    }

    private fun showLensDialog() {
        val ids = listOf(
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK to "Rear Wide",
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT to "Front",
            android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL to "External"
        )
        val items = ids.map { it.second }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Lens")
            .setItems(items) { _, which ->
                cameraController.switchLens(ids[which].first, binding.previewView) { refreshUiFromSettings() }
            }
            .show()
    }

    private fun showLogDialog() {
        val items = LogProfile.entries.map { "${it.displayName} - ${it.description}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Log / Gamma Profile")
            .setItems(items) { _, which ->
                cameraController.updateSettings { copy(logProfile = LogProfile.entries[which]) }
                refreshUiFromSettings()
            }
            .show()
    }

    private fun showShutterDialog() {
        val angles = listOf(45f, 90f, 144f, 172f, 180f, 270f, 360f)
        val items = angles.map { "$it°" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Shutter Angle")
            .setItems(items) { _, which ->
                cameraController.updateSettings { copy(shutterMode = CameraSettings.ControlMode.MANUAL, shutterAngle = angles[which]) }
                refreshUiFromSettings()
            }
            .show()
    }

    private fun showResolutionDialog() {
        val resolutions = listOf(
            Size(3840, 2160),
            Size(1920, 1080),
            Size(1280, 720)
        )
        val items = resolutions.map { "${it.width}x${it.height}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Resolution")
            .setItems(items) { _, which ->
                cameraController.updateSettings { copy(resolution = resolutions[which]) }
                refreshUiFromSettings()
            }
            .show()
    }

    private fun toggleHdr() {
        cameraController.updateSettings { copy(hdrEnabled = !hdrEnabled) }
        refreshUiFromSettings()
    }

    private fun toggleTenBit() {
        cameraController.updateSettings { copy(tenBitEnabled = !tenBitEnabled) }
        refreshUiFromSettings()
    }

    private fun toggleStabilization() {
        cameraController.updateSettings { copy(stabilizationEnabled = !stabilizationEnabled) }
        refreshUiFromSettings()
    }

    private fun toggleHistogram() {
        cameraController.updateSettings { copy(histogramEnabled = !histogramEnabled) }
        binding.histogramView.visibility = if (cameraController.getSettings().histogramEnabled) View.VISIBLE else View.GONE
        refreshUiFromSettings()
    }

    private fun toggleFocusBracket() {
        val settings = cameraController.getSettings()
        if (settings.focusBracketEnabled) {
            cameraController.updateSettings { copy(focusBracketEnabled = false) }
            cameraController.stopFocusBracket()
        } else {
            cameraController.updateSettings { copy(focusBracketEnabled = true) }
            cameraController.startFocusBracket()
        }
        refreshUiFromSettings()
    }

    private fun toggleNoiseReduction() {
        cameraController.updateSettings { copy(noiseReductionEnabled = !noiseReductionEnabled) }
        refreshUiFromSettings()
    }

    private fun toggleSharpening() {
        cameraController.updateSettings { copy(sharpeningEnabled = !sharpeningEnabled) }
        refreshUiFromSettings()
    }

    private fun showSettingsDialog() {
        val settings = cameraController.getSettings()
        val options = arrayOf(
            "Audio: ${if (settings.audioEnabled) "ON" else "OFF"}",
            "Codec: ${settings.codec}",
            "Grid: ${if (settings.gridEnabled) "ON" else "OFF"}",
            "Zebra: ${if (settings.zebraEnabled) "ON" else "OFF"}",
            "Focus Peaking: ${if (settings.peakingEnabled) "ON" else "OFF"}"
        )
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> cameraController.updateSettings { copy(audioEnabled = !audioEnabled) }
                    1 -> cameraController.updateSettings { copy(codec = if (codec == CameraSettings.VideoCodec.HEVC) CameraSettings.VideoCodec.AVC else CameraSettings.VideoCodec.HEVC) }
                    2 -> cameraController.updateSettings { copy(gridEnabled = !gridEnabled) }
                    3 -> cameraController.updateSettings { copy(zebraEnabled = !zebraEnabled) }
                    4 -> cameraController.updateSettings { copy(peakingEnabled = !peakingEnabled) }
                }
                refreshUiFromSettings()
            }
            .show()
    }

    private fun refreshUiFromSettings() {
        val settings = cameraController.getSettings()
        binding.tvResolution.text = "${settings.resolution.width / 1000}K"
        binding.tvFps.text = "${settings.frameRate} FPS"
        binding.tvLogProfile.text = settings.logProfile.displayName

        binding.btnIso.text = "ISO\n${if (settings.isoMode == CameraSettings.ControlMode.AUTO) "AUTO" else settings.isoValue}"
        binding.btnShutter.text = "SHUTTER\n${if (settings.shutterMode == CameraSettings.ControlMode.AUTO) "AUTO" else settings.shutterAngle.toInt().toString() + "°"}"
        binding.btnFocus.text = "FOCUS\n${if (settings.focusMode == CameraSettings.ControlMode.AUTO) "AUTO" else "MAN"}"
        binding.btnWb.text = "WB\n${if (settings.wbMode == CameraSettings.ControlMode.AUTO) "AUTO" else settings.wbTemperature.toString() + "K"}"
        binding.btnEv.text = "EV\n${settings.exposureCompensation}"
        binding.btnFpsSelect.text = "FPS\n${settings.frameRate}"

        binding.btnHdr.text = "HDR\n${if (settings.hdrEnabled) "ON" else "OFF"}"
        binding.btnTenbit.text = "10-BIT\n${if (settings.tenBitEnabled) "ON" else "OFF"}"
        binding.btnStabilization.text = "STAB\n${if (settings.stabilizationEnabled) "ON" else "OFF"}"
        binding.btnNoiseReduction.text = "NR\n${if (settings.noiseReductionEnabled) "ON" else "OFF"}"
        binding.btnSharpening.text = "SHARP\n${if (settings.sharpeningEnabled) "ON" else "OFF"}"
        binding.btnHistogram.text = "HIST\n${if (settings.histogramEnabled) "ON" else "OFF"}"
        binding.btnFocusBracket.text = "F-BRK\n${if (settings.focusBracketEnabled) "ON" else "OFF"}"

        if (activeSlider != null) updateSliderLabel(settings)
    }

    private fun hasPermissions(): Boolean {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
    }
}
