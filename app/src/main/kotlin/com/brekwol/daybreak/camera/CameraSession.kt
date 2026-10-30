package com.brekwol.daybreak.camera

import android.annotation.SuppressLint
import android.net.Uri
import androidx.camera.core.DynamicRange
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.brekwol.daybreak.data.CapturedFrame
import com.brekwol.daybreak.data.PhotoFormat
import com.brekwol.daybreak.data.PhotoRepository
import com.brekwol.daybreak.engine.BurstMerge
import com.google.common.util.concurrent.ListenableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the capture screen needs to know about the open camera. */
data class CameraState(
    val ready: Boolean = false,
    val backCamera: Boolean = true,
    val capabilities: CameraCapabilities = CameraCapabilities(),
    /** RAW + DNG is on for this session (asked for and supported). */
    val rawActive: Boolean = false,
    val histogramAvailable: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val error: String? = null,
    /** Bound for video: the shutter records. */
    val videoActive: Boolean = false,
    /** What this camera can record. */
    val videoUhdSupported: Boolean = false,
    val videoHdrSupported: Boolean = false,
    /** What the bound video use case records, e.g. "4K" and HDR on. */
    val videoQuality: String = "",
    val videoHdrActive: Boolean = false,
    /** Why video isn't exactly what was asked for (HDR or stabilization not possible), or null. */
    val videoNotice: String? = null,
) {
    /** Zoom presets for the pill: ultra-wide when present, 1×, 2×, 5×. */
    val zoomOptions: List<Float>
        get() = buildList {
            if (minZoom < 0.95f) add(minZoom)
            add(1f)
            if (maxZoom >= 2f) add(2f)
            if (maxZoom >= 5f) add(5f)
        }
}

/** How video is recorded: 4K (else 1080p) and HDR (10-bit HLG, else SDR), where the camera can. */
data class VideoOptions(val uhd: Boolean = true, val hdr: Boolean = false)

/** A recording in progress. */
data class RecordingInfo(val durationMs: Long = 0L)

