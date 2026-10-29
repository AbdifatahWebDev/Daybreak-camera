package com.brekwol.daybreak.edit

import android.graphics.Bitmap
import com.brekwol.daybreak.AppContainer
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.data.PhotoFormat
import com.brekwol.daybreak.engine.EditRecipe
import com.brekwol.daybreak.engine.ImageSize
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What one preview render needs. */
data class PreviewRequest(
    val recipe: EditRecipe,
    /** Show the whole straightened frame, for drawing the crop. */
    val showFullFrame: Boolean,
    /** A finger is moving: render small and fast. */
    val draft: Boolean,
    val histogram: Boolean,
)

class PreviewFrame(
    val bitmap: Bitmap,
    /** 64 brightness bins scaled to 0..1, when asked for. */
    val histogram: FloatArray?,
    val request: PreviewRequest,
)

/**
 * One photo open in the editor: smaller copies of it for fast previews, and
 * rendering the finished edit at full size.
 *
 * Edits always render from [source], the original. When [replacing] is set, the
 * editor was opened on an edited copy, and saving updates that copy.
 */
class EditSession private constructor(
    private val container: AppContainer,
    val source: Photo,
    val replacing: Photo?,
    val initial: EditRecipe,
    /** Up to [LARGE] pixels on the long side: the settled preview and "before". */
    val large: Bitmap,
    private val small: Bitmap,
    /** Long side of the full-size photo, so previews match the export. */
    val fullLongSide: Int,
) {
    private val engine = container.engine
    private val pool = BitmapPool()

    /** Thumbnail-sized copy, for preset previews. */
    val thumb: Bitmap = scaleDown(small, THUMB)

    fun outputSize(recipe: EditRecipe, applyCrop: Boolean = true): ImageSize =
        engine.editOutputSize(large.width, large.height, recipe, applyCrop)

    /** Blocking; call off the main thread. */
    fun renderPreview(request: PreviewRequest): PreviewFrame {
        val src = if (request.draft) small else large
        val maxLong = if (request.draft) DRAFT_LONG else PREVIEW_LONG
        val size = fit(engine.editOutputSize(src.width, src.height, request.recipe, !request.showFullFrame), maxLong)
        val destination = pool.obtain(size.width, size.height)
        engine.renderEditInto(src, destination, request.recipe, fullLongSide, !request.showFullFrame)
        val histogram = if (request.histogram) lumaHistogram(destination) else null
        return PreviewFrame(destination, histogram, request)
    }

    /** A small render of [recipe] (a preset) on the thumbnail. Blocking. */
    fun renderThumb(recipe: EditRecipe): Bitmap {
        val size = engine.editOutputSize(thumb.width, thumb.height, recipe, true)
        val destination = Bitmap.createBitmap(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        engine.renderEditInto(thumb, destination, recipe, fullLongSide, true)
        return destination
    }

    /**
     * Renders [recipe] from the full-size original, saves it as a new photo in
     * the format chosen in Settings, and records the edit so it can be changed later.
     */
    suspend fun save(recipe: EditRecipe): Photo = withContext(Dispatchers.Default) {
        val original = decodeFull(container, source)
        val rendered = try {
            engine.renderEdit(original, recipe, maxLongSide = 0, fullLongSide = 0, applyCrop = true)
        } finally {
            original.recycle()
        }
        val saved = try {
            val settings = container.settings.current()
            val heic = settings.format == PhotoFormat.Heic && container.heic.isSupported
            val format = if (heic) PhotoFormat.Heic else PhotoFormat.Jxl
            val bytes = if (heic) {
                container.heic.encode(rendered, rotationDegrees = 0, quality = settings.quality, exifBlock = null)
            } else {
                engine.encodeJxl(rendered, rotationDegrees = 0, settings = settings.encodeSettings)
            }
            val stem = source.stem + "_E" + SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date())
            container.photos.save(bytes, stem, format, rendered.width, rendered.height)
        } finally {
            rendered.recycle()
        }
        container.edits.saveEdit(saved.stem, SavedEdit(source.stem, recipe))
        // The copy this edit replaces goes; if Android won't let it go, both stay.
        replacing?.let { old ->
            if (old.uri != saved.uri && container.photos.deleteOwned(old)) {
                container.edits.forgetEdit(old.stem)
            }
        }
        saved
    }

    private fun fit(size: ImageSize, maxLong: Int): ImageSize {
        val long = max(size.width, size.height)
        if (long <= maxLong || long == 0) return ImageSize(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        val scale = maxLong.toFloat() / long
        return ImageSize(
            (size.width * scale).roundToInt().coerceAtLeast(1),
            (size.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    /** A few bitmaps per size, reused in turn so the one on screen is never drawn into. */
    private class BitmapPool {
        private var width = 0
        private var height = 0
        private val buffers = arrayOfNulls<Bitmap>(3)
        private var next = 0

        @Synchronized
        fun obtain(w: Int, h: Int): Bitmap {
            if (w != width || h != height) {
                width = w
                height = h
                buffers.fill(null)
                next = 0
            }
            val slot = next
            next = (next + 1) % buffers.size
            return buffers[slot] ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffers[slot] = it }
        }
    }

    companion object {
        const val LARGE = 2400
        const val SMALL = 1024
        const val THUMB = 288
        const val PREVIEW_LONG = 1800
        const val DRAFT_LONG = 1024

        /**
         * Opens [photo] for editing. An edited copy opens its original with the
         * edit it was made with; anything else opens as it is.
         */
        suspend fun open(container: AppContainer, photo: Photo): EditSession = withContext(Dispatchers.Default) {
            val saved = container.edits.editFor(photo.stem)
            val original = saved?.let { edit ->
                container.photos.list().firstOrNull { it.stem == edit.sourceStem && it.uri != photo.uri }
            }
            val source = original ?: photo
            val initial = if (original != null && saved != null) saved.recipe else EditRecipe.Neutral
            val large = argb(container.decoder.decode(source, maxDimension = LARGE))
            val small = scaleDown(large, SMALL)
            val known = max(source.width, source.height)
            val fullLong = if (known > max(large.width, large.height)) known else max(large.width, large.height)
            EditSession(
                container = container,
                source = source,
                replacing = if (original != null) photo else null,
                initial = initial,
                large = large,
                small = small,
                fullLongSide = fullLong,
            )
        }

        private suspend fun decodeFull(container: AppContainer, photo: Photo): Bitmap {
            val bitmap = try {
                container.decoder.decode(photo, maxDimension = FULL_LIMIT)
            } catch (tooBig: OutOfMemoryError) {
                container.decoder.decode(photo, maxDimension = FALLBACK_LIMIT)
            }
            return argb(bitmap)
        }

        /** The engine works on 8-bit RGBA; 10-bit and HDR decodes are converted. */
        private fun argb(bitmap: Bitmap): Bitmap {
            if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
            val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Could not read this photo's colors")
            bitmap.recycle()
            return copy
        }

        private fun scaleDown(bitmap: Bitmap, maxLong: Int): Bitmap {
            val long = max(bitmap.width, bitmap.height)
            if (long <= maxLong) return bitmap
            val scale = maxLong.toFloat() / long
            return Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )
        }

        /** 64 brightness bins, scaled so the tallest is 1, on a square-root scale. */
        fun lumaHistogram(bitmap: Bitmap): FloatArray {
            val bins = FloatArray(64)
            val w = bitmap.width
            val h = bitmap.height
            val stepY = max(1, h / 300)
            val row = IntArray(w)
            var y = 0
            while (y < h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = row[x]
                    val luma = (((c shr 16) and 0xFF) * 54 + ((c shr 8) and 0xFF) * 183 + (c and 0xFF) * 19) shr 8
                    bins[luma shr 2] += 1f
                    x += stepY
                }
                y += stepY
            }
            var peak = 0f
            for (i in bins.indices) {
                bins[i] = sqrt(bins[i])
                if (bins[i] > peak) peak = bins[i]
            }
            if (peak > 0f) for (i in bins.indices) bins[i] /= peak
            return bins
        }

        /** About 50 megapixels; larger photos are scaled down when saving an edit. */
        private const val FULL_LIMIT = 8192
        private const val FALLBACK_LIMIT = 4096
    }
}
