package com.brekwol.daybreak.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brekwol.daybreak.designsystem.Daybreak

/**
 * The Daybreak editing slider. Dragging moves the value from where it is (the
 * thumb never jumps to the finger), it clicks into the neutral value on the
 * way past, and a double tap resets it.
 */
@Composable
fun EditSlider(
    adjustment: Adjustment,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    track: Track = Track.Plain,
    height: Dp = 44.dp,
) {
    val colors = Daybreak.colors
    val haptics = LocalHapticFeedback.current
    val currentAdjustment by rememberUpdatedState(adjustment)
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnDone by rememberUpdatedState(onDone)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(adjustment.key) {
                var raw = 0f
                detectHorizontalDragGestures(
                    onDragStart = { raw = currentValue },
                    onDragEnd = { currentOnDone() },
                    onDragCancel = { currentOnDone() },
                ) { change, dragAmount ->
                    change.consume()
                    val a = currentAdjustment
                    val usable = (size.width - THUMB_RADIUS.toPx() * 2).coerceAtLeast(1f)
                    raw = (raw + dragAmount / usable * (a.max - a.min)).coerceIn(a.min, a.max)
                    val next = a.clean(raw)
                    if (next == a.neutral && currentValue != a.neutral) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    if (next != currentValue) currentOnChange(next)
                }
            }
            .pointerInput(adjustment.key) {
                detectTapGestures(onDoubleTap = {
                    currentOnChange(currentAdjustment.neutral)
                    currentOnDone()
                })
            }
            .semantics {
                contentDescription = adjustment.label
                stateDescription = adjustment.format(value)
                progressBarRangeInfo = ProgressBarRangeInfo(value, adjustment.min..adjustment.max)
                setProgress { target ->
                    currentOnChange(currentAdjustment.clean(target))
                    currentOnDone()
                    true
                }
            },
    ) {
        val radius = THUMB_RADIUS.toPx()
        val left = radius
        val right = size.width - radius
        val span = (adjustment.max - adjustment.min).takeIf { it > 0f } ?: 1f
        fun xOf(v: Float) = left + (v.coerceIn(adjustment.min, adjustment.max) - adjustment.min) / span * (right - left)
        val cy = size.height / 2f
        val thumbX = xOf(value)
        val neutralX = xOf(adjustment.neutral)
        when (track) {
            Track.Plain -> {
                drawLine(colors.line, Offset(left, cy), Offset(right, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                val from = if (adjustment.bipolar) neutralX else left
                if (thumbX != from) {
                    drawLine(colors.sun, Offset(from, cy), Offset(thumbX, cy), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            is Track.Gradient -> {
                val bar = 6.dp.toPx()
                drawRoundRect(
                    brush = Brush.horizontalGradient(track.colors, startX = left, endX = right),
                    topLeft = Offset(left, cy - bar / 2f),
                    size = Size(right - left, bar),
                    cornerRadius = CornerRadius(bar / 2f),
                )
            }
        }
        if (adjustment.bipolar) {
            val tick = if (track is Track.Gradient) 7.dp.toPx() else 10.dp.toPx()
            drawLine(colors.inkMuted, Offset(neutralX, cy - tick), Offset(neutralX, cy + tick), strokeWidth = 2.dp.toPx())
        }
        drawCircle(colors.ink, radius = radius, center = Offset(thumbX, cy))
        val ring = 3.dp.toPx()
        drawCircle(colors.sun, radius = radius - ring / 2f, center = Offset(thumbX, cy), style = Stroke(width = ring))
    }
}

private val THUMB_RADIUS = 12.dp

/** Label and big readout over a full-width slider, with its end values below. */
@Composable
fun LargeAdjustment(
    adjustment: Adjustment,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Daybreak.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(adjustment.label, style = Daybreak.type.label, color = colors.inkMuted, modifier = Modifier.weight(1f))
            Text(
                adjustment.format(value),
                style = Daybreak.type.readoutLarge,
                color = if (value != adjustment.neutral) colors.sun else colors.ink,
            )
        }
        Spacer(Modifier.height(4.dp))
        EditSlider(adjustment, value, onChange, onDone)
        Row(Modifier.fillMaxWidth()) {
            Text(adjustment.minLabel, style = Daybreak.type.readout, color = colors.inkMuted)
            Spacer(Modifier.weight(1f))
            val middle = when {
                !adjustment.bipolar -> ""
                adjustment.neutral == 0f -> "0"
                else -> adjustment.format(adjustment.neutral)
            }
            Text(middle, style = Daybreak.type.readout, color = colors.inkMuted)
            Spacer(Modifier.weight(1f))
            Text(adjustment.maxLabel, style = Daybreak.type.readout, color = colors.inkMuted)
        }
    }
}

/** Label and small readout over a slider, for panels with several sliders. */
@Composable
fun CompactAdjustment(
    adjustment: Adjustment,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    track: Track = Track.Plain,
) {
    val colors = Daybreak.colors
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(adjustment.label, style = Daybreak.type.label, color = colors.inkMuted, modifier = Modifier.weight(1f))
            Text(
                adjustment.format(value),
                style = Daybreak.type.readout,
                color = if (value != adjustment.neutral) colors.sun else colors.ink,
            )
        }
        EditSlider(adjustment, value, onChange, onDone, track = track, height = 36.dp)
    }
}

/** A pill that selects a tool or a part of one; a dot marks parts already changed. */
@Composable
fun ToolChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    edited: Boolean = false,
    mono: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = Daybreak.colors
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) colors.sunGlow else colors.surface200)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp)
            .semantics { this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        leading?.invoke()
        Text(
            text,
            style = if (mono) Daybreak.type.readout else Daybreak.type.label,
            color = if (selected) colors.sun else colors.ink,
            maxLines = 1,
        )
        if (edited) {
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(colors.sun),
            )
        }
    }
}

/** A plain rounded button for secondary actions (Delete point, Reset, Save mine). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = Daybreak.colors
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface200)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Daybreak.type.label, color = if (enabled) colors.ink else colors.ink.copy(alpha = 0.4f))
    }
}

/** The sun-colored main action (Done, Apply). */
@Composable
fun PrimaryPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = Daybreak.colors
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (enabled) colors.sun else colors.sun.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Daybreak.type.body, color = colors.onSun)
    }
}

/** Text-only action in a top bar (Cancel). */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Daybreak.type.body, color = Daybreak.colors.ink)
    }
}

/** One tab of the editor's bottom bar. */
@Composable
fun ToolTab(
    iconRes: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Daybreak.colors
    val tint = if (selected) colors.sun else colors.inkMuted
    Column(
        modifier = modifier
            .widthIn(min = 56.dp)
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = Daybreak.type.caption.copy(fontSize = Daybreak.type.mode.fontSize), color = tint)
    }
}

/** A gap of [width] in a row. */
@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
