package com.pixelgamer4k.vidultra.core

data class Resolution(
    val width: Int,
    val height: Int,
    val label: String,
    val fps: Int = 30
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
    val displayName: String get() = "$label ${width}x${height}"
    
    companion object {
        // Preset resolutions
        val PRESET_1080P_16_9 = Resolution(1920, 1080, "1080p", 30)
        val PRESET_4K_16_9 = Resolution(3840, 2160, "4K", 30)
        val PRESET_4K_20_9 = Resolution(4000, 2400, "4K 20:9", 30)
        val PRESET_6K = Resolution(6000, 3376, "6K", 30)
        val PRESET_8K = Resolution(7680, 4320, "8K", 30)
        
        val ALL_PRESETS = listOf(
            PRESET_1080P_16_9,
            PRESET_4K_16_9,
            PRESET_4K_20_9,
            PRESET_6K,
            PRESET_8K
        )
    }
}
