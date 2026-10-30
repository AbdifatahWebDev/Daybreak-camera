package com.brekwol.daybreak.capture

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brekwol.daybreak.camera.CameraCapabilities
import com.brekwol.daybreak.camera.FocusMode
import com.brekwol.daybreak.camera.LiveExposure
import com.brekwol.daybreak.camera.ProFormat
import com.brekwol.daybreak.camera.ProSettings
import com.brekwol.daybreak.camera.WhiteBalance
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakChip
import com.brekwol.daybreak.designsystem.RulerDial
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

private const val FOCUS_TICKS = 101

enum class CaptureMode(val label: String) { Night("Night"), Photo("Photo"), Pro("Pro") }

enum class ProControl(val label: String) {
    Iso("ISO"),
    Shutter("Shutter"),
    Focus("Focus"),
    WhiteBalance("WB"),
    Ev("EV"),
}

/** The row of Pro values under the viewfinder. Tapping one opens its dial. */
@Composable
fun ProControlBar(
    pro: ProSettings,
    capabilities: CameraCapabilities,
    live: LiveExposure,
    selected: ProControl?,
    onSelect: (ProControl?) -> Unit,
    onAllAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // One tap back to fully automatic.
        ProValue(
            label = "All",
            value = "Auto",
            manual = !pro.allAuto,
            selected = false,
            enabled = !pro.allAuto,
            onClick = onAllAuto,
        )
        ProControl.entries.forEach { control ->
            val (value, manual) = displayValue(control, pro, capabilities, live)
            val enabled = isAvailable(control, pro, capabilities)
            ProValue(
                label = control.label,
                value = value,
                manual = manual,
                selected = control == selected,
                enabled = enabled,
                onClick = { onSelect(if (control == selected) null else control) },
            )
        }
    }
}

@Composable
private fun ProValue(
    label: String,
    value: String,
    manual: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = Daybreak.colors
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.sunGlow else colors.surface100)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = "$label $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = Daybreak.type.caption, color = colors.inkMuted)
        Text(
            text = value,
            style = Daybreak.type.readout,
            color = if (manual || selected) colors.sun else colors.ink,
            maxLines = 1,
        )
    }
}

/**
 * The dial for one Pro control: its value, an Auto button and a slider
 * (or presets for white balance). Shown over the bottom of the viewfinder.
 */
