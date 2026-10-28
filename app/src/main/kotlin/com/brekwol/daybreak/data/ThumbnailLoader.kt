package com.brekwol.daybreak.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import com.brekwol.daybreak.engine.DaybreakEngine
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Library thumbnails: decoded once from the JPEG XL file, then kept in memory and
 * as small WebP files in the app cache (never in the photo library).
 */
class ThumbnailLoader(
    context: Context,
    private val engine: DaybreakEngine,
    private val photos: PhotoRepository,
) {
    private val cacheDir = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val decodeSlots = Semaphore(2)
    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(photo: Photo, maxDimension: Int): Bitmap? {
        val key = "${photo.id}_${photo.sizeBytes}_$maxDimension"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = File(cacheDir, "$key.webp")
            val bitmap = (if (file.exists()) BitmapFactory.decodeFile(file.path) else null)
                ?: decodeFresh(photo, maxDimension)?.also { writeWebp(it, file) }
            bitmap?.also { memory.put(key, it) }
        }
    }

    private suspend fun decodeFresh(photo: Photo, maxDimension: Int): Bitmap? =
        decodeSlots.withPermit {
            try {
                engine.decodeJxl(photos.readBytes(photo.uri), maxDimension)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                null
            }
        }

    private fun writeWebp(bitmap: Bitmap, file: File) {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        runCatching { file.outputStream().use { bitmap.compress(format, 85, it) } }
    }
}
