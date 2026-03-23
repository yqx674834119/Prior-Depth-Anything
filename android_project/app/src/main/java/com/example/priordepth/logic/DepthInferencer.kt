package com.example.priordepth.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

object DepthInferencer {
    private const val TAG = "DepthInferencer"
    private const val MODEL_NAME = "prior_depth_anything_vits_fp16.onnx"
    // Note: ensure this matches the exported ONNX constraints.
    private const val INPUT_SIZE = 518

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /**
     * Initializes the ONNX environment and loads the model from assets.
     */
    fun init(context: Context) {
        if (ortSession != null) return
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            
            // We use fp16 so NNAPI might be beneficial, but let's stick to CPU/XNNPACK default for broad compat
            val options = OrtSession.SessionOptions().apply {
                // setIntraOpNumThreads(4)
            }
            
            val modelBytes = context.assets.open(MODEL_NAME).readBytes()
            ortSession = ortEnvironment?.createSession(modelBytes, options)
            Log.i(TAG, "ONNX Model loaded successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model", e)
        }
    }

    /**
     * Runs inference synchronously using the full PriorDepthAnything pipeline.
     * 
     * @param rgbBitmap The captured frame from the camera.
     * @param depthMm 64-element IntArray from the ESP32 ToF UDP stream.
     * @return Output predicted depth Bitmap scaled for visualization.
     */
    fun runInference(rgbBitmap: Bitmap, depthMm: IntArray): Bitmap? {
        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        try {
            // 1. Prepare RGB Tensor [1, 3, 518, 518]
            val resizedRgb = Bitmap.createScaledBitmap(rgbBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val rgbBuffer = allocateRgbBuffer(resizedRgb)
            val rgbShape = longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val rgbTensor = OnnxTensor.createTensor(env, rgbBuffer, rgbShape)

            // 2. Prepare Prior Depth Tensor [1, 518, 518]
            val priorBuffer = allocatePriorBuffer(depthMm)
            val priorShape = longArrayOf(1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            val priorTensor = OnnxTensor.createTensor(env, priorBuffer, priorShape)

            Log.d(TAG, "Starting ONNX Inference execution...")
            // 3. Execution (Assuming input names "rgb" and "prior_depth" from mobile_export.py)
            val inputs = mapOf("rgb" to rgbTensor, "prior_depth" to priorTensor)
            val result = session.run(inputs)

            // 4. Post-processing
            val outputTensor = result[0] as OnnxTensor
            // Output shape [1, 518, 518] based on export
            val outputFloatArray = (outputTensor.value as Array<Array<FloatArray>>)[0]
            
            return generateResultBitmap(outputFloatArray)
            
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed", e)
            return null
        }
    }

    /**
     * Extracts planar RGB (R, G, B) and normalizes to [0, 1].
     */
    private fun allocateRgbBuffer(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val floatBuffer = FloatBuffer.allocate(3 * width * height)
        val rOffset = 0
        val gOffset = width * height
        val bOffset = 2 * width * height

        for (i in pixels.indices) {
            val color = pixels[i]
            // Scale 0-255 to 0.0-1.0
            floatBuffer.put(rOffset + i, Color.red(color) / 255.0f)
            floatBuffer.put(gOffset + i, Color.green(color) / 255.0f)
            floatBuffer.put(bOffset + i, Color.blue(color) / 255.0f)
        }
        return floatBuffer
    }

    /**
     * Maps the 64 (8x8) distances to a sparse 518x518 FloatBuffer.
     * Expects input in millimeters, converts tightly to physical meters.
     */
    private fun allocatePriorBuffer(depthMm: IntArray): FloatBuffer {
        val totalPixels = INPUT_SIZE * INPUT_SIZE
        val buffer = FloatBuffer.allocate(totalPixels)
        // Initialize to 0.0f
        for (i in 0 until totalPixels) {
            buffer.put(0.0f)
        }

        val rows = 8
        val cols = 8
        val cellWidth = INPUT_SIZE / cols.toFloat()
        val cellHeight = INPUT_SIZE / rows.toFloat()

        for (i in 0 until 64) {
            val mm = depthMm.getOrElse(i) { 0 }
            if (mm <= 0 || mm > 9999) continue // Filter invalid

            val metricMeters = mm / 1000.0f

            val r = i / cols
            val c = i % cols

            // Find center of this 8x8 cell section
            val centerX = ((c + 0.5f) * cellWidth).toInt().coerceIn(0, INPUT_SIZE - 1)
            val centerY = ((r + 0.5f) * cellHeight).toInt().coerceIn(0, INPUT_SIZE - 1)

            val index = centerY * INPUT_SIZE + centerX
            buffer.put(index, metricMeters)
        }
        
        buffer.rewind()
        return buffer
    }

    /**
     * Converts a 518x518 output FloatArray into a normalized grayscale/viridis Bitmap.
     */
    private fun generateResultBitmap(floatArray2D: Array<FloatArray>): Bitmap {
        val height = floatArray2D.size
        val width = floatArray2D[0].size
        
        var minDrop = Float.MAX_VALUE
        var maxDrop = Float.MIN_VALUE

        // Calculate min/max for normalization
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = floatArray2D[y][x]
                if (v < minDrop) minDrop = v
                if (v > maxDrop) maxDrop = v
            }
        }

        val range = if (maxDrop > minDrop) maxDrop - minDrop else 1f
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = floatArray2D[y][x]
                // Inverse depth for visualization if needed, or straight linear scaling
                val norm = ((value - minDrop) / range).coerceIn(0f, 1f)
                val c = (norm * 255).toInt()
                
                // Representing depth in standard Grayscale
                pixels[y * width + x] = Color.argb(255, c, c, c)
            }
        }

        val outputBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        outputBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return outputBitmap
    }

    fun close() {
        ortSession?.close()
        ortEnvironment?.close()
        ortSession = null
        ortEnvironment = null
    }
}
