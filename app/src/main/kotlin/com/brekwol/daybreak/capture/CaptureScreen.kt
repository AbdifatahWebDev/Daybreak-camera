package com.brekwol.daybreak.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brekwol.daybreak.camera.BurstKind
import com.brekwol.daybreak.camera.CameraSession
import com.brekwol.daybreak.camera.FocusMode
import com.brekwol.daybreak.camera.ProSettings
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.DaybreakSettings
import com.brekwol.daybreak.data.HdrMode
import com.brekwol.daybreak.data.PhotoFormat
import com.brekwol.daybreak.data.SaveQueue
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakChip
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.FormatBadge
import com.brekwol.daybreak.designsystem.R as DsR
import com.brekwol.daybreak.designsystem.ShutterButton
import com.brekwol.daybreak.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

@Composable
fun CaptureScreen(
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        denied = !granted
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Daybreak.colors.surface000),
    ) {
        if (hasPermission) {
            CameraContent(onOpenLibrary = onOpenLibrary, onOpenSettings = onOpenSettings)
        } else {
            PermissionRequest(
                denied = denied,
                onAllow = { launcher.launch(Manifest.permission.CAMERA) },
                onOpenAndroidSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onOpenLibrary = onOpenLibrary,
            )
        }
    }
}

@Composable
private fun CameraContent(
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = context.container
    val colors = Daybreak.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val loadedSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val settings = loadedSettings ?: DaybreakSettings()
    val pending by container.saveQueue.pending.collectAsStateWithLifecycle()
    val lastSaved by container.saveQueue.lastSaved.collectAsStateWithLifecycle()
    val update by container.updates.state.collectAsStateWithLifecycle()
    val updateWaiting = update is UpdateState.Available || update is UpdateState.Ready

    val session = remember { CameraSession(context) }
    DisposableEffect(session) {
        onDispose { session.release() }
    }
    val camera by session.state.collectAsStateWithLifecycle()
    val live by session.live.collectAsStateWithLifecycle()
    // Fast-changing values are passed on as State and read only by the small
    // overlays that draw them, so the whole screen doesn't redraw 10+ times a second.
    val histogram = session.histogram.collectAsStateWithLifecycle()
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }

    var backCamera by rememberSaveable { mutableStateOf(true) }
    var mode by rememberSaveable { mutableStateOf(CaptureMode.Photo) }
    var pro by rememberSaveable(stateSaver = ProSettings.Saver) { mutableStateOf(ProSettings()) }
    var selectedControl by remember { mutableStateOf<ProControl?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }

    val settingsLoaded = loadedSettings != null
    // Frames are always analyzed (for the exposure meter); the setting only shows the graph.
    // Night photos are merged from in-memory frames, so Night mode runs without RAW.
    val useRaw = settings.raw && mode != CaptureMode.Night
    LaunchedEffect(settingsLoaded, backCamera, useRaw, lifecycleOwner) {
        if (settingsLoaded) {
            session.bind(
                owner = lifecycleOwner,
                surfaceProvider = previewView.surfaceProvider,
                backCamera = backCamera,
                raw = useRaw,
                histogram = true,
            )
        }
    }
    // Frames taken and merging state of a Night or HDR burst, or null.
    var burstProgress by remember { mutableStateOf<BurstProgress?>(null) }
    val meter = session.meter.collectAsStateWithLifecycle()
    // Dials change values many times a second; the camera gets at most about 20
    // updates a second (always the newest), so the viewfinder doesn't stutter.
    LaunchedEffect(camera.ready) {
        if (!camera.ready) return@LaunchedEffect
        snapshotFlow { if (mode == CaptureMode.Pro) pro else ProSettings() }
            .conflate()
            .collect { settings ->
                session.applyPro(settings)
                delay(APPLY_INTERVAL_MS)
            }
    }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(1200)
            focusPoint = null
        }
    }

    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(lastSaved) {
        val photo = lastSaved ?: container.photos.list().firstOrNull()
        thumbnail = photo?.let { container.thumbnails.load(it, 160) }
    }
    LaunchedEffect(Unit) {
        container.saveQueue.errors.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val roll = rememberRoll(enabled = settings.level)
    // A light tick when the phone becomes level. It must tilt past 1.5° before it can tick again.
    LaunchedEffect(settings.level) {
        var level = false
        snapshotFlow { roll.value?.let { kotlin.math.abs(levelError(it)) } }.collect { error ->
            val next = when {
                error == null -> false
                level -> error < 1.5f
                else -> error < 0.8f
            }
            if (next && !level) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            level = next
        }
    }
    val flash = remember { Animatable(0f) }

    fun capture() {
        if (burstProgress != null) return
        val waiting = container.saveQueue.pending.value + session.capturesInFlight
        if (waiting >= SaveQueue.MAX_PENDING) {
            Toast.makeText(context, "Still saving, one moment", Toast.LENGTH_SHORT).show()
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(durationMillis = 180))
        }
        val burstKind = when {
            mode == CaptureMode.Night -> BurstKind.Night
            mode == CaptureMode.Photo && !camera.rawActive && (
                settings.hdr == HdrMode.On || (settings.hdr == HdrMode.Auto && session.wantsHdr())
            ) -> BurstKind.Hdr
            else -> null
        }
        if (burstKind != null) {
            val plan = session.planBurst(burstKind)
            burstProgress = BurstProgress(plan.kind, taken = 0, total = plan.frames)
            scope.launch {
                try {
                    val frame = session.takeBurst(plan, container.photos.newStem(), settings.format) { taken ->
                        burstProgress = BurstProgress(plan.kind, taken = taken, total = plan.frames)
                    }
                    container.saveQueue.submit(frame)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    container.saveQueue.reportError(error.message ?: "Could not take the photo")
                } catch (tooBig: OutOfMemoryError) {
                    container.saveQueue.reportError("Not enough memory to merge the photos")
                } finally {
                    burstProgress = null
                }
            }
            return
        }
        session.takePicture(
            stem = container.photos.newStem(),
            format = settings.format,
            photos = container.photos,
            deliverFrame = { frame -> container.saveQueue.submit(frame) },
            rawSaved = { container.photos.notifyChanged() },
            reportError = { message -> container.saveQueue.reportError(message) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // Top bar: what the next file will be, RAW, grid, settings.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val jxlLossless = settings.format == PhotoFormat.Jxl && settings.lossless
            FormatBadge(
                format = if (settings.format == PhotoFormat.Heic && !container.heic.isSupported) {
                    PhotoFormat.Jxl.badge
                } else {
                    settings.format.badge
                },
                detail = if (jxlLossless) "Lossless" else "Q${settings.quality}",
                detailColor = if (jxlLossless) colors.focus else colors.inkMuted,
                onClick = onOpenSettings,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (camera.capabilities.raw) {
                Spacer(Modifier.width(6.dp))
                DaybreakChip(
                    text = "RAW",
                    selected = settings.raw,
                    mono = true,
                    onClick = { scope.launch { container.settings.setRaw(!settings.raw) } },
                    modifier = Modifier.semantics {
                        contentDescription = if (settings.raw) "RAW on" else "RAW off"
                    },
                )
            }
            if (mode == CaptureMode.Photo && !useRaw) {
                Spacer(Modifier.width(6.dp))
                DaybreakChip(
                    text = when (settings.hdr) {
                        HdrMode.Auto -> "HDR A"
                        HdrMode.On -> "HDR"
                        HdrMode.Off -> "HDR off"
                    },
                    selected = settings.hdr == HdrMode.On,
                    mono = true,
                    onClick = { scope.launch { container.settings.setHdr(settings.hdr.next) } },
                    modifier = Modifier.semantics { contentDescription = "HDR ${settings.hdr.label}" },
                )
            }
            Spacer(Modifier.weight(1f))
            DaybreakIconButton(
                iconRes = DsR.drawable.ic_grid,
                contentDescription = if (settings.grid) "Grid on" else "Grid off",
                tint = if (settings.grid) colors.sun else colors.ink,
                onClick = { scope.launch { container.settings.setGrid(!settings.grid) } },
            )
            Box {
                DaybreakIconButton(
                    iconRes = DsR.drawable.ic_settings,
                    contentDescription = if (updateWaiting) "Settings, update available" else "Settings",
                    onClick = onOpenSettings,
                )
                if (updateWaiting) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.sun),
                    )
                }
            }
        }

        // Viewfinder, 3:4.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Camera viewfinder" },
            )
            // Tap to focus, pinch to zoom.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(session) {
                        detectTapGestures { offset ->
                            if (session.focusAt(previewView.meteringPointFactory, offset.x, offset.y)) {
                                focusPoint = offset
                            }
                        }
                    }
                    .pointerInput(session) {
                        detectTransformGestures { _, _, zoom, _ ->
                            if (zoom != 1f) session.setZoom(session.state.value.zoomRatio * zoom)
                        }
                    },
            )
            if (settings.grid) ThirdsGrid(Modifier.fillMaxSize())
            if (settings.level) LiveLevel(roll, Modifier.fillMaxSize())
            focusPoint?.let { point -> FocusRing(point, Modifier.fillMaxSize()) }
            if (mode == CaptureMode.Pro && pro.focusMode == FocusMode.Center && camera.capabilities.centerFocus) {
                CenterFocusBox(Modifier.fillMaxSize())
            }
            if (settings.histogram) {
                LiveHistogram(
                    state = histogram,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .size(width = 112.dp, height = 56.dp),
                )
            }
            // With ISO or shutter set by hand, show how far the exposure is from normal.
            if (mode == CaptureMode.Pro && pro.manualExposure) {
                LiveMeter(
                    state = meter,
                    onBalance = { ev -> pro = pro.balanced(ev, camera.capabilities, session.live.value) },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                )
            }
            camera.error?.let { message ->
                Text(
                    text = message,
                    style = Daybreak.type.body,
                    color = colors.ink,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                )
            }

            val control = selectedControl
            if (mode == CaptureMode.Pro && control != null) {
                ProDial(
                    control = control,
                    pro = pro,
                    capabilities = camera.capabilities,
                    live = live,
                    onChange = { pro = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                )
            } else {
                ZoomPill(
                    options = camera.zoomOptions,
                    current = camera.zoomRatio,
                    onSelect = { ratio -> session.setZoom(ratio) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                )
            }
            if (mode == CaptureMode.Night && burstProgress == null && camera.ready) {
                val seconds = ((session.planBurst(BurstKind.Night).captureMs + 500) / 1000).coerceAtLeast(1)
                Text(
                    text = "Night · hold still about $seconds s",
                    style = Daybreak.type.caption,
                    color = colors.ink,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.scrim)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            burstProgress?.let { progress ->
                BurstOverlay(progress, Modifier.align(Alignment.Center))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flash.value)),
            )
        }

        if (mode == CaptureMode.Pro) {
            Spacer(Modifier.height(10.dp))
            ProControlBar(
                pro = pro,
                capabilities = camera.capabilities,
                live = live,
                selected = selectedControl,
                onSelect = { selectedControl = it },
                onAllAuto = {
                    pro = ProSettings()
                    selectedControl = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(10.dp))
        ModeSwitcher(
            mode = mode,
            onMode = {
                mode = it
                selectedControl = null
            },
        )

        // Shutter row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LastPhotoButton(thumbnail = thumbnail, pending = pending, onClick = onOpenLibrary)
            ShutterButton(onClick = ::capture, enabled = camera.ready && burstProgress == null)
            DaybreakIconButton(
                iconRes = DsR.drawable.ic_flip,
                contentDescription = if (backCamera) "Switch to front camera" else "Switch to back camera",
                background = colors.surface200,
                size = 52.dp,
                onClick = { backCamera = !backCamera },
            )
        }
    }
}

