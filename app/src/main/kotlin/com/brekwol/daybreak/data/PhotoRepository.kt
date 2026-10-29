package com.brekwol.daybreak.data

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A photo saved by Daybreak. A photo taken with RAW on carries its DNG in [raw]. */
data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val width: Int,
    val height: Int,
    val format: PhotoFormat,
    val raw: Photo? = null,
) {
    /** File name without the extension; a photo and its DNG share it. */
    val stem: String
        get() = name.substringBeforeLast('.')

    /** "JXL", "HEIC", "RAW" or "JXL+RAW". */
    val badge: String
        get() = if (raw != null) "${format.badge}+RAW" else format.badge
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object Failed : DeleteResult
    /** Android needs the user to confirm, e.g. after a reinstall. */
    data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult
}

/**
 * Reads and writes Daybreak's photos in MediaStore under DCIM/Daybreak.
 * Files are inserted as pending and published only when fully written.
 * If a phone's MediaStore refuses a type in DCIM, photos go to Download/Daybreak.
 */
class PhotoRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    private val _changes = MutableStateFlow(0)
    /** Increments whenever photos are added or removed. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** MediaStore collection for images, used for DNGs written by the camera. */
    val imagesCollection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** A new file name stem like DB_20261028_221400_123, shared by a photo and its DNG. */
    fun newStem(): String =
        "DB_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** MediaStore values for a DNG the camera writes itself. */
    fun rawValues(stem: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.${PhotoFormat.Dng.extension}")
        put(MediaStore.MediaColumns.MIME_TYPE, PhotoFormat.Dng.mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
    }

    suspend fun save(
        bytes: ByteArray,
        stem: String,
        format: PhotoFormat,
        width: Int,
        height: Int,
    ): Photo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val name = "$stem.${format.extension}"
        val target = insertPending(imagesCollection, RELATIVE_PATH, name, format.mime, now, width, height)
            ?: insertPending(downloads, "Download/Daybreak", name, format.mime, now, width, height)
            ?: throw IOException("Android refused to create the photo file")
        val (collection, uri) = target
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the photo file")
            stream.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        notifyChanged()
        Photo(
            id = ContentUris.parseId(uri),
            uri = ContentUris.withAppendedId(collection, ContentUris.parseId(uri)),
            name = name,
            sizeBytes = bytes.size.toLong(),
            dateAddedSeconds = now / 1000,
            width = width,
            height = height,
            format = format,
        )
    }

    private fun insertPending(
        collection: Uri,
        relativePath: String,
        name: String,
        mime: String,
        now: Long,
        width: Int,
        height: Int,
    ): Pair<Uri, Uri>? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, now)
            put(MediaStore.MediaColumns.WIDTH, width)
            put(MediaStore.MediaColumns.HEIGHT, height)
        }
        return try {
            resolver.insert(collection, values)?.let { collection to it }
        } catch (ignored: IllegalArgumentException) {
            null
        } catch (ignored: UnsupportedOperationException) {
            null
        }
    }

    /** All Daybreak photos, newest first, with each DNG attached to its photo. */
    suspend fun list(): List<Photo> = withContext(Dispatchers.IO) {
        val all = (query(imagesCollection) + query(downloads)).distinctBy { it.uri }
        all.groupBy { it.stem }
            .values
            .mapNotNull { group ->
                val dng = group.firstOrNull { it.format == PhotoFormat.Dng }
                val processed = group.firstOrNull { it.format != PhotoFormat.Dng }
                when {
                    processed != null -> processed.copy(raw = dng)
                    else -> dng
                }
            }
            .sortedByDescending { it.dateAddedSeconds }
    }

    private fun query(collection: Uri): List<Photo> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        val nameColumn = MediaStore.MediaColumns.DISPLAY_NAME
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "($nameColumn LIKE ? OR $nameColumn LIKE ? OR $nameColumn LIKE ?)"
        val args = arrayOf("%Daybreak%", "%.jxl", "%.heic", "%.dng")
        val result = mutableListOf<Photo>()
        try {
            resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        val format = PhotoFormat.fromFileName(name) ?: continue
                        result += Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = name,
                            sizeBytes = cursor.getLong(2),
                            dateAddedSeconds = cursor.getLong(3),
                            width = cursor.getInt(4),
                            height = cursor.getInt(5),
                            format = format,
                        )
                    }
                }
        } catch (ignored: Exception) {
            // A collection this phone cannot query simply contributes nothing.
        }
        return result
    }

    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val stream = resolver.openInputStream(uri) ?: throw IOException("Could not open the photo")
        stream.use { it.readBytes() }
    }

    /** Deletes a photo and its DNG, if it has one. */
    suspend fun delete(photo: Photo): DeleteResult = withContext(Dispatchers.IO) {
        val uris = listOfNotNull(photo.uri, photo.raw?.uri)
        try {
            var deleted = 0
            for (uri in uris) deleted += resolver.delete(uri, null, null)
            notifyChanged()
            if (deleted > 0) DeleteResult.Deleted else DeleteResult.Failed
        } catch (error: SecurityException) {
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(resolver, uris).intentSender
            } else {
                (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }
            if (sender != null) DeleteResult.NeedsConfirmation(sender) else DeleteResult.Failed
        }
    }

    /**
     * Saves a converted copy (JPEG or PNG) in Download/Daybreak, outside the
     * camera folder, so the photo library stays JPEG XL and HEIC only.
     */
    suspend fun saveCopyToDownloads(file: java.io.File, mime: String): Uri = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Daybreak")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(downloads, values) ?: throw IOException("Android refused to create the file")
        try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("Could not open the file")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        uri
    }

    /**
     * Deletes a photo Daybreak saved, without asking. Returns false when Android
     * wants the user to confirm (for example after a reinstall); the photo then stays.
     */
    suspend fun deleteOwned(photo: Photo): Boolean = withContext(Dispatchers.IO) {
        try {
            val deleted = resolver.delete(photo.uri, null, null) > 0
            if (deleted) notifyChanged()
            deleted
        } catch (refused: SecurityException) {
            false
        }
    }

    /** Call after photos changed outside this class (a system dialog, a DNG from the camera). */
    fun notifyChanged() {
        _changes.update { it + 1 }
    }

    companion object {
        const val RELATIVE_PATH = "DCIM/Daybreak"
    }
}
