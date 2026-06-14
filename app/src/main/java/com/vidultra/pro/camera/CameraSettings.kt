package com.vidultra.pro.camera

import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import com.vidultra.pro.utils.LogProfile

/**
 * Immutable holder for all user-controllable capture / recording settings.
 */
data class CameraSettings(
    val lensFacing: Int = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK,
    val resolution: Size = Size(3840, 2160),
    val frameRate: Int = 30,
    val videoBitrate: Int = 100_000_000,
    val codec: VideoCodec = VideoCodec.HEVC,
    val logProfile: LogProfile = LogProfile.REC709,
    val hdrEnabled: Boolean = false,
    val tenBitEnabled: Boolean = false,
    val stabilizationEnabled: Boolean = false,
    val noiseReductionEnabled: Boolean = true,
    val sharpeningEnabled: Boolean = false,
    val isoMode: ControlMode = ControlMode.AUTO,
    val isoValue: Int = 100,
    val shutterMode: ControlMode = ControlMode.AUTO,
    val shutterNs: Long = 33_333_333L, // ~1/30s
    val shutterAngle: Float = 180f,
    val focusMode: ControlMode = ControlMode.AUTO,
    val focusDistance: Float = 0f, // 0 = infinity-ish for some devices, use diopters
    val wbMode: ControlMode = ControlMode.AUTO,
    val wbTemperature: Int = 5600,
    val exposureCompensation: Int = 0,
    val audioEnabled: Boolean = true,
    val audioBitrate: Int = 256_000,
    val audioSampleRate: Int = 48000,
    val histogramEnabled: Boolean = true,
    val focusBracketEnabled: Boolean = false,
    val focusBracketSteps: Int = 5,
    val focusBracketRange: Float = 1.0f,
    val gridEnabled: Boolean = false,
    val zebraEnabled: Boolean = false,
    val peakingEnabled: Boolean = false
) {
    enum class ControlMode { AUTO, MANUAL }
    enum class VideoCodec { AVC, HEVC }

    /**
     * Effective sensor exposure time for the selected shutter angle at current FPS.
     * shutterAngle / 360 * frameDuration
     */
    fun effectiveShutterNs(): Long {
        val frameDurationNs = 1_000_000_000L / frameRate
        return ((shutterAngle / 360f) * frameDurationNs).toLong().coerceAtLeast(1L)
    }

    fun createCaptureRequestBuilder(
        cameraDevice: android.hardware.camera2.CameraDevice,
        template: Int
    ): CaptureRequest.Builder {
        return cameraDevice.createCaptureRequest(template)
    }
}