private const val APPLY_INTERVAL_MS = 50L

/** How far a Night or HDR burst has got. */
private data class BurstProgress(val kind: BurstKind, val taken: Int, val total: Int) {
    val merging: Boolean get() = taken >= total
}

/** "Hold still" with a ring that fills as frames are taken, then "Merging…". */
@Composable
private fun BurstOverlay(progress: BurstProgress, modifier: Modifier = Modifier) {
    val colors = Daybreak.colors
    val fraction = if (progress.total > 0) progress.taken.toFloat() / progress.total else 0f
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.scrim)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics {
                contentDescription = if (progress.merging) "Merging the photo" else "Hold still, frame ${progress.taken} of ${progress.total}"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 4.dp.toPx()
                drawCircle(colors.line, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
                drawArc(
                    color = colors.sun,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
            Text(
                if (progress.merging) "…" else "${progress.taken}/${progress.total}",
                style = Daybreak.type.readout,
                color = colors.ink,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                progress.merging -> "Merging…"
                progress.kind == BurstKind.Night -> "Hold still"
                else -> "HDR · hold still"
            },
            style = Daybreak.type.label,
            color = colors.ink,
        )
    }
}

@Composable
private fun ModeSwitcher(mode: CaptureMode, onMode: (CaptureMode) -> Unit) {
    val colors = Daybreak.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        CaptureMode.entries.forEach { option ->
            val selected = option == mode
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onMode(option) }
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .semantics { contentDescription = "${option.label} mode" + if (selected) ", selected" else "" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = option.label.uppercase(),
                    style = Daybreak.type.mode,
                    color = if (selected) colors.sun else colors.inkMuted,
                )
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(if (selected) colors.sun else Color.Transparent),
                )
            }
        }
    }
}