/**
 * Owns the camera: Preview, ImageCapture and (for the histogram) ImageAnalysis,
 * bound with ProcessCameraProvider. Manual Pro settings go to the camera through
 * Camera2 interop and apply to the preview and to every photo.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraSession(context: Context) {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val captureDir = File(this.context.cacheDir, "capture")
    private val captureExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val _histogram = MutableStateFlow<FloatArray?>(null)
    /** 64 brightness bins scaled to 0..1, or null when there is no histogram. */
    val histogram: StateFlow<FloatArray?> = _histogram.asStateFlow()

    private val _meter = MutableStateFlow<Float?>(null)
    /** Stops between the preview's average brightness and mid-grey; null without frames to measure. */
    val meter: StateFlow<Float?> = _meter.asStateFlow()

    private val _live = MutableStateFlow(LiveExposure())
    /** ISO, shutter and focus the camera is using right now. */
    val live: StateFlow<LiveExposure> = _live.asStateFlow()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private val _recording = MutableStateFlow<RecordingInfo?>(null)
    /** The recording in progress, or null. */
    val recordingState: StateFlow<RecordingInfo?> = _recording.asStateFlow()
    private var imageAnalysis: ImageAnalysis? = null
    private var rawActive = false
    private var pro = ProSettings()
    private var zoomSource: LiveData<ZoomState>? = null
    private var targetRotation: Int? = null

    // Last exposure chosen by auto exposure; Pro mode keeps brightness from it
    // when only one of ISO and shutter is set by hand.
    @Volatile private var metered: LiveExposure? = null
    @Volatile private var autoExposureOn = true
    @Volatile private var lastLiveMs = 0L

    // Fraction of the last analyzed frame that was blown out, for HDR Auto.
    @Volatile private var clipped = 0f

    // While a burst is taken, its exposure replaces automatic exposure.
    @Volatile private var burst: BurstPlan? = null

    private val inFlight = AtomicInteger(0)
    /** Photos taken whose image hasn't reached the save queue yet. */
    val capturesInFlight: Int
        get() = inFlight.get()

    private val zoomObserver = Observer<ZoomState> { zoom ->
        _state.update {
            it.copy(zoomRatio = zoom.zoomRatio, minZoom = zoom.minZoomRatio, maxZoom = zoom.maxZoomRatio)
        }
    }

    // Which way up each photo is saved. Keeps the last sure reading while the
    // phone points at the sky or the floor.
    private val orientation = OrientationTracker(this.context) { rotation ->
        targetRotation = rotation
        imageCapture?.setTargetRotation(rotation)
        // A recording keeps the rotation it started with.
        if (recording == null) videoCapture?.targetRotation = rotation
    }.also { it.start() }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLiveMs < LIVE_INTERVAL_MS) return
            lastLiveMs = now
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val shutter = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            val exposure = LiveExposure(iso = iso, shutterNs = shutter, focusDiopters = focus)
            if (autoExposureOn) metered = exposure
            _live.value = exposure
        }
    }

    /**
     * Opens the camera. Call again to switch camera or to turn RAW or the histogram
     * on or off; the previous use cases are unbound first.
     */
    suspend fun bind(
        owner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        backCamera: Boolean,
        raw: Boolean,
        histogram: Boolean,
        video: VideoOptions? = null,
    ) {
        if (recording != null) return
        try {
            val provider = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
            val selector = if (backCamera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            if (!runCatching { provider.hasCamera(selector) }.getOrDefault(false)) {
                _state.update { it.copy(ready = false, error = "This phone has no camera on that side.") }
                return
            }
            val info = provider.getCameraInfo(selector)
            val rawSupported = runCatching {
                ImageCapture.getImageCaptureCapabilities(info).supportedOutputFormats
                    .contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            }.getOrDefault(false)
            val useRaw = raw && rawSupported

            val previewBuilder = Preview.Builder().setResolutionSelector(previewResolution)
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(captureCallback)
            val preview = previewBuilder.build()
            preview.setSurfaceProvider(surfaceProvider)

            val captureBuilder = ImageCapture.Builder()
                .setResolutionSelector(captureResolution)
                .setCaptureMode(
                    if (useRaw) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY,
                )
            if (useRaw) captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG)
            targetRotation?.let { captureBuilder.setTargetRotation(it) }
            val capture = if (video == null) captureBuilder.build() else null

            // Video: 4K or 1080p, HDR when asked for and the camera can, stabilized when it can.
            val videoCapabilities = runCatching { Recorder.getVideoCapabilities(info) }.getOrNull()
            val hdrSupported = videoCapabilities?.supportedDynamicRanges?.contains(DynamicRange.HLG_10_BIT) == true
            val uhdSupported = videoCapabilities?.getSupportedQualities(DynamicRange.SDR)?.contains(Quality.UHD) == true
            val stabilizationSupported = videoCapabilities?.isStabilizationSupported == true

            // What to try binding, best first. Cameras that list HDR and stabilization
            // can still refuse them together (or with the histogram stream), so video
            // falls back step by step: without stabilization, then in SDR.
            val candidates: List<VideoAttempt?> = if (video == null) {
                listOf(null)
            } else {
                buildList {
                    if (video.hdr && hdrSupported) {
                        if (stabilizationSupported) add(VideoAttempt(hdr = true, stabilized = true))
                        add(VideoAttempt(hdr = true, stabilized = false))
                    }
                    if (stabilizationSupported) add(VideoAttempt(hdr = false, stabilized = true))
                    add(VideoAttempt(hdr = false, stabilized = false))
                }
            }

            val analysis = if (histogram) {
                ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, HistogramAnalyzer { bins, ev, clippedFraction ->
                        _histogram.value = bins
                        _meter.value = ev
                        clipped = clippedFraction
                    }) }
            } else {
                null
            }

            zoomSource?.removeObserver(zoomObserver)
            imageAnalysis?.clearAnalyzer()
            provider.unbindAll()

            var bound: Camera? = null
            var withHistogram = false
            var videoUseCase: VideoCapture<Recorder>? = null
            var chosen: VideoAttempt? = null
            var videoQuality = ""
            var lastError: IllegalArgumentException? = null
            for (attempt in candidates) {
                val videoCase = attempt?.let { buildVideoCapture(it, video, videoCapabilities) }
                val main: UseCase = capture ?: videoCase?.first ?: error("Nothing to bind")
                // With the histogram stream first, then without it.
                for (withAnalysis in listOfNotNull(analysis?.let { true }, false)) {
                    try {
                        val useCases = listOfNotNull(preview, main, if (withAnalysis) analysis else null)
                        bound = provider.bindToLifecycle(owner, selector, *useCases.toTypedArray())
                        withHistogram = withAnalysis
                        break
                    } catch (unsupported: IllegalArgumentException) {
                        lastError = unsupported
                        provider.unbindAll()
                    }
                }
                if (bound != null) {
                    videoUseCase = videoCase?.first
                    videoQuality = videoCase?.second.orEmpty()
                    chosen = attempt
                    break
                }
            }
            if (bound == null) throw lastError ?: IllegalStateException("The camera could not start.")
            if (!withHistogram) analysis?.clearAnalyzer()
            val videoHdr = chosen?.hdr == true
            val notice = when {
                video == null -> null
                video.hdr && hdrSupported && !videoHdr -> "This phone can't record HDR here, so video is SDR"
                videoHdr && stabilizationSupported && chosen?.stabilized == false ->
                    "Stabilization is off: this phone can't stabilize HDR video"
                else -> null
            }

            camera = bound
            imageCapture = capture
            videoCapture = videoUseCase
            imageAnalysis = if (withHistogram) analysis else null
            rawActive = useRaw
            if (!withHistogram) {
                _histogram.value = null
                _meter.value = null
            }
            bound.cameraInfo.zoomState.also {
                zoomSource = it
                it.observe(owner, zoomObserver)
            }
            _state.value = CameraState(
                ready = true,
                backCamera = backCamera,
                capabilities = readCapabilities(bound.cameraInfo, rawSupported),
                rawActive = useRaw,
                histogramAvailable = withHistogram,
                zoomRatio = bound.cameraInfo.zoomState.value?.zoomRatio ?: 1f,
                minZoom = bound.cameraInfo.zoomState.value?.minZoomRatio ?: 1f,
                maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f,
                videoActive = videoUseCase != null,
                videoUhdSupported = uhdSupported,
                videoHdrSupported = hdrSupported,
                videoQuality = videoQuality,
                videoHdrActive = videoHdr,
                videoNotice = notice,
            )
            applyPro(pro)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "Binding the camera failed", error)
            // The camera's own message is technical; say what to do instead.
            val message = when {
                error !is IllegalArgumentException -> error.message ?: "The camera could not start."
                video != null -> "The camera can't record with these settings. Try 1080p or SDR."
                else -> "The camera can't run this mode on this phone."
            }
            _state.update { it.copy(ready = false, error = message) }
        }
    }

    /** One way of setting up video, tried in turn until the camera accepts one. */
    private data class VideoAttempt(val hdr: Boolean, val stabilized: Boolean)

    /** The video use case for [attempt], and its quality label ("4K" or "1080p"). */
    private fun buildVideoCapture(
        attempt: VideoAttempt,
        options: VideoOptions?,
        capabilities: androidx.camera.video.VideoCapabilities?,
    ): Pair<VideoCapture<Recorder>, String> {
        val range = if (attempt.hdr) DynamicRange.HLG_10_BIT else DynamicRange.SDR
        val qualities = capabilities?.getSupportedQualities(range).orEmpty()
        val wanted = if (options?.uhd == true && Quality.UHD in qualities) Quality.UHD else Quality.FHD
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(wanted, FallbackStrategy.lowerQualityOrHigherThan(wanted)))
            .build()
        val builder = VideoCapture.Builder(recorder).setDynamicRange(range)
        if (attempt.stabilized) builder.setVideoStabilizationEnabled(true)
        targetRotation?.let { builder.setTargetRotation(it) }
        return builder.build() to if (wanted == Quality.UHD) "4K" else "1080p"
    }

    /** Applies Pro settings. [ProSettings] with every value null is fully automatic. */
    fun applyPro(settings: ProSettings) {
        pro = settings
        applyCurrent()
    }

    /** Sends the Pro settings, or a running burst's exposure, to the camera. */
    private fun applyCurrent(): ListenableFuture<Void?>? {
        val settings = pro
        val camera = camera ?: return null
        val caps = _state.value.capabilities
        val options = CaptureRequestOptions.Builder()

        val isoRange = caps.isoRange
        val shutterRange = caps.shutterRangeNs
        val plan = burst
        val burstIso = plan?.iso
        val burstShutter = plan?.shutterNs
        val burstManual = burstIso != null && burstShutter != null && caps.manualSensor &&
            isoRange != null && shutterRange != null
        val manualExposure = burstManual ||
            (settings.manualExposure && caps.manualSensor && isoRange != null && shutterRange != null)
        if (burstManual && burstIso != null && burstShutter != null && isoRange != null && shutterRange != null) {
            val shutter = burstShutter.coerceIn(shutterRange)
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, burstIso.coerceIn(isoRange))
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shutter, STEADY_FRAME_NS))
        } else if (manualExposure && isoRange != null && shutterRange != null) {
            // Keep the metered brightness for whichever of ISO and shutter is still automatic.
            val exposure = metered?.let { it.iso.toDouble() * it.shutterNs } ?: DEFAULT_EXPOSURE
            val iso = (settings.iso ?: (exposure / (settings.shutterNs ?: 1L)).roundToInt())
                .coerceIn(isoRange)
            // An automatic shutter stays hand-holdable; the meter shows when that leaves the photo dark.
            val shutter = (settings.shutterNs ?: (exposure / iso).roundToLong().coerceAtMost(ProSettings.HANDHELD_LIMIT_NS))
                .coerceIn(shutterRange)
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            // Short shutters keep the viewfinder at a steady 30 frames a second.
            options.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(shutter, STEADY_FRAME_NS))
        }
        val focus = settings.focusDiopters
        if (focus != null && caps.manualFocus) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, caps.minFocusDiopters))
        } else if (settings.focusCenter && caps.centerFocus) {
            // Continuous autofocus that only looks at the middle of the frame.
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            )
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(centerRegion(caps)))
        }
        if (settings.whiteBalance != WhiteBalance.Auto && settings.whiteBalance in caps.whiteBalances) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, settings.whiteBalance.awbMode)
        }
        autoExposureOn = !manualExposure
        val applied = Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
        if (!manualExposure && caps.exposureCompensation) {
            val ev = plan?.evIndex ?: settings.evIndex
            camera.cameraControl.setExposureCompensationIndex(ev.coerceIn(caps.evRange))
        }
        return applied
    }

    /** True when HDR Auto should merge a burst: part of the frame is blown out, or it is dim. */
    fun wantsHdr(): Boolean = clipped >= HDR_AUTO_CLIPPED || (_meter.value ?: 0f) < HDR_AUTO_DARK

    /** The exposure and finishing for a Night or HDR burst taken now. */
    fun planBurst(kind: BurstKind): BurstPlan =
        BurstPlanner.plan(kind, _state.value.capabilities, metered ?: _live.value.takeIf { it.iso > 0 }, _meter.value)

    /**
     * Takes [plan]'s frames and merges them into one photo. [onProgress] gets the
     * number of frames taken so far. The camera returns to normal exposure as
     * soon as the last frame is taken, while merging continues.
     */
    suspend fun takeBurst(
        plan: BurstPlan,
        stem: String,
        format: PhotoFormat,
        onProgress: (Int) -> Unit,
    ): CapturedFrame {
        val capture = imageCapture ?: throw IllegalStateException("The camera isn't ready yet.")
        if (rawActive) throw IllegalStateException("Turn RAW off to take Night and HDR photos.")
        inFlight.incrementAndGet()
        burst = plan
        try {
            runCatching { applyCurrent()?.await() }
            // Manual exposure applies to the next frame; automatic exposure needs time to settle.
            delay(if (plan.iso == null && plan.evIndex != null) AE_SETTLE_MS else EXPOSURE_SETTLE_MS)
            val merge = BurstMerge()
            try {
                return coroutineScope {
                    val shots = Channel<Shot>(capacity = 1)
                    launch {
                        try {
                            repeat(plan.frames) { shots.send(shoot(capture)) }
                        } finally {
                            shots.close()
                            burst = null
                            applyCurrent()
                        }
                    }
                    var first: Shot? = null
                    var width = 0
                    var height = 0
                    var taken = 0
                    for (shot in shots) {
                        taken++
                        onProgress(taken)
                        val bitmap = withContext(Dispatchers.Default) { shot.toBitmap() }
                        try {
                            if (first == null) {
                                first = shot
                                width = bitmap.width
                                height = bitmap.height
                            }
                            // A frame of another size (the camera changed its crop) is left out.
                            if (bitmap.width == width && bitmap.height == height) {
                                withContext(Dispatchers.Default) { merge.add(bitmap) }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val reference = first ?: throw IllegalStateException("The camera took no frames")
                    val photo = withContext(Dispatchers.Default) { merge.finish(width, height, plan.finish) }
                    CapturedFrame(photo, reference.rotation, reference.jpeg, stem, format)
                }
            } finally {
                merge.close()
            }
        } finally {
            burst = null
            applyCurrent()
            inFlight.decrementAndGet()
        }
    }

    /** One burst frame, copied out of the camera's buffer so the next can be taken. */
    private class Shot(val jpeg: ByteArray?, val bitmap: Bitmap?, val rotation: Int, val crop: Rect, val full: Rect)

    private fun Shot.toBitmap(): Bitmap = bitmap ?: decodeJpeg(jpeg ?: error("Empty frame")).cropTo(crop, full)

    /** Waits for the camera to take new settings; a newer change replacing them is fine too. */
    private suspend fun ListenableFuture<Void?>.await() {
        suspendCancellableCoroutine { continuation ->
            addListener({ continuation.resume(Unit) }, Runnable::run)
        }
    }

    private suspend fun shoot(capture: ImageCapture): Shot = suspendCancellableCoroutine { continuation ->
        capture.takePicture(
            captureExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val shot = try {
                        val full = Rect(0, 0, image.width, image.height)
                        if (image.format == ImageFormat.JPEG) {
                            val buffer = image.planes[0].buffer
                            buffer.rewind()
                            val jpeg = ByteArray(buffer.remaining())
                            buffer.get(jpeg)
                            Shot(jpeg, null, image.imageInfo.rotationDegrees, Rect(image.cropRect), full)
                        } else {
                            Shot(null, image.toBitmap(), image.imageInfo.rotationDegrees, full, full)
                        }
                    } catch (error: Exception) {
                        image.close()
                        continuation.resumeWithException(error)
                        return
                    }
                    image.close()
                    continuation.resume(shot)
                }

                override fun onError(exception: ImageCaptureException) {
                    continuation.resumeWithException(exception)
                }
            },
        )
    }

    /** Tap to focus and meter at a point of the preview. Returns false when everything is manual. */
    fun focusAt(factory: MeteringPointFactory, x: Float, y: Float): Boolean {
        val camera = camera ?: return false
        var flags = 0
        // Center and manual focus keep their own focus; a tap then only meters.
        if (pro.focusMode == FocusMode.Multi) flags = flags or FocusMeteringAction.FLAG_AF
        if (!pro.manualExposure) flags = flags or FocusMeteringAction.FLAG_AE
        if (pro.whiteBalance == WhiteBalance.Auto) flags = flags or FocusMeteringAction.FLAG_AWB
        if (flags == 0) return false
        val action = FocusMeteringAction.Builder(factory.createPoint(x, y), flags)
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()
        camera.cameraControl.startFocusAndMetering(action)
        return true
    }

    fun setZoom(ratio: Float) {
        val state = _state.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(state.minZoom, maxOf(state.minZoom, state.maxZoom)))
    }

    /**
     * Takes a photo. The processed image arrives in [deliverFrame] for encoding.
     * With RAW on, the camera also writes a DNG straight to DCIM/Daybreak and
     * [rawSaved] is called when it is there.
     */
    fun takePicture(
        stem: String,
        format: PhotoFormat,
        photos: PhotoRepository,
        deliverFrame: (CapturedFrame) -> Unit,
        rawSaved: () -> Unit,
        reportError: (String) -> Unit,
    ) {
        val capture = imageCapture
        if (capture == null) {
            reportError("The camera isn't ready yet.")
            return
        }
        // Counts this photo until its image reaches the save queue or fails, exactly once.
        inFlight.incrementAndGet()
        val settled = AtomicBoolean(false)
        val settle = { if (settled.compareAndSet(false, true)) inFlight.decrementAndGet() }

        if (!rawActive) {
            capture.takePicture(
                captureExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val frame = try {
                            image.toCapturedFrame(stem, format)
                        } catch (error: Exception) {
                            reportError(error.message ?: "Could not read the camera frame")
                            null
                        } finally {
                            image.close()
                        }
                        if (frame != null) deliverFrame(frame)
                        settle()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        settle()
                        reportError(exception.message ?: "The camera could not take the photo")
                    }
                },
            )
            return
        }

        captureDir.mkdirs()
        val jpegFile = File(captureDir, "$stem.jpg")
        val rawOptions = ImageCapture.OutputFileOptions.Builder(resolver, photos.imagesCollection, photos.rawValues(stem)).build()
        val jpegOptions = ImageCapture.OutputFileOptions.Builder(jpegFile).build()
        capture.takePicture(
            rawOptions,
            jpegOptions,
            captureExecutor,
            object : ImageCapture.OnImageSavedCallback {
                // Called once per file, in either order: the DNG (RAW_SENSOR) and the JPEG.
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    if (outputFileResults.imageFormat == ImageFormat.RAW_SENSOR) {
                        rawSaved()
                        return
                    }
                    val frame = try {
                        frameFromJpeg(jpegFile.readBytes(), stem, format)
                    } catch (error: Exception) {
                        reportError(error.message ?: "Could not read the camera frame")
                        null
                    } finally {
                        jpegFile.delete()
                    }
                    if (frame != null) deliverFrame(frame)
                    settle()
                }

                override fun onError(exception: ImageCaptureException) {
                    jpegFile.delete()
                    settle()
                    reportError(exception.message ?: "The camera could not take the photo")
                }
            },
        )
    }

    /**
     * Starts recording a video into DCIM/Daybreak. [onSaved] gets the new video's
     * address when recording stops; [withAudio] needs the microphone permission.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        stem: String,
        photos: PhotoRepository,
        withAudio: Boolean,
        onSaved: (Uri) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val video = videoCapture ?: return false
        if (recording != null) return false
        val output = MediaStoreOutputOptions.Builder(resolver, photos.videosCollection)
            .setContentValues(photos.videoValues(stem))
            .build()
        var pending = video.output.prepareRecording(context, output)
        if (withAudio) pending = pending.withAudioEnabled()
        _recording.value = RecordingInfo()
        recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
            when (event) {
                is VideoRecordEvent.Status ->
                    _recording.value = RecordingInfo(event.recordingStats.recordedDurationNanos / 1_000_000L)
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    _recording.value = null
                    val uri = event.outputResults.outputUri
                    val kept = uri != Uri.EMPTY && event.error in KEPT_ERRORS
                    if (kept) onSaved(uri)
                    when {
                        !kept -> onError(event.cause?.message ?: "The video could not be saved")
                        event.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE ->
                            onError("The phone ran out of space; the video was saved up to there")
                    }
                }
                else -> Unit
            }
        }
        return true
    }

    fun stopRecording() {
        recording?.stop()
    }

    /** Closes the camera and stops background work. */
    fun release() {
        recording?.stop()
        recording = null
        orientation.stop()
        zoomSource?.removeObserver(zoomObserver)
        imageAnalysis?.clearAnalyzer()
        runCatching { provider?.unbindAll() }
        camera = null
        imageCapture = null
        videoCapture = null
        imageAnalysis = null
        captureExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    /** Whether photos follow how the phone is held (false on phones without the sensor). */
    val followsRotation: Boolean
        get() = orientation.available

    private fun readCapabilities(info: CameraInfo, rawSupported: Boolean): CameraCapabilities {
        val camera2 = Camera2CameraInfo.from(info)
        val available = camera2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val manualSensor = available.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
        val isoRange = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val minFocus = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val afModes = camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        val awbModes = camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        val activeArray = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val exposure = info.exposureState
        return CameraCapabilities(
            manualSensor = manualSensor && isoRange != null && shutterRange != null,
            isoRange = isoRange?.let { it.lower..it.upper },
            shutterRangeNs = shutterRange?.let { it.lower..minOf(it.upper, MAX_SHUTTER_NS) },
            minFocusDiopters = if (manualSensor || afModes.contains(CameraMetadata.CONTROL_AF_MODE_OFF)) minFocus else 0f,
            whiteBalances = WhiteBalance.entries.filter { it == WhiteBalance.Auto || awbModes.contains(it.awbMode) },
            evRange = if (exposure.isExposureCompensationSupported) {
                exposure.exposureCompensationRange.let { it.lower..it.upper }
            } else {
                0..0
            },
            evStep = exposure.exposureCompensationStep.toFloat(),
            raw = rawSupported,
            afRegions = (camera2.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) > 0,
            activeArrayWidth = activeArray?.width() ?: 0,
            activeArrayHeight = activeArray?.height() ?: 0,
        )
    }

    /** A square in the middle of the frame, a fifth of its short side. */
    private fun centerRegion(caps: CameraCapabilities): MeteringRectangle {
        val side = (minOf(caps.activeArrayWidth, caps.activeArrayHeight) / 5).coerceAtLeast(1)
        return MeteringRectangle(
            (caps.activeArrayWidth - side) / 2,
            (caps.activeArrayHeight - side) / 2,
            side,
            side,
            MeteringRectangle.METERING_WEIGHT_MAX,
        )
    }

    private companion object {
        const val TAG = "DaybreakCamera"
        const val LIVE_INTERVAL_MS = 150L
        const val STEADY_FRAME_NS = 33_333_333L

        /** Recording ends that still leave a playable video. */
        val KEPT_ERRORS = setOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
        )
        const val EXPOSURE_SETTLE_MS = 150L
        const val AE_SETTLE_MS = 700L
        const val HDR_AUTO_CLIPPED = 0.005f
        const val HDR_AUTO_DARK = -1.5f
        const val MAX_SHUTTER_NS = 30_000_000_000L
        // ISO 400 at 1/100 s, used before anything was metered.
        const val DEFAULT_EXPOSURE = 400.0 * 10_000_000L

        val previewResolution: ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()

        // About 12 MP: full detail on most phones without 50 MP bitmaps in memory.
        val captureResolution: ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()

        val analysisResolution: ResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
            )
            .build()

        /** A CameraX in-memory capture as an ARGB bitmap plus its JPEG for EXIF. */
        fun ImageProxy.toCapturedFrame(stem: String, format: PhotoFormat): CapturedFrame {
            val rotation = imageInfo.rotationDegrees
            val full = Rect(0, 0, width, height)
            return if (this.format == ImageFormat.JPEG) {
                val buffer = planes[0].buffer
                buffer.rewind()
                val jpeg = ByteArray(buffer.remaining())
                buffer.get(jpeg)
                val decoded = decodeJpeg(jpeg)
                CapturedFrame(decoded.cropTo(cropRect, full), rotation, jpeg, stem, format)
            } else {
                CapturedFrame(toBitmap(), rotation, null, stem, format)
            }
        }

        /** A JPEG the camera wrote to a file (RAW mode); its rotation is in EXIF. */
        fun frameFromJpeg(jpeg: ByteArray, stem: String, format: PhotoFormat): CapturedFrame {
            val orientation = ExifInterface(ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            return CapturedFrame(decodeJpeg(jpeg), rotation, jpeg, stem, format)
        }

        fun decodeJpeg(jpeg: ByteArray): Bitmap {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
                ?: error("Could not read the camera frame")
        }

        fun Bitmap.cropTo(crop: Rect, full: Rect): Bitmap {
            if (crop == full || crop.isEmpty) return this
            val left = crop.left.coerceIn(0, width - 1)
            val top = crop.top.coerceIn(0, height - 1)
            val w = crop.width().coerceAtMost(width - left)
            val h = crop.height().coerceAtMost(height - top)
            if (w <= 0 || h <= 0 || (w == width && h == height)) return this
            val cropped = Bitmap.createBitmap(this, left, top, w, h)
            if (cropped !== this) recycle()
            return cropped
        }
    }
}
