package com.brekwol.daybreak.edit

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.R as DsR
import com.brekwol.daybreak.designsystem.RulerDial
import com.brekwol.daybreak.engine.CropRect
import com.brekwol.daybreak.engine.CurveChannel
import com.brekwol.daybreak.engine.EditRecipe
import com.brekwol.daybreak.engine.MixerBand
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The editor's tools, in tab order. */
enum class EditTool(val label: String, val icon: Int) {
    Light("Light", DsR.drawable.ic_light),
    Color("Color", DsR.drawable.ic_color),
    Curves("Curves", DsR.drawable.ic_curves),
    Detail("Detail", DsR.drawable.ic_detail),
    Crop("Crop", DsR.drawable.ic_crop),
}

private sealed interface Opening {
    data object Loading : Opening
    data class Ready(val session: EditSession) : Opening
    data class Failed(val message: String) : Opening
}

/**
 * The photo editor. Edits render live from a smaller copy of the photo; Done
 * renders the full-size photo and saves it as a new copy. The original is never changed.
 */
@Composable
fun EditorScreen(photo: Photo, onClose: () -> Unit, onSaved: (Photo) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val opening by produceState<Opening>(Opening.Loading, photo.uri) {
        value = try {
            Opening.Ready(EditSession.open(container, photo))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Opening.Failed(error.message ?: "Could not open this photo")
        } catch (tooBig: OutOfMemoryError) {
            Opening.Failed("This photo is too large to edit on this phone")
        }
    }
    when (val current = opening) {
        Opening.Loading -> Box(
            Modifier
                .fillMaxSize()
                .background(colors.surface000),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.sun)
        }
        is Opening.Failed -> Column(
            Modifier
                .fillMaxSize()
                .background(colors.surface000)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(current.message, style = Daybreak.type.body, color = colors.inkMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            SecondaryButton("Close", onClick = onClose)
        }
        is Opening.Ready -> Editor(current.session, onClose, onSaved)
    }
}

/** Renders previews in the background, always of the newest request, never two at once. */
@Composable
fun rememberPreview(session: EditSession, request: () -> PreviewRequest): State<PreviewFrame?> {
    val frame = remember(session) { mutableStateOf<PreviewFrame?>(null) }
    val currentRequest by rememberUpdatedState(request)
    LaunchedEffect(session) {
        snapshotFlow { currentRequest() }
            .conflate()
            .collect { next ->
                frame.value = try {
                    withContext(Dispatchers.Default) { session.renderPreview(next) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    frame.value
                }
            }
    }
    return frame
}

@Composable
private fun Editor(session: EditSession, onClose: () -> Unit, onSaved: (Photo) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = remember(session) { EditorState(session.initial) }
    var tool by rememberSaveable { mutableStateOf(EditTool.Light) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    fun close() {
        if (state.hasChanges) confirmDiscard = true else onClose()
    }

    fun done() {
        if (saving) return
        // Nothing changed, or nothing to save: there is no new copy to make.
        if (!state.hasChanges || (state.recipe.isIdentity && session.replacing == null)) {
            onClose()
            return
        }
        saving = true
        scope.launch {
            try {
                val saved = session.save(state.recipe)
                Toast.makeText(context, "Saved as a copy. Your original is unchanged.", Toast.LENGTH_SHORT).show()
                onSaved(saved)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saving = false
                Toast.makeText(context, error.message ?: "Could not save the edit", Toast.LENGTH_LONG).show()
            } catch (tooBig: OutOfMemoryError) {
                saving = false
                Toast.makeText(context, "Not enough memory to save this photo at full size", Toast.LENGTH_LONG).show()
            }
        }
    }

    BackHandler {
        when {
            saving -> Unit
            showPresets -> showPresets = false
            else -> close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (showPresets) {
            PresetsScreen(
                session = session,
                base = state.recipe,
                onApply = { look ->
                    state.set(state.recipe.withLook(look))
                    showPresets = false
                },
                onCancel = { showPresets = false },
            )
        } else {
            EditorBody(
                session = session,
                state = state,
                tool = tool,
                onTool = { tool = it },
                onCancel = ::close,
                onDone = ::done,
                onPresets = { showPresets = true },
            )
        }

        if (saving) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Daybreak.colors.scrim)
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Daybreak.colors.sun)
                    Spacer(Modifier.height(12.dp))
                    Text("Saving at full size…", style = Daybreak.type.label, color = Daybreak.colors.ink)
                }
            }
        }
    }

    if (confirmDiscard) {
        val colors = Daybreak.colors
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your edits?") },
            text = { Text("The changes you made since opening the editor are lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Discard", color = colors.record) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing", color = colors.ink) }
            },
            containerColor = colors.surface100,
            titleContentColor = colors.ink,
            textContentColor = colors.inkMuted,
        )
    }
}

