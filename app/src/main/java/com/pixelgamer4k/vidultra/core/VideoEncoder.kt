package com.pixelgamer4k.vidultra.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * VideoEncoder - MediaCodec-based encoder with color space control
 * Replaces MediaRecorder to enable Rec.2020 and HDR metadata configuration
 */
class VideoEncoder(
    private val outputFile: File,
    private val width: Int = 3840,
    private val height: Int = 2160,
    private val frameRate: Int = 30,
    private val bitDepth: Int = 8
) {
    
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    private var isStarted = false
    
    var inputSurface: Surface? = null
        private set
    
    // Color space configuration
    var colorStandard: Int = MediaFormat.COLOR_STANDARD_BT709
    var colorTransfer: Int = MediaFormat.COLOR_TRANSFER_SDR_VIDEO
    var colorRange: Int = MediaFormat.COLOR_RANGE_LIMITED
    
    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME_TYPE = "video/hevc"
        private const val IFRAME_INTERVAL = 1 // seconds
    }
    
    /**
     * Initialize encoder with configured color space
     */
    fun prepare() {
        try {
            // Create MediaFormat
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            
            // Basic video parameters
            format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            
            // Bitrate configuration based on bit depth
            val bitrate = if (bitDepth == 10) 150_000_000 else 100_000_000
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            
            // Color space configuration (THE KEY PART!)
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange)
            
            // 10-bit configuration
            if (bitDepth == 10) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            }
            
            Log.i(TAG, "Encoder Format: $format")
            
            // Create and configure codec
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // Get input surface
            inputSurface = mediaCodec?.createInputSurface()
            
            // Create muxer
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            Log.i(TAG, "VideoEncoder prepared: ${width}x${height} @ ${frameRate}fps, ${bitDepth}-bit")
            Log.i(TAG, "Color: Standard=$colorStandard, Transfer=$colorTransfer, Range=$colorRange")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare encoder", e)
            release()
            throw e
        }
    }
    
    /**
     * Start encoding
     */
    fun start() {
        try {
            mediaCodec?.start()
            isStarted = true
            
            // Start encoder thread to drain output buffers
            startEncoderThread()
            
            Log.i(TAG, "Encoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoder", e)
            throw e
        }
    }
    
    /**
     * Stop encoding and finalize the file
     */
    fun stop() {
        try {
            if (!isStarted) return
            
            // Signal end of stream
            mediaCodec?.signalEndOfInputStream()
            
            // Wait for encoder thread to finish draining
            Thread.sleep(500)
            
            // Stop codec
            mediaCodec?.stop()
            
            // Stop muxer
            mediaMuxer?.stop()
            
            isStarted = false
            Log.i(TAG, "Encoder stopped")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        }
    }
    
    /**
     * Release all resources
     */
    fun release() {
        try {
            inputSurface?.release()
            inputSurface = null
            
            mediaCodec?.release()
            mediaCodec = null
            
            mediaMuxer?.release()
            mediaMuxer = null
            
            Log.i(TAG, "Encoder released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing encoder", e)
        }
    }
    
    /**
     * Background thread to drain encoded data from codec
     */
    private fun startEncoderThread() {
        Thread {
            try {
                drainEncoder()
            } catch (e: Exception) {
                Log.e(TAG, "Encoder thread error", e)
            }
        }.start()
    }
    
    /**
     * Drain encoded frames from codec output buffers
     */
    private fun drainEncoder() {
        val bufferInfo = MediaCodec.BufferInfo()
        
        while (isStarted) {
            val outputBufferId = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10_000) ?: continue
            
            when {
                outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // First buffer - add track to muxer
                    val newFormat = mediaCodec?.outputFormat
                    Log.i(TAG, "Output format changed: $newFormat")
                    
                    if (trackIndex == -1) {
                        trackIndex = mediaMuxer?.addTrack(newFormat!!) ?: -1
                        mediaMuxer?.start()
                        Log.i(TAG, "Muxer started, track index: $trackIndex")
                    }
                }
                
                outputBufferId >= 0 -> {
                    val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferId)
                    
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        // Adjust buffer position
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        
                        // Write to muxer if track is ready
                        if (trackIndex >= 0) {
                            mediaMuxer?.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                    }
                    
                    // Release buffer
                    mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    
                    // Check for end of stream
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "End of stream reached")
                        break
                    }
                }
            }
        }
    }
}
