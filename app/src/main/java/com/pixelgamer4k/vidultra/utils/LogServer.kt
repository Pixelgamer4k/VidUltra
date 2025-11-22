package com.pixelgamer4k.vidultra.utils

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Simple HTTP Server to serve logs on port 9000
 * Access via: http://<device-ip>:9000
 */
class LogServer {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val logs = ConcurrentLinkedQueue<String>()
    
    fun start() {
        if (isRunning) return
        isRunning = true
        
        thread {
            try {
                serverSocket = ServerSocket(9000)
                Log.d(TAG, "LogServer started on port 9000")
                
                while (isRunning) {
                    val client = serverSocket?.accept()
                    client?.let { handleClient(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "LogServer error: ", e)
            }
        }
    }
    
    fun log(msg: String) {
        logs.add(msg)
        // Keep last 1000 logs
        while (logs.size > 1000) {
            logs.poll()
        }
    }
    
    private fun handleClient(socket: Socket) {
        thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = PrintWriter(socket.getOutputStream(), true)
                
                // Read request (ignore content, just consume headers)
                var line = reader.readLine()
                while (line != null && line.isNotEmpty()) {
                    line = reader.readLine()
                }
                
                // Send response
                out.println("HTTP/1.1 200 OK")
                out.println("Content-Type: text/plain")
                out.println("Connection: close")
                out.println()
                
                out.println("=== VidUltra Debug Logs ===")
                for (log in logs) {
                    out.println(log)
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client: ", e)
            }
        }
    }
    
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        serverSocket = null
    }
    
    companion object {
        private const val TAG = "LogServer"
    }
}
