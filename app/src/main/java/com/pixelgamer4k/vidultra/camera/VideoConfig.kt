package com.pixelgamer4k.vidultra.camera

import android.media.MediaRecorder
import android.os.Build
import android.util.Log

object VideoConfig {
    private const val TAG = "VideoConfig"

    fun setupMediaRecorder(recorder: MediaRecorder, width: Int, height: Int, fps: Int, bitrate: Int) {
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            
            // HEVC (H.265)
            setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(fps)
            setVideoSize(width, height)
            
            // 10-bit HDR (Profile 2 for HEVC is Main 10)
            // This is device dependent and might crash if not supported.
            // In a real app, we should check capabilities first.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Try to set profile for 10-bit if possible, but MediaRecorder API for profiles is tricky.
                // Often better to use MediaCodec directly for full control, but MediaRecorder is requested for simplicity in this scope.
                // We will stick to standard HEVC for now which often defaults to 8-bit unless HDR is explicitly triggered via CaptureRequest.
            }
        }
    }
    
    fun getBestResolution(): Pair<Int, Int> {
        // Placeholder: In real app, query CameraCharacteristics
        return Pair(3840, 2160) // 4K
    }
}
