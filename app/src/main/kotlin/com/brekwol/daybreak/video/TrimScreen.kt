package com.brekwol.daybreak.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.edit.PrimaryPill
import com.brekwol.daybreak.edit.TextAction
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STRIP_FRAMES = 8
private const val MIN_CLIP_MS = 500L

/**
 * Cuts a video to a shorter clip and can remove its sound. The clip is saved
 * as a new video; the original stays as it is.
 */
@Composable
fun TrimScreen(video: Photo, onClose: () -> Unit, onSaved: (Photo) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val scope = rememberCoroutineScope()
    val player = rememberPlayerState()
    val duration = if (player.durationMs > 0) player.durationMs else video.durationMs
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(0L) }
    var mute by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Where the saved clip will really start: the key frame at or before startMs.
    var keyStartMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(duration) {
        if (duration > 0 && endMs == 0L) endMs = duration
    }
    LaunchedEffect(startMs, endMs) {
        player.startMs = startMs
        player.endMs = if (endMs > 0) endMs else null
    }
    LaunchedEffect(startMs) {
        keyStartMs = container.trimmer.keyFrameAtOrBefore(video, startMs)
    }

    BackHandler(enabled = saving) { }

    val frames by produceState<List<Bitmap>>(emptyList(), video.uri) {
        value = withContext(Dispatchers.IO) { stripFrames(context, video, STRIP_FRAMES) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface000),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction("Cancel", onClick = { if (!saving) onClose() })
            Text(
                "Trim",
                style = Daybreak.type.body,
                color = colors.ink,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val changed = startMs > 0L || (endMs in 1 until duration) || mute
            PrimaryPill(
                "Save",
                enabled = changed && !saving && endMs - startMs >= MIN_CLIP_MS,
                onClick = {
                    saving = true
                    player.pause()
                    scope.launch {
                        try {
                            val clip = container.trimmer.trim(video, startMs, endMs, mute)
                            Toast.makeText(context, "Saved as a new video", Toast.LENGTH_SHORT).show()
                            onSaved(clip)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            saving = false
                            Toast.makeText(context, error.message ?: "Could not save the clip", Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            VideoSurface(video.uri, player, Modifier.fillMaxSize())
            if (saving) {
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.scrim)
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = colors.sun)
                    Spacer(Modifier.height(10.dp))
                    Text("Saving the clip…", style = Daybreak.type.label, color = colors.ink)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayButton(playing = player.playing, onClick = { player.toggle() })
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${preciseTime(startMs)} → ${preciseTime(endMs)}",
                        style = Daybreak.type.readout,
                        color = colors.sun,
                    )
                    Text(
                        String.format(Locale.US, "%.1f s", (endMs - startMs).coerceAtLeast(0L) / 1000.0),
                        style = Daybreak.type.caption,
                        color = colors.inkMuted,
                    )
                }
            }
            TrimStrip(
                frames = frames,
                durationMs = duration,
                startMs = startMs,
                endMs = endMs,
                positionMs = player.positionMs,
                onStart = {
                    startMs = it.coerceIn(0L, (endMs - MIN_CLIP_MS).coerceAtLeast(0L))
                    player.seekTo(startMs)
                },
                onEnd = {
                    endMs = it.coerceIn(startMs + MIN_CLIP_MS, duration)
                    player.seekTo(endMs)
                },
                onSeek = { player.seekTo(it.coerceIn(startMs, endMs)) },
                enabled = !saving && duration > 0,
            )
            if (abs(keyStartMs - startMs) >= 100) {
                Text(
                    "The clip starts at ${preciseTime(keyStartMs)}, the nearest key frame. Nothing is re-encoded, so the quality stays exactly the same.",
                    style = Daybreak.type.caption,
                    color = colors.inkMuted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Remove sound", style = Daybreak.type.body, color = colors.ink)
                    Text("The clip is saved without its audio.", style = Daybreak.type.caption, color = colors.inkMuted)
                }
                Switch(
                    checked = mute,
                    onCheckedChange = { if (!saving) mute = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.onSun,
                        checkedTrackColor = colors.sun,
                        uncheckedThumbColor = colors.inkMuted,
                        uncheckedTrackColor = colors.surface200,
                        uncheckedBorderColor = colors.line,
                    ),
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 0:03.4 */
private fun preciseTime(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0L) + 50) / 100
    val seconds = tenths / 10
    return String.format(Locale.US, "%d:%02d.%d", seconds / 60, seconds % 60, tenths % 10)
}

/** [count] frames spread over the video, small, for the strip. */
private fun stripFrames(context: android.content.Context, video: Photo, count: Int): List<Bitmap> {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, video.uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            ?: video.durationMs
        (0 until count).mapNotNull { i ->
            val timeUs = (durationMs * 1000L) * (2 * i + 1) / (2 * count)
            runCatching {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
            }.getOrNull()
        }
    } catch (error: Exception) {
        emptyList()
    } finally {
        retriever.release()
    }
}

/** The video as a strip of frames, with handles for the clip's start and end and the playhead. */
@Composable
private fun TrimStrip(
    frames: List<Bitmap>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    positionMs: Long,
    onStart: (Long) -> Unit,
    onEnd: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    enabled: Boolean,
) {
    val colors = Daybreak.colors
    val currentStart by rememberUpdatedState(startMs)
    val currentEnd by rememberUpdatedState(endMs)
    val currentDuration by rememberUpdatedState(durationMs)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val images = remember(frames) { frames.map { it.asImageBitmap() } }
    val handle = 14.dp

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = "Clip from ${preciseTime(startMs)} to ${preciseTime(endMs)}" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val pad = handle.toPx()
                fun timeAt(x: Float): Long {
                    val width = (size.width - 2 * pad).coerceAtLeast(1f)
                    return (((x - pad) / width).coerceIn(0f, 1f) * currentDuration).roundToInt().toLong()
                }
                fun xOf(ms: Long): Float {
                    val width = size.width - 2 * pad
                    return pad + if (currentDuration > 0) width * ms / currentDuration else 0f
                }
                var dragging = 0 // 1 start, 2 end, 3 playhead
                detectDragGestures(
                    onDragStart = { at ->
                        val toStart = abs(at.x - (xOf(currentStart) - pad / 2))
                        val toEnd = abs(at.x - (xOf(currentEnd) + pad / 2))
                        dragging = when {
                            toStart < pad * 2.5f && toStart <= toEnd -> 1
                            toEnd < pad * 2.5f -> 2
                            else -> 3
                        }
                    },
                ) { change, _ ->
                    change.consume()
                    val x = change.position.x
                    when (dragging) {
                        1 -> currentOnStart(timeAt(x + pad / 2))
                        2 -> currentOnEnd(timeAt(x - pad / 2))
                        else -> currentOnSeek(timeAt(x))
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val pad = handle.toPx()
                detectTapGestures { at ->
                    val width = (size.width - 2 * pad).coerceAtLeast(1f)
                    currentOnSeek((((at.x - pad) / width).coerceIn(0f, 1f) * currentDuration).toLong())
                }
            },
    ) {
        val pad = handle.toPx()
        val width = size.width - 2 * pad
        val radius = CornerRadius(8.dp.toPx())
        // Frames side by side.
        drawRoundRect(colors.surface200, topLeft = Offset(pad, 0f), size = Size(width, size.height), cornerRadius = radius)
        if (images.isNotEmpty()) {
            val each = width / images.size
            images.forEachIndexed { i, image ->
                val left = pad + i * each
                // Center-crop each frame into its cell.
                val scale = maxOf(each / image.width, size.height / image.height)
                val srcW = (each / scale).roundToInt().coerceIn(1, image.width)
                val srcH = (size.height / scale).roundToInt().coerceIn(1, image.height)
                drawImage(
                    image = image,
                    srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset(left.roundToInt(), 0),
                    dstSize = IntSize(each.roundToInt() + 1, size.height.roundToInt()),
                )
            }
        }
        val x0 = pad + if (durationMs > 0) width * startMs / durationMs else 0f
        val x1 = pad + if (durationMs > 0) width * endMs / durationMs else width
        // Dim what is cut away.
        drawRect(colors.scrim, topLeft = Offset(pad, 0f), size = Size((x0 - pad).coerceAtLeast(0f), size.height))
        drawRect(colors.scrim, topLeft = Offset(x1, 0f), size = Size((pad + width - x1).coerceAtLeast(0f), size.height))
        // The kept part, framed in sun with grips at both ends.
        val stroke = 3.dp.toPx()
        drawRect(colors.sun, topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(0f), size.height), style = Stroke(stroke))
        drawRoundRect(colors.sun, topLeft = Offset(x0 - pad, 0f), size = Size(pad, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
        drawRoundRect(colors.sun, topLeft = Offset(x1, 0f), size = Size(pad, size.height), cornerRadius = CornerRadius(4.dp.toPx()))
        val grip = colors.onSun
        drawLine(grip, Offset(x0 - pad / 2, size.height * 0.35f), Offset(x0 - pad / 2, size.height * 0.65f), strokeWidth = 2.dp.toPx())
        drawLine(grip, Offset(x1 + pad / 2, size.height * 0.35f), Offset(x1 + pad / 2, size.height * 0.65f), strokeWidth = 2.dp.toPx())
        // Playhead.
        if (durationMs > 0) {
            val px = pad + width * positionMs.coerceIn(0L, durationMs) / durationMs
            drawLine(colors.ink, Offset(px, -2f), Offset(px, size.height + 2f), strokeWidth = 2.dp.toPx())
        }
    }
}
