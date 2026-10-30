package com.brekwol.daybreak.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Cuts a video to a shorter clip, optionally without its sound, by copying the
 * encoded frames: nothing is re-encoded, so the clip keeps the original's exact
 * quality (and HDR) and saving takes moments. A clip has to begin on a key
 * frame, so it starts at the last key frame at or before the chosen start.
 */
class VideoTrimmer(context: Context, private val photos: PhotoRepository) {
    private val context = context.applicationContext

    /** Where a clip asked to start at [startMs] really starts: the key frame at or before it. */
    suspend fun keyFrameAtOrBefore(video: Photo, startMs: Long): Long = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, video.uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.mimeOf(it).startsWith("video/") }
                ?: return@withContext startMs
            extractor.selectTrack(track)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val time = extractor.sampleTime
            if (time < 0) startMs else time / 1000
        } catch (error: Exception) {
            startMs
        } finally {
            extractor.release()
        }
    }

    /**
     * Saves the part of [video] from [startMs] to [endMs] as a new video next to
     * it, without sound when [mute] is set. Returns the new video.
     */
    @SuppressLint("WrongConstant")
    suspend fun trim(video: Photo, startMs: Long, endMs: Long, mute: Boolean): Photo = withContext(Dispatchers.IO) {
        require(endMs > startMs) { "The clip is empty" }
        val stem = video.stem.substringBefore("_T") + "_T" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date())
        val target = photos.newPendingVideo(stem)
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(context, video.uri, null)
            val descriptor = context.contentResolver.openFileDescriptor(target, "rw")
                ?: throw IOException("Could not open the new video file")
            descriptor.use { pfd ->
                val out = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                muxer = out

                // Which tracks to copy, and where they go in the new file.
                val mapping = HashMap<Int, Int>()
                var videoTrack = -1
                var bufferSize = 1 shl 20
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val keep = mime.startsWith("video/") || (mime.startsWith("audio/") && !mute)
                    if (!keep) continue
                    if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                    mapping[i] = out.addTrack(format)
                    extractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
                if (videoTrack < 0) throw IOException("This file has no video")
                out.setOrientationHint(rotationOf(video.uri))

                // The clip starts on the key frame at or before the chosen start.
                val base = keyFrameAtOrBefore(video, startMs) * 1000
                extractor.seekTo(base, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val endUs = endMs * 1000
                val buffer = ByteBuffer.allocateDirect(bufferSize)
                val info = MediaCodec.BufferInfo()
                out.start()
                started = true
                while (true) {
                    coroutineContext.ensureActive()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val track = extractor.sampleTrackIndex
                    val time = extractor.sampleTime
                    val outTrack = mapping[track]
                    if (outTrack != null && time >= base) {
                        if (time > endUs) {
                            extractor.unselectTrack(track)
                        } else {
                            val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            info.set(0, size, time - base, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                            out.writeSampleData(outTrack, buffer, info)
                        }
                    }
                    extractor.advance()
                }
                out.stop()
                started = false
                out.release()
                muxer = null
            }
            photos.publish(target)
        } catch (error: Throwable) {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            photos.discard(target)
            throw error
        } finally {
            extractor.release()
        }
        photos.findVideo(target) ?: throw IOException("The clip was saved but can't be found")
    }

    private fun MediaExtractor.mimeOf(track: Int): String =
        getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()

    private fun rotationOf(uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (error: Exception) {
            0
        } finally {
            retriever.release()
        }
    }
}
