package com.brekwol.daybreak.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.ZoomState
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.CapturedFrame
import com.brekwol.daybreak.data.DaybreakSettings
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakChip
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.FormatBadge
import com.brekwol.daybreak.designsystem.R as DsR
import com.brekwol.daybreak.designsystem.ShutterButton
import java.util.concurrent.Executors
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

    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = DaybreakSettings())
    val pending by container.saveQueue.pending.collectAsStateWithLifecycle()
    val lastSaved by container.saveQueue.lastSaved.collectAsStateWithLifecycle()

    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        }
    }
    val frameExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
        }
    }
    DisposableEffect(Unit) {
        onDispose { frameExecutor.shutdown() }
    }

    var useBackCamera by remember { mutableStateOf(true) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var zoomOptions by remember { mutableStateOf(listOf(1f, 2f)) }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = Observer<ZoomState> { state ->
            zoomRatio = state.zoomRatio
            zoomOptions = buildList {
                if (state.minZoomRatio < 0.95f) add(state.minZoomRatio)
                add(1f)
                if (state.maxZoomRatio >= 2f) add(2f)
                if (state.maxZoomRatio >= 5f) add(5f)
            }
        }
        controller.zoomState.observe(lifecycleOwner, observer)
        onDispose { controller.zoomState.removeObserver(observer) }
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

    val flash = remember { Animatable(0f) }

    fun capture() {
        if (!container.saveQueue.canAcceptMore) {
            Toast.makeText(context, "Still saving, one moment", Toast.LENGTH_SHORT).show()
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(durationMillis = 180))
        }
        controller.takePicture(
            frameExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val frame = try {
                        image.toCapturedFrame()
                    } catch (error: Exception) {
                        container.saveQueue.reportError(error.message ?: "Could not read the camera frame")
                        null
                    } finally {
                        image.close()
                    }
                    if (frame != null) container.saveQueue.submit(frame)
                }

                override fun onError(exception: ImageCaptureException) {
                    container.saveQueue.reportError(exception.message ?: "The camera could not take the photo")
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // Top bar: what the next file will be, grid, settings.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatBadge(
                format = "JXL",
                detail = if (settings.lossless) "Lossless" else "Q${settings.quality}",
                detailColor = if (settings.lossless) colors.focus else colors.inkMuted,
                onClick = onOpenSettings,
                modifier = Modifier.padding(start = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            DaybreakIconButton(
                iconRes = DsR.drawable.ic_grid,
                contentDescription = if (settings.grid) "Grid on" else "Grid off",
                tint = if (settings.grid) colors.sun else colors.ink,
                onClick = { scope.launch { container.settings.setGrid(!settings.grid) } },
            )
            DaybreakIconButton(
                iconRes = DsR.drawable.ic_settings,
                contentDescription = "Settings",
                onClick = onOpenSettings,
            )
        }

        // Viewfinder, 3:4.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f),
        ) {
            AndroidView(
                factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        this.controller = controller
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = "Camera viewfinder" },
            )
            if (settings.grid) ThirdsGrid(Modifier.fillMaxSize())
            ZoomPill(
                options = zoomOptions,
                current = zoomRatio,
                onSelect = { ratio ->
                    zoomRatio = ratio
                    controller.setZoomRatio(ratio)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flash.value)),
            )
        }

        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PHOTO", style = Daybreak.type.mode, color = colors.sun)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(colors.sun),
            )
        }

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
            ShutterButton(onClick = ::capture)
            DaybreakIconButton(
                iconRes = DsR.drawable.ic_flip,
                contentDescription = if (useBackCamera) "Switch to front camera" else "Switch to back camera",
                background = colors.surface200,
                size = 52.dp,
                onClick = {
                    val next = if (useBackCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    if (controller.hasCamera(next)) {
                        controller.cameraSelector = next
                        useBackCamera = !useBackCamera
                    }
                },
            )
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

/** Turns a CameraX in-memory capture into an ARGB bitmap plus its JPEG for EXIF. */
private fun ImageProxy.toCapturedFrame(): CapturedFrame {
    val rotation = imageInfo.rotationDegrees
    val full = android.graphics.Rect(0, 0, width, height)
    return if (format == ImageFormat.JPEG) {
        val buffer = planes[0].buffer
        buffer.rewind()
        val jpeg = ByteArray(buffer.remaining())
        buffer.get(jpeg)
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: error("Could not read the camera frame")
        CapturedFrame(decoded.cropTo(cropRect, full), rotation, jpeg)
    } else {
        CapturedFrame(toBitmap(), rotation, null)
    }
}

private fun Bitmap.cropTo(crop: android.graphics.Rect, full: android.graphics.Rect): Bitmap {
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
