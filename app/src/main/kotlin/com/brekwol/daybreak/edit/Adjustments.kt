package com.brekwol.daybreak.edit

import androidx.compose.ui.graphics.Color
import com.brekwol.daybreak.engine.EditRecipe
import com.brekwol.daybreak.engine.MixerBand
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** How a slider's track is painted. */
sealed interface Track {
    /** A thin line filled from the neutral point to the value. */
    data object Plain : Track

    /** A colored band, for color sliders. */
    data class Gradient(val colors: List<Color>) : Track
}

/** One slider: its range, how its value reads, and which recipe field it moves. */
class Adjustment(
    val key: String,
    val label: String,
    val min: Float,
    val max: Float,
    val neutral: Float = 0f,
    val format: (Float) -> String = ::formatSigned,
    /** End labels under the large slider. */
    val minLabel: String = "−100",
    val maxLabel: String = "+100",
    val get: (EditRecipe) -> Float,
    val set: (EditRecipe, Float) -> EditRecipe,
) {
    val bipolar: Boolean get() = min < neutral

    fun isEdited(recipe: EditRecipe): Boolean = get(recipe) != neutral

    /** Clamps, snaps to the neutral value when very close, and turns -0 into 0. */
    fun clean(value: Float): Float {
        val clamped = value.coerceIn(min, max)
        return if (abs(clamped - neutral) < (max - min) * SNAP) neutral else clamped + 0f
    }

    companion object {
        private const val SNAP = 0.006f
    }
}

fun formatSigned(value: Float): String {
    val n = (value * 100).roundToInt()
    return when {
        n > 0 -> "+$n"
        n < 0 -> "−${-n}"
        else -> "0"
    }
}

fun formatPercent(value: Float): String = (value * 100).roundToInt().toString()

fun formatStops(value: Float): String {
    val text = String.format(Locale.US, "%.2f", abs(value))
    return when {
        value >= 0.005f -> "+$text"
        value <= -0.005f -> "−$text"
        else -> "0.00"
    }
}

fun formatDegrees(value: Float): String = "${value.roundToInt()}°"

fun formatPixels(value: Float): String = String.format(Locale.US, "%.1f px", value)

fun formatAngle(value: Float): String {
    val text = String.format(Locale.US, "%.1f°", abs(value))
    return when {
        value >= 0.05f -> "+$text"
        value <= -0.05f -> "−$text"
        else -> "0.0°"
    }
}

/** An sRGB color from Oklab lightness, chroma and hue in degrees (the engine's hue angles). */
fun oklchColor(lightness: Float, chroma: Float, hueDegrees: Float): Color {
    val h = hueDegrees * PI.toFloat() / 180f
    val a = chroma * cos(h)
    val b = chroma * sin(h)
    val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
    val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
    val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
    val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
    val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
    val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
    fun encode(v: Float): Float {
        val c = v.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else 1.055f * c.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }
    return Color(encode(r), encode(g), encode(bl))
}

/** Centre hue of each mixer band, in Oklab degrees (matches the engine). */
val MixerBand.hueDegrees: Float
    get() = when (this) {
        MixerBand.Red -> 25f
        MixerBand.Orange -> 60f
        MixerBand.Yellow -> 105f
        MixerBand.Green -> 145f
        MixerBand.Aqua -> 195f
        MixerBand.Blue -> 260f
        MixerBand.Purple -> 295f
        MixerBand.Magenta -> 335f
    }

/** The swatch shown for a mixer band. */
val MixerBand.swatch: Color
    get() = oklchColor(0.7f, 0.15f, hueDegrees)

object Adjustments {
    val Exposure = Adjustment(
        "exposure", "Exposure", -4f, 4f, format = ::formatStops, minLabel = "−4", maxLabel = "+4",
        get = { it.exposure }, set = { r, v -> r.copy(exposure = v) },
    )
    val Brightness = Adjustment("brightness", "Brightness", -1f, 1f, get = { it.brightness }, set = { r, v -> r.copy(brightness = v) })
    val Contrast = Adjustment("contrast", "Contrast", -1f, 1f, get = { it.contrast }, set = { r, v -> r.copy(contrast = v) })
    val Highlights = Adjustment("highlights", "Highlights", -1f, 1f, get = { it.highlights }, set = { r, v -> r.copy(highlights = v) })
    val Shadows = Adjustment("shadows", "Shadows", -1f, 1f, get = { it.shadows }, set = { r, v -> r.copy(shadows = v) })
    val Whites = Adjustment("whites", "Whites", -1f, 1f, get = { it.whites }, set = { r, v -> r.copy(whites = v) })
    val Blacks = Adjustment("blacks", "Blacks", -1f, 1f, get = { it.blacks }, set = { r, v -> r.copy(blacks = v) })
    val Clarity = Adjustment("clarity", "Clarity", -1f, 1f, get = { it.clarity }, set = { r, v -> r.copy(clarity = v) })
    val Vignette = Adjustment("vignette", "Vignette", -1f, 1f, get = { it.vignette }, set = { r, v -> r.copy(vignette = v) })

    val light = listOf(Exposure, Brightness, Contrast, Highlights, Shadows, Whites, Blacks, Clarity, Vignette)

    val Temperature = Adjustment("temperature", "Temperature", -1f, 1f, get = { it.temperature }, set = { r, v -> r.copy(temperature = v) })
    val Tint = Adjustment("tint", "Tint", -1f, 1f, get = { it.tint }, set = { r, v -> r.copy(tint = v) })
    val Vibrance = Adjustment("vibrance", "Vibrance", -1f, 1f, get = { it.vibrance }, set = { r, v -> r.copy(vibrance = v) })
    val Saturation = Adjustment("saturation", "Saturation", -1f, 1f, get = { it.saturation }, set = { r, v -> r.copy(saturation = v) })

