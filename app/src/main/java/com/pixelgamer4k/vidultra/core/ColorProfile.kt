package com.pixelgamer4k.vidultra.core

import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.TonemapCurve
import android.media.MediaFormat
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * ColorProfile - Combines Camera2 tone mapping with encoder color space
 * This is the key to proper Rec.2020 implementation
 */
data class ColorProfile(
    val id: Int,
    val name: String,
    val displayName: String,
    
    // Camera2 ISP settings
    val tonemapMode: Int,
    val tonemapCurve: TonemapCurve? = null,
    val tonemapGamma: Float? = null,
    val tonemapPresetCurve: Int? = null,
    
    // MediaFormat encoder settings
    val colorStandard: Int,
    val colorTransfer: Int,
    val colorRange: Int = MediaFormat.COLOR_RANGE_LIMITED,
    
    // Description
    val description: String = ""
)

object ColorProfiles {
    
    /**
     * Create HLG-approximation curve for HDR capture
     * Based on ARIB STD-B67 (simplified)
     */
    private fun createHLGCurve(): TonemapCurve {
        val points = mutableListOf<Float>()
        
        // Generate 32 control points
        for (i in 0..31) {
            val Lin = i / 31f
            
            // Simplified HLG OETF
            val Lout = when {
                Lin <= 1f / 12f -> sqrt(3f * Lin)
                else -> {
                    val a = 0.17883277f
                    val b = 0.28466892f
                    val c = 0.55991073f
                    a * ln(12f * Lin - b) + c
                }
            }
            
            points.add(Lin)
            points.add(Lout.coerceIn(0f, 1f))
        }
        
        val array = points.toFloatArray()
        return TonemapCurve(array, array, array)
    }
    
    /**
     * Available color profiles
     */
    val profiles = listOf(
        ColorProfile(
            id = 0,
            name = "FAST",
            displayName = "Fast",
            tonemapMode = CameraMetadata.TONEMAP_MODE_FAST,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            description = "Stock camera processing with BT.709"
        ),
        
        ColorProfile(
            id = 1,
            name = "HQ",
            displayName = "High Quality",
            tonemapMode = CameraMetadata.TONEMAP_MODE_HIGH_QUALITY,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            description = "High quality processing with BT.709"
        ),
        
        ColorProfile(
            id = 2,
            name = "FLAT",
            displayName = "Flat (Linear)",
            tonemapMode = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE,
            tonemapCurve = TonemapCurve(
                floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f),
                floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f),
                floatArrayOf(0.0f, 0.0f, 1.0f, 1.0f)
            ),
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_LINEAR,
            description = "Linear 1:1 curve for color grading"
        ),
        
        ColorProfile(
            id = 3,
            name = "GAMMA",
            displayName = "Gamma 2.2",
            tonemapMode = CameraMetadata.TONEMAP_MODE_GAMMA_VALUE,
            tonemapGamma = 2.2f,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            description = "Standard gamma 2.2 with BT.709"
        ),
        
        ColorProfile(
            id = 4,
            name = "REC709",
            displayName = "Rec.709",
            tonemapMode = CameraMetadata.TONEMAP_MODE_PRESET_CURVE,
            tonemapPresetCurve = CameraMetadata.TONEMAP_PRESET_CURVE_REC709,
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            description = "Standard HD video (Rec.709)"
        ),
        
        ColorProfile(
            id = 5,
            name = "REC2020",
            displayName = "Rec.2020 (HLG)",
            tonemapMode = CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE,
            tonemapCurve = createHLGCurve(),
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
            description = "Wide color gamut HDR (Rec.2020 + HLG)"
        )
    )
    
    fun getById(id: Int): ColorProfile = profiles.getOrNull(id) ?: profiles[3]
    
    fun getByName(name: String): ColorProfile = profiles.find { it.name == name } ?: profiles[3]
}