@Composable
private fun EditorBody(
    session: EditSession,
    state: EditorState,
    tool: EditTool,
    onTool: (EditTool) -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
    onPresets: () -> Unit,
) {
    val colors = Daybreak.colors
    val cropping = tool == EditTool.Crop
    val frame by rememberPreview(session) {
        PreviewRequest(
            recipe = state.recipe,
            showFullFrame = tool == EditTool.Crop,
            draft = state.interacting,
            histogram = tool == EditTool.Curves,
        )
    }
    val cropChoice = remember(session) { CropChoice() }
    var comparing by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(frame) { frame?.histogram?.let { histogram = it } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface000),
    ) {
        // Top bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(60.dp)
                .padding(start = 10.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextAction("Cancel", onClick = onCancel)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.surface200)
                        .clickable(role = Role.Button, onClick = onPresets)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(DsR.drawable.ic_presets), null, tint = colors.ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Presets", style = Daybreak.type.label, color = colors.ink)
                }
            }
            DaybreakIconButton(
                DsR.drawable.ic_undo,
                "Undo",
                onClick = state::undo,
                tint = if (state.canUndo) colors.ink else colors.ink.copy(alpha = 0.3f),
                size = 44.dp,
            )
            if (state.canRedo) {
                DaybreakIconButton(DsR.drawable.ic_redo, "Redo", onClick = state::redo, size = 44.dp)
            }
            Spacer(Modifier.width(4.dp))
            PrimaryPill("Done", onClick = onDone)
        }

        // The photo.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val current = frame
            if (cropping) {
                val shown = current?.takeIf { it.request.showFullFrame }
                val full = session.outputSize(state.recipe, applyCrop = false)
                val aspect = full.width.toFloat() / full.height.coerceAtLeast(1)
                CropOverlay(
                    frame = shown?.bitmap?.asImageBitmap(),
                    aspect = aspect,
                    crop = state.recipe.crop,
                    ratio = cropChoice.ratio(aspect),
                    onCrop = { state.preview(state.recipe.copy(crop = it)) },
                    onCropDone = state::commit,
                )
            } else {
                val bitmap = when {
                    comparing -> session.large
                    current != null && !current.request.showFullFrame -> current.bitmap
                    else -> null
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = if (comparing) "Original photo" else "Edited photo",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(onPress = {
                                    comparing = true
                                    tryAwaitRelease()
                                    comparing = false
                                })
                            },
                    )
                } else {
                    CircularProgressIndicator(color = colors.sun, modifier = Modifier.align(Alignment.Center))
                }
                Text(
                    text = if (comparing) "Original" else "Hold to compare",
                    style = Daybreak.type.caption,
                    color = colors.ink,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.scrim)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        // The tool's controls.
        Column(Modifier.fillMaxWidth()) {
            when (tool) {
                EditTool.Light -> LightPanel(state)
                EditTool.Color -> ColorPanel(state)
                EditTool.Curves -> CurvesPanel(state, histogram)
                EditTool.Detail -> DetailPanel(state)
                EditTool.Crop -> CropPanel(state, session, cropChoice)
            }
        }

        // Tool tabs.
        Column(Modifier.fillMaxWidth().background(colors.surface100)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.line))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
            ) {
                EditTool.entries.forEach { entry ->
                    ToolTab(
                        iconRes = entry.icon,
                        label = entry.label,
                        selected = entry == tool,
                        onClick = { onTool(entry) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ panels

private val PanelPadding = Modifier.padding(horizontal = 16.dp)

/** Applies a slider change to the recipe being edited. */
private fun EditorState.change(adjustment: Adjustment, value: Float) {
    preview(adjustment.set(recipe, value))
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun LightPanel(state: EditorState) {
    var key by rememberSaveable { mutableStateOf(Adjustments.Exposure.key) }
    val adjustment = Adjustments.light.firstOrNull { it.key == key } ?: Adjustments.Exposure
    ChipRow {
        Adjustments.light.forEach { item ->
            ToolChip(
                text = item.label,
                selected = item.key == key,
                edited = item.isEdited(state.recipe),
                onClick = { key = item.key },
            )
        }
    }
    LargeAdjustment(
        adjustment = adjustment,
        value = adjustment.get(state.recipe),
        onChange = { state.change(adjustment, it) },
        onDone = state::commit,
        modifier = PanelPadding.padding(bottom = 12.dp),
    )
}

private enum class ColorSection(val label: String) { Basic("Basic"), Mixer("Mixer"), Grading("Grading") }

@Composable
private fun ColorPanel(state: EditorState) {
    var section by rememberSaveable { mutableStateOf(ColorSection.Basic) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ColorSection.entries.forEach { entry ->
            val edited = when (entry) {
                ColorSection.Basic -> Adjustments.basicColor.any { it.isEdited(state.recipe) }
                ColorSection.Mixer -> MixerBand.entries.any { state.recipe.mixerEdited(it) }
                ColorSection.Grading -> state.recipe.shadowAmount > 0f || state.recipe.highlightAmount > 0f
            }
            ToolChip(
                text = entry.label,
                selected = entry == section,
                edited = edited,
                onClick = { section = entry },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Column(PanelPadding.padding(bottom = 8.dp)) {
        when (section) {
            ColorSection.Basic -> Adjustments.basicColor.forEach { adjustment ->
                CompactAdjustment(
                    adjustment = adjustment,
                    value = adjustment.get(state.recipe),
                    onChange = { state.change(adjustment, it) },
                    onDone = state::commit,
                    track = Adjustments.trackFor(adjustment),
                )
            }
            ColorSection.Mixer -> MixerControls(state)
            ColorSection.Grading -> GradingControls(state)
        }
    }
}

@Composable
private fun MixerControls(state: EditorState) {
    val colors = Daybreak.colors
    var bandName by rememberSaveable { mutableStateOf(MixerBand.Orange.name) }
    val band = MixerBand.entries.firstOrNull { it.name == bandName } ?: MixerBand.Orange
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        MixerBand.entries.forEach { entry ->
            val selected = entry == band
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.sun else colors.surface000)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(colors.surface000)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(entry.swatch)
                    .clickable(role = Role.RadioButton) { bandName = entry.name }
                    .semantics { contentDescription = entry.label + if (selected) ", selected" else "" },
                contentAlignment = Alignment.Center,
            ) {
                if (state.recipe.mixerEdited(entry)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(colors.surface000))
                }
            }
        }
    }
    Text(band.label, style = Daybreak.type.label, color = colors.inkMuted, modifier = Modifier.padding(top = 6.dp))
    listOf(Adjustments.mixerHue(band), Adjustments.mixerSaturation(band), Adjustments.mixerLuminance(band)).forEach { adjustment ->
        CompactAdjustment(
            adjustment = adjustment,
            value = adjustment.get(state.recipe),
            onChange = { state.change(adjustment, it) },
            onDone = state::commit,
            track = Adjustments.trackFor(adjustment, band),
        )
    }
}

@Composable
private fun GradingControls(state: EditorState) {
    var highlights by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolChip(
            "Shadows",
            selected = !highlights,
            edited = state.recipe.shadowAmount > 0f,
            onClick = { highlights = false },
        )
        ToolChip(
            "Highlights",
            selected = highlights,
            edited = state.recipe.highlightAmount > 0f,
            onClick = { highlights = true },
        )
    }
    Spacer(Modifier.height(4.dp))
    val hue = if (highlights) Adjustments.HighlightHue else Adjustments.ShadowHue
    val amount = if (highlights) Adjustments.HighlightAmount else Adjustments.ShadowAmount
    listOf(hue, amount, Adjustments.Balance).forEach { adjustment ->
        CompactAdjustment(
            adjustment = adjustment,
            value = adjustment.get(state.recipe),
            onChange = { state.change(adjustment, it) },
            onDone = state::commit,
            track = Adjustments.trackFor(adjustment),
        )
    }
}

@Composable
private fun CurvesPanel(state: EditorState, histogram: FloatArray?) {
    var channelName by rememberSaveable { mutableStateOf(CurveChannel.Rgb.name) }
    val channel = CurveChannel.entries.firstOrNull { it.name == channelName } ?: CurveChannel.Rgb
    var selected by remember(channel) { mutableStateOf<Int?>(null) }
    val points = state.recipe.curve(channel).let { if (it.size >= 2) CurveSpline.clean(it) else CurveSpline.Identity }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CurveChannel.entries.forEach { entry ->
            ToolChip(
                text = entry.label,
                selected = entry == channel,
                edited = !EditRecipe.curveIsIdentity(state.recipe.curve(entry)),
                mono = true,
                onClick = { channelName = entry.name },
                modifier = Modifier.weight(1f),
                leading = {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(entry.color))
                },
            )
        }
    }
    CurveEditor(
        points = points,
        channel = channel,
        histogram = histogram,
        selected = selected,
        onSelect = { selected = it },
        onPoints = { list, done ->
            val next = state.recipe.withCurve(channel, list)
            if (done) state.set(next) else state.preview(next)
        },
        modifier = PanelPadding
            .fillMaxWidth()
            .height(210.dp),
    )
    Row(
        modifier = PanelPadding.padding(top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val index = selected
        val removable = index != null && index > 0 && index < points.lastIndex
        SecondaryButton(
            "Delete point",
            enabled = removable,
            onClick = {
                if (index != null && removable) {
                    state.set(state.recipe.withCurve(channel, points.filterIndexed { i, _ -> i != index }))
                    selected = null
                }
            },
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            "Reset curve",
            enabled = !EditRecipe.curveIsIdentity(state.recipe.curve(channel)),
            onClick = {
                state.set(state.recipe.withCurve(channel, emptyList()))
                selected = null
            },
            modifier = Modifier.weight(1f),
        )
    }
}

private enum class DetailSection(val label: String) { Sharpen("Sharpen"), Noise("Noise reduction"), Grain("Grain") }

@Composable
private fun DetailPanel(state: EditorState) {
    var section by rememberSaveable { mutableStateOf(DetailSection.Sharpen) }
    ChipRow {
        DetailSection.entries.forEach { entry ->
            val edited = when (entry) {
                DetailSection.Sharpen -> state.recipe.sharpen > 0f
                DetailSection.Noise -> state.recipe.noiseLuma > 0f || state.recipe.noiseColor > 0f
                DetailSection.Grain -> state.recipe.grain > 0f
            }
            ToolChip(entry.label, selected = entry == section, edited = edited, onClick = { section = entry })
        }
    }
    val adjustments = when (section) {
        DetailSection.Sharpen -> listOf(Adjustments.Sharpen, Adjustments.SharpenRadius)
        DetailSection.Noise -> listOf(Adjustments.NoiseLuma, Adjustments.NoiseColor)
        DetailSection.Grain -> listOf(Adjustments.Grain, Adjustments.GrainSize)
    }
    Column(PanelPadding.padding(bottom = 12.dp)) {
        adjustments.forEach { adjustment ->
            CompactAdjustment(
                adjustment = adjustment,
                value = adjustment.get(state.recipe),
                onChange = { state.change(adjustment, it) },
                onDone = state::commit,
            )
        }
    }
}

/** The crop shape picked under the frame. */
private class CropChoice {
    var shape by mutableStateOf(CropShape.Free)
    var turned by mutableStateOf(false)

    fun ratio(aspect: Float): Float? = CropMath.ratioFor(shape, aspect, turned)
}

@Composable
private fun CropPanel(state: EditorState, session: EditSession, choice: CropChoice) {
    val colors = Daybreak.colors
    val recipe = state.recipe
    val full = session.outputSize(recipe, applyCrop = false)
    val aspect = full.width.toFloat() / full.height.coerceAtLeast(1)

    // Straighten, from -45 to 45 degrees in half-degree ticks.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            formatAngle(recipe.straighten),
            style = Daybreak.type.readout,
            color = if (recipe.straighten != 0f) colors.sun else colors.ink,
        )
        RulerDial(
            position = (recipe.straighten + 45f) * 2f,
            tickCount = 181,
            onPosition = { position ->
                val degrees = (position / 2f - 45f).let { if (kotlin.math.abs(it) < 0.25f) 0f else (it * 10f).roundToInt() / 10f }
                state.preview(state.recipe.copy(straighten = degrees))
            },
            onRelease = state::commit,
            onReset = { state.set(state.recipe.copy(straighten = 0f)) },
            contentDescription = "Straighten",
            stateDescription = formatAngle(recipe.straighten),
            majorEvery = 10,
            tickSpacing = 7.dp,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DaybreakIconButton(DsR.drawable.ic_rotate, "Rotate left", onClick = {
            val r = state.recipe
            state.set(
                r.copy(
                    quarterTurns = (r.quarterTurns + if (r.flip) 1 else 3) % 4,
                    crop = r.crop.turnedCounterclockwise(),
                ),
            )
        })
        DaybreakIconButton(DsR.drawable.ic_mirror, "Flip", onClick = {
            val r = state.recipe
            state.set(r.copy(flip = !r.flip, straighten = -r.straighten + 0f, crop = r.crop.mirrored()))
        })
        Spacer(Modifier.weight(1f))
        SecondaryButton(
            "Reset",
            enabled = !recipe.geometryIsIdentity,
            onClick = {
                choice.shape = CropShape.Free
                choice.turned = false
                state.set(state.recipe.copy(quarterTurns = 0, flip = false, straighten = 0f, crop = CropRect.Full))
            },
        )
    }

    ChipRow {
        CropShape.entries.forEach { shape ->
            ToolChip(
                text = CropMath.labelFor(shape, aspect, if (shape == choice.shape) choice.turned else false),
                selected = shape == choice.shape,
                mono = true,
                onClick = {
                    if (shape == choice.shape && shape != CropShape.Free && shape != CropShape.Square && shape != CropShape.Original) {
                        choice.turned = !choice.turned
                    } else {
                        choice.shape = shape
                        choice.turned = false
                    }
                    val ratio = CropMath.ratioFor(choice.shape, aspect, choice.turned)
                    if (ratio != null) state.set(state.recipe.copy(crop = CropMath.fit(state.recipe.crop, ratio, aspect)))
                },
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}
