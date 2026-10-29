package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.CurveChannel
import com.brekwol.daybreak.engine.CurvePoint
import com.brekwol.daybreak.engine.EditRecipe
import com.brekwol.daybreak.engine.MixerAdjust
import com.brekwol.daybreak.engine.MixerBand
import org.json.JSONObject

/** A named look: a plain list of adjustments, without crop or rotation. */
data class Preset(
    val id: String,
    val name: String,
    val recipe: EditRecipe,
    val builtIn: Boolean = false,
) {
    companion object {
        const val FILE_EXTENSION = "dbpreset"
        const val MIME = "application/json"
        private const val FORMAT_KEY = "daybreakPreset"

        /** The small file a preset is shared as. */
        fun toFile(preset: Preset): String = JSONObject()
            .put(FORMAT_KEY, 1)
            .put("name", preset.name)
            .put("edit", EditJson.toJson(preset.recipe.look))
            .toString(2)

        /** Reads a shared preset file, or returns null if it isn't one. */
        fun fromFile(text: String, id: String): Preset? = runCatching {
            val json = JSONObject(text)
            if (!json.has(FORMAT_KEY)) return null
            val name = json.optString("name").trim().take(MAX_NAME).ifEmpty { "Imported" }
            val edit = json.optJSONObject("edit") ?: return null
            Preset(id = id, name = name, recipe = EditJson.fromJson(edit).look)
        }.getOrNull()

        const val MAX_NAME = 40
    }
}

/** The presets that come with Daybreak. Each one is a starting point, not a filter. */
object BuiltInPresets {
    private fun curve(vararg values: Float): List<CurvePoint> =
        values.toList().chunked(2).map { (x, y) -> CurvePoint(x, y) }

    val Original = Preset("original", "Original", EditRecipe.Neutral, builtIn = true)

    val all: List<Preset> = listOf(
        Original,
        Preset(
            "natural",
            "Natural",
            EditRecipe(
                contrast = 0.08f,
                highlights = -0.2f,
                shadows = 0.15f,
                clarity = 0.08f,
                vibrance = 0.15f,
                sharpen = 0.2f,
            ),
            builtIn = true,
        ),
        Preset(
            "dawn",
            "Dawn",
            EditRecipe(
                exposure = 0.1f,
                contrast = -0.05f,
                highlights = -0.25f,
                shadows = 0.2f,
                blacks = 0.06f,
                temperature = 0.18f,
                tint = 0.06f,
                vibrance = 0.12f,
                saturation = -0.05f,
                shadowHue = 250f,
                shadowAmount = 0.2f,
                highlightHue = 55f,
                highlightAmount = 0.3f,
            ),
            builtIn = true,
        ),
        Preset(
            "warm-film",
            "Warm film",
            EditRecipe(
                contrast = 0.1f,
                temperature = 0.22f,
                saturation = -0.12f,
                vignette = -0.15f,
                highlightHue = 60f,
                highlightAmount = 0.15f,
                grain = 0.3f,
                grainSize = 0.35f,
            ).withMixer(MixerBand.Orange, MixerAdjust(saturation = 0.1f))
                .withMixer(MixerBand.Green, MixerAdjust(hue = -0.25f, saturation = -0.15f))
                .withCurve(CurveChannel.Rgb, curve(0f, 0.06f, 0.25f, 0.24f, 0.75f, 0.78f, 1f, 0.96f)),
            builtIn = true,
        ),
        Preset(
            "cool-fade",
            "Cool fade",
            EditRecipe(
                contrast = -0.12f,
                temperature = -0.18f,
                tint = -0.03f,
                saturation = -0.2f,
                shadowHue = 230f,
                shadowAmount = 0.2f,
            ).withCurve(CurveChannel.Rgb, curve(0f, 0.1f, 0.5f, 0.52f, 1f, 0.94f)),
            builtIn = true,
        ),
        Preset(
            "mono",
            "Mono",
            EditRecipe(
                contrast = 0.22f,
                clarity = 0.15f,
                saturation = -1f,
                grain = 0.15f,
            ),
            builtIn = true,
        ),
        Preset(
            "punch",
            "Punch",
            EditRecipe(
                contrast = 0.25f,
                highlights = -0.1f,
                shadows = 0.1f,
                blacks = -0.08f,
                clarity = 0.2f,
                vibrance = 0.3f,
                saturation = 0.08f,
                sharpen = 0.3f,
            ),
            builtIn = true,
        ),
    )
}
