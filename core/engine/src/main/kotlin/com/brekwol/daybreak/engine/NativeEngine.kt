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
}