/**
 * How many stops the preview is from a normal exposure. Tapping it moves the
 * manual ISO and shutter to a normal exposure.
 */
@Composable
private fun ExposureMeter(ev: Float, onBalance: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Daybreak.colors
    val off = kotlin.math.abs(ev)
    val text = String.format(java.util.Locale.US, "%+.1f EV", ev).replace('-', '−')
    val hint = when {
        off < 0.4f -> "balanced"
        ev < 0 -> if (off >= 9.5f) "far too dark · tap to fix" else "too dark · tap to fix"
        else -> if (off >= 9.5f) "far too bright · tap to fix" else "too bright · tap to fix"
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.scrim)
            .clickable(enabled = off >= 0.4f, onClick = onBalance)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "Exposure $text, $hint" },
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = text,
            style = Daybreak.type.readout,
            color = when {
                off < 0.4f -> colors.sun
                off < 2f -> colors.ink
                else -> colors.record
            },
        )
        Text(hint, style = Daybreak.type.caption, color = colors.inkMuted)
    }
}

@Composable
private fun LiveLevel(roll: State<Float?>, modifier: Modifier = Modifier) {
    val current = roll.value ?: return
    LevelOverlay(roll = current, levelColor = Daybreak.colors.sun, modifier = modifier)
}

@Composable
private fun LiveHistogram(state: State<FloatArray?>, modifier: Modifier = Modifier) {
    val bins = state.value ?: return
    HistogramView(bins, modifier)
}

