package com.brekwol.daybreak.engine

import android.graphics.Bitmap

/**
 * How a merged burst is finished: [gain] brightens the whole photo (1 keeps the
 * frames' brightness); [under] and [over] are how many stops below and above it
 * the short and long virtual exposures sit. Bright areas come from the short one
 * and dark areas from the long one.
 */
data class BurstFinish(val gain: Float = 1f, val under: Float = 1f, val over: Float = 1f)

/**
 * Merges a burst of frames of one scene into one cleaner photo (Night and HDR).
 * Frames are added one at a time and aligned to the first, which is the moment
 * the photo shows. Every call blocks; use it off the main thread. Close it to
 * free its memory (about 8 bytes per pixel).
 */
class BurstMerge : AutoCloseable {
    private var handle: Long = NativeEngine.burstCreate()

    /** Frames merged so far. */
    val frames: Int
        @Synchronized get() = if (handle == 0L) 0 else NativeEngine.burstFrames(handle)

    /** Adds an ARGB_8888 frame; all frames must be the size of the first. */
    @Synchronized
    fun add(frame: Bitmap) {
        check(handle != 0L) { "The burst is closed" }
        require(frame.config == Bitmap.Config.ARGB_8888) { "Frames must be ARGB_8888" }
        NativeEngine.burstAdd(handle, frame)
    }

    /** The finished photo, the size of the frames. */
    @Synchronized
    fun finish(width: Int, height: Int, finish: BurstFinish): Bitmap {
        check(handle != 0L) { "The burst is closed" }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            NativeEngine.burstFinish(handle, out, finish.gain, finish.under, finish.over)
        } catch (error: Throwable) {
            out.recycle()
            throw error
        }
        return out
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeEngine.burstRelease(handle)
            handle = 0L
        }
    }

    companion object {
        /** The most frames one burst can merge. */
        const val MAX_FRAMES = 8
    }
}
