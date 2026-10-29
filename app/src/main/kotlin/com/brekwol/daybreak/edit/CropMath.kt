package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.CropRect
import kotlin.math.max
import kotlin.math.min

/** The part of the crop frame a finger grabbed. */
enum class CropHandle { Move, Left, Top, Right, Bottom, TopLeft, TopRight, BottomLeft, BottomRight }

/** Crop shapes offered under the frame. Ratios are width / height. */
enum class CropShape(val label: String, val ratio: Float?) {
    Free("Free", null),
    Original("Original", null),
    Square("1:1", 1f),
    FourFive("4:5", 4f / 5f),
    ThreeTwo("3:2", 3f / 2f),
    SixteenNine("16:9", 16f / 9f),
}

/**
 * Crop rectangle geometry. Rectangles are fractions of the straightened frame;
 * [aspect] is that frame's width / height in pixels, and a ratio is the crop's
 * width / height in pixels.
 */
object CropMath {
    /** Smallest crop side, as a fraction of the frame. */
    const val MIN = 0.08f

    /**
     * [start] after its [handle] moved by ([dx], [dy]) fractions of the frame,
     * keeping [ratio] when it isn't null.
     */
    fun drag(start: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float?, aspect: Float): CropRect {
        if (handle == CropHandle.Move) {
            val left = (start.left + dx).coerceIn(0f, 1f - start.width)
            val top = (start.top + dy).coerceIn(0f, 1f - start.height)
            return CropRect(left, top, left + start.width, top + start.height)
        }
        return if (ratio == null) dragFree(start, handle, dx, dy) else dragLocked(start, handle, dx, dy, ratio, aspect)
    }

    private fun dragFree(start: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
        var (l, t, r, b) = listOf(start.left, start.top, start.right, start.bottom)
        if (handle in LEFT) l = (start.left + dx).coerceIn(0f, start.right - MIN)
        if (handle in RIGHT) r = (start.right + dx).coerceIn(start.left + MIN, 1f)
        if (handle in TOP) t = (start.top + dy).coerceIn(0f, start.bottom - MIN)
        if (handle in BOTTOM) b = (start.bottom + dy).coerceIn(start.top + MIN, 1f)
        return CropRect(l, t, r, b)
    }

    private fun dragLocked(start: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float, aspect: Float): CropRect {
        // Height as a fraction of the frame for a width w (also a fraction): h = w * k.
        val k = aspect / ratio
        val minW = max(MIN, MIN / k)
        val sx = when (handle) { in LEFT -> -1f; in RIGHT -> 1f; else -> 0f }
        val sy = when (handle) { in TOP -> -1f; in BOTTOM -> 1f; else -> 0f }

        if (sx != 0f && sy != 0f) {
            // A corner: the opposite corner stays put.
            val anchorX = if (sx > 0) start.left else start.right
            val anchorY = if (sy > 0) start.top else start.bottom
            val wantW = start.width + sx * dx
            val wantH = start.height + sy * dy
            val availW = if (sx > 0) 1f - anchorX else anchorX
            val availH = if (sy > 0) 1f - anchorY else anchorY
            val w = ((wantW + wantH / k) / 2f).coerceAtLeast(minW).let { min(it, min(availW, availH / k)) }
            val h = w * k
            val l = if (sx > 0) anchorX else anchorX - w
            val t = if (sy > 0) anchorY else anchorY - h
            return CropRect(l, t, l + w, t + h)
        }
        if (sx != 0f) {
            // A side edge: the opposite edge stays, the height follows around the middle.
            val anchorX = if (sx > 0) start.left else start.right
            val availW = if (sx > 0) 1f - anchorX else anchorX
            val w = (start.width + sx * dx).coerceAtLeast(minW).let { min(it, min(availW, 1f / k)) }
            val h = w * k
            val cy = (start.top + start.bottom) / 2f
            val t = (cy - h / 2f).coerceIn(0f, 1f - h)
            val l = if (sx > 0) anchorX else anchorX - w
            return CropRect(l, t, l + w, t + h)
        }
        // A top or bottom edge: the width follows around the middle.
        val anchorY = if (sy > 0) start.top else start.bottom
        val availH = if (sy > 0) 1f - anchorY else anchorY
        val h = (start.height + sy * dy).coerceAtLeast(minW * k).let { min(it, min(availH, k)) }
        val w = h / k
        val cx = (start.left + start.right) / 2f
        val l = (cx - w / 2f).coerceIn(0f, 1f - w)
        val t = if (sy > 0) anchorY else anchorY - h
        return CropRect(l, t, l + w, t + h)
    }

    /** The largest crop of [ratio] in the frame, centered where [current] is. */
    fun fit(current: CropRect, ratio: Float, aspect: Float): CropRect {
        val k = aspect / ratio
        val w = min(1f, 1f / k)
        val h = w * k
        val cx = (current.left + current.right) / 2f
        val cy = (current.top + current.bottom) / 2f
        val l = (cx - w / 2f).coerceIn(0f, 1f - w)
        val t = (cy - h / 2f).coerceIn(0f, 1f - h)
        return CropRect(l, t, l + w, t + h)
    }

    /** The crop's ratio in pixels, for a frame of [aspect]. */
    fun ratioOf(crop: CropRect, aspect: Float): Float = crop.width * aspect / crop.height

    /**
     * The ratio a shape gives on a frame of [aspect]: shapes other than square
     * turn to match the frame (2:3 on a tall photo), and [turned] flips that.
     */
    fun ratioFor(shape: CropShape, aspect: Float, turned: Boolean): Float? {
        val base = when (shape) {
            CropShape.Free -> return null
            CropShape.Original -> return aspect
            else -> shape.ratio ?: return null
        }
        if (base == 1f) return 1f
        val wide = max(base, 1f / base)
        val frameWide = aspect >= 1f
        val landscape = if (turned) !frameWide else frameWide
        return if (landscape) wide else 1f / wide
    }

    /** Label for a shape as it will be applied, e.g. "2:3" on a tall photo. */
    fun labelFor(shape: CropShape, aspect: Float, turned: Boolean): String {
        val ratio = ratioFor(shape, aspect, turned) ?: return shape.label
        if (shape == CropShape.Original || shape == CropShape.Square) return shape.label
        val parts = shape.label.split(':')
        val a = parts[0]
        val b = parts[1]
        val first = if ((a.toFloat() >= b.toFloat()) == (ratio >= 1f)) a else b
        val second = if (first == a) b else a
        return "$first:$second"
    }

    private val LEFT = setOf(CropHandle.Left, CropHandle.TopLeft, CropHandle.BottomLeft)
    private val RIGHT = setOf(CropHandle.Right, CropHandle.TopRight, CropHandle.BottomRight)
    private val TOP = setOf(CropHandle.Top, CropHandle.TopLeft, CropHandle.TopRight)
    private val BOTTOM = setOf(CropHandle.Bottom, CropHandle.BottomLeft, CropHandle.BottomRight)
}
