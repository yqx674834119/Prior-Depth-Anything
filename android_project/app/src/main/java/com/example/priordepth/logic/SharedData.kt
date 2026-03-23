package com.example.priordepth.logic

import android.graphics.Bitmap

/**
 * Singleton to pass heavy data (Bitmap + IntArray) from ScanningScreen to ProcessingScreen
 * without exceeding Intent parcel size limits or needing complex ViewModels across NavGraph.
 */
object SharedData {
    var capturedRgbBitmap: Bitmap? = null
    var capturedDepthMm: IntArray? = null

    fun clear() {
        capturedRgbBitmap = null
        capturedDepthMm = null
    }
}
