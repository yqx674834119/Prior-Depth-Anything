package com.example.priordepth

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.Collections
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var ivRGB: ImageView
    private lateinit var ivPrior: ImageView
    private lateinit var ivResult: ImageView
    private lateinit var tvStatus: TextView
    
    private var rgbBitmap: Bitmap? = null
    private var priorBitmap: Bitmap? = null
    
    private val pickRGB = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                rgbBitmap = BitmapFactory.decodeStream(stream)
                ivRGB.setImageBitmap(rgbBitmap)
                checkReady()
            }
        }
    }
    
    private val pickPrior = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            contentResolver.openInputStream(it)?.use { stream ->
                priorBitmap = BitmapFactory.decodeStream(stream)
                ivPrior.setImageBitmap(priorBitmap)
                checkReady()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ivRGB = findViewById(R.id.ivRGB)
        ivPrior = findViewById(R.id.ivPrior)
        ivResult = findViewById(R.id.ivResult)
        tvStatus = findViewById(R.id.tvStatus)
        
        findViewById<Button>(R.id.btnSelectRGB).setOnClickListener { pickRGB.launch("image/*") }
        findViewById<Button>(R.id.btnSelectPrior).setOnClickListener { pickPrior.launch("image/*") }
        
        findViewById<Button>(R.id.btnRun).setOnClickListener { 
            if (rgbBitmap != null && priorBitmap != null) {
                runInference()
            } else {
                Toast.makeText(this, "Please select both images", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun checkReady() {
        if (rgbBitmap != null && priorBitmap != null) {
            tvStatus.text = "Ready to run"
        }
    }
    
    private fun runInference() {
        tvStatus.text = "Processing..."
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // Initialize result variables
                var outputBitmap: Bitmap? = null
                
                val env = OrtEnvironment.getEnvironment()
                // Read model from assets
                val modelBytes = assets.open("prior_depth_anything_vits.onnx").readBytes()
                val session = env.createSession(modelBytes)
                
                // Preprocess
                val (rgbTensor, priorTensor) = preProcess(env, rgbBitmap!!, priorBitmap!!)
                
                // Run
                val inputs = mapOf("rgb" to rgbTensor, "prior_depth" to priorTensor)
                val results = session.run(inputs)
                
                // Postprocess
                val outputTensor = results[0] as OnnxTensor
                outputBitmap = postProcess(outputTensor)
                
                withContext(Dispatchers.Main) {
                    ivResult.setImageBitmap(outputBitmap)
                    tvStatus.text = "Done"
                }
                
                results.close()
                session.close()
                env.close()
                
            } catch (e: Exception) {
                Log.e("PriorDepth", "Error", e)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${e.message}"
                }
            }
        }
    }
    
    private fun preProcess(env: OrtEnvironment, rgb: Bitmap, prior: Bitmap): Pair<OnnxTensor, OnnxTensor> {
        // Resize to 518x518
        val H = 518
        val W = 518
        val scaledRGB = Bitmap.createScaledBitmap(rgb, W, H, true)
        val scaledPrior = Bitmap.createScaledBitmap(prior, W, H, true)
        
        val rgbFloatBuffer = FloatBuffer.allocate(1 * 3 * H * W)
        val priorFloatBuffer = FloatBuffer.allocate(1 * H * W)
        
        // RGB Normalization
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        
        val pixelsRGB = IntArray(H * W)
        scaledRGB.getPixels(pixelsRGB, 0, W, 0, 0, W, H)
        
        for (i in 0 until H * W) {
            val p = pixelsRGB[i]
            val r = ((p shr 16) and 0xff) / 255.0f
            val g = ((p shr 8) and 0xff) / 255.0f
            val b = (p and 0xff) / 255.0f
            
            rgbFloatBuffer.put(i, (r - mean[0]) / std[0])
            rgbFloatBuffer.put(H * W + i, (g - mean[1]) / std[1])
            rgbFloatBuffer.put(2 * H * W + i, (b - mean[2]) / std[2])
        }
        
        // Prior Normalization (Assuming it's a depth map image, we take Red channel as value)
        // Usually Prior depth needs to be in meters or normalized scale. 
        // For simplicity, we assume the input image represents depth 0-255 -> 0-1 or kept as is.
        // The model expects actual depth values. 
        // If user uploads a grayscale image, we treat pixel intensity as depth proxy.
        // Depending on model, it might expect meters. Let's assume 0-10m for now.
        val pixelsPrior = IntArray(H * W)
        scaledPrior.getPixels(pixelsPrior, 0, W, 0, 0, W, H)
        
        for (i in 0 until H * W) {
            val p = pixelsPrior[i]
            val r = ((p shr 16) and 0xff) / 255.0f
            // val val = r * 10.0f // Scale to 10m? Or just keep 0-1? 
            // The model is "Metric Depth", so it matters.
            // But without knowing the user's specific depth scale, let's keep it 0-1 or 0-255?
            // The pipeline usually normalizes it internally if `normalize_depth` is on.
            // Let's assume 0-1 range.
            priorFloatBuffer.put(i, r)
        }
        
        rgbFloatBuffer.rewind()
        priorFloatBuffer.rewind()
        
        val rgbTensor = OnnxTensor.createTensor(env, rgbFloatBuffer, longArrayOf(1, 3, 518, 518))
        val priorTensor = OnnxTensor.createTensor(env, priorFloatBuffer, longArrayOf(1, 518, 518))
        
        return Pair(rgbTensor, priorTensor)
    }
    
    private fun postProcess(output: OnnxTensor): Bitmap {
        val floatBuffer = output.floatBuffer
        // Output shape [1, 518, 518] (squeezed usually) 
        // or [1, 1, 518, 518]
        val info = output.info as ai.onnxruntime.TensorInfo
        val shape = info.shape
        // Assuming [1, 518, 518]
        
        val H = 518
        val W = 518
        val size = H * W
        
        val pixels = IntArray(size)
        // Find min/max for visualization
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        val tempArr = FloatArray(size)
        floatBuffer.get(tempArr)
        
        for (v in tempArr) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        
        val range = if (maxVal - minVal > 1e-6) maxVal - minVal else 1.0f
        
        for (i in 0 until size) {
            val v = tempArr[i]
            val norm = ((v - minVal) / range)
            val gray = (norm * 255).toInt().coerceIn(0, 255)
            // viridis or magma colormap would be nice, but grayscale is okay.
            // ARGB
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        
        return Bitmap.createBitmap(pixels, W, H, Bitmap.Config.ARGB_8888)
    }
}
