package com.brekwol.daybreak.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.brekwol.daybreak.engine.DaybreakEngine
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes any Daybreak photo to a bitmap, upright. JPEG XL goes through the
 * Daybreak engine; HEIC and DNG through Android's ImageDecoder.
 */
class PhotoDecoder(
    context: Context,
    private val engine: DaybreakEngine,
    private val photos: PhotoRepository,
) {
    private val resolver = context.applicationContext.contentResolver

    /** [maxDimension] > 0 limits the longer side. */
    suspend fun decode(photo: Photo, maxDimension: Int = 0): Bitmap = when (photo.format) {
        PhotoFormat.Jxl -> engine.decodeJxl(photos.readBytes(photo.uri), maxDimension)
        PhotoFormat.Heic, PhotoFormat.Dng -> decodeWithPlatform(photo, maxDimension)
    }

    private suspend fun decodeWithPlatform(photo: Photo, maxDimension: Int): Bitmap =
        withContext(Dispatchers.IO) {
            val source = ImageDecoder.createSource(resolver, photo.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val longest = max(width, height)
                if (maxDimension in 1 until longest) {
                    val scale = maxDimension.toFloat() / longest
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }
}
