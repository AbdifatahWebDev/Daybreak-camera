package com.brekwol.daybreak.edit

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.brekwol.daybreak.container
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.engine.EditRecipe
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val AmountAdjustment = Adjustment(
    key = "preset_amount",
    label = "Amount",
    min = 0f,
    max = 1f,
    format = ::formatPercent,
    get = { 0f },
    set = { recipe, _ -> recipe },
)

/**
 * Presets over a large preview: Daybreak's own and the user's. Apply replaces
 * the edit's light, color, curve and detail settings with the preset at the
 * chosen amount; crop and rotation stay.
 */
@Composable
fun PresetsScreen(
    session: EditSession,
    base: EditRecipe,
    onApply: (EditRecipe) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val scope = rememberCoroutineScope()

    var mine by rememberSaveable { mutableStateOf(false) }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableFloatStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }

    val ownPresets by container.edits.presets.collectAsState()
    LaunchedEffect(Unit) { container.edits.loadPresets() }
    val all = BuiltInPresets.all + ownPresets.orEmpty()
    val picked = all.firstOrNull { it.id == pickedId }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().takeIf { it.size <= MAX_PRESET_BYTES }?.decodeToString()
                    }
                }.getOrNull()
            }
            val preset = text?.let { container.edits.importPreset(it) }
            if (preset == null) {
                Toast.makeText(context, "That file isn't a Daybreak preset", Toast.LENGTH_LONG).show()
            } else {
                mine = true
                pickedId = preset.id
                amount = 1f
                Toast.makeText(context, "Added “${preset.name}”", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val frame by rememberPreview(session) {
        val look = picked?.recipe?.lookAtStrength(amount)
        PreviewRequest(
            recipe = if (look != null) base.withLook(look) else base,
            showFullFrame = false,
            draft = dragging,
            histogram = false,
        )
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
            TextAction("Cancel", onClick = onCancel)
            Text(
                "Presets",
                style = Daybreak.type.body,
                color = colors.ink,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            PrimaryPill(
                "Apply",
                enabled = picked != null,
                onClick = { picked?.let { onApply(it.recipe.lookAtStrength(amount)) } },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val bitmap = frame?.bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Photo with the ${picked?.name ?: "current"} look",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else {
                CircularProgressIndicator(color = colors.sun, modifier = Modifier.align(Alignment.Center))
            }
            Text(
                picked?.name ?: "Your edit",
                style = Daybreak.type.caption,
                color = colors.ink,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.scrim)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolChip("Daybreak", selected = !mine, onClick = { mine = false }, modifier = Modifier.weight(1f))
            ToolChip("My presets", selected = mine, onClick = { mine = true }, modifier = Modifier.weight(1f))
        }

        val shown = if (mine) ownPresets.orEmpty() else BuiltInPresets.all
        if (mine && shown.isEmpty()) {
            Text(
                "Save your edit as a preset to use it on other photos, or import one someone shared.",
                style = Daybreak.type.caption,
                color = colors.inkMuted,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PresetTileHeight)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PresetTileHeight),
            ) {
                items(shown, key = { it.id }) { preset ->
                    PresetTile(
                        session = session,
                        preset = preset,
                        selected = preset.id == pickedId,
                        onClick = {
                            if (pickedId != preset.id) amount = 1f
                            pickedId = preset.id
                        },
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            if (picked != null && !picked.recipe.isIdentity) {
                CompactAdjustment(
                    adjustment = AmountAdjustment,
                    value = amount,
                    onChange = {
                        dragging = true
                        amount = it
                    },
                    onDone = { dragging = false },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mine) {
                    SecondaryButton(
                        "Save mine",
                        enabled = !base.look.isIdentity,
                        onClick = { naming = true },
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        "Import",
                        onClick = { importer.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) },
                        modifier = Modifier.weight(1f),
                    )
                    val own = picked?.takeIf { !it.builtIn }
                    if (own != null) {
                        SecondaryButton("Share", onClick = { sharePreset(context, own) }, modifier = Modifier.weight(1f))
                        SecondaryButton("Delete", onClick = { confirmDelete = own }, modifier = Modifier.weight(1f))
                    }
                } else {
                    SecondaryButton(
                        "Save mine",
                        enabled = !base.look.isIdentity,
                        onClick = { naming = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Presets are plain adjustment lists. Your own ones export as a small file you can share.",
                style = Daybreak.type.caption,
                color = colors.inkMuted,
            )
        }
        Spacer(Modifier.navigationBarsPadding().height(12.dp))
    }

    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save as a preset") },
            text = {
                Column {
                    Text(
                        "Saves your light, color, curve and detail settings. Crop and rotation aren't included.",
                        style = Daybreak.type.caption,
                        color = colors.inkMuted,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(Preset.MAX_NAME) },
                        singleLine = true,
                        label = { Text("Name") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.sun,
                            focusedLabelColor = colors.sun,
                            cursorColor = colors.sun,
                            focusedTextColor = colors.ink,
                            unfocusedTextColor = colors.ink,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        naming = false
                        scope.launch {
                            val preset = container.edits.addPreset(name, base)
                            mine = true
                            pickedId = preset.id
                            amount = 1f
                        }
                    },
                ) { Text("Save", color = if (name.isNotBlank()) colors.sun else colors.inkMuted) }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Cancel", color = colors.ink) }
            },
            containerColor = colors.surface100,
            titleContentColor = colors.ink,
            textContentColor = colors.inkMuted,
        )
    }

    confirmDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${preset.name}”?") },
            text = { Text("Photos you already edited with it keep their look.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    if (pickedId == preset.id) pickedId = null
                    scope.launch { container.edits.deletePreset(preset.id) }
                }) { Text("Delete", color = colors.record) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Keep it", color = colors.ink) }
            },
            containerColor = colors.surface100,
            titleContentColor = colors.ink,
            textContentColor = colors.inkMuted,
        )
    }
}

private val PresetTileHeight = 132.dp
private const val MAX_PRESET_BYTES = 64 * 1024

@Composable
private fun PresetTile(session: EditSession, preset: Preset, selected: Boolean, onClick: () -> Unit) {
    val colors = Daybreak.colors
    val thumb by produceState<Bitmap?>(null, preset.id, preset.recipe) {
        value = withContext(Dispatchers.Default) { runCatching { session.renderThumb(preset.recipe) }.getOrNull() }
    }
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = preset.name
                this.selected = selected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, if (selected) colors.sun else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(3.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surface200),
        ) {
            thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            preset.name,
            style = Daybreak.type.label,
            color = if (selected) colors.sun else colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Shares a preset as a small `.dbpreset` file. */
private fun sharePreset(context: Context, preset: Preset) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val safe = preset.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "preset" }
        val file = File(dir, "$safe.${Preset.FILE_EXTENSION}")
        file.writeText(Preset.toFile(preset))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = Preset.MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Daybreak preset: ${preset.name}")
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share preset"))
    }.onFailure {
        Toast.makeText(context, "Could not share the preset", Toast.LENGTH_LONG).show()
    }
}
