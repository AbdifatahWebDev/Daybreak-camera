package com.brekwol.daybreak.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Builds a 64-bin brightness histogram from the luma plane of small preview
 * frames, about ten times a second. Bins are square-root scaled so the tallest is 1.
 * It also meters the frame: how many stops the average brightness is from
 * mid-grey, which is what a normal exposure aims for, and what fraction of it
 * is blown out (which tells HDR Auto when to step in).
 */
class HistogramAnalyzer(
    private val onFrame: (histogram: FloatArray, meterEv: Float, clipped: Float) -> Unit,
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
            var linearSum = 0.0
            var samples = 0
            var clipped = 0
            var y = 0
            while (y < image.height) {
                val row = y * rowStride
                var x = 0
                while (x < image.width) {
                    val index = row + x * pixelStride
                    if (index >= limit) break
                    val luma = buffer.get(index).toInt() and 0xFF
                    bins[luma shr 2]++
                    if (luma >= CLIPPED) clipped++
                    linearSum += LINEAR[luma]
                    samples++
                    x += SAMPLE_STEP
                }
                y += SAMPLE_STEP
            }
            // Square-root scale: a large black or white area would otherwise
            // flatten every other tone to nothing.
            var tallest = 1
            for (count in bins) if (count > tallest) tallest = count
            val top = sqrt(tallest.toFloat())
            val mean = if (samples > 0) linearSum / samples else 0.0
            val meterEv = (ln(mean.coerceAtLeast(1e-6) / MID_GREY) / ln(2.0)).toFloat()
                .coerceIn(-METER_RANGE, METER_RANGE)
            val clippedFraction = if (samples > 0) clipped.toFloat() / samples else 0f
            onFrame(FloatArray(BINS) { sqrt(bins[it].toFloat()) / top }, meterEv, clippedFraction)
        } finally {
            image.close()
        }
    }

    private companion object {
        const val BINS = 64
        const val SAMPLE_STEP = 4
        const val INTERVAL_MS = 100L
        const val MID_GREY = 0.18
        const val METER_RANGE = 10f
        const val CLIPPED = 250

        /** sRGB-encoded luma to linear light. */
        val LINEAR = DoubleArray(256) { i ->
            val v = i / 255.0
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
    }
}
