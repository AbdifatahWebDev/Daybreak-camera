package com.brekwol.daybreak.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.engine.CurveChannel
import com.brekwol.daybreak.engine.CurvePoint
import kotlin.math.hypot
import kotlin.math.roundToInt

/** The color each curve channel is drawn in. */
val CurveChannel.color: Color
    get() = when (this) {
        CurveChannel.Rgb -> Color(0xFFF4EFE6)
        CurveChannel.Red -> Color(0xFFFF5A47)
        CurveChannel.Green -> Color(0xFF6FDCAA)
        CurveChannel.Blue -> Color(0xFF8CC8FF)
        CurveChannel.Luma -> Color(0xFFA39D93)
    }

private const val MAX_POINTS = 16
private const val GAP = 0.02f

/**
 * The tone curve editor: the photo's histogram behind a curve with draggable
 * points. Tap to add a point, drag a point to move it.
 * [onPoints] reports each change; `done` is true when a gesture ends.
 */
@Composable
fun CurveEditor(
    points: List<CurvePoint>,
    channel: CurveChannel,
    histogram: FloatArray?,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onPoints: (points: List<CurvePoint>, done: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Daybreak.colors
    val shown = if (points.size >= 2) CurveSpline.clean(points) else CurveSpline.Identity
    val currentPoints by rememberUpdatedState(shown)
    val currentOnPoints by rememberUpdatedState(onPoints)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val inset = 14.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface100),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "${channel.label} tone curve with ${shown.size} points. Tap to add a point, drag to move it."
                }
                .pointerInput(Unit) {
                    val pad = inset.toPx()
                    fun toPoint(offset: Offset) = CurvePoint(
                        ((offset.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f),
                        (1f - (offset.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f),
                    )
                    fun nearest(offset: Offset, reach: Float): Int? {
                        val list = currentPoints
                        var best: Int? = null
                        var bestDistance = reach
                        list.forEachIndexed { i, p ->
                            val x = pad + p.x * (size.width - 2 * pad)
                            val y = pad + (1f - p.y) * (size.height - 2 * pad)
                            val d = hypot(x - offset.x, y - offset.y)
                            if (d < bestDistance) {
                                bestDistance = d
                                best = i
                            }
                        }
                        return best
                    }
                    detectTapGestures { offset ->
                        val hit = nearest(offset, 28.dp.toPx())
                        if (hit != null) {
                            currentOnSelect(hit)
                        } else if (currentPoints.size < MAX_POINTS) {
                            val x = toPoint(offset).x
                            val (list, index) = insert(currentPoints, x)
                            if (index != null) {
                                currentOnPoints(list, true)
                                currentOnSelect(index)
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    val pad = inset.toPx()
                    var working: List<CurvePoint> = emptyList()
                    var index: Int? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            working = currentPoints
                            val list = working
                            var best: Int? = null
                            var bestDistance = 36.dp.toPx()
                            list.forEachIndexed { i, p ->
                                val x = pad + p.x * (size.width - 2 * pad)
                                val y = pad + (1f - p.y) * (size.height - 2 * pad)
                                val d = hypot(x - offset.x, y - offset.y)
                                if (d < bestDistance) {
                                    bestDistance = d
                                    best = i
                                }
                            }
                            index = best ?: if (list.size < MAX_POINTS) {
                                val x = ((offset.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                                val (inserted, at) = insert(list, x)
                                working = inserted
                                at
                            } else {
                                null
                            }
                            index?.let { currentOnSelect(it) }
                        },
                        onDragEnd = { if (index != null) currentOnPoints(working, true) },
                        onDragCancel = { if (index != null) currentOnPoints(working, true) },
                    ) { change, _ ->
                        val i = index ?: return@detectDragGestures
                        change.consume()
                        val x = ((change.position.x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                        val y = (1f - (change.position.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        val low = if (i == 0) 0f else working[i - 1].x + GAP
                        val high = if (i == working.lastIndex) 1f else working[i + 1].x - GAP
                        val moved = CurvePoint(if (low <= high) x.coerceIn(low, high) else working[i].x, y)
                        working = working.toMutableList().also { it[i] = moved }
                        currentOnPoints(working, false)
                    }
                },
        ) {
            val pad = inset.toPx()
            val w = size.width - 2 * pad
            val h = size.height - 2 * pad
            // Histogram behind everything.
            histogram?.let { bins ->
                if (bins.isNotEmpty()) {
                    val barWidth = w / bins.size
                    bins.forEachIndexed { i, v ->
                        val barHeight = v * h
                        drawRect(
                            color = colors.inkMuted.copy(alpha = 0.28f),
                            topLeft = Offset(pad + i * barWidth, pad + h - barHeight),
                            size = Size(barWidth * 0.85f, barHeight),
                        )
                    }
                }
            }
            // Quarter grid and the straight line.
            val grid = colors.line
            for (q in 1..3) {
                val gx = pad + w * q / 4f
                val gy = pad + h * q / 4f
                drawLine(grid, Offset(gx, pad), Offset(gx, pad + h), strokeWidth = 1.dp.toPx())
                drawLine(grid, Offset(pad, gy), Offset(pad + w, gy), strokeWidth = 1.dp.toPx())
            }
            drawLine(grid, Offset(pad, pad + h), Offset(pad + w, pad), strokeWidth = 1.dp.toPx())

            val spline = CurveSpline(shown)
            val path = Path()
            val steps = 128
            for (s in 0..steps) {
                val x = s / steps.toFloat()
                val px = pad + x * w
                val py = pad + (1f - spline(x)) * h
                if (s == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, channel.color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
            shown.forEachIndexed { i, p ->
                val center = Offset(pad + p.x * w, pad + (1f - p.y) * h)
                if (i == selected) {
                    drawCircle(colors.sun, radius = 9.dp.toPx(), center = center)
                    drawCircle(colors.surface000, radius = 9.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                } else {
                    drawCircle(colors.surface000, radius = 7.dp.toPx(), center = center)
                    drawCircle(channel.color, radius = 7.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
                }
            }
        }
        val point = selected?.let { shown.getOrNull(it) }
        if (point != null) {
            Text(
                text = "${(point.x * 255).roundToInt()} → ${(point.y * 255).roundToInt()}",
                style = Daybreak.type.readout,
                color = colors.sun,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.scrim)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** [points] with a new point on the curve at [x], and its index; no point when one is too close. */
private fun insert(points: List<CurvePoint>, x: Float): Pair<List<CurvePoint>, Int?> {
    if (points.any { kotlin.math.abs(it.x - x) < GAP }) return points to null
    val y = CurveSpline(points)(x)
    val list = (points + CurvePoint(x, y)).sortedBy { it.x }
    return list to list.indexOfFirst { it.x == x }
}
