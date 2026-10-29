@file:OptIn(ExperimentalMaterial3Api::class)

package com.brekwol.daybreak.viewer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.ExportFormat
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.data.PhotoFormat
import com.brekwol.daybreak.designsystem.Daybreak
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class ShareKind { Jpeg, Original, Png, Raw }

private enum class ShareSize(val label: String, val longSide: Int) {
    Full("Full", 0),
    Large("2048 px", 2048),
    Small("1080 px", 1080),
}

/**
 * Choose what the other person receives: a JPEG or PNG copy (optionally
 * smaller), the original file, or the RAW file. Copies can also be saved to
 * Download/Daybreak.
 */
@Composable
fun ShareSheet(photo: Photo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The DNG offered separately: the one paired with this photo (a DNG-only photo is its own original).
    val raw = photo.raw
    var kind by remember { mutableStateOf(ShareKind.Jpeg) }
    var size by remember { mutableStateOf(ShareSize.Full) }
    var busy by remember { mutableStateOf(false) }
    val converted = kind == ShareKind.Jpeg || kind == ShareKind.Png
    val exportFormat = if (kind == ShareKind.Png) ExportFormat.Png else ExportFormat.Jpeg

    fun finish() {
        scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() }
    }

    fun act(save: Boolean) {
        if (busy) return
        when (kind) {
            ShareKind.Original -> {
                sendUri(context, photo.uri, photo.format.mime, photo.name)
                finish()
            }
            ShareKind.Raw -> raw?.let {
                sendUri(context, it.uri, PhotoFormat.Dng.mime, it.name)
                finish()
            }
            ShareKind.Jpeg, ShareKind.Png -> {
                busy = true
                scope.launch {
                    try {
                        val file = container.exporter.export(photo, exportFormat, size.longSide)
                        if (save) {
                            container.photos.saveCopyToDownloads(file, exportFormat.mime)
                            Toast.makeText(context, "Saved to Download/Daybreak", Toast.LENGTH_SHORT).show()
                        } else {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            sendUri(context, uri, exportFormat.mime, file.name)
                        }
                        finish()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Toast.makeText(context, error.message ?: "Could not prepare the copy", Toast.LENGTH_LONG).show()
                    } catch (tooBig: OutOfMemoryError) {
                        Toast.makeText(context, "This photo is too large to convert here", Toast.LENGTH_LONG).show()
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = colors.surface100,
        contentColor = colors.ink,
        scrimColor = colors.scrim,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Share 1 photo", style = Daybreak.type.title, color = colors.ink)
                Text(
                    if (photo.format == PhotoFormat.Jxl) {
                        "Many apps can't open JPEG XL yet. Pick what the other person will receive."
                    } else {
                        "Pick what the other person will receive."
                    },
                    style = Daybreak.type.caption,
                    color = colors.inkMuted,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareOption("Compatible · JPEG", "Opens everywhere. 8-bit, no HDR.", kind == ShareKind.Jpeg) {
                    kind = ShareKind.Jpeg
                }
                ShareOption(
                    "Original · ${photo.format.label}",
                    "Full quality, ${formatBytes(photo.sizeBytes)}. The other app must support ${photo.format.badge}.",
                    kind == ShareKind.Original,
                ) { kind = ShareKind.Original }
                ShareOption("Lossless · PNG", "Large file. Good for print or design work.", kind == ShareKind.Png) {
                    kind = ShareKind.Png
                }
                if (raw != null) {
                    ShareOption(
                        "RAW · DNG",
                        "${formatBytes(raw.sizeBytes)}. For editors like Lightroom and Snapseed.",
                        kind == ShareKind.Raw,
                    ) { kind = ShareKind.Raw }
                }
            }
            if (converted) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Size", style = Daybreak.type.body, color = colors.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ShareSize.entries.forEach { option ->
                            val selected = option == size
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) colors.sunGlow else colors.surface200)
                                    .clickable(role = Role.RadioButton) { size = option }
                                    .semantics { this.selected = selected },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(option.label, style = Daybreak.type.readout, color = if (selected) colors.sun else colors.ink)
                            }
                        }
                    }
                }
            }
            Text(
                if (converted) {
                    "Copies carry no location or camera info. Your original keeps it."
                } else {
                    "The file is sent as it is, with its camera info."
                },
                style = Daybreak.type.caption,
                color = colors.inkMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (converted) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(colors.surface200)
                            .clickable(enabled = !busy, role = Role.Button) { act(save = true) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Save copy", style = Daybreak.type.body, color = colors.ink)
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(2f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.sun)
                        .clickable(enabled = !busy, role = Role.Button) { act(save = false) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(color = colors.onSun, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        Text("Share", style = Daybreak.type.body, color = colors.onSun)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareOption(title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Daybreak.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(2.dp, if (selected) colors.sun else Color.Transparent, RoundedCornerShape(14.dp))
            .background(colors.surface200)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, if (selected) colors.sun else colors.inkMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.sun))
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Daybreak.type.body, color = colors.ink)
            Text(body, style = Daybreak.type.caption, color = colors.inkMuted)
        }
    }
}

private fun sendUri(context: Context, uri: Uri, mime: String, name: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share photo")) }
        .onFailure { Toast.makeText(context, "No app can receive this file", Toast.LENGTH_LONG).show() }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%d KB", bytes / 1_000)
    else -> "$bytes B"
}
