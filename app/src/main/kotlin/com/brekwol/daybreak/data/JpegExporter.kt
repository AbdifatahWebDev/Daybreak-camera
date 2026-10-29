package com.brekwol.daybreak.data

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Makes JPEG copies of photos for other apps (Share, the system file picker,
 * "choose a photo" requests). Copies live in the app cache, never in the photo
 * library, and are reused while the photo is unchanged. Copies carry no EXIF,
 * so no camera or location details leave the phone with them.
 */
class JpegExporter(context: Context, private val decoder: PhotoDecoder) {
    private val dir = File(context.cacheDir, "export")
    private val lock = Mutex()

    /** The cached copy of [photo] if one was already made, otherwise null. */
    fun existing(photo: Photo): File? = target(photo).takeIf { it.isFile && it.length() > 0 }

    /** A JPEG copy of [photo], named like the photo (DB_…jpg). */
    suspend fun export(photo: Photo): File = lock.withLock {
        withContext(Dispatchers.IO) {
            existing(photo)?.let { return@withContext it }
            removeOld()
            val file = target(photo)
            file.parentFile?.mkdirs()
            val bitmap = try {
                decoder.decode(photo, maxDimension = 0)
            } catch (tooBig: OutOfMemoryError) {
                decoder.decode(photo, maxDimension = FALLBACK_DIMENSION)
            }
            val part = File(file.parentFile, file.name + ".part")
            try {
                part.outputStream().use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) {
                        throw IOException("Could not make a JPEG copy")
                    }
                }
            } finally {
                bitmap.recycle()
            }
            if (!part.renameTo(file)) throw IOException("Could not save the JPEG copy")
            file
        }
    }

    // One folder per photo version keeps the friendly file name and changes when the photo does.
    private fun target(photo: Photo): File =
        File(File(dir, "${photo.format.extension}_${photo.id}_${photo.sizeBytes}"), "${photo.stem}.jpg")

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