@Composable
private fun LiveMeter(state: State<Float?>, onBalance: (Float) -> Unit, modifier: Modifier = Modifier) {
    val ev = state.value ?: return
    ExposureMeter(ev = ev, onBalance = { onBalance(ev) }, modifier = modifier)
}

@Composable
private fun HistogramView(bins: FloatArray, modifier: Modifier = Modifier) {
    val colors = Daybreak.colors
    Canvas(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.scrim)
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .semantics { contentDescription = "Brightness histogram" },
    ) {
        if (bins.size < 2) return@Canvas
        val step = size.width / (bins.size - 1)
        val path = Path().apply {
            moveTo(0f, size.height)
            bins.forEachIndexed { index, value -> lineTo(index * step, size.height * (1f - value)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun FocusRing(point: Offset, modifier: Modifier = Modifier) {
    val color = Daybreak.colors.sun
    Canvas(modifier) {
        drawCircle(color, radius = 32.dp.toPx(), center = point, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/** Where Center focus looks: a square in the middle, a fifth of the frame's width. */
@Composable
private fun CenterFocusBox(modifier: Modifier = Modifier) {
    val color = Daybreak.colors.ink.copy(alpha = 0.85f)
    Canvas(modifier) {
        val side = size.width / 5f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val arm = side / 4f
        val stroke = 1.5.dp.toPx()
        val corners = listOf(
            Offset(left, top) to Offset(1f, 1f),
            Offset(left + side, top) to Offset(-1f, 1f),
            Offset(left, top + side) to Offset(1f, -1f),
            Offset(left + side, top + side) to Offset(-1f, -1f),
        )
        corners.forEach { (corner, direction) ->
            drawLine(color, corner, corner + Offset(arm * direction.x, 0f), strokeWidth = stroke)
            drawLine(color, corner, corner + Offset(0f, arm * direction.y), strokeWidth = stroke)
        }
    }
}

@Composable
private fun ThirdsGrid(modifier: Modifier) {
    val line = Color(0x2EF4EFE6)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..2) {
            drawLine(line, Offset(w * i / 3f, 0f), Offset(w * i / 3f, h), strokeWidth = 1.dp.toPx())
            drawLine(line, Offset(0f, h * i / 3f), Offset(w, h * i / 3f), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun ZoomPill(
    options: List<Float>,
    current: Float,
    onSelect: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Daybreak.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.scrim)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { ratio ->
            val selected = kotlin.math.abs(current - ratio) < 0.05f
            DaybreakChip(
                text = zoomLabel(ratio, selected),
                selected = selected,
                mono = true,
                onClick = { onSelect(ratio) },
            )
        }
    }
}

private fun zoomLabel(ratio: Float, selected: Boolean): String {
    val text = if (ratio < 1f) {
        "." + ((ratio * 10).toInt())
    } else if (ratio % 1f == 0f) {
        ratio.toInt().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", ratio)
    }
    return if (selected) "$text×" else text
}

@Composable
private fun LastPhotoButton(thumbnail: Bitmap?, pending: Int, onClick: () -> Unit) {
    val colors = Daybreak.colors
    Box(
        modifier = Modifier
            .size(56.dp)
            .semantics {
                contentDescription = if (pending > 0) "Open library, $pending saving" else "Open library"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface200)
                .border(1.5.dp, colors.line, RoundedCornerShape(14.dp))
                .clickable(onClick = onClick),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (pending > 0) {
            CircularProgressIndicator(
                modifier = Modifier.size(56.dp),
                color = colors.sun,
                strokeWidth = 2.dp,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 6.dp, y = (-6).dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.sun)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("$pending", style = Daybreak.type.readout, color = colors.onSun)
            }
        }
    }
}

@Composable
private fun PermissionRequest(
    denied: Boolean,
    onAllow: () -> Unit,
    onOpenAndroidSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    val colors = Daybreak.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.surface200),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(DsR.drawable.ic_camera),
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (denied) "Camera access is off" else "Daybreak needs the camera",
            style = Daybreak.type.title,
            color = colors.ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (denied) {
                "Turn it on in Android Settings, under Apps › Daybreak › Permissions."
            } else {
                "Photos stay on this phone. Daybreak has no account and no tracking."
            },
            style = Daybreak.type.body,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(colors.sun)
                .clickable(onClick = if (denied) onOpenAndroidSettings else onAllow)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(
                text = if (denied) "Open Android settings" else "Allow camera",
                style = Daybreak.type.label,
                color = colors.onSun,
            )
        }
        if (denied) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Try again",
                style = Daybreak.type.label,
                color = colors.ink,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onAllow)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Go to library",
            style = Daybreak.type.label,
            color = colors.inkMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .clickable(onClick = onOpenLibrary)
                .padding(12.dp)
                .width(160.dp),
            textAlign = TextAlign.Center,
        )
    }
}
