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
    /** Length of a video; 0 for photos. */
    val durationMs: Long = 0L,
) {
    /** File name without the extension; a photo and its DNG share it. */
    val stem: String
        get() = name.substringBeforeLast('.')

    val isVideo: Boolean
        get() = format.isVideo

    /** "JXL", "HEIC", "RAW", "JXL+RAW", or a video's length like "1:05". */
    val badge: String
        get() = when {
            isVideo -> formatDuration(durationMs)
            raw != null -> "${format.badge}+RAW"
            else -> format.badge
        }
}

/** "0:07", "1:05" or "1:02:03". */
fun formatDuration(ms: Long): String {
    val total = (ms + 500) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
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

    /** MediaStore collection for videos. */
    val videosCollection: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
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

    /** MediaStore values for a video the camera records. */
    fun videoValues(stem: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "$stem.${PhotoFormat.Mp4.extension}")
        put(MediaStore.MediaColumns.MIME_TYPE, PhotoFormat.Mp4.mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
    }

    /** A new, still hidden video file in DCIM/Daybreak; [publish] it once written, or [discard] it. */
    suspend fun newPendingVideo(stem: String): Uri = withContext(Dispatchers.IO) {
        val values = videoValues(stem).apply {
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
        }
        resolver.insert(videosCollection, values) ?: throw IOException("Android refused to create the video file")
    }

    suspend fun publish(uri: Uri) = withContext(Dispatchers.IO) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        notifyChanged()
    }

    suspend fun discard(uri: Uri) = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(uri, null, null) }
        Unit
    }

    /** The library entry of a video Daybreak recorded, by its MediaStore address. */
    suspend fun findVideo(uri: Uri): Photo? {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        return list(includeVideos = true).firstOrNull { it.isVideo && it.id == id }
    }

    /**
     * All Daybreak photos, newest first, with each DNG attached to its photo.
     * [includeVideos] adds videos (the library shows them; "choose a photo" doesn't).
     */
    suspend fun list(includeVideos: Boolean = false): List<Photo> = withContext(Dispatchers.IO) {
        val videos = if (includeVideos) queryVideos() else emptyList()
        val all = (query(imagesCollection) + query(downloads) + videos).distinctBy { it.uri }
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

    private fun queryVideos(): List<Photo> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.Video.VideoColumns.DURATION,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%Daybreak%", "%.mp4")
        val result = mutableListOf<Photo>()
        try {
            resolver.query(videosCollection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        result += Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(videosCollection, id),
                            name = name,
                            sizeBytes = cursor.getLong(2),
                            dateAddedSeconds = cursor.getLong(3),
                            width = cursor.getInt(4),
                            height = cursor.getInt(5),
                            format = PhotoFormat.Mp4,
                            durationMs = cursor.getLong(6),
                        )
                    }
                }
        } catch (ignored: Exception) {
            // No videos to show.
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
