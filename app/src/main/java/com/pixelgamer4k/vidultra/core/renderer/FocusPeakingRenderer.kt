package com.pixelgamer4k.vidultra.core.renderer

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class FocusPeakingRenderer(
    private val onSurfaceTextureCreated: (SurfaceTexture) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    var requestRender: (() -> Unit)? = null

    private var surfaceTexture: SurfaceTexture? = null
    private var textureId: Int = 0
    private var programId: Int = 0
    
    // Uniform locations
    private var uMVPMatrixHandle: Int = 0
    private var uSTMatrixHandle: Int = 0
    private var uTextureHandle: Int = 0
    private var uStepSizeHandle: Int = 0
    private var uEnablePeakingHandle: Int = 0
    
    // Matrices
    private val mMVPMatrix = FloatArray(16)
    private val mSTMatrix = FloatArray(16)
    
    // Buffers
    private val triangleVertices: FloatBuffer
    
    // State
    private var updateSurface = false
    private var width = 0
    private var height = 0
    var isPeakingEnabled = false

    init {
        val triangleVerticesData = floatArrayOf(
            // X, Y, Z, U, V
            -1.0f, -1.0f, 0f, 0f, 0f,
            1.0f, -1.0f, 0f, 1f, 0f,
            -1.0f,  1.0f, 0f, 0f, 1f,
            1.0f,  1.0f, 0f, 1f, 1f
        )

        triangleVertices = ByteBuffer.allocateDirect(triangleVerticesData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        triangleVertices.put(triangleVerticesData).position(0)

        Matrix.setIdentityM(mSTMatrix, 0)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        textureId = createTexture()
        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        
        onSurfaceTextureCreated(surfaceTexture!!)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(programId))
            GLES20.glDeleteProgram(programId)
            programId = 0
        }

        uMVPMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        uTextureHandle = GLES20.glGetUniformLocation(programId, "sTexture")
        uStepSizeHandle = GLES20.glGetUniformLocation(programId, "uStepSize")
        uEnablePeakingHandle = GLES20.glGetUniformLocation(programId, "uEnablePeaking")
        
        val aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        val aTextureCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        
        triangleVertices.position(TRIANGLE_VERTICES_DATA_POS_OFFSET)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, TRIANGLE_VERTICES_DATA_STRIDE_BYTES, triangleVertices)
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(this) {
            if (updateSurface) {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(mSTMatrix)
                updateSurface = false
            }
        }

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, mSTMatrix, 0)
        GLES20.glUniform1i(uTextureHandle, 0)
        
        // Pass step size for Sobel (1/width, 1/height)
        GLES20.glUniform2f(uStepSizeHandle, 1.0f / width.toFloat(), 1.0f / height.toFloat())
        
        // Toggle peaking
        GLES20.glUniform1i(uEnablePeakingHandle, if (isPeakingEnabled) 1 else 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glFinish()
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(this) {
            updateSurface = true
        }
        requestRender?.invoke()
    }
    
    fun setTransform(matrix: FloatArray) {
        System.arraycopy(matrix, 0, mMVPMatrix, 0, 16)
    }

    private fun createTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        return textureId
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        var shader = GLES20.glCreateShader(shaderType)
        if (shader != 0) {
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Could not compile shader $shaderType:")
                Log.e(TAG, GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                shader = 0
            }
        }
        return shader
    }

    companion object {
        private const val TAG = "FocusPeakingRenderer"
        private const val FLOAT_SIZE_BYTES = 4
        private const val TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES
        private const val TRIANGLE_VERTICES_DATA_POS_OFFSET = 3

        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uStepSize;
            uniform int uEnablePeaking;

            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                
                if (uEnablePeaking == 1) {
                    // Sobel Operator
                    float dx = uStepSize.x;
                    float dy = uStepSize.y;
                    
                    // Sample surrounding pixels (luminance only)
                    // Using standard Rec.709 luma coefficients
                    const vec3 W = vec3(0.2125, 0.7154, 0.0721);
                    
                    float tl = dot(texture2D(sTexture, vTextureCoord + vec2(-dx, -dy)).rgb, W);
                    float t  = dot(texture2D(sTexture, vTextureCoord + vec2(0.0, -dy)).rgb, W);
                    float tr = dot(texture2D(sTexture, vTextureCoord + vec2(dx, -dy)).rgb, W);
                    float l  = dot(texture2D(sTexture, vTextureCoord + vec2(-dx, 0.0)).rgb, W);
                    float r  = dot(texture2D(sTexture, vTextureCoord + vec2(dx, 0.0)).rgb, W);
                    float bl = dot(texture2D(sTexture, vTextureCoord + vec2(-dx, dy)).rgb, W);
                    float b  = dot(texture2D(sTexture, vTextureCoord + vec2(0.0, dy)).rgb, W);
                    float br = dot(texture2D(sTexture, vTextureCoord + vec2(dx, dy)).rgb, W);
                    
                    float x = tl + 2.0*l + bl - tr - 2.0*r - br;
                    float y = tl + 2.0*t + tr - bl - 2.0*b - br;
                    
                    float edge = sqrt(x*x + y*y);
                    
                    // Thresholding
                    if (edge > 0.2) {
                        // Green highlight for edges
                        gl_FragColor = vec4(0.0, 1.0, 0.0, 1.0);
                    } else {
                        gl_FragColor = color;
                    }
                } else {
                    gl_FragColor = color;
                }
            }
        """
    }
}
