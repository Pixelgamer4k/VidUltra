package com.pixelgamer4k.vidultra.core

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogWriter - Writes logs to file for debugging
 */
object LogWriter {
    private const val TAG = "LogWriter"
    private var logFile: File? = null
    private var fileWriter: FileWriter? = null
    
    fun init(context: Context) {
        try {
            val logDir = File(context.getExternalFilesDir(null), "logs")
            if (!logDir.exists()) logDir.mkdirs()
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            logFile = File(logDir, "encoder_log_$timestamp.txt")
            fileWriter = FileWriter(logFile, true)
            
            writeLog("========================================")
            writeLog("VidUltra Encoder Log")
            writeLog("Started: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            writeLog("========================================")
            
            Log.i(TAG, "Log file created: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create log file", e)
        }
    }
    
    fun writeLog(message: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            fileWriter?.apply {
                write("[$timestamp] $message\n")
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }
    
    fun close() {
        try {
            writeLog("========================================")
            writeLog("Log ended: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            writeLog("========================================")
            fileWriter?.close()
            fileWriter = null
            
            if (logFile != null) {
                Log.i(TAG, "Log file saved: ${logFile?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close log file", e)
        }
    }
}
