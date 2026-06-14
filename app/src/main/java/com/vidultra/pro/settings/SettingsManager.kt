package com.vidultra.pro.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Size
import com.vidultra.pro.camera.CameraSettings
import com.vidultra.pro.utils.LogProfile

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var current: CameraSettings
        get() = load()
        set(value) = save(value)

    private fun load(): CameraSettings {
        return CameraSettings(
            lensFacing = prefs.getInt(KEY_LENS_FACING, android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK),
            resolution = Size(
                prefs.getInt(KEY_RES_W, 3840),
                prefs.getInt(KEY_RES_H, 2160)
            ),
            frameRate = prefs.getInt(KEY_FRAME_RATE, 30),
            videoBitrate = prefs.getInt(KEY_BITRATE, 100_000_000),
            codec = CameraSettings.VideoCodec.valueOf(prefs.getString(KEY_CODEC, "HEVC") ?: "HEVC"),
            logProfile = LogProfile.valueOf(prefs.getString(KEY_LOG, "REC709") ?: "REC709"),
            hdrEnabled = prefs.getBoolean(KEY_HDR, false),
            tenBitEnabled = prefs.getBoolean(KEY_TEN_BIT, false),
            stabilizationEnabled = prefs.getBoolean(KEY_STABILIZATION, false),
            noiseReductionEnabled = prefs.getBoolean(KEY_NOISE_REDUCTION, true),
            sharpeningEnabled = prefs.getBoolean(KEY_SHARPENING, false),
            isoMode = CameraSettings.ControlMode.valueOf(prefs.getString(KEY_ISO_MODE, "AUTO") ?: "AUTO"),
            isoValue = prefs.getInt(KEY_ISO_VALUE, 100),
            shutterMode = CameraSettings.ControlMode.valueOf(prefs.getString(KEY_SHUTTER_MODE, "AUTO") ?: "AUTO"),
            shutterNs = prefs.getLong(KEY_SHUTTER_NS, 33_333_333L),
            shutterAngle = prefs.getFloat(KEY_SHUTTER_ANGLE, 180f),
            focusMode = CameraSettings.ControlMode.valueOf(prefs.getString(KEY_FOCUS_MODE, "AUTO") ?: "AUTO"),
            focusDistance = prefs.getFloat(KEY_FOCUS_DISTANCE, 0f),
            wbMode = CameraSettings.ControlMode.valueOf(prefs.getString(KEY_WB_MODE, "AUTO") ?: "AUTO"),
            wbTemperature = prefs.getInt(KEY_WB_TEMP, 5600),
            exposureCompensation = prefs.getInt(KEY_EV, 0),
            audioEnabled = prefs.getBoolean(KEY_AUDIO, true),
            audioBitrate = prefs.getInt(KEY_AUDIO_BITRATE, 256_000),
            audioSampleRate = prefs.getInt(KEY_AUDIO_SR, 48000),
            histogramEnabled = prefs.getBoolean(KEY_HISTOGRAM, true),
            focusBracketEnabled = prefs.getBoolean(KEY_FOCUS_BRACKET, false),
            focusBracketSteps = prefs.getInt(KEY_FOCUS_BRACKET_STEPS, 5),
            focusBracketRange = prefs.getFloat(KEY_FOCUS_BRACKET_RANGE, 1.0f),
            gridEnabled = prefs.getBoolean(KEY_GRID, false),
            zebraEnabled = prefs.getBoolean(KEY_ZEBRA, false),
            peakingEnabled = prefs.getBoolean(KEY_PEAKING, false)
        )
    }

    private fun save(settings: CameraSettings) {
        prefs.edit().apply {
            putInt(KEY_LENS_FACING, settings.lensFacing)
            putInt(KEY_RES_W, settings.resolution.width)
            putInt(KEY_RES_H, settings.resolution.height)
            putInt(KEY_FRAME_RATE, settings.frameRate)
            putInt(KEY_BITRATE, settings.videoBitrate)
            putString(KEY_CODEC, settings.codec.name)
            putString(KEY_LOG, settings.logProfile.name)
            putBoolean(KEY_HDR, settings.hdrEnabled)
            putBoolean(KEY_TEN_BIT, settings.tenBitEnabled)
            putBoolean(KEY_STABILIZATION, settings.stabilizationEnabled)
            putBoolean(KEY_NOISE_REDUCTION, settings.noiseReductionEnabled)
            putBoolean(KEY_SHARPENING, settings.sharpeningEnabled)
            putString(KEY_ISO_MODE, settings.isoMode.name)
            putInt(KEY_ISO_VALUE, settings.isoValue)
            putString(KEY_SHUTTER_MODE, settings.shutterMode.name)
            putLong(KEY_SHUTTER_NS, settings.shutterNs)
            putFloat(KEY_SHUTTER_ANGLE, settings.shutterAngle)
            putString(KEY_FOCUS_MODE, settings.focusMode.name)
            putFloat(KEY_FOCUS_DISTANCE, settings.focusDistance)
            putString(KEY_WB_MODE, settings.wbMode.name)
            putInt(KEY_WB_TEMP, settings.wbTemperature)
            putInt(KEY_EV, settings.exposureCompensation)
            putBoolean(KEY_AUDIO, settings.audioEnabled)
            putInt(KEY_AUDIO_BITRATE, settings.audioBitrate)
            putInt(KEY_AUDIO_SR, settings.audioSampleRate)
            putBoolean(KEY_HISTOGRAM, settings.histogramEnabled)
            putBoolean(KEY_FOCUS_BRACKET, settings.focusBracketEnabled)
            putInt(KEY_FOCUS_BRACKET_STEPS, settings.focusBracketSteps)
            putFloat(KEY_FOCUS_BRACKET_RANGE, settings.focusBracketRange)
            putBoolean(KEY_GRID, settings.gridEnabled)
            putBoolean(KEY_ZEBRA, settings.zebraEnabled)
            putBoolean(KEY_PEAKING, settings.peakingEnabled)
            apply()
        }
    }

    companion object {
        private const val PREFS_NAME = "vidultra_settings_v1"
        private const val KEY_LENS_FACING = "lens_facing"
        private const val KEY_RES_W = "res_w"
        private const val KEY_RES_H = "res_h"
        private const val KEY_FRAME_RATE = "frame_rate"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_CODEC = "codec"
        private const val KEY_LOG = "log_profile"
        private const val KEY_HDR = "hdr"
        private const val KEY_TEN_BIT = "ten_bit"
        private const val KEY_STABILIZATION = "stabilization"
        private const val KEY_NOISE_REDUCTION = "noise_reduction"
        private const val KEY_SHARPENING = "sharpening"
        private const val KEY_ISO_MODE = "iso_mode"
        private const val KEY_ISO_VALUE = "iso_value"
        private const val KEY_SHUTTER_MODE = "shutter_mode"
        private const val KEY_SHUTTER_NS = "shutter_ns"
        private const val KEY_SHUTTER_ANGLE = "shutter_angle"
        private const val KEY_FOCUS_MODE = "focus_mode"
        private const val KEY_FOCUS_DISTANCE = "focus_distance"
        private const val KEY_WB_MODE = "wb_mode"
        private const val KEY_WB_TEMP = "wb_temp"
        private const val KEY_EV = "ev"
        private const val KEY_AUDIO = "audio"
        private const val KEY_AUDIO_BITRATE = "audio_bitrate"
        private const val KEY_AUDIO_SR = "audio_sr"
        private const val KEY_HISTOGRAM = "histogram"
        private const val KEY_FOCUS_BRACKET = "focus_bracket"
        private const val KEY_FOCUS_BRACKET_STEPS = "focus_bracket_steps"
        private const val KEY_FOCUS_BRACKET_RANGE = "focus_bracket_range"
        private const val KEY_GRID = "grid"
        private const val KEY_ZEBRA = "zebra"
        private const val KEY_PEAKING = "peaking"
    }
}
