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

/** A JPEG XL photo saved by Daybreak. */
data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val width: Int,
    val height: Int,
)

sealed interface DeleteResult {
    data object Deleted : DeleteResult
    data object Failed : DeleteResult
    /** Android needs the user to confirm, e.g. after a reinstall. */
    data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult
}

/**
 * Reads and writes Daybreak's photos in MediaStore under DCIM/Daybreak.
 * Files are inserted as pending and published only when fully written.
 * If a phone's MediaStore refuses the image/jxl type in DCIM, photos go to
 * Download/Daybreak instead.
 */
class PhotoRepository(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    private val _changes = MutableStateFlow(0)
    /** Increments whenever photos are added or removed. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    suspend fun save(bytes: ByteArray, width: Int, height: Int): Photo = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val name = "DB_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(now)) + ".jxl"
        val target = insertPending(images, "DCIM/Daybreak", name, now, width, height)
            ?: insertPending(downloads, "Download/Daybreak", name, now, width, height)
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
        _changes.update { it + 1 }
        Photo(
            id = ContentUris.parseId(uri),
            uri = ContentUris.withAppendedId(collection, ContentUris.parseId(uri)),
            name = name,
            sizeBytes = bytes.size.toLong(),
            dateAddedSeconds = now / 1000,
            width = width,
            height = height,
        )
    }

    private fun insertPending(
        collection: Uri,
        relativePath: String,
        name: String,
        now: Long,
        width: Int,
        height: Int,
    ): Pair<Uri, Uri>? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, JXL_MIME)
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

    /** All Daybreak photos, newest first. */
    suspend fun list(): List<Photo> = withContext(Dispatchers.IO) {
        (query(images) + query(downloads))
            .distinctBy { it.uri }
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
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%Daybreak%", "%.jxl%")
        val result = mutableListOf<Photo>()
        try {
            resolver.query(collection, projection, selection, args, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        result += Photo(
                            id = id,
                            uri = ContentUris.withAppendedId(collection, id),
                            name = cursor.getString(1) ?: "photo.jxl",
                            sizeBytes = cursor.getLong(2),
                            dateAddedSeconds = cursor.getLong(3),
                            width = cursor.getInt(4),
                            height = cursor.getInt(5),
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

    suspend fun delete(photo: Photo): DeleteResult = withContext(Dispatchers.IO) {
        try {
            if (resolver.delete(photo.uri, null, null) > 0) {
                _changes.update { it + 1 }
                DeleteResult.Deleted
            } else {
                DeleteResult.Failed
            }
        } catch (error: SecurityException) {
            val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                MediaStore.createDeleteRequest(resolver, listOf(photo.uri)).intentSender
            } else {
                (error as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender
            }
            if (sender != null) DeleteResult.NeedsConfirmation(sender) else DeleteResult.Failed
        }
    }

    /** Call after the user confirmed a system delete dialog. */
    fun notifyChanged() {
        _changes.update { it + 1 }
    }

    companion object {
        const val JXL_MIME = "image/jxl"
    }
}
