package com.brekwol.daybreak.video

import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.brekwol.daybreak.data.formatDuration
import com.brekwol.daybreak.designsystem.Daybreak
import kotlinx.coroutines.delay

/** Playback of one video: position, length, playing or not, and the range it may play in. */
@Stable
class PlayerState {
    var playing by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        internal set
    var durationMs by mutableLongStateOf(0L)
        internal set
    var ready by mutableStateOf(false)
        internal set

    /** Playback stops (and returns to [startMs]) at [endMs]; the whole video when null. */
    var startMs by mutableLongStateOf(0L)
    var endMs by mutableStateOf<Long?>(null)

    internal var view: VideoView? = null

    fun play() {
        val v = view ?: return
        if (!ready) return
        val end = endMs
        if (end != null && positionMs >= end - 50) seekTo(startMs)
        if (positionMs < startMs) seekTo(startMs)
        v.start()
        playing = true
    }

    fun pause() {
        view?.pause()
        playing = false
    }

    fun toggle() = if (playing) pause() else play()

    fun seekTo(ms: Long) {
        val v = view ?: return
        val target = ms.coerceIn(0L, durationMs.coerceAtLeast(0L))
        v.seekTo(target.toInt())
        positionMs = target
    }

    internal fun completed() {
        playing = false
        positionMs = durationMs
    }
}

@Composable
fun rememberPlayerState(): PlayerState = remember { PlayerState() }

/**
 * The video itself, letterboxed and centered, using Android's own player (it
 * handles HEVC and HDR as well as the phone's hardware can). Tap to play or pause.
 */
@Composable
fun VideoSurface(uri: Uri, state: PlayerState, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            FrameLayout(context).apply {
                val video = VideoView(context).apply {
                    setOnPreparedListener { player ->
                        state.durationMs = player.duration.toLong().coerceAtLeast(0L)
                        state.ready = true
                        // Show the first frame instead of black.
                        seekTo(state.startMs.toInt().coerceAtLeast(1))
                    }
                    setOnCompletionListener { state.completed() }
                    setVideoURI(uri)
                }
                addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                state.view = video
            }
        },
        onRelease = { layout ->
            (layout.getChildAt(0) as? VideoView)?.stopPlayback()
            if (state.view?.parent === layout) state.view = null
        },
        modifier = modifier
            .clickable(role = Role.Button, onClickLabel = "Play or pause") { state.toggle() }
            .semantics { contentDescription = "Video" },
    )

    // Follow the playhead while playing, and stop at the end of the range.
    LaunchedEffect(state.playing) {
        while (state.playing) {
            val v = state.view ?: break
            state.positionMs = v.currentPosition.toLong()
            val end = state.endMs
            if (end != null && state.positionMs >= end) {
                state.pause()
                state.seekTo(end)
            }
            delay(50)
        }
    }
}

/** Play or pause, the time, and a scrubber across the whole video. */
@Composable
fun PlayerControls(state: PlayerState, modifier: Modifier = Modifier) {
    val colors = Daybreak.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayButton(playing = state.playing, onClick = { state.toggle() })
        Spacer(Modifier.width(8.dp))
        Text(
            "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
            style = Daybreak.type.readout,
            color = colors.ink,
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
            onValueChange = { fraction -> state.seekTo((fraction * state.durationMs).toLong()) },
            enabled = state.ready,
            colors = SliderDefaults.colors(
                thumbColor = colors.ink,
                activeTrackColor = colors.sun,
                inactiveTrackColor = colors.line,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/** A round play/pause button drawn in the Daybreak style. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Daybreak.colors
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(colors.surface200)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (playing) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
            if (playing) {
                val bar = size.width * 0.32f
                drawRect(colors.ink, topLeft = Offset(size.width * 0.08f, 0f), size = androidx.compose.ui.geometry.Size(bar, size.height))
                drawRect(colors.ink, topLeft = Offset(size.width * 0.6f, 0f), size = androidx.compose.ui.geometry.Size(bar, size.height))
            } else {
                val path = Path().apply {
                    moveTo(size.width * 0.15f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * 0.15f, size.height)
                    close()
                }
                drawPath(path, colors.ink)
            }
        }
    }
}

/** A full-screen-ish box that centers the video. */
@Composable
fun VideoStage(uri: Uri, state: PlayerState, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VideoSurface(uri, state, Modifier.fillMaxSize())
    }
}
