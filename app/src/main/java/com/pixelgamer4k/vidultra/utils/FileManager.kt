package com.pixelgamer4k.vidultra.utils

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileManager {
    
    /**
     * Generates a filename in the format:
     * Friday_(Aug_15_2025)_11:06:28_PM_3840x2160_h265.mp4
     */
    fun generateVideoFilename(width: Int, height: Int, codecName: String): String {
        val calendar = Calendar.getInstance()
        
        // Day of week (e.g., "Friday")
        val dayOfWeek = SimpleDateFormat("EEEE", Locale.US).format(calendar.time)
        
        // Date in format (Month_DD_YYYY)
        val month = SimpleDateFormat("MMM", Locale.US).format(calendar.time)
        val day = SimpleDateFormat("dd", Locale.US).format(calendar.time)
        val year = SimpleDateFormat("yyyy", Locale.US).format(calendar.time)
        val datePart = "(${month}_${day}_${year})"
        
        // Time in format HH:MM:SS_AM/PM
        val hour = SimpleDateFormat("hh", Locale.US).format(calendar.time)
        val minute = SimpleDateFormat("mm", Locale.US).format(calendar.time)
        val second = SimpleDateFormat("ss", Locale.US).format(calendar.time)
        val amPm = SimpleDateFormat("a", Locale.US).format(calendar.time)
        val timePart = "${hour}:${minute}:${second}_${amPm}"
        
        // Resolution
        val resolution = "${width}x${height}"
        
        // Codec (e.g., h265, h264)
        val codec = codecName.lowercase()
        
        return "${dayOfWeek}_${datePart}_${timePart}_${resolution}_${codec}.mp4"
    }
    
    /**
     * Creates output file for video recording
     * For Android 10+, uses scoped storage
     * For older versions, uses legacy external storage
     */
    fun createVideoFile(context: Context, width: Int, height: Int, codecName: String): File {
        val filename = generateVideoFilename(width, height, codecName)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use app-specific directory
            val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            File(moviesDir, filename)
        } else {
            // Legacy - Use public Movies directory
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val vidUltraDir = File(moviesDir, "VidUltra")
            if (!vidUltraDir.exists()) {
                vidUltraDir.mkdirs()
            }
            File(vidUltraDir, filename)
        }
    }
    
    /**
     * Adds video to MediaStore for gallery visibility
     */
    fun addVideoToMediaStore(context: Context, videoFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidUltra")
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } else {
            // For older Android, scan the file
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, videoFile.absolutePath)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            }
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }
    }
}
