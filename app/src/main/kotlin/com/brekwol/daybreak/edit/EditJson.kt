package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.CropRect
import com.brekwol.daybreak.engine.CurveChannel
import com.brekwol.daybreak.engine.CurvePoint
import com.brekwol.daybreak.engine.EditRecipe
import com.brekwol.daybreak.engine.MixerAdjust
import com.brekwol.daybreak.engine.MixerBand
import org.json.JSONArray
import org.json.JSONObject

/**
 * Edits as small JSON objects. Only values that differ from the default are
 * written, so files stay short and stay readable when new adjustments arrive.
 * Unknown keys are ignored and missing ones keep their default.
 */
object EditJson {
    private class Field(
        val key: String,
        val get: (EditRecipe) -> Float,
        val set: (EditRecipe, Float) -> EditRecipe,
    )

    /** Every plain number field, by its JSON key. */
    private val numbers: List<Field> = listOf(
        Field("exposure", { it.exposure }) { r, v -> r.copy(exposure = v) },
        Field("brightness", { it.brightness }) { r, v -> r.copy(brightness = v) },
        Field("contrast", { it.contrast }) { r, v -> r.copy(contrast = v) },
        Field("highlights", { it.highlights }) { r, v -> r.copy(highlights = v) },
        Field("shadows", { it.shadows }) { r, v -> r.copy(shadows = v) },
        Field("whites", { it.whites }) { r, v -> r.copy(whites = v) },
        Field("blacks", { it.blacks }) { r, v -> r.copy(blacks = v) },
        Field("clarity", { it.clarity }) { r, v -> r.copy(clarity = v) },
        Field("vignette", { it.vignette }) { r, v -> r.copy(vignette = v) },
        Field("temperature", { it.temperature }) { r, v -> r.copy(temperature = v) },
        Field("tint", { it.tint }) { r, v -> r.copy(tint = v) },
        Field("vibrance", { it.vibrance }) { r, v -> r.copy(vibrance = v) },
        Field("saturation", { it.saturation }) { r, v -> r.copy(saturation = v) },
        Field("shadowHue", { it.shadowHue }) { r, v -> r.copy(shadowHue = v) },
        Field("shadowAmount", { it.shadowAmount }) { r, v -> r.copy(shadowAmount = v) },
        Field("highlightHue", { it.highlightHue }) { r, v -> r.copy(highlightHue = v) },
        Field("highlightAmount", { it.highlightAmount }) { r, v -> r.copy(highlightAmount = v) },
        Field("gradingBalance", { it.gradingBalance }) { r, v -> r.copy(gradingBalance = v) },
        Field("sharpen", { it.sharpen }) { r, v -> r.copy(sharpen = v) },
        Field("sharpenRadius", { it.sharpenRadius }) { r, v -> r.copy(sharpenRadius = v) },
        Field("noiseLuma", { it.noiseLuma }) { r, v -> r.copy(noiseLuma = v) },
        Field("noiseColor", { it.noiseColor }) { r, v -> r.copy(noiseColor = v) },
        Field("grain", { it.grain }) { r, v -> r.copy(grain = v) },
        Field("grainSize", { it.grainSize }) { r, v -> r.copy(grainSize = v) },
        Field("straighten", { it.straighten }) { r, v -> r.copy(straighten = v) },
    )

    fun toJson(recipe: EditRecipe): JSONObject {
        val json = JSONObject()
        val neutral = EditRecipe.Neutral
        for (field in numbers) {
            val value = field.get(recipe)
            if (value != field.get(neutral)) json.put(field.key, round(value))
        }
        val mixer = JSONObject()
        for (band in MixerBand.entries) {
            val adjust = recipe.mixer(band)
            if (adjust.isNeutral) continue
            mixer.put(
                band.key,
                JSONObject().apply {
                    if (adjust.hue != 0f) put("hue", round(adjust.hue))
                    if (adjust.saturation != 0f) put("saturation", round(adjust.saturation))
                    if (adjust.luminance != 0f) put("luminance", round(adjust.luminance))
                },
            )
        }
        if (mixer.length() > 0) json.put("mixer", mixer)
        val curves = JSONObject()
        for (channel in CurveChannel.entries) {
            val points = recipe.curve(channel)
            if (EditRecipe.curveIsIdentity(points)) continue
            curves.put(
                channel.key,
                JSONArray().apply { points.forEach { put(JSONArray().put(round(it.x)).put(round(it.y))) } },
            )
        }
        if (curves.length() > 0) json.put("curves", curves)
        val turns = ((recipe.quarterTurns % 4) + 4) % 4
        if (turns != 0) json.put("quarterTurns", turns)
        if (recipe.flip) json.put("flip", true)
        if (!recipe.crop.isFull) {
            json.put(
                "crop",
                JSONArray()
                    .put(round(recipe.crop.left)).put(round(recipe.crop.top))
                    .put(round(recipe.crop.right)).put(round(recipe.crop.bottom)),
            )
        }
        return json
    }

    fun fromJson(json: JSONObject): EditRecipe {
        var recipe = EditRecipe.Neutral
        for (field in numbers) {
            val value = json.optDouble(field.key, Double.NaN)
            if (!value.isNaN() && !value.isInfinite()) recipe = field.set(recipe, value.toFloat())
        }
        json.optJSONObject("mixer")?.let { mixer ->
            for (band in MixerBand.entries) {
                val entry = mixer.optJSONObject(band.key) ?: continue
                recipe = recipe.withMixer(
                    band,
                    MixerAdjust(
                        hue = entry.optFloat("hue"),
                        saturation = entry.optFloat("saturation"),
                        luminance = entry.optFloat("luminance"),
                    ),
                )
            }
        }
        json.optJSONObject("curves")?.let { curves ->
            for (channel in CurveChannel.entries) {
                val list = curves.optJSONArray(channel.key) ?: continue
                val points = (0 until list.length()).mapNotNull { i ->
                    val pair = list.optJSONArray(i) ?: return@mapNotNull null
                    val x = pair.optDouble(0, Double.NaN)
                    val y = pair.optDouble(1, Double.NaN)
                    if (x.isNaN() || y.isNaN()) null else CurvePoint(x.toFloat().coerceIn(0f, 1f), y.toFloat().coerceIn(0f, 1f))
                }.sortedBy { it.x }
                recipe = recipe.withCurve(channel, points)
            }
        }
        recipe = recipe.copy(
            quarterTurns = ((json.optInt("quarterTurns", 0) % 4) + 4) % 4,
            flip = json.optBoolean("flip", false),
        )
        json.optJSONArray("crop")?.let { crop ->
            if (crop.length() == 4) {
                val l = crop.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f)
                val t = crop.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f)
                val r = crop.optDouble(2, 1.0).toFloat().coerceIn(0f, 1f)
                val b = crop.optDouble(3, 1.0).toFloat().coerceIn(0f, 1f)
                if (r > l && b > t) recipe = recipe.copy(crop = CropRect(l, t, r, b))
            }
        }
        return recipe
    }

    private fun JSONObject.optFloat(key: String): Float {
        val value = optDouble(key, 0.0)
        return if (value.isNaN() || value.isInfinite()) 0f else value.toFloat()
    }

    /** Four decimals is far finer than any slider step and keeps files tidy. */
    private fun round(value: Float): Double = Math.round(value.toDouble() * 10_000.0) / 10_000.0
}
