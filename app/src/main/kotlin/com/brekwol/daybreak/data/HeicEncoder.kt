package com.brekwol.daybreak.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.heifwriter.HeifWriter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encodes HEIC with the phone's hardware HEVC encoder through HeifWriter.
 * Rotation is stored in the file (no pixels are turned) and EXIF is copied in.
 */
class HeicEncoder(context: Context) {
    private val dir = File(context.cacheDir, "heic")

    /** True when this phone has an HEVC or HEIC encoder. */
    val isSupported: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { type ->
                    type.equals(MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC, ignoreCase = true) ||
                        type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    /**
     * @param rotationDegrees clockwise rotation that shows the photo upright.
     * @param exifBlock `Exif\0\0` + TIFF metadata with the orientation set to normal, or null.
     */
    suspend fun encode(
        bitmap: Bitmap,
        rotationDegrees: Int,
        quality: Int,
        exifBlock: ByteArray?,
    ): ByteArray = withContext(Dispatchers.Default) {
        dir.mkdirs()
        val file = File.createTempFile("encode", ".heic", dir)
        try {
            val writer = HeifWriter.Builder(file.path, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
                .setQuality(quality.coerceIn(0, 100))
                .setRotation(((rotationDegrees % 360) + 360) % 360)
                .setMaxImages(1)
                .setPrimaryIndex(0)
                .setGridEnabled(true)
                .build()
            try {
                writer.start()
                writer.addBitmap(bitmap)
                if (exifBlock != null && exifBlock.size > 6) writer.addExifData(0, exifBlock, 0, exifBlock.size)
                writer.stop(ENCODE_TIMEOUT_MS)
            } finally {
                writer.close()
            }
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val ENCODE_TIMEOUT_MS = 20_000L
    }
}
