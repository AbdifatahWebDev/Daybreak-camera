package com.brekwol.daybreak.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brekwol.daybreak.BuildConfig
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.DaybreakSettings
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakChip
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.R as DsR
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val SOURCE_URL = "https://github.com/AbdifatahWebDev/Daybreak-camera"

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = DaybreakSettings())

    // Slider moves locally and saves when released.
    var quality by remember { mutableFloatStateOf(settings.quality.toFloat()) }
    LaunchedEffect(settings.quality) { quality = settings.quality.toFloat() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface000)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DaybreakIconButton(DsR.drawable.ic_back, "Back", onClick = onBack)
            Text("Settings", style = Daybreak.type.title, color = colors.ink)
        }

        Section("PHOTO") {
            ValueRow("Save format", "JPEG XL")
            Divider()
            SwitchRow(
                label = "Lossless",
                description = "Bit-exact pixels. Larger files.",
                checked = settings.lossless,
                onChange = { scope.launch { container.settings.setLossless(it) } },
            )
            Divider()
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Quality",
                        style = Daybreak.type.body,
                        color = if (settings.lossless) colors.inkMuted else colors.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (settings.lossless) "Lossless" else qualityLabel(quality.roundToInt()),
                        style = Daybreak.type.readout,
                        color = if (settings.lossless) colors.inkMuted else colors.sun,
                    )
                }
                Slider(
                    value = quality,
                    onValueChange = { quality = it },
                    onValueChangeFinished = { scope.launch { container.settings.setQuality(quality.roundToInt()) } },
                    valueRange = 50f..100f,
                    enabled = !settings.lossless,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.ink,
                        activeTrackColor = colors.sun,
                        inactiveTrackColor = colors.line,
                    ),
                )
            }
            Divider()
            Column(Modifier.padding(vertical = 12.dp)) {
                Text("Encode effort", style = Daybreak.type.body, color = colors.ink)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surface200)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf("Fast" to 3, "Balanced" to 7, "Smallest" to 9).forEach { (label, value) ->
                        DaybreakChip(
                            text = label,
                            selected = settings.effort == value,
                            onClick = { scope.launch { container.settings.setEffort(value) } },
                        )
                    }
                }
            }
            Divider()
            SwitchRow(
                label = "Grid",
                description = "Rule-of-thirds lines on the viewfinder.",
                checked = settings.grid,
                onChange = { scope.launch { container.settings.setGrid(it) } },
            )
        }

        Section("ABOUT") {
            ValueRow("Version", BuildConfig.VERSION_NAME)
            Divider()
            ValueRow("libjxl", remember { container.engine.libjxlVersion })
            Divider()
            ValueRow("Licence", "GPL-3.0-or-later")
            Divider()
            ValueRow("Source code", "GitHub") {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun qualityLabel(value: Int): String = when {
    value >= 90 -> "$value · visually lossless"
    value >= 75 -> "$value · high"
    else -> "$value · small files"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = Daybreak.colors
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = Daybreak.type.mode,
            color = colors.inkMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface100)
                .padding(horizontal = 14.dp),
            content = content,
        )
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(color = Daybreak.colors.line, thickness = 1.dp)
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val colors = Daybreak.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Daybreak.type.body, color = colors.ink, modifier = Modifier.weight(1f))
        Text(value, style = Daybreak.type.readout, color = colors.inkMuted)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val colors = Daybreak.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Daybreak.type.body, color = colors.ink)
            Text(description, style = Daybreak.type.caption, color = colors.inkMuted)
        }
        Box(Modifier.padding(start = 12.dp)) {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onSun,
                    checkedTrackColor = colors.sun,
                    uncheckedThumbColor = colors.ink,
                    uncheckedTrackColor = colors.line,
                    uncheckedBorderColor = colors.line,
                ),
            )
        }
    }
}
