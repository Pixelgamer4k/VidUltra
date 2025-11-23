package com.pixelgamer4k.vidultra.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
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
            
            // ===== COLOR SPACE CONFIGURATION (THE KEY PART!) =====
            val colorStdName = getColorStandardName(colorStandard)
            val colorTrfName = getColorTransferName(colorTransfer)
            
            Log.i(TAG, "Attempting to set color space:")
            Log.i(TAG, "  - COLOR_STANDARD: $colorStandard ($colorStdName)")
            Log.i(TAG, "  - COLOR_TRANSFER: $colorTransfer ($colorTrfName)")
            Log.i(TAG, "  - COLOR_RANGE: $colorRange")
            
            LogWriter.writeLog("========================================")
            LogWriter.writeLog("ENCODER CONFIGURATION")
            LogWriter.writeLog("Resolution: ${width}x${height} @ ${frameRate}fps")
            LogWriter.writeLog("Bit Depth: $bitDepth-bit")
            LogWriter.writeLog("Requested Color Space:")
            LogWriter.writeLog("  - COLOR_STANDARD: $colorStandard ($colorStdName)")
            LogWriter.writeLog("  - COLOR_TRANSFER: $colorTransfer ($colorTrfName)")
            LogWriter.writeLog("  - COLOR_RANGE: $colorRange")
            
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, colorTransfer)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, colorRange)
            
            // 10-bit configuration
            if (bitDepth == 10) {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
                LogWriter.writeLog("Profile: HEVC Main 10")
            } else {
                LogWriter.writeLog("Profile: HEVC Main")
            }
            
            Log.i(TAG, "Encoder Format (before configure): $format")
            LogWriter.writeLog("========================================")
            
            // Create and configure codec
            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            // Get input surface
            inputSurface = mediaCodec?.createInputSurface()
            
            // Create muxer
            mediaMuxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            Log.i(TAG, "VideoEncoder prepared: ${width}x${height} @ ${frameRate}fps, ${bitDepth}-bit")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare encoder", e)
            release()
            throw e
        }
    }
    
    private fun getColorStandardName(value: Int): String = when (value) {
        1 -> "BT709"
        2 -> "BT601_625"
        4 -> "BT601_525"
        6 -> "BT2020"
        else -> "Unknown($value)"
    }
    
    private fun getColorTransferName(value: Int): String = when (value) {
        1 -> "LINEAR"
        2 -> "SRGB"
        3 -> "SDR_VIDEO"
        6 -> "HLG"
        7 -> "ST2084 (PQ)"
        else -> "Unknown($value)"
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
            
            LogWriter.writeLog("Encoder stopped")
            LogWriter.close()
            
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
                    Log.i(TAG, "===== OUTPUT FORMAT CHANGED =====")
                    Log.i(TAG, "Output format: $newFormat")
                    
                    // Check if color space was applied
                    try {
                        val outColorStandard = newFormat?.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                        val outColorTransfer = newFormat?.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        val outColorRange = newFormat?.getInteger(MediaFormat.KEY_COLOR_RANGE)
                        
                        val outStdName = getColorStandardName(outColorStandard ?: 0)
                        val outTrfName = getColorTransferName(outColorTransfer ?: 0)
                        
                        Log.i(TAG, "===== ACTUAL ENCODER OUTPUT COLOR SPACE =====")
                        Log.i(TAG, "  COLOR_STANDARD: $outColorStandard ($outStdName)")
                        Log.i(TAG, "  COLOR_TRANSFER: $outColorTransfer ($outTrfName)")
                        Log.i(TAG, "  COLOR_RANGE: $outColorRange")
                        Log.i(TAG, "============================================")
                        
                        LogWriter.writeLog("")
                        LogWriter.writeLog("ENCODER OUTPUT FORMAT")
                        LogWriter.writeLog("Actual Color Space:")
                        LogWriter.writeLog("  - COLOR_STANDARD: $outColorStandard ($outStdName)")
                        LogWriter.writeLog("  - COLOR_TRANSFER: $outColorTransfer ($outTrfName)")
                        LogWriter.writeLog("  - COLOR_RANGE: $outColorRange")
                        
                        if (outColorStandard != colorStandard || outColorTransfer != colorTransfer) {
                            Log.w(TAG, "⚠️ WARNING: Encoder IGNORED color space settings!")
                            Log.w(TAG, "  Requested: Standard=$colorStandard, Transfer=$colorTransfer")
                            Log.w(TAG, "  Got: Standard=$outColorStandard, Transfer=$outColorTransfer")
                            
                            LogWriter.writeLog("")
                            LogWriter.writeLog("⚠️ WARNING: COLOR SPACE MISMATCH!")
                            LogWriter.writeLog("  Requested: Standard=$colorStandard (${getColorStandardName(colorStandard)}), Transfer=$colorTransfer (${getColorTransferName(colorTransfer)})")
                            LogWriter.writeLog("  Got: Standard=$outColorStandard ($outStdName), Transfer=$outColorTransfer ($outTrfName)")
                            LogWriter.writeLog("  This means the encoder IGNORED the color space settings!")
                        } else {
                            Log.i(TAG, "✅ Color space settings APPLIED successfully!")
                            LogWriter.writeLog("")
                            LogWriter.writeLog("✅ SUCCESS: Color space settings APPLIED correctly!")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read color space from output format: ${e.message}")
                        LogWriter.writeLog("ERROR: Could not read color space from output: ${e.message}")
                    }
                    
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