@Composable
fun ProDial(
    control: ProControl,
    pro: ProSettings,
    capabilities: CameraCapabilities,
    live: LiveExposure,
    onChange: (ProSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Daybreak.colors
    val (value, manual) = displayValue(control, pro, capabilities, live)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.scrim)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(control.label, style = Daybreak.type.label, color = colors.inkMuted)
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                style = Daybreak.type.readout,
                color = if (manual) colors.sun else colors.ink,
                modifier = Modifier.weight(1f),
            )
            if (isAvailable(control, pro, capabilities)) {
                DaybreakChip(
                    text = "Auto",
                    selected = !manual,
                    onClick = { onChange(resetToAuto(control, pro)) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        when (control) {
            ProControl.Iso -> {
                val stops = capabilities.isoStops
                if (!capabilities.manualSensor || stops.isEmpty()) {
                    Unavailable("This camera doesn't allow manual ISO.")
                } else {
                    val current = pro.iso ?: live.iso.takeIf { it > 0 } ?: stops.first()
                    StopSlider(
                        count = stops.size,
                        index = ProFormat.nearestIndex(stops, current) { a, b -> abs(ln(a.toDouble() / b)) },
                        onIndex = { onChange(pro.copy(iso = stops[it])) },
                        label = "ISO",
                        valueText = value,
                    )
                }
            }
            ProControl.Shutter -> {
                val stops = capabilities.shutterStops
                if (!capabilities.manualSensor || stops.isEmpty()) {
                    Unavailable("This camera doesn't allow a manual shutter speed.")
                } else {
                    val current = pro.shutterNs ?: live.shutterNs.takeIf { it > 0 } ?: stops.first()
                    StopSlider(
                        count = stops.size,
                        index = ProFormat.nearestIndex(stops, current) { a, b -> abs(ln(a.toDouble() / b)) },
                        onIndex = { onChange(pro.copy(shutterNs = stops[it])) },
                        label = "Shutter speed",
                        valueText = value,
                    )
                    val chosen = pro.shutterNs
                    if (chosen != null && chosen > ProSettings.HANDHELD_LIMIT_NS) {
                        Text(
                            "Slow shutter: the viewfinder updates slowly, and photos blur unless the phone is kept still.",
                            style = Daybreak.type.caption,
                            color = colors.inkMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            ProControl.Focus -> FocusDial(pro, capabilities, live, onChange)
            ProControl.WhiteBalance -> {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    capabilities.whiteBalances.forEach { preset ->
                        DaybreakChip(
                            text = preset.label,
                            selected = pro.whiteBalance == preset,
                            onClick = { onChange(pro.copy(whiteBalance = preset)) },
                        )
                    }
                }
            }
            ProControl.Ev -> when {
                !capabilities.exposureCompensation -> Unavailable("This camera doesn't support exposure compensation.")
                pro.manualExposure -> Unavailable("Set ISO and shutter to Auto to use exposure compensation.")
                else -> {
                    val range = capabilities.evRange
                    StopSlider(
                        count = range.last - range.first + 1,
                        index = pro.evIndex.coerceIn(range) - range.first,
                        onIndex = { onChange(pro.copy(evIndex = range.first + it)) },
                        label = "Exposure compensation",
                        valueText = value,
                    )
                }
            }
        }
    }
}

@Composable
private fun Unavailable(text: String) {
    Text(
        text = text,
        style = Daybreak.type.caption,
        color = Daybreak.colors.inkMuted,
        textAlign = TextAlign.Start,
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

/** Center, Multi and Manual focus, and the distance ruler while focus is manual. */
@Composable
private fun FocusDial(
    pro: ProSettings,
    capabilities: CameraCapabilities,
    live: LiveExposure,
    onChange: (ProSettings) -> Unit,
) {
    val closest = capabilities.minFocusDiopters
    val modes = buildList {
        if (capabilities.centerFocus) add(FocusMode.Center)
        add(FocusMode.Multi)
        if (capabilities.manualFocus) add(FocusMode.Manual)
    }
    if (modes.size == 1) {
        Unavailable("This lens has fixed focus.")
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        modes.forEach { mode ->
            FocusModeButton(
                mode = mode,
                selected = pro.focusMode == mode,
                onClick = {
                    onChange(
                        when (mode) {
                            FocusMode.Multi -> pro.copy(focusDiopters = null, focusCenter = false)
                            FocusMode.Center -> pro.copy(focusDiopters = null, focusCenter = true)
                            // Start from the distance autofocus found, so nothing jumps.
                            FocusMode.Manual -> pro.copy(
                                focusDiopters = pro.focusDiopters ?: live.focusDiopters.coerceIn(0f, closest),
                                focusCenter = false,
                            )
                        },
                    )
                },
            )
        }
    }
    val diopters = pro.focusDiopters
    if (diopters != null && closest > 0f) {
        Spacer(Modifier.height(6.dp))
        // Left is the closest focus, right is infinity.
        val ticks = FOCUS_TICKS
        RulerDial(
            position = (1f - diopters / closest).coerceIn(0f, 1f) * (ticks - 1),
            tickCount = ticks,
            onPosition = { position ->
                onChange(pro.copy(focusDiopters = (1f - position / (ticks - 1)) * closest))
            },
            contentDescription = "Focus distance",
            stateDescription = ProFormat.focus(diopters),
            majorEvery = 10,
        )
        Row(Modifier.fillMaxWidth()) {
            Text("Near", style = Daybreak.type.caption, color = Daybreak.colors.inkMuted, modifier = Modifier.weight(1f))
            Text("∞", style = Daybreak.type.readout, color = Daybreak.colors.inkMuted)
        }
    }
}

@Composable
private fun FocusModeButton(mode: FocusMode, selected: Boolean, onClick: () -> Unit) {
    val colors = Daybreak.colors
    val tint = if (selected) colors.sun else colors.ink
    Column(
        modifier = Modifier
            .widthIn(min = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.sunGlow else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "${mode.label} focus" + if (selected) ", selected" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (mode) {
                FocusMode.Manual -> Text("M", style = Daybreak.type.label, color = tint)
                else -> Canvas(Modifier.size(18.dp)) {
                    val dot = 1.6.dp.toPx()
                    if (mode == FocusMode.Center) {
                        drawCircle(tint, radius = dot * 1.4f, center = center)
                    } else {
                        for (row in 0..2) {
                            for (column in 0..2) {
                                drawCircle(
                                    tint,
                                    radius = dot,
                                    center = Offset(size.width * (0.2f + 0.3f * column), size.height * (0.2f + 0.3f * row)),
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(mode.label.uppercase(), style = Daybreak.type.mode, color = tint)
    }
}

/** A ruler over [count] stops that reports the nearest stop, with a tick of haptics per stop. */
@Composable
private fun StopSlider(count: Int, index: Int, onIndex: (Int) -> Unit, label: String, valueText: String) {
    if (count <= 1) return
    val haptics = LocalHapticFeedback.current
    RulerDial(
        position = index.toFloat(),
        tickCount = count,
        onPosition = { position ->
            val next = position.roundToInt().coerceIn(0, count - 1)
            if (next != index) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onIndex(next)
            }
        },
        contentDescription = label,
        stateDescription = valueText,
        majorEvery = 3,
        tickSpacing = 14.dp,
    )
}

private fun isAvailable(control: ProControl, pro: ProSettings, caps: CameraCapabilities): Boolean = when (control) {
    ProControl.Iso, ProControl.Shutter -> caps.manualSensor
    ProControl.Focus -> caps.manualFocus || caps.centerFocus
    ProControl.WhiteBalance -> caps.whiteBalances.size > 1
    ProControl.Ev -> caps.exposureCompensation && !pro.manualExposure
}

private fun resetToAuto(control: ProControl, pro: ProSettings): ProSettings = when (control) {
    ProControl.Iso -> pro.copy(iso = null)
    ProControl.Shutter -> pro.copy(shutterNs = null)
    ProControl.Focus -> pro.copy(focusDiopters = null, focusCenter = false)
    ProControl.WhiteBalance -> pro.copy(whiteBalance = WhiteBalance.Auto)
    ProControl.Ev -> pro.copy(evIndex = 0)
}

/** The text shown for a control, and whether it is set by hand. */
private fun displayValue(
    control: ProControl,
    pro: ProSettings,
    caps: CameraCapabilities,
    live: LiveExposure,
): Pair<String, Boolean> = when (control) {
    ProControl.Iso -> pro.iso?.let { ProFormat.iso(it) to true }
        ?: ((if (live.iso > 0) "A ${live.iso}" else "Auto") to false)
    ProControl.Shutter -> pro.shutterNs?.let { ProFormat.shutter(it) to true }
        ?: ((if (live.shutterNs > 0) "A ${ProFormat.shutter(live.shutterNs)}" else "Auto") to false)
    ProControl.Focus -> when (pro.focusMode) {
        FocusMode.Manual -> ProFormat.focus(pro.focusDiopters ?: 0f) to true
        FocusMode.Center -> "AF·C" to true
        FocusMode.Multi -> "AF" to false
    }
    ProControl.WhiteBalance -> if (pro.whiteBalance == WhiteBalance.Auto) {
        "AWB" to false
    } else {
        pro.whiteBalance.label to true
    }
    ProControl.Ev -> ProFormat.ev(pro.evIndex, caps.evStep) to (pro.evIndex != 0)
}