    val basicColor = listOf(Temperature, Tint, Vibrance, Saturation)

    fun mixerHue(band: MixerBand) = Adjustment(
        "mixer_hue", "Hue", -1f, 1f,
        get = { it.mixer(band).hue },
        set = { r, v -> r.withMixer(band, r.mixer(band).copy(hue = v)) },
    )

    fun mixerSaturation(band: MixerBand) = Adjustment(
        "mixer_saturation", "Saturation", -1f, 1f,
        get = { it.mixer(band).saturation },
        set = { r, v -> r.withMixer(band, r.mixer(band).copy(saturation = v)) },
    )

    fun mixerLuminance(band: MixerBand) = Adjustment(
        "mixer_luminance", "Luminance", -1f, 1f,
        get = { it.mixer(band).luminance },
        set = { r, v -> r.withMixer(band, r.mixer(band).copy(luminance = v)) },
    )

    val ShadowHue = Adjustment(
        "shadow_hue", "Hue", 0f, 360f, neutral = EditRecipe.DEFAULT_SHADOW_HUE, format = ::formatDegrees,
        get = { it.shadowHue }, set = { r, v -> r.copy(shadowHue = v) },
    )
    val ShadowAmount = Adjustment(
        "shadow_amount", "Amount", 0f, 1f, format = ::formatPercent,
        get = { it.shadowAmount }, set = { r, v -> r.copy(shadowAmount = v) },
    )
    val HighlightHue = Adjustment(
        "highlight_hue", "Hue", 0f, 360f, neutral = EditRecipe.DEFAULT_HIGHLIGHT_HUE, format = ::formatDegrees,
        get = { it.highlightHue }, set = { r, v -> r.copy(highlightHue = v) },
    )
    val HighlightAmount = Adjustment(
        "highlight_amount", "Amount", 0f, 1f, format = ::formatPercent,
        get = { it.highlightAmount }, set = { r, v -> r.copy(highlightAmount = v) },
    )
    val Balance = Adjustment(
        "grading_balance", "Balance", -1f, 1f,
        get = { it.gradingBalance }, set = { r, v -> r.copy(gradingBalance = v) },
    )

    val Sharpen = Adjustment(
        "sharpen", "Amount", 0f, 1f, format = ::formatPercent,
        get = { it.sharpen }, set = { r, v -> r.copy(sharpen = v) },
    )
    val SharpenRadius = Adjustment(
        "sharpen_radius", "Radius", 0.5f, 3f, neutral = EditRecipe.DEFAULT_SHARPEN_RADIUS, format = ::formatPixels,
        get = { it.sharpenRadius }, set = { r, v -> r.copy(sharpenRadius = v) },
    )
    val NoiseLuma = Adjustment(
        "noise_luma", "Luminance", 0f, 1f, format = ::formatPercent,
        get = { it.noiseLuma }, set = { r, v -> r.copy(noiseLuma = v) },
    )
    val NoiseColor = Adjustment(
        "noise_color", "Color", 0f, 1f, format = ::formatPercent,
        get = { it.noiseColor }, set = { r, v -> r.copy(noiseColor = v) },
    )
    val Grain = Adjustment(
        "grain", "Amount", 0f, 1f, format = ::formatPercent,
        get = { it.grain }, set = { r, v -> r.copy(grain = v) },
    )
    val GrainSize = Adjustment(
        "grain_size", "Size", 0f, 1f, neutral = EditRecipe.DEFAULT_GRAIN_SIZE, format = ::formatPercent,
        get = { it.grainSize }, set = { r, v -> r.copy(grainSize = v) },
    )

    /** Track colors for the color sliders. */
    fun trackFor(adjustment: Adjustment, band: MixerBand? = null): Track {
        val gray = Color(0xFF6B6560)
        return when {
            adjustment === Temperature -> Track.Gradient(listOf(Color(0xFF4A7FD6), Color(0xFFD9D2C4), Color(0xFFE8B547)))
            adjustment === Tint -> Track.Gradient(listOf(Color(0xFF5BB36A), Color(0xFFD9D2C4), Color(0xFFD45CB4)))
            adjustment === Saturation || adjustment === Vibrance ->
                Track.Gradient(listOf(gray, oklchColor(0.7f, 0.17f, 40f), oklchColor(0.7f, 0.17f, 150f), oklchColor(0.7f, 0.17f, 260f)))
            adjustment.key == "mixer_hue" && band != null ->
                Track.Gradient((-30..30 step 10).map { oklchColor(0.7f, 0.15f, band.hueDegrees + it) })
            adjustment.key == "mixer_saturation" && band != null ->
                Track.Gradient(listOf(gray, band.swatch))
            adjustment.key == "mixer_luminance" && band != null ->
                Track.Gradient(listOf(oklchColor(0.3f, 0.08f, band.hueDegrees), oklchColor(0.88f, 0.08f, band.hueDegrees)))
            adjustment === ShadowHue || adjustment === HighlightHue ->
                Track.Gradient((0..360 step 30).map { oklchColor(0.7f, 0.13f, it.toFloat()) })
            else -> Track.Plain
        }
    }
}

/** True when anything in the mixer is set for [band]. */
fun EditRecipe.mixerEdited(band: MixerBand): Boolean = !mixer(band).isNeutral
