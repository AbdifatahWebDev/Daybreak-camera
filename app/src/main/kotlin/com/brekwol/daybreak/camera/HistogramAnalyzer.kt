package com.brekwol.daybreak.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.sqrt

/**
 * Builds a 64-bin brightness histogram from the luma plane of small preview
 * frames, about ten times a second. Bins are square-root scaled so the tallest is 1.
 */
class HistogramAnalyzer(
    private val onHistogram: (FloatArray) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastMs = 0L
    private val bins = IntArray(BINS)

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastMs < INTERVAL_MS) return
            lastMs = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val limit = buffer.limit()
            bins.fill(0)
            var y = 0
            while (y < image.height) {
                val row = y * rowStride
                var x = 0
                while (x < image.width) {
                    val index = row + x * pixelStride
                    if (index >= limit) break
                    bins[(buffer.get(index).toInt() and 0xFF) shr 2]++
                    x += SAMPLE_STEP
                }
                y += SAMPLE_STEP
            }
            // Square-root scale: a large black or white area would otherwise
            // flatten every other tone to nothing.
            var tallest = 1
            for (count in bins) if (count > tallest) tallest = count
            val top = sqrt(tallest.toFloat())
            onHistogram(FloatArray(BINS) { sqrt(bins[it].toFloat()) / top })
        } finally {
            image.close()
        }
    }

    private companion object {
        const val BINS = 64
        const val SAMPLE_STEP = 4
        const val INTERVAL_MS = 100L
    }
}
