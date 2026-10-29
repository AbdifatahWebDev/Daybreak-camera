package com.brekwol.daybreak.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Formats photos can be converted to for other apps. They never enter the photo library. */
enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    Jpeg("jpg", "image/jpeg", "JPEG"),
    Png("png", "image/png", "PNG"),
}

/**
 * Makes JPEG (or PNG) copies of photos for other apps (Share, the system file
 * picker, "choose a photo" requests). Copies live in the app cache, never in the
 * photo library, and are reused while the photo is unchanged. Copies carry no
 * EXIF, so no camera or location details leave the phone with them.
 */
class JpegExporter(context: Context, private val decoder: PhotoDecoder) {
    private val dir = File(context.cacheDir, "export")
    private val lock = Mutex()

    /** The cached copy of [photo] if one was already made, otherwise null. */
    fun existing(photo: Photo, format: ExportFormat = ExportFormat.Jpeg, maxLongSide: Int = 0): File? =
        target(photo, format, maxLongSide).takeIf { it.isFile && it.length() > 0 }

    /**
     * A copy of [photo] named like the photo (DB_….jpg). [maxLongSide] > 0 scales
     * it down so its longer side is at most that many pixels.
     */
    suspend fun export(photo: Photo, format: ExportFormat = ExportFormat.Jpeg, maxLongSide: Int = 0): File =
        lock.withLock {
            withContext(Dispatchers.IO) {
                existing(photo, format, maxLongSide)?.let { return@withContext it }
                removeOld()
                val file = target(photo, format, maxLongSide)
                file.parentFile?.mkdirs()
                val decoded = try {
                    decoder.decode(photo, maxDimension = maxLongSide)
                } catch (tooBig: OutOfMemoryError) {
                    decoder.decode(photo, maxDimension = FALLBACK_DIMENSION)
                }
                val bitmap = fitted(decoded, maxLongSide)
                val part = File(file.parentFile, file.name + ".part")
                try {
                    part.outputStream().use { out ->
                        val compress = when (format) {
                            ExportFormat.Jpeg -> Bitmap.CompressFormat.JPEG
                            ExportFormat.Png -> Bitmap.CompressFormat.PNG
                        }
                        if (!bitmap.compress(compress, QUALITY, out)) {
                            throw IOException("Could not make a ${format.label} copy")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
                if (!part.renameTo(file)) throw IOException("Could not save the ${format.label} copy")
                file
            }
        }

    /** Decoders may return a little more than asked; this makes the size exact. */
    private fun fitted(bitmap: Bitmap, maxLongSide: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (maxLongSide <= 0 || long <= maxLongSide) return bitmap
        val scale = maxLongSide.toFloat() / long
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    // One folder per photo version and size keeps the friendly file name and changes when the photo does.
    private fun target(photo: Photo, format: ExportFormat, maxLongSide: Int): File {
        val size = if (maxLongSide > 0) "_$maxLongSide" else ""
        return File(
            File(dir, "${photo.format.extension}_${photo.id}_${photo.sizeBytes}$size"),
            "${photo.stem}.${format.extension}",
        )
    }

    private fun removeOld() {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { folder ->
            if (folder.lastModified() < cutoff) folder.deleteRecursively()
        }
    }

    private companion object {
        const val QUALITY = 92
        const val FALLBACK_DIMENSION = 4096
        const val KEEP_MS = 24L * 60 * 60 * 1000
    }
}
