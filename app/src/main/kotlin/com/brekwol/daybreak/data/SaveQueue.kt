package com.brekwol.daybreak.data

import android.graphics.Bitmap
import com.brekwol.daybreak.engine.DaybreakEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One captured frame waiting to be encoded and saved. */
class CapturedFrame(
    val bitmap: Bitmap,
    /** Clockwise rotation needed to show the frame upright. */
    val rotationDegrees: Int,
    /** The camera's JPEG, used only as the source of EXIF metadata. */
    val exifSourceJpeg: ByteArray?,
)

/**
 * The shutter never waits for a file. Frames go into this queue and are encoded
 * to JPEG XL one at a time in the background.
 */
class SaveQueue(
    private val scope: CoroutineScope,
    private val engine: DaybreakEngine,
    private val photos: PhotoRepository,
    private val settings: SettingsRepository,
) {
    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending.asStateFlow()

    private val _lastSaved = MutableStateFlow<Photo?>(null)
    val lastSaved: StateFlow<Photo?> = _lastSaved.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    // One encode at a time keeps memory bounded; libjxl already uses every core.
    private val encoder = Semaphore(1)

    /** Each frame holds a full-size bitmap, so only a few may wait at once. */
    val canAcceptMore: Boolean
        get() = _pending.value < MAX_PENDING

    fun submit(frame: CapturedFrame) {
        _pending.update { it + 1 }
        scope.launch {
            try {
                encoder.withPermit {
                    val current = settings.current()
                    val bytes = engine.encodeJxl(
                        bitmap = frame.bitmap,
                        rotationDegrees = frame.rotationDegrees,
                        settings = current.encodeSettings,
                        exifSourceJpeg = frame.exifSourceJpeg,
                    )
                    val sideways = frame.rotationDegrees % 180 != 0
                    val width = if (sideways) frame.bitmap.height else frame.bitmap.width
                    val height = if (sideways) frame.bitmap.width else frame.bitmap.height
                    _lastSaved.value = photos.save(bytes, width, height)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errors.tryEmit(error.message ?: "Could not save the photo")
            } finally {
                frame.bitmap.recycle()
                _pending.update { it - 1 }
            }
        }
    }

    fun reportError(message: String) {
        _errors.tryEmit(message)
    }

    companion object {
        const val MAX_PENDING = 3
    }
}
