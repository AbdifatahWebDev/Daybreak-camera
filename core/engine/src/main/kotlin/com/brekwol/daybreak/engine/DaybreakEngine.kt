package com.brekwol.daybreak.engine

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JPEG XL settings chosen in the format sheet. */
data class EncodeSettings(
    /** 50..100. Ignored when [lossless] is true. 90 is visually lossless. */
    val quality: Int = 90,
    /** 1..10. 3 = Fast, 7 = Balanced, 9 = Smallest. */
    val effort: Int = 7,
    val lossless: Boolean = false,
)

/** Width and height as shown on screen, orientation already applied. */
data class ImageSize(val width: Int, val height: Int)

/**
 * Kotlin face of the C++ engine. Every call is a suspend function that runs on
 * [Dispatchers.Default]; errors from the engine arrive as exceptions.
 */
class DaybreakEngine {

    /**
     * Encodes an ARGB_8888 bitmap as JPEG XL.
     * [rotationDegrees] is CameraX's clockwise rotation needed to show the photo upright;
     * it is stored as the JPEG XL orientation, so no pixels are rotated.
     * [exifSourceJpeg] is the camera's JPEG, used only to copy its EXIF metadata.
     */
    suspend fun encodeJxl(
        bitmap: Bitmap,
        rotationDegrees: Int,
        settings: EncodeSettings,
        exifSourceJpeg: ByteArray? = null,
    ): ByteArray = withContext(Dispatchers.Default) {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be ARGB_8888" }
        NativeEngine.encodeBitmap(
            bitmap,
            rotationDegrees,
            settings.quality.coerceIn(0, 100).toFloat(),
            settings.effort.coerceIn(1, 10),
            settings.lossless,
            exifSourceJpeg,
        )
    }

    /** Decodes a JPEG XL file. [maxDimension] > 0 limits the longer side. */
    suspend fun decodeJxl(data: ByteArray, maxDimension: Int = 0): Bitmap =
        withContext(Dispatchers.Default) {
            NativeEngine.decodeToBitmap(data, maxDimension)
        }

    suspend fun readSize(data: ByteArray): ImageSize = withContext(Dispatchers.Default) {
        val size = NativeEngine.readSize(data)
        ImageSize(size[0], size[1])
    }

    val libjxlVersion: String by lazy { NativeEngine.libjxlVersion() }

    /**
     * The EXIF metadata of a camera JPEG as an `Exif\0\0` + TIFF block, with the
     * orientation reset to normal (for formats that store rotation separately, like HEIC).
     */
    fun exifBlock(jpeg: ByteArray): ByteArray? = NativeEngine.exifBlock(jpeg)

    /** Size of [width] x [height] after the edit's rotation, straighten and (optionally) crop. */
    fun editOutputSize(width: Int, height: Int, edit: EditRecipe, applyCrop: Boolean = true): ImageSize {
        val size = NativeEngine.editOutputSize(width, height, edit.packParams(), applyCrop)
        return ImageSize(size[0], size[1])
    }

    /**
     * Renders [source] through [edit] into a new bitmap whose long side is at most
     * [maxLongSide] (0 keeps the edited size). [fullLongSide] is the long side of the
     * full-resolution photo, so a preview made from a smaller [source] matches the export.
     */
    suspend fun renderEdit(
        source: Bitmap,
        edit: EditRecipe,
        maxLongSide: Int = 0,
        fullLongSide: Int = 0,
        applyCrop: Boolean = true,
    ): Bitmap = withContext(Dispatchers.Default) {
        require(source.config == Bitmap.Config.ARGB_8888) { "Bitmap must be ARGB_8888" }
        val out = editOutputSize(source.width, source.height, edit, applyCrop)
        var width = out.width
        var height = out.height
        if (maxLongSide > 0 && maxOf(width, height) > maxLongSide) {
            val scale = maxLongSide.toFloat() / maxOf(width, height)
            width = (width * scale).toInt().coerceAtLeast(1)
            height = (height * scale).toInt().coerceAtLeast(1)
        }
        val destination = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        renderEditInto(source, destination, edit, fullLongSide, applyCrop)
        destination
    }

    /** Like [renderEdit], into an existing bitmap (reused between preview frames). */
    fun renderEditInto(
        source: Bitmap,
        destination: Bitmap,
        edit: EditRecipe,
        fullLongSide: Int = 0,
        applyCrop: Boolean = true,
    ) {
        require(destination.config == Bitmap.Config.ARGB_8888 && destination.isMutable) {
            "Destination must be a mutable ARGB_8888 bitmap"
        }
        NativeEngine.renderEdit(source, destination, edit.packParams(), edit.packCurves(), fullLongSide, applyCrop)
    }
}
