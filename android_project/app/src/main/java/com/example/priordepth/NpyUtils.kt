package com.example.priordepth

import android.graphics.Bitmap
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays

object NpyUtils {

    data class NpyHeader(
        val shape: IntArray,
        val dataType: String, // e.g. "<f4", "|u1"
        val isFortranOrder: Boolean
    )

    fun readNpyToBitmap(inputStream: InputStream): Bitmap? {
        try {
            val bytes = inputStream.readBytes()
            val buffer = ByteBuffer.wrap(bytes)
            buffer.order(ByteOrder.LITTLE_ENDIAN)

            // 1. Magic string check: \x93NUMPY
            val magic = ByteArray(6)
            buffer.get(magic)
            if (!Arrays.equals(magic, byteArrayOf(0x93.toByte(), 'N'.toByte(), 'U'.toByte(), 'M'.toByte(), 'P'.toByte(), 'Y'.toByte()))) {
                Log.e("NpyUtils", "Invalid NPY magic string")
                return null
            }

            // 2. Version
            val major = buffer.get()
            val minor = buffer.get()

            // 3. Header length
            val headerLen = if (major.toInt() == 1) {
                buffer.short.toInt() and 0xFFFF
            } else {
                buffer.int
            }

            // 4. Header string
            val headerBytes = ByteArray(headerLen)
            buffer.get(headerBytes)
            val headerStr = String(headerBytes, StandardCharsets.US_ASCII)
            val header = parseHeader(headerStr)

            // 5. Data
            // Handle specific cases: 3D (H,W,3) RGB or 2D (H,W) Depth/Gray
            // Assume <f4 (float32) or |u1 (uint8)

            val shape = header.shape
            if (shape.isEmpty()) return null
            
            val h: Int
            val w: Int
            val channels: Int
            
            if (shape.size == 2) {
                h = shape[0]
                w = shape[1]
                channels = 1
            } else if (shape.size == 3) {
                if (shape[0] == 3) { // (3, H, W)
                    channels = 3
                    h = shape[1]
                    w = shape[2]
                } else { // (H, W, 3)
                    h = shape[0]
                    w = shape[1]
                    channels = 3
                }
            } else {
                 Log.e("NpyUtils", "Unsupported shape: ${shape.contentToString()}")
                 return null
            }
            
            val totalPixels = h * w
            val pixels = IntArray(totalPixels)
            
            if (channels == 3) {
                // RGB
                // Assume float 0..1 or uint8 0..255
                // We need to read R, G, B
                // If shape is (H, W, 3), interleaved
                // If shape is (3, H, W), planar
                
                // Simplified reader for standard layout (H, W, 3) usually for images in numpy
                // But pytorch often saves (3, H, W) or (1, 3, H, W).
                // Let's assume (H, W, 3) for "rgb.npy" loaded from disk as image often is.
                // Or check sample-5 logic if we knew it. Let's assume standard image layout.
                
                // If float32
                if (header.dataType.contains("f4")) {
                     for (i in 0 until totalPixels) {
                        val r = buffer.float
                        val g = buffer.float
                        val b = buffer.float
                        
                        val rInt = (r * 255).toInt().coerceIn(0, 255)
                        val gInt = (g * 255).toInt().coerceIn(0, 255)
                        val bInt = (b * 255).toInt().coerceIn(0, 255)
                        
                        pixels[i] = (0xFF shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
                     }
                } else if (header.dataType.contains("u1")) {
                     for (i in 0 until totalPixels) {
                        val r = buffer.get().toInt() and 0xFF
                        val g = buffer.get().toInt() and 0xFF
                        val b = buffer.get().toInt() and 0xFF
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                     }
                }
                 
            } else {
                // Single channel (Depth)
                // Normalize min-max for visualization
                // First pass read to array
                 if (header.dataType.contains("f4")) {
                     val data = FloatArray(totalPixels)
                     var minVal = Float.MAX_VALUE
                     var maxVal = Float.MIN_VALUE
                     
                     for (i in 0 until totalPixels) {
                         val v = buffer.float
                         data[i] = v
                         if (v < minVal) minVal = v
                         if (v > maxVal) maxVal = v
                     }
                     
                     val range = if (maxVal - minVal > 1e-6) maxVal - minVal else 1f
                     // Apply grayscale or colormap? 
                     // The requirement is to allow loading it. We will return Bitmap.
                     // It might be displayed as "Prior".
                     // We can return ARGB. The calling code might re-colorize it, but better ensure it's viewable here.
                     // Return Grayscale here, allow calling code to rely on applyColorMap?
                     // Or just return raw values? Bitmap must be int colors.
                     
                     for (i in 0 until totalPixels) {
                         val norm = (data[i] - minVal) / range
                         val gray = (norm * 255).toInt().coerceIn(0, 255)
                         pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                     }
                     
                 } else {
                     // Assume uint8 or similar
                     // ...
                 }
            }

            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            
        } catch (e: Exception) {
            Log.e("NpyUtils", "Error reading NPY", e)
            return null
        }
    }

    private fun parseHeader(header: String): NpyHeader {
        // Example header: {'descr': '<f4', 'fortran_order': False, 'shape': (518, 518, 3), }
        val dict = header.trim()
        
        // Extract dtype
        val dtypeStart = dict.indexOf("'descr': '") + 10
        val dtypeEnd = dict.indexOf("'", dtypeStart)
        val dtype = dict.substring(dtypeStart, dtypeEnd)
        
        // Extract fortran_order
        val fortranStart = dict.indexOf("'fortran_order': ") + 17
        val fortranEnd = dict.indexOf(",", fortranStart)
        val fortran = dict.substring(fortranStart, fortranEnd).trim().toBoolean()
        
        // Extract shape
        val shapeStart = dict.indexOf("'shape': (") + 10
        val shapeEnd = dict.indexOf(")", shapeStart)
        val shapeStr = dict.substring(shapeStart, shapeEnd)
        
        val shape = if (shapeStr.isBlank()) {
            intArrayOf() // Scalar?
        } else {
            shapeStr.split(",").filter { it.isNotBlank() }.map { it.trim().toInt() }.toIntArray()
        }
        
        return NpyHeader(shape, dtype, fortran)
    }
}
