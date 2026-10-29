package com.brekwol.daybreak.engine

import android.graphics.Bitmap

/**
 * JNI entry points into the C++ engine (native/src/jni/engine_jni.cpp).
 * Every call is blocking: use [DaybreakEngine], which runs them off the main thread.
 */
internal object NativeEngine {
    init {
        System.loadLibrary("daybreak_engine")
    }

    external fun encodeBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        quality: Float,
        effort: Int,
        lossless: Boolean,
        exifSourceJpeg: ByteArray?,
    ): ByteArray

    external fun decodeToBitmap(data: ByteArray, maxDimension: Int): Bitmap

    external fun readSize(data: ByteArray): IntArray

    external fun libjxlVersion(): String

    external fun exifBlock(jpeg: ByteArray): ByteArray?

    /** Size of the edited image; see [EditRecipe.packParams] for the layout of [params]. */
    external fun editOutputSize(width: Int, height: Int, params: FloatArray, applyCrop: Boolean): IntArray

    /** Renders [source] through an edit into [destination]; both ARGB_8888. */
    external fun renderEdit(
        source: Bitmap,
        destination: Bitmap,
        params: FloatArray,
        curves: FloatArray,
        fullLongSide: Int,
        applyCrop: Boolean,
    )
}
