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
import androidx.appcompat.app.AlertDialog
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
    private lateinit var tvStats: TextView
    private lateinit var tvHardware: TextView
    
    // Performance Tuning
    // Standard size for DepthAnything is 518. 
    // To achieve 10 FPS, we might need to lower this to 224 or 252 if model supports dynamic shapes.
    private var INPUT_SIZE = 518 
    
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var currentModelName = "prior_depth_anything_vits.onnx"
    
    private lateinit var spinnerModel: android.widget.Spinner
    
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
                ivPrior.setImageBitmap(applyColorMap(priorBitmap!!))
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
        tvStats = findViewById(R.id.tvStats)
        tvHardware = findViewById(R.id.tvHardware)
        spinnerModel = findViewById(R.id.spinnerModel)
        
        findViewById<Button>(R.id.btnSelectRGB).setOnClickListener { pickRGB.launch("image/*") }
        findViewById<Button>(R.id.btnSelectPrior).setOnClickListener { pickPrior.launch("image/*") }
        findViewById<Button>(R.id.btnLoadSample).setOnClickListener { showSampleSelectionDialog() }
        
        findViewById<Button>(R.id.btnRun).setOnClickListener { 
            if (rgbBitmap != null && priorBitmap != null) {
                runInference()
            } else {
                Toast.makeText(this, "Please select both images", Toast.LENGTH_SHORT).show()
            }
        }
        
        findViewById<Button>(R.id.btnBenchmark).setOnClickListener {
            runBenchmark()
        }
        
        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val newModel = when(position) {
                    0 -> "prior_depth_anything_vits.onnx"
                    1 -> "prior_depth_anything_vits_fp16.onnx"
                    2 -> "prior_depth_anything_vits_int8.onnx"
                    else -> "prior_depth_anything_vits.onnx"
                }
                if (newModel != currentModelName) {
                    currentModelName = newModel
                    loadModel()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        
        // Initialize model in background
        loadModel()
    }
    
    private fun loadModel() {
        // Close previous session if exists
        try {
            ortSession?.close()
            ortSession = null
        } catch (e: Exception) {}

        lifecycleScope.launch(Dispatchers.Default) {
             try {
                 withContext(Dispatchers.Main) { 
                     tvStatus.text = "Loading ${currentModelName}..." 
                     findViewById<Button>(R.id.btnRun).isEnabled = false
                 }
                 
                 if (ortEnv == null) {
                     ortEnv = OrtEnvironment.getEnvironment()
                 }
                 
                 // Copy model to cache dir if not exists
                 val modelFile = java.io.File(cacheDir, currentModelName)
                 if (!modelFile.exists()) {
                     assets.open(currentModelName).use { input ->
                         java.io.FileOutputStream(modelFile).use { output ->
                             input.copyTo(output)
                         }
                     }
                 }
                 
                 val opts = OrtSession.SessionOptions()
                 opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                 opts.setIntraOpNumThreads(4)
                 
                 var nnapiEnabled = false
                 try {
                     // Try to enable NNAPI
                     opts.addNnapi()
                     Log.d("PriorDepth", "NNAPI enabled")
                     nnapiEnabled = true
                 } catch (e: Exception) {
                     Log.w("PriorDepth", "NNAPI not supported, falling back to CPU", e)
                 }
                 
                 ortSession = ortEnv?.createSession(modelFile.absolutePath, opts)
                 
                 withContext(Dispatchers.Main) {
                     tvStatus.text = "Model Loaded: $currentModelName"
                     findViewById<Button>(R.id.btnRun).isEnabled = true
                     tvHardware.text = if (nnapiEnabled) "Hardware: NNAPI (GPU/NPU)" else "Hardware: CPU + 4 Threads"
                 }
                 
             } catch (e: Exception) {
                 Log.e("PriorDepth", "Error loading model", e)
                 withContext(Dispatchers.Main) {
                     tvStatus.text = "Error loading model: ${e.message}"
                 }
             }
        }
    }
    
    private fun checkReady() {
        if (rgbBitmap != null && priorBitmap != null) {
            tvStatus.text = "Ready to run"
        }
    }

    private fun showSampleSelectionDialog() {
        val samples = try {
            assets.list("")?.filter { it.startsWith("sample-") }?.sorted()?.toTypedArray() ?: emptyArray()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error listing assets", e)
            emptyArray()
        }

        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples found", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Select Sample")
            .setItems(samples) { _, which ->
                loadSample(samples[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadSample(sampleName: String) {
        try {
            val files = assets.list(sampleName) ?: return
            
            // Check for NPY first, then image
            val rgbNpy = files.find { it.lowercase() == "rgb.npy" }
            val rgbImg = files.find { it.lowercase().startsWith("rgb.") && !it.endsWith(".npy") }
            
            val priorNpy = files.find { it.lowercase() == "gt_depth.npy" || it.lowercase() == "prior_depth.npy" }
            val priorImg = files.find { (it.lowercase().startsWith("prior_depth.") || it.lowercase().startsWith("gt_depth.")) && !it.endsWith(".npy") }

            // Load RGB
            if (rgbNpy != null) {
                assets.open("$sampleName/$rgbNpy").use { stream ->
                    rgbBitmap = NpyUtils.readNpyToBitmap(stream)
                    if (rgbBitmap != null) {
                        ivRGB.setImageBitmap(rgbBitmap)
                    } else {
                        Toast.makeText(this, "Failed to parse $rgbNpy", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (rgbImg != null) {
                assets.open("$sampleName/$rgbImg").use { stream ->
                    rgbBitmap = BitmapFactory.decodeStream(stream)
                    ivRGB.setImageBitmap(rgbBitmap)
                }
            } else {
                 Toast.makeText(this, "No RGB image in $sampleName", Toast.LENGTH_SHORT).show()
            }

            // Load Prior
            if (priorNpy != null) {
                 assets.open("$sampleName/$priorNpy").use { stream ->
                    priorBitmap = NpyUtils.readNpyToBitmap(stream)
                    if (priorBitmap != null) {
                        ivPrior.setImageBitmap(applyColorMap(priorBitmap!!))
                    } else {
                        Toast.makeText(this, "Failed to parse $priorNpy", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (priorImg != null) {
                assets.open("$sampleName/$priorImg").use { stream ->
                    priorBitmap = BitmapFactory.decodeStream(stream)
                    ivPrior.setImageBitmap(applyColorMap(priorBitmap!!))
                }
            } else {
                priorBitmap = null
                ivPrior.setImageDrawable(null)
                Toast.makeText(this, "No Prior image in $sampleName", Toast.LENGTH_SHORT).show()
            }
            
            checkReady()

        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading sample", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun runInference() {
        if (ortSession == null) {
            Toast.makeText(this, "Model not loaded", Toast.LENGTH_SHORT).show()
            return
        }

        tvStatus.text = "Processing..."
        tvStats.text = "Computing..."
        lifecycleScope.launch(Dispatchers.Default) {
             val startTime = System.currentTimeMillis()
             val runtime = Runtime.getRuntime()
             val startMem = runtime.totalMemory() - runtime.freeMemory()

            try {
                // Initialize result variables
                var outputBitmap: Bitmap? = null
                
                // Preprocess
                val (rgbTensor, priorTensor) = preProcess(ortEnv!!, rgbBitmap!!, priorBitmap!!)
                
                // Construct inputs
                val inputs = mutableMapOf<String, OnnxTensor>()
                val inputInfo = ortSession!!.inputInfo
                var rgbName = "rgb"
                var priorName = "prior_depth"
                
                inputInfo.forEach { (name, nodeInfo) ->
                    val info = nodeInfo.info as ai.onnxruntime.TensorInfo
                    val shape = info.shape 
                    // Log input shape for debug
                    Log.d("PriorDepth", "Input $name shape: ${shape.contentToString()}")
                    
                    if (shape[1] == 3L) {
                        rgbName = name
                    } else if (shape[1] == 1L) {
                        priorName = name
                    }
                }
                
                inputs[rgbName] = rgbTensor
                inputs[priorName] = priorTensor

                Log.d("PriorDepth", "Using inputs: $rgbName, $priorName")

                // Run
                val results = ortSession!!.run(inputs)
                
                // Postprocess
                val outputTensor = results[0] as OnnxTensor
                val rawOutput = postProcess(outputTensor)
                
                // Resize to original RGB dimensions
                outputBitmap = Bitmap.createScaledBitmap(rawOutput, rgbBitmap!!.width, rgbBitmap!!.height, true)
                
                val endTime = System.currentTimeMillis()
                val endMem = runtime.totalMemory() - runtime.freeMemory()
                val usedMem = (endMem - startMem) / 1024 / 1024

                withContext(Dispatchers.Main) {
                    ivResult.setImageBitmap(outputBitmap)
                    tvStatus.text = "Done"
                    tvStats.text = "Time: ${endTime - startTime}ms | Memory: ${usedMem}MB | Res: ${INPUT_SIZE}x${INPUT_SIZE}"
                }
                
                results.close()
                // Do NOT close session or env here, keep them alive
                
            } catch (t: Throwable) {
                Log.e("PriorDepth", "Error", t)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${t.message}"
                }
            }
        }
    }
    
    private fun preProcess(env: OrtEnvironment, rgb: Bitmap, prior: Bitmap): Pair<OnnxTensor, OnnxTensor> {
        val H = INPUT_SIZE
        val W = INPUT_SIZE
        
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
        
        // Prior Normalization
        val pixelsPrior = IntArray(H * W)
        scaledPrior.getPixels(pixelsPrior, 0, W, 0, 0, W, H)
        
        for (i in 0 until H * W) {
            val p = pixelsPrior[i]
            val r = ((p shr 16) and 0xff) / 255.0f
            priorFloatBuffer.put(i, r)
        }
        
        rgbFloatBuffer.rewind()
        priorFloatBuffer.rewind()
        
        val rgbTensor = OnnxTensor.createTensor(env, rgbFloatBuffer, longArrayOf(1, 3, H.toLong(), W.toLong()))
        val priorTensor = OnnxTensor.createTensor(env, priorFloatBuffer, longArrayOf(1, H.toLong(), W.toLong()))
        
        return Pair(rgbTensor, priorTensor)
    }
    
    private fun postProcess(output: OnnxTensor): Bitmap {
        val floatBuffer = output.floatBuffer
        val info = output.info as ai.onnxruntime.TensorInfo
        val shape = info.shape
        // Assuming [1, H, W]
        val H = shape[shape.size - 2].toInt()
        val W = shape[shape.size - 1].toInt()
        val size = H * W
        
        val pixels = IntArray(size)
        // Find min/max for visualization
        var minVal = Float.MAX_VALUE
        var maxVal = Float.MIN_VALUE
        val tempArr = FloatArray(size)
        // Check if buffer has enough data
        if (floatBuffer.remaining() < size) {
             Log.w("PriorDepth", "Output buffer size mismatch. Expected $size, got ${floatBuffer.remaining()}")
             return Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        }
        floatBuffer.get(tempArr)
        
        for (v in tempArr) {
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        
        val range = if (maxVal - minVal > 1e-6) maxVal - minVal else 1.0f
        
        for (i in 0 until size) {
            val v = tempArr[i]
            val norm = ((v - minVal) / range).coerceIn(0f, 1f)
            
            // Simple thermal/inferno-like colormap approximation
            val r: Int
            val g: Int
            val b: Int
            
            if (norm < 0.25f) {
                val t = norm / 0.25f
                r = 0
                g = (t * 255).toInt()
                b = 255
            } else if (norm < 0.5f) {
                val t = (norm - 0.25f) / 0.25f
                r = 0
                g = 255
                b = ((1 - t) * 255).toInt()
            } else if (norm < 0.75f) {
                val t = (norm - 0.5f) / 0.25f
                r = (t * 255).toInt()
                g = 255
                b = 0
            } else {
                val t = (norm - 0.75f) / 0.25f
                r = 255
                g = ((1 - t) * 255).toInt()
                b = 0
            }
            
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        return Bitmap.createBitmap(pixels, W, H, Bitmap.Config.ARGB_8888)
    }

    private fun applyColorMap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        
        var minVal = 255
        var maxVal = 0
        
        val grayValues = IntArray(pixels.size)
        
        for (i in pixels.indices) {
            val red = (pixels[i] shr 16) and 0xFF
            grayValues[i] = red
            if (red < minVal) minVal = red
            if (red > maxVal) maxVal = red
        }
        
        val range = if (maxVal - minVal > 0) maxVal - minVal else 1
        
        val newPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            var norm = ((grayValues[i] - minVal).toFloat() / range).coerceIn(0f, 1f)
            
            val r: Int
            val g: Int
            val b: Int
            
            if (norm < 0.25f) { 
                val t = norm / 0.25f
                r = 0
                g = (t * 255).toInt()
                b = 255
            } else if (norm < 0.5f) { 
                val t = (norm - 0.25f) / 0.25f
                r = 0
                g = 255
                b = ((1 - t) * 255).toInt()
            } else if (norm < 0.75f) { 
                val t = (norm - 0.5f) / 0.25f
                r = (t * 255).toInt()
                g = 255
                b = 0
            } else { 
                val t = (norm - 0.75f) / 0.25f
                r = 255
                g = ((1 - t) * 255).toInt()
                b = 0
            }
            
            newPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        
        return Bitmap.createBitmap(newPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private data class BenchmarkSample(val rgb: Bitmap, val prior: Bitmap)
    
    private fun runBenchmark() {
        if (ortSession == null) {
             Toast.makeText(this, "Model not loaded", Toast.LENGTH_SHORT).show()
             return
        }
        
        tvStatus.text = "Benchmarking..."
        findViewById<Button>(R.id.btnBenchmark).isEnabled = false
        findViewById<Button>(R.id.btnRun).isEnabled = false
        
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                // 1. Prepare samples
                withContext(Dispatchers.Main) { tvStatus.text = "Preparing samples..." }
                val samples = mutableListOf<BenchmarkSample>()
                val sampleNames = listOf("sample-1", "sample-2", "sample-3", "sample-4", "sample-5", "sample-6")
                
                for (name in sampleNames) {
                    val s = loadSampleInternal(name)
                    if (s != null) samples.add(s)
                }
                
                if (samples.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "No samples found for benchmark"
                        findViewById<Button>(R.id.btnBenchmark).isEnabled = true
                        findViewById<Button>(R.id.btnRun).isEnabled = true
                    }
                    return@launch
                }
                
                // Batch configuration
                val BATCH_SIZE = 4
                val totalItemsToProcess = 60
                val infiniteSamples = sequence { while(true) yieldAll(samples) }
                val sampleIterator = infiniteSamples.iterator()
                val batchesNeeded = (totalItemsToProcess + BATCH_SIZE - 1) / BATCH_SIZE
                
                val runtime = Runtime.getRuntime()
                runtime.gc()
                val startMem = runtime.totalMemory() - runtime.freeMemory()
                
                // Preheat
                // preProcess(ortEnv!!, samples[0].rgb, samples[0].prior)
                
                val startTime = System.currentTimeMillis()
                var processedCount = 0
                
                for (i in 0 until batchesNeeded) {
                    withContext(Dispatchers.Main) { 
                        tvStatus.text = "Benchmark: Batch ${i+1}/$batchesNeeded" 
                    }
                    
                    val batchSamples = (1..BATCH_SIZE).mapNotNull { if (sampleIterator.hasNext()) sampleIterator.next() else null }
                    if (batchSamples.isEmpty()) break
                    
                    val currentBatchSize = batchSamples.size
                    
                    // Preprocess
                    val rgbTensors = mutableListOf<OnnxTensor>()
                    val priorTensors = mutableListOf<OnnxTensor>()
                    
                    for (sample in batchSamples) {
                        val (r, p) = preProcess(ortEnv!!, sample.rgb, sample.prior)
                        rgbTensors.add(r)
                        priorTensors.add(p)
                    }
                    
                    // Run Sequentially (imitating batch throughput)
                    for (k in 0 until currentBatchSize) {
                         val rgbTensor = rgbTensors[k]
                         val priorTensor = priorTensors[k]
                         
                         val inputs = mutableMapOf<String, OnnxTensor>()
                          val inputInfo = ortSession!!.inputInfo
                          var rName = "rgb"
                          var pName = "prior_depth"
                          inputInfo.forEach { (name, nodeInfo) ->
                              val info = nodeInfo.info as ai.onnxruntime.TensorInfo
                              if (info.shape[1] == 3L) rName = name else pName = name
                          }
                          
                         inputs[rName] = rgbTensor
                         inputs[pName] = priorTensor
                         
                         val result = ortSession!!.run(inputs)
                         result[0].close()
                         result.close()
                         
                         rgbTensor.close()
                         priorTensor.close()
                    }
                    processedCount += currentBatchSize
                }
                
                val endTime = System.currentTimeMillis()
                val totalTimeMs = endTime - startTime
                val avgTimePerFrame = totalTimeMs.toDouble() / processedCount
                val fps = 1000.0 / avgTimePerFrame
                
                runtime.gc()
                val endMem = runtime.totalMemory() - runtime.freeMemory()
                val peakMemOffset = (endMem - startMem) / 1024 / 1024 
                
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Benchmark Complete"
                    tvStats.text = "FPS: %.2f | Avg: %.0fms".format(fps, avgTimePerFrame)
                    
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Benchmark Results")
                        .setMessage(
                            "Inferences: $processedCount\n" +
                            "Resolution: ${INPUT_SIZE}x${INPUT_SIZE}\n" +
                            "Threads: 4\n" + 
                            "Total Time: ${totalTimeMs}ms\n" +
                            "Avg Latency: %.2f ms\n".format(avgTimePerFrame) +
                            "FPS: %.2f\n".format(fps) +
                            "Memory Delta: ${peakMemOffset}MB"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                        
                    findViewById<Button>(R.id.btnBenchmark).isEnabled = true
                    findViewById<Button>(R.id.btnRun).isEnabled = true
                }
                
            } catch (e: Exception) {
                Log.e("Benchmark", "Error", e)
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Error: ${e.message}"
                    findViewById<Button>(R.id.btnBenchmark).isEnabled = true
                    findViewById<Button>(R.id.btnRun).isEnabled = true
                }
            }
        }
    }
    
    private fun loadSampleInternal(sampleName: String): BenchmarkSample? {
         try {
            val files = assets.list(sampleName) ?: return null
            val rgbNpy = files.find { it.lowercase() == "rgb.npy" }
            val rgbImg = files.find { it.lowercase().startsWith("rgb.") && !it.endsWith(".npy") }
            val priorNpy = files.find { it.lowercase() == "gt_depth.npy" || it.lowercase() == "prior_depth.npy" }
            val priorImg = files.find { (it.lowercase().startsWith("prior_depth.") || it.lowercase().startsWith("gt_depth.")) && !it.endsWith(".npy") }

            var rBitmap: Bitmap? = null
            var pBitmap: Bitmap? = null

            if (rgbNpy != null) {
                assets.open("$sampleName/$rgbNpy").use { rBitmap = NpyUtils.readNpyToBitmap(it) }
            } else if (rgbImg != null) {
                assets.open("$sampleName/$rgbImg").use { rBitmap = BitmapFactory.decodeStream(it) }
            }

            if (priorNpy != null) {
                 assets.open("$sampleName/$priorNpy").use { pBitmap = NpyUtils.readNpyToBitmap(it) }
            } else if (priorImg != null) {
                assets.open("$sampleName/$priorImg").use { pBitmap = BitmapFactory.decodeStream(it) }
            }
            
            if (rBitmap != null && pBitmap != null) {
                return BenchmarkSample(rBitmap!!, pBitmap!!)
            }
        } catch (e: Exception) {
            Log.e("Benchmark", "Error loading $sampleName", e)
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.e("PriorDepth", "Error closing ORT", e)
        }
    }
}
