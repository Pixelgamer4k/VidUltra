package com.pixelgamer4k.vidultra.camera

import android.media.MediaRecorder

object VideoConfig {

    enum class Codec(val displayName: String, val encoderName: String, val fileExtension: String) {
        H264("H.264", "h264", "h264"),
        H265("HEVC (H.265)", "h265", "h265")
    }

    enum class BitratePreset(val displayName: String, val bitsPerSecond: Int) {
        MBPS_50("50 Mbps", 50_000_000),
        MBPS_100("100 Mbps", 100_000_000),
        MBPS_200("200 Mbps", 200_000_000),
        MBPS_400("400 Mbps", 400_000_000),
        MBPS_800("800 Mbps", 800_000_000)
    }

    fun setupMediaRecorder(
        recorder: MediaRecorder,
        outputFile: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        codec: Codec = Codec.H265
    ) {
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            
            // Set video encoder based on codec selection
            when (codec) {
                Codec.H265 -> setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
                Codec.H264 -> setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            }
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            
            setOutputFile(outputFile)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(width, height)
            
            // Audio settings
            setAudioEncodingBitRate(128_000) // 128 kbps
            setAudioSamplingRate(48000) // 48 kHz
        }
    }
    
    fun getBestResolution(): Pair<Int, Int> {
        // Default to 4K, but should query camera characteristics in production
        return Pair(3840, 2160)
    }
}
