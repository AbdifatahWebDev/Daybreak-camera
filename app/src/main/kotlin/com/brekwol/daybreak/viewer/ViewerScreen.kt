package com.brekwol.daybreak.viewer

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.DeleteResult
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.data.PhotoFormat
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.FormatBadge
import com.brekwol.daybreak.designsystem.R as DsR
import java.io.File
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Loaded {
    data object Loading : Loaded
    data class Ready(val bitmap: Bitmap) : Loaded
    data class Failed(val message: String) : Loaded
}

@Composable
fun ViewerScreen(photo: Photo, onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val scope = rememberCoroutineScope()

    val state by produceState<Loaded>(initialValue = Loaded.Loading, photo.uri) {
        value = try {
            Loaded.Ready(container.decoder.decode(photo, maxDimension = 4096))
        } catch (error: Exception) {
            Loaded.Failed(error.message ?: "Could not open this photo")
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseShare by remember { mutableStateOf(false) }
    // The DNG to offer when sharing: the one paired with this photo, or the photo itself.
    val rawFile = photo.raw ?: photo.takeIf { it.format == PhotoFormat.Dng }
    var busy by remember { mutableStateOf(false) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            container.photos.notifyChanged()
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface000),
    ) {
        when (val current = state) {
            Loaded.Loading -> CircularProgressIndicator(
                color = colors.sun,
                modifier = Modifier.align(Alignment.Center),
            )
            is Loaded.Failed -> Text(
                text = current.message,
                style = Daybreak.type.body,
                color = colors.inkMuted,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            is Loaded.Ready -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "Photo ${photo.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.scrim)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DaybreakIconButton(DsR.drawable.ic_back, "Back to library", onClick = onBack)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = DateFormat.getMediumDateFormat(context).format(Date(photo.dateAddedSeconds * 1000)) +
                            ", " + DateFormat.getTimeFormat(context).format(Date(photo.dateAddedSeconds * 1000)),
                        style = Daybreak.type.label,
                        color = colors.ink,
                    )
                    Text(photo.name, style = Daybreak.type.caption, color = colors.inkMuted)
                }
                FormatBadge(photo.badge, formatSize(photo.sizeBytes + (photo.raw?.sizeBytes ?: 0L)))
                Spacer(Modifier.width(8.dp))
            }

            Spacer(Modifier.weight(1f))

            if (showInfo) {
                InfoPanel(photo = photo, state = state)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.scrim)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                ActionButton(DsR.drawable.ic_share, "Share", colors.ink, enabled = !busy) {
                    if (rawFile != null) {
                        chooseShare = true
                        return@ActionButton
                    }
                    val ready = state as? Loaded.Ready ?: return@ActionButton
                    busy = true
                    scope.launch {
                        try {
                            shareAsJpeg(context, photo, ready.bitmap)
                        } catch (error: Exception) {
                            Toast.makeText(context, error.message ?: "Could not share", Toast.LENGTH_LONG).show()
                        } finally {
                            busy = false
                        }
                    }
                }
                ActionButton(DsR.drawable.ic_info, "Info", if (showInfo) colors.sun else colors.ink) {
                    showInfo = !showInfo
                }
                ActionButton(DsR.drawable.ic_delete, "Delete", colors.record, enabled = !busy) {
                    confirmDelete = true
                }
            }
        }
    }

    if (chooseShare && rawFile != null) {
        AlertDialog(
            onDismissRequest = { chooseShare = false },
            title = { Text("Share") },
            text = {
                Text("A JPEG copy opens in any app. The RAW file is for editors like Lightroom and Snapseed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    chooseShare = false
                    shareRaw(context, rawFile)
                }) { Text("RAW (DNG)", color = colors.sun) }
            },
            dismissButton = {
                TextButton(
                    enabled = state is Loaded.Ready,
                    onClick = {
                        chooseShare = false
                        val ready = state as? Loaded.Ready
                        if (ready != null) {
                            busy = true
                            scope.launch {
                                try {
                                    shareAsJpeg(context, photo, ready.bitmap)
                                } catch (error: Exception) {
                                    Toast.makeText(context, error.message ?: "Could not share", Toast.LENGTH_LONG).show()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                ) { Text("JPEG copy", color = colors.ink) }
            },
            containerColor = colors.surface100,
            titleContentColor = colors.ink,
            textContentColor = colors.inkMuted,
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this photo?") },
            text = {
                Text(
                    if (photo.raw != null) {
                        "The photo and its RAW file are removed from this phone. This can't be undone."
                    } else {
                        "It's removed from this phone. This can't be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        when (val result = container.photos.delete(photo)) {
                            DeleteResult.Deleted -> onBack()
                            DeleteResult.Failed ->
                                Toast.makeText(context, "Could not delete the photo", Toast.LENGTH_LONG).show()
                            is DeleteResult.NeedsConfirmation ->
                                deleteLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                        }
                    }
                }) { Text("Delete", color = colors.record) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = colors.ink) }
            },
            containerColor = colors.surface100,
            titleContentColor = colors.ink,
            textContentColor = colors.inkMuted,
        )
    }
}

@Composable
private fun InfoPanel(photo: Photo, state: Loaded) {
    val colors = Daybreak.colors
    val bitmap = (state as? Loaded.Ready)?.bitmap
    val width = if (photo.width > 0) photo.width else bitmap?.width ?: 0
    val height = if (photo.height > 0) photo.height else bitmap?.height ?: 0
    val raw = photo.raw
    val rows = buildList {
        add("Format" to photo.format.label)
        add("Resolution" to if (width > 0) "$width × $height" else "—")
        add("File size" to formatSize(photo.sizeBytes))
        if (raw != null) add("RAW" to "DNG · ${formatSize(raw.sizeBytes)}")
        add("Saved in" to "DCIM/Daybreak")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface100)
            .padding(horizontal = 14.dp),
    ) {
        rows.forEach { (label, value) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = Daybreak.type.caption, color = colors.inkMuted, modifier = Modifier.weight(1f))
                Text(value, style = Daybreak.type.readout, color = colors.ink)
            }
        }
    }
}

@Composable
private fun ActionButton(
    iconRes: Int,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (enabled) tint else tint.copy(alpha = 0.4f),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(label, style = Daybreak.type.label, color = if (enabled) tint else tint.copy(alpha = 0.4f))
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%d KB", bytes / 1_000)
    else -> "$bytes B"
}

/**
 * Many apps can't open JPEG XL, HEIC or DNG, so sharing sends a temporary JPEG copy.
 * The copy lives in the app cache and never enters the photo library.
 */
private suspend fun shareAsJpeg(context: Context, photo: Photo, preview: Bitmap) {
    val uri: Uri = withContext(Dispatchers.IO) {
        val container = context.container
        val full = try {
            container.decoder.decode(photo, maxDimension = 0)
        } catch (ignored: OutOfMemoryError) {
            preview
        }
        val dir = File(context.cacheDir, "share").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, photo.name.substringBeforeLast('.') + ".jpg")
        file.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (full !== preview) full.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(photo.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share photo"))
}

/** Shares the DNG itself, straight from the photo library. */
private fun shareRaw(context: Context, raw: Photo) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = PhotoFormat.Dng.mime
        putExtra(Intent.EXTRA_STREAM, raw.uri)
        clipData = ClipData.newRawUri(raw.name, raw.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share RAW")) }
        .onFailure { Toast.makeText(context, "No app can receive a DNG file", Toast.LENGTH_LONG).show() }
}
