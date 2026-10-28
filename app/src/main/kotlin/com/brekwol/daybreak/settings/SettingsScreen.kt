package com.brekwol.daybreak.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brekwol.daybreak.BuildConfig
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.DaybreakSettings
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakChip
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.R as DsR
import com.brekwol.daybreak.update.InstallStart
import com.brekwol.daybreak.update.UpdateState
import java.util.Locale
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

        if (container.updates.enabled) {
            val update by container.updates.state.collectAsStateWithLifecycle()
            LaunchedEffect(Unit) {
                container.updates.messages.collect { message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
            Section("UPDATES") {
                UpdateStatus(
                    state = update,
                    currentVersion = container.updates.currentVersion,
                    onCheck = { container.updates.checkNow() },
                    onDownload = { container.updates.download() },
                    onInstall = {
                        if (container.updates.install() == InstallStart.NeedsPermission) {
                            Toast.makeText(
                                context,
                                "Allow Daybreak to install updates, then tap Install again.",
                                Toast.LENGTH_LONG,
                            ).show()
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                    },
                    onOpenPage = { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                )
                Divider()
                SwitchRow(
                    label = "Check for updates",
                    description = "Looks for a new version on GitHub once a day.",
                    checked = settings.updateAutoCheck,
                    onChange = { scope.launch { container.settings.setUpdateAutoCheck(it) } },
                )
                Divider()
                SwitchRow(
                    label = "Download automatically",
                    description = "Downloads new versions in the background on Wi-Fi.",
                    checked = settings.updateAutoDownload && settings.updateAutoCheck,
                    enabled = settings.updateAutoCheck,
                    onChange = { scope.launch { container.settings.setUpdateAutoDownload(it) } },
                )
                Divider()
                SwitchRow(
                    label = "Include test builds",
                    description = "Also offers pre-releases, which may have bugs.",
                    checked = settings.updatePreReleases,
                    onChange = { scope.launch { container.settings.setUpdatePreReleases(it) } },
                )
            }
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
    enabled: Boolean = true,
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
            Text(label, style = Daybreak.type.body, color = if (enabled) colors.ink else colors.inkMuted)
            Text(description, style = Daybreak.type.caption, color = colors.inkMuted)
        }
        Box(Modifier.padding(start = 12.dp)) {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
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

@Composable
private fun UpdateStatus(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onOpenPage: (String) -> Unit,
) {
    val colors = Daybreak.colors
    val title: String
    val detail: String
    var action: Pair<String, () -> Unit>? = "Check now" to onCheck
    var checking = false
    var upToDate = false
    when (state) {
        UpdateState.Idle -> {
            title = "Daybreak $currentVersion"
            detail = "Not checked yet"
        }
        UpdateState.Checking -> {
            title = "Checking for updates…"
            detail = "Asking GitHub for the newest version"
            action = null
            checking = true
        }
        is UpdateState.UpToDate -> {
            title = "You're up to date"
            detail = "Daybreak $currentVersion is the latest version · checked ${checkedAgo(state.checkedAt)}"
            action = "Check again" to onCheck
            upToDate = true
        }
        is UpdateState.Available -> {
            title = "Daybreak ${state.release.version} is out"
            detail = "${formatSize(state.release.apkSize)} · you have $currentVersion"
            action = "Download" to onDownload
        }
        is UpdateState.Downloading -> {
            title = "Downloading ${state.release.version}"
            detail = if (state.progress >= 0f) "${(state.progress * 100).roundToInt()}% of ${formatSize(state.release.apkSize)}" else "Starting…"
            action = null
        }
        is UpdateState.Ready -> {
            title = "Daybreak ${state.release.version} is ready"
            detail = "Daybreak closes while it updates."
            action = "Install" to onInstall
        }
        is UpdateState.Installing -> {
            title = "Installing ${state.release.version}"
            detail = "Confirm in the Android dialog if it asks."
            action = null
        }
        is UpdateState.Failed -> {
            title = when {
                state.reinstall -> "Can't update this copy"
                state.release == null -> "Couldn't check for updates"
                else -> "Update failed"
            }
            detail = state.message
            val page = state.release?.pageUrl
            action = if (state.reinstall && page != null) "Open GitHub" to { onOpenPage(page) } else "Try again" to onCheck
        }
    }

    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (upToDate) {
                        Icon(
                            painter = painterResource(DsR.drawable.ic_check),
                            contentDescription = null,
                            tint = colors.sun,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(title, style = Daybreak.type.body, color = colors.ink)
                }
                Text(detail, style = Daybreak.type.caption, color = colors.inkMuted)
            }
            if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 8.dp)
                        .size(24.dp),
                    color = colors.sun,
                    trackColor = colors.line,
                    strokeWidth = 2.5.dp,
                )
            }
            action?.let { (label, onClick) ->
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.sun)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(label, style = Daybreak.type.label, color = colors.onSun)
                }
            }
        }
        if (state is UpdateState.Downloading) {
            Spacer(Modifier.height(10.dp))
            if (state.progress >= 0f) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.sun,
                    trackColor = colors.line,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.sun,
                    trackColor = colors.line,
                )
            }
        }
        val release = when (state) {
            is UpdateState.Available -> state.release
            is UpdateState.Ready -> state.release
            else -> null
        }
        if (release != null && release.pageUrl.isNotBlank()) {
            Text(
                text = "What's new",
                style = Daybreak.type.label,
                color = colors.sun,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onOpenPage(release.pageUrl) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

private fun checkedAgo(time: Long): String {
    val now = System.currentTimeMillis()
    return if (now - time < DateUtils.MINUTE_IN_MILLIS) {
        "just now"
    } else {
        DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%d KB", bytes / 1_000)
    else -> "$bytes B"
}
