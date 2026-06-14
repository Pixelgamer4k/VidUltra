package com.vidultra.pro.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import com.vidultra.pro.camera.CameraSettings.VideoCodec
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wraps MediaRecorder and persists output to MediaStore.
 */
class VideoRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false

    val surface: Surface?
        get() = mediaRecorder?.surface

    /**
     * Prepare the recorder. Must be called after camera surface is available.
     */
    fun prepare(settings: CameraSettings, videoSize: Size): Surface? {
        release()
        outputFile = createOutputFile()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        mediaRecorder = recorder

        recorder.apply {
            if (settings.audioEnabled) {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

            // Use highest available profile that fits the resolution.
            // On many devices this enables HEVC / 10-bit / HDR automatically when paired with setProfile.
            val profile = selectCamcorderProfile(videoSize.width, videoSize.height)
            if (profile != null) {
                setProfile(profile)
                // Override bitrate if user requested higher / explicit
                setVideoEncodingBitRate(settings.videoBitrate)
            } else {
                setVideoEncoder(
                    when (settings.codec) {
                        VideoCodec.AVC -> MediaRecorder.VideoEncoder.H264
                        VideoCodec.HEVC -> MediaRecorder.VideoEncoder.HEVC
                    }
                )
                setVideoSize(videoSize.width, videoSize.height)
                setVideoFrameRate(settings.frameRate)
                setVideoEncodingBitRate(settings.videoBitrate)
                if (settings.audioEnabled) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioSamplingRate(settings.audioSampleRate)
                    setAudioEncodingBitRate(settings.audioBitrate)
                    setAudioChannels(2)
                }
            }

            setOutputFile(outputFile?.absolutePath)
            setOrientationHint(0) // TODO: read sensor orientation

            prepare()
        }
        return recorder.surface
    }

    fun start(): Boolean {
        if (isRecording) return false
        return try {
            mediaRecorder?.start()
            isRecording = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        return try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            isRecording = false
            insertIntoMediaStore(outputFile)
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            release()
        }
    }

    fun release() {
        try {
            mediaRecorder?.release()
        } catch (_: Exception) {
        }
        mediaRecorder = null
        isRecording = false
    }

    private fun createOutputFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "VIDULTRA_${timeStamp}.mp4"
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        return File(moviesDir, fileName)
    }

    private fun insertIntoMediaStore(file: File?) {
        file ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VidUltra")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().copyTo(out)
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(it, values, null, null)
            }
        } else {
            @Suppress("DEPRECATION")
            android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
    }

    @Suppress("DEPRECATION")
    private fun selectCamcorderProfile(width: Int, height: Int): android.media.CamcorderProfile? {
        return try {
            when {
                width >= 3840 && height >= 2160 ->
                    android.media.CamcorderProfile.get(android.media.CamcorderProfile.QUALITY_2160P)
                width >= 1920 && height >= 1080 ->
                    android.media.CamcorderProfile.get(android.media.CamcorderProfile.QUALITY_1080P)
                width >= 1280 && height >= 720 ->
                    android.media.CamcorderProfile.get(android.media.CamcorderProfile.QUALITY_720P)
                else ->
                    android.media.CamcorderProfile.get(android.media.CamcorderProfile.QUALITY_480P)
            }
        } catch (e: Exception) {
            null
        }
    }
}
