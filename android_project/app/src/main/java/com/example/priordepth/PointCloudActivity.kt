package com.example.priordepth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PointCloudActivity : AppCompatActivity() {

    private lateinit var glView: PointCloudSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        
        val rgbPath = intent.getStringExtra("RGB_PATH")
        val depthPath = intent.getStringExtra("DEPTH_PATH")
        
        if (rgbPath == null || depthPath == null) {
            finish()
            return
        }

        val rgbBitmap = BitmapFactory.decodeStream(FileInputStream(rgbPath))
        val depthBitmap = BitmapFactory.decodeStream(FileInputStream(depthPath))

        glView = PointCloudSurfaceView(this, rgbBitmap, depthBitmap)
        
        val frameLayout = android.widget.FrameLayout(this)
        frameLayout.addView(glView)
        
        val closeButton = android.widget.Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
            val params = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                setMargins(0, 50, 50, 0)
            }
            layoutParams = params
        }
        frameLayout.addView(closeButton)
        
        setContentView(frameLayout)
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }
}

class PointCloudSurfaceView(context: Context, rgb: Bitmap, depth: Bitmap) : GLSurfaceView(context) {

    private val renderer: PointCloudRenderer

    init {
        setEGLContextClientVersion(2)
        renderer = PointCloudRenderer(rgb, depth)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private val TOUCH_SCALE_FACTOR: Float = 180.0f / 320

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x: Float = e.x
        val y: Float = e.y

        when (e.action) {
            MotionEvent.ACTION_MOVE -> {
                var dx: Float = x - previousX
                var dy: Float = y - previousY

                // Reverse direction of rotation above the mid-line
                if (y > height / 2) {
                    dx = dx * -1
                }

                // Reverse direction of rotation to left of the mid-line
                if (x < width / 2) {
                    dy = dy * -1
                }

                renderer.angleX += dx * TOUCH_SCALE_FACTOR
                renderer.angleY += dy * TOUCH_SCALE_FACTOR
                
                requestRender()
            }
        }

        previousX = x
        previousY = y
        return true
    }
}

class PointCloudRenderer(private val rgbBitmap: Bitmap, private val depthBitmap: Bitmap) : GLSurfaceView.Renderer {

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        attribute vec4 vPosition; // x, y, u, v
        attribute vec2 vTexCoord;
        varying vec2 fTexCoord;
        uniform sampler2D uDepthTexture;
        
        void main() {
            fTexCoord = vTexCoord;
            
            // Sample depth from red channel (grayscale)
            float depth = texture2D(uDepthTexture, vTexCoord).r;
            
            // Depth Anything output is relative inverse depth (disparity).
            // Value 1.0 = Near, Value 0.0 = Far.
            // Camera is at Z=-3 looking at Z=0.
            // Z+ is away from camera.
            // We want Near(1.0) to have lower Z (closer to camera/negative).
            // We want Far(0.0) to have higher Z (near 0 or positive).
            
            float scale = 1.5;
            float z = -depth * scale; // 1.0 -> -1.5 (Close), 0.0 -> 0.0 (Far)
            
            vec4 pos = vPosition;
            pos.z = z;
            
            gl_Position = uMVPMatrix * pos;
            gl_PointSize = 4.0; // Point size
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec2 fTexCoord;
        uniform sampler2D uRGBTexture;
        
        void main() {
            gl_FragColor = texture2D(uRGBTexture, fTexCoord);
        }
    """.trimIndent()

    private var mProgram: Int = 0
    private var vertexBuffer: FloatBuffer? = null
    
    // Matrices
    private val vPMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16)
    
    // Handles
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var mVPMatrixHandle: Int = 0
    private var rgbTextureHandle: Int = 0
    private var depthTextureHandle: Int = 0
    
    // Interaction
    @Volatile
    var angleX: Float = 0f
    @Volatile
    var angleY: Float = 0f

    override fun onSurfaceCreated(unused: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        
        // Initialize shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        mProgram = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }
        
        // Generate Grid Mesh
        generateGrid(256, 256) // Use lower res grid for performance if needed
        
        // Load Textures
        rgbTextureHandle = loadTexture(rgbBitmap)
        depthTextureHandle = loadTexture(depthBitmap)
    }
    
    private fun generateGrid(rows: Int, cols: Int) {
        val vertices = FloatArray(rows * cols * 4) // x, y, u, v
        var index = 0
        
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                // Normalized coordinates -1.0 to 1.0 for position
                val x = (j.toFloat() / (cols - 1)) * 2.0f - 1.0f
                val y = (i.toFloat() / (rows - 1)) * 2.0f - 1.0f
                
                // UV coordinates 0.0 to 1.0 (Texture is typically flipped in GL)
                // Android Bitmap is Top-Left origin. OpenGL is Bottom-Left.
                val u = j.toFloat() / (cols - 1)
                val v = 1.0f - (i.toFloat() / (rows - 1)) 
                
                vertices[index++] = x
                vertices[index++] = -y // Flip Y for screen coord match
                vertices[index++] = u
                vertices[index++] = v
            }
        }
        
        vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
        }
    }

    override fun onSurfaceChanged(unused: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio: Float = width.toFloat() / height.toFloat()
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 1f, 10f)
    }

    override fun onDrawFrame(unused: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        // Camera Position
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -3f, 0f, 0f, 0f, 0f, 1.0f, 0.0f)
        
        // Calculate the projection and view transformation
        Matrix.multiplyMM(vPMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        // Rotation
        val scratch = FloatArray(16)
        // Combine rotation
        // For simplicity just Rotate around Y axis
        Matrix.rotateM(vPMatrix, 0, angleX, 0f, 1f, 0f)
        Matrix.rotateM(vPMatrix, 0, angleY, 1f, 0f, 0f)

        // Draw
        GLES20.glUseProgram(mProgram)

        // Get Handles
        positionHandle = GLES20.glGetAttribLocation(mProgram, "vPosition")
        texCoordHandle = GLES20.glGetAttribLocation(mProgram, "vTexCoord")
        mVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        val uRGB = GLES20.glGetUniformLocation(mProgram, "uRGBTexture")
        val uDepth = GLES20.glGetUniformLocation(mProgram, "uDepthTexture")
        
        // Pass Vertex Data
        // Stride: 4 floats * 4 bytes = 16 bytes
        val stride = 4 * 4
        
        vertexBuffer?.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        
        vertexBuffer?.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        
        // Pass Matrix
        GLES20.glUniformMatrix4fv(mVPMatrixHandle, 1, false, vPMatrix, 0)
        
        // Bind Textures
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rgbTextureHandle)
        GLES20.glUniform1i(uRGB, 0)
        
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTextureHandle)
        GLES20.glUniform1i(uDepth, 1)
        
        // Draw Points
        // Count = buffer size / 4 components
        val vertexCount = vertexBuffer!!.capacity() / 4
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
    
    private fun loadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)
        GLES20.glGenTextures(1, textureHandle, 0)
        
        if (textureHandle[0] != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
            
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        }
        return textureHandle[0]
    }
}
