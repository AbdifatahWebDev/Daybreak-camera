package com.brekwol.daybreak.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A ruler of ticks that slides under a fixed sun-colored needle. Dragging moves
 * [position] (in ticks, 0 until [tickCount]) by one tick per [tickSpacing].
 * Double tap calls [onReset] when given.
 */
@Composable
fun RulerDial(
    position: Float,
    tickCount: Int,
    onPosition: (Float) -> Unit,
    contentDescription: String,
    stateDescription: String,
    modifier: Modifier = Modifier,
    majorEvery: Int = 5,
    tickSpacing: Dp = 9.dp,
    height: Dp = 40.dp,
    onRelease: () -> Unit = {},
    onReset: (() -> Unit)? = null,
) {
    val colors = Daybreak.colors
    val last = (tickCount - 1).coerceAtLeast(0).toFloat()
    val currentPosition by rememberUpdatedState(position)
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnReset by rememberUpdatedState(onReset)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(tickCount, tickSpacing) {
                val spacing = tickSpacing.toPx()
                var raw = 0f
                detectHorizontalDragGestures(
                    onDragStart = { raw = currentPosition },
                    onDragEnd = { currentOnRelease() },
                    onDragCancel = { currentOnRelease() },
                ) { change, dragAmount ->
                    change.consume()
                    // The ruler follows the finger, so dragging left moves to higher values.
                    raw = (raw - dragAmount / spacing).coerceIn(0f, last)
                    currentOnPosition(raw)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnReset?.invoke() })
            }
            .semantics {
                this.contentDescription = contentDescription
                this.stateDescription = stateDescription
                progressBarRangeInfo = ProgressBarRangeInfo(position.coerceIn(0f, last), 0f..last)
                setProgress { target ->
                    currentOnPosition(target.coerceIn(0f, last))
                    currentOnRelease()
                    true
                }
            },
    ) {
        val spacing = tickSpacing.toPx()
        val centerX = size.width / 2f
        val bottom = size.height * 0.8f
        val visible = centerX / spacing + 1f
        val first = ceil(position - visible).toInt().coerceAtLeast(0)
        val lastVisible = floor(position + visible).toInt().coerceAtMost(tickCount - 1)
        for (i in first..lastVisible) {
            val x = centerX + (i - position) * spacing
            val major = i % majorEvery == 0
            val length = if (major) size.height * 0.5f else size.height * 0.28f
            // Ticks fade toward the ends of the ruler.
            val fade = (1f - abs(x - centerX) / centerX).coerceIn(0f, 1f)
            val color = if (major) colors.ink else colors.inkMuted
            drawLine(
                color = color.copy(alpha = color.alpha * (0.25f + 0.75f * fade)),
                start = Offset(x, bottom - length),
                end = Offset(x, bottom),
                strokeWidth = (if (major) 2.dp else 1.5.dp).toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawLine(
            color = colors.sun,
            start = Offset(centerX, size.height * 0.08f),
            end = Offset(centerX, size.height * 0.92f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
