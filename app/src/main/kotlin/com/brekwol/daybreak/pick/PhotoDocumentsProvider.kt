package com.brekwol.daybreak.pick

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import com.brekwol.daybreak.AppContainer
import com.brekwol.daybreak.DaybreakApplication
import com.brekwol.daybreak.R
import com.brekwol.daybreak.data.Photo
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Shows Daybreak in Android's file picker, so any app that lets you attach a
 * file (Claude, WhatsApp documents, email) can take a Daybreak photo. Every
 * photo is offered as a JPEG copy, made when it is picked, because most apps
 * can't open JPEG XL, HEIC or DNG. Apps only see the photo the user picks.
 */
class PhotoDocumentsProvider : DocumentsProvider() {

    // The app container exists once Application.onCreate has run, which is
    // always before the picker calls in; providers are created earlier than that.
    private val container: AppContainer
        get() = (context!!.applicationContext as DaybreakApplication).container

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
            add(Root.COLUMN_TITLE, "Daybreak")
            add(Root.COLUMN_SUMMARY, "Photos as JPEG")
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, JPEG)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (documentId == ROOT_DOCUMENT) {
            addFolderRow(cursor)
        } else {
            addPhotoRow(cursor, findPhoto(documentId))
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (parentDocumentId == ROOT_DOCUMENT) {
            runBlocking { container.photos.list() }.forEach { addPhotoRow(cursor, it) }
        }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.contains('w') || mode.contains('t')) throw FileNotFoundException("Daybreak photos are read-only")
        val photo = findPhoto(documentId)
        val file = try {
            runBlocking { container.exporter.export(photo) }
        } catch (error: Exception) {
            throw FileNotFoundException(error.message ?: "Could not open the photo")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val photo = findPhoto(documentId)
        val size = maxOf(sizeHint?.x ?: 0, sizeHint?.y ?: 0).coerceIn(MIN_THUMBNAIL, MAX_THUMBNAIL)
        val folder = File(context!!.cacheDir, "picker-thumbnails").apply { mkdirs() }
        val file = File(folder, "${photo.format.extension}_${photo.id}_${photo.sizeBytes}_$size.jpg")
        if (!file.isFile) {
            val bitmap = runBlocking { container.thumbnails.load(photo, size) }
                ?: throw FileNotFoundException("No thumbnail")
            // The bitmap is shared with Daybreak's thumbnail cache, so it isn't recycled here.
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    private fun findPhoto(documentId: String): Photo {
        val photos = runBlocking { container.photos.list() }
        return photos.firstOrNull { documentIdOf(it) == documentId }
            ?: throw FileNotFoundException("No such photo: $documentId")
    }

    private fun addFolderRow(cursor: MatrixCursor) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_DISPLAY_NAME, "Daybreak")
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
            add(Document.COLUMN_SIZE, null)
            add(Document.COLUMN_LAST_MODIFIED, null)
        }
    }

    private fun addPhotoRow(cursor: MatrixCursor, photo: Photo) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentIdOf(photo))
            add(Document.COLUMN_MIME_TYPE, JPEG)
            add(Document.COLUMN_DISPLAY_NAME, "${photo.stem}.jpg")
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
            // The JPEG is made when picked, so its size is known only once it exists.
            add(Document.COLUMN_SIZE, container.exporter.existing(photo)?.length())
            add(Document.COLUMN_LAST_MODIFIED, photo.dateAddedSeconds * 1000)
        }
    }

    private companion object {
        const val ROOT_ID = "daybreak"
        const val ROOT_DOCUMENT = "photos"
        const val JPEG = "image/jpeg"
        const val MIN_THUMBNAIL = 96
        const val MAX_THUMBNAIL = 512

        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_MIME_TYPES,
        )

        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        fun documentIdOf(photo: Photo): String = "${photo.format.extension}:${photo.id}"
    }
}
