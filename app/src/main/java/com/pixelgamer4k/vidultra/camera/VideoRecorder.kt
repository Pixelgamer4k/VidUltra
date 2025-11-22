package com.pixelgamer4k.vidultra.camera

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import com.pixelgamer4k.vidultra.utils.FileManager
import java.io.File

/**
 * Handles all MediaRecorder lifecycle and file management
 * Separated from camera control for better error handling
 */
class VideoRecorder(private val context: Context) {
    
    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoFile: File? = null
    private var recordingSurface: Surface? = null
    
    var currentBitrate = VideoConfig.BitratePreset.MBPS_100
    var currentCodec = VideoConfig.Codec.H265
    
    /**
     * Prepares MediaRecorder and returns surface for camera session
     * Must be called BEFORE creating camera capture session
     */
    fun prepareRecording(width: Int, height: Int): Surface? {
        try {
            // Create output file
            currentVideoFile = FileManager.createVideoFile(
                context,
                width,
                height,
                currentCodec.fileExtension
            )
            
            Log.d(TAG, "Output file: ${currentVideoFile?.absolutePath}")
            
            // Release any existing recorder
            releaseRecorder()
            
            // Create and configure MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                // CRITICAL: Order matters!
                VideoConfig.setupMediaRecorder(
                    this,
                    currentVideoFile!!.absolutePath,
                    width,
                    height,
                    30, // FPS
                    currentBitrate.bitsPerSecond,
                    currentCodec
                )
                
                // Prepare BEFORE getting surface
                prepare()
                
                // Get surface AFTER prepare
                recordingSurface = surface
            }
            
            Log.d(TAG, "MediaRecorder prepared, surface: ${recordingSurface != null}")
            return recordingSurface
            
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing recording: ", e)
            e.printStackTrace()
            releaseRecorder()
            return null
        }
    }
    
    /**
     * Starts actual recording
     * Call this AFTER camera session is configured with recording surface
     */
    fun startRecording(): Boolean {
        return try {
            mediaRecorder?.start()
            Log.d(TAG, "Recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ", e)
            e.printStackTrace()
            false
        }
    }
    
    /**
     * Stops recording and saves file
     */
    fun stopRecording(): File? {
        return try {
            mediaRecorder?.stop()
            Log.d(TAG, "Recording stopped")
            
            // Add to MediaStore
            currentVideoFile?.let {
                FileManager.addVideoToMediaStore(context, it)
                Log.d(TAG, "Video saved: ${it.absolutePath}")
            }
            
            releaseRecorder()
            currentVideoFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording: ", e)
            e.printStackTrace()
            releaseRecorder()
            null
        }
    }
    
    /**
     * Releases MediaRecorder resources
     */
    fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing recorder: ", e)
        } finally {
            mediaRecorder = null
            recordingSurface = null
        }
    }
    
    fun isReady(): Boolean = recordingSurface != null
    
    companion object {
        private const val TAG = "VideoRecorder"
    }
}
