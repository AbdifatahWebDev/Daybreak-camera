package com.brekwol.daybreak.engine

/** Hue bands of the color mixer, in the engine's order. */
enum class MixerBand(val key: String, val label: String) {
    Red("red", "Red"),
    Orange("orange", "Orange"),
    Yellow("yellow", "Yellow"),
    Green("green", "Green"),
    Aqua("aqua", "Aqua"),
    Blue("blue", "Blue"),
    Purple("purple", "Purple"),
    Magenta("magenta", "Magenta"),
}

/** Tone curve channels, in the engine's order. */
enum class CurveChannel(val key: String, val label: String) {
    Rgb("rgb", "RGB"),
    Red("red", "R"),
    Green("green", "G"),
    Blue("blue", "B"),
    Luma("luma", "Luma"),
}

data class MixerAdjust(
    /** -1..1 = -30..30 degrees. */
    val hue: Float = 0f,
    val saturation: Float = 0f,
    val luminance: Float = 0f,
) {
    val isNeutral: Boolean
        get() = hue == 0f && saturation == 0f && luminance == 0f

    fun scaled(amount: Float) = MixerAdjust(hue * amount, saturation * amount, luminance * amount)
}

/** A curve point; both coordinates run 0..1. */
data class CurvePoint(val x: Float, val y: Float)

/** Crop rectangle as fractions of the straightened frame. */
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isFull: Boolean
        get() = left <= 0f && top <= 0f && right >= 1f && bottom >= 1f

    /** The same region after the frame turns 90 degrees counterclockwise. */
    fun turnedCounterclockwise() = CropRect(left = top, top = 1f - right, right = bottom, bottom = 1f - left)

    /** The same region after the frame is mirrored left to right. */
    fun mirrored() = CropRect(left = 1f - right, top = top, right = 1f - left, bottom = bottom)

    companion object {
        val Full = CropRect()
    }
}

/**
 * Everything an edit does, as plain values. The defaults leave the photo unchanged.
 * Field meanings and ranges match `daybreak::EditRecipe` (native/include/daybreak/edit.hpp):
 * most sliders run -1..1 or 0..1, exposure is in stops.
 */
data class EditRecipe(
    // Light
    val exposure: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val clarity: Float = 0f,
    val vignette: Float = 0f,
    // Color
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val mixer: List<MixerAdjust> = NeutralMixer,
    val shadowHue: Float = DEFAULT_SHADOW_HUE,
    val shadowAmount: Float = 0f,
    val highlightHue: Float = DEFAULT_HIGHLIGHT_HUE,
    val highlightAmount: Float = 0f,
    val gradingBalance: Float = 0f,
    // Detail
    val sharpen: Float = 0f,
    val sharpenRadius: Float = DEFAULT_SHARPEN_RADIUS,
    val noiseLuma: Float = 0f,
    val noiseColor: Float = 0f,
    val grain: Float = 0f,
    val grainSize: Float = DEFAULT_GRAIN_SIZE,
    // Curves, one list per [CurveChannel]; an empty list is the identity.
    val curves: List<List<CurvePoint>> = NeutralCurves,
    // Geometry, applied in this order: quarter turns, flip, straighten, crop.
    /** Clockwise, 0..3. */
    val quarterTurns: Int = 0,
    /** Mirror left to right. */
    val flip: Boolean = false,
    /** Degrees, -45..45; positive turns the picture clockwise. */
    val straighten: Float = 0f,
    val crop: CropRect = CropRect.Full,
) {
    init {
        require(mixer.size == MixerBand.entries.size) { "The mixer needs one entry per band" }
        require(curves.size == CurveChannel.entries.size) { "Curves need one list per channel" }
    }

    fun mixer(band: MixerBand): MixerAdjust = mixer[band.ordinal]

    fun withMixer(band: MixerBand, adjust: MixerAdjust): EditRecipe =
        copy(mixer = mixer.toMutableList().also { it[band.ordinal] = adjust })

    fun curve(channel: CurveChannel): List<CurvePoint> = curves[channel.ordinal]

    fun withCurve(channel: CurveChannel, points: List<CurvePoint>): EditRecipe =
        copy(curves = curves.toMutableList().also { it[channel.ordinal] = points })

    /** True when the edit changes nothing, as the engine decides it (-0 counts as 0). */
    val isIdentity: Boolean
        get() = lookIsIdentity && geometryIsIdentity

    /** True when no light, color, curve or detail adjustment is active. */
    val lookIsIdentity: Boolean
        get() = exposure == 0f && brightness == 0f && contrast == 0f && highlights == 0f &&
            shadows == 0f && whites == 0f && blacks == 0f && clarity == 0f && vignette == 0f &&
            temperature == 0f && tint == 0f && vibrance == 0f && saturation == 0f &&
            shadowAmount <= 0f && highlightAmount <= 0f && sharpen <= 0f && noiseLuma <= 0f &&
            noiseColor <= 0f && grain <= 0f && mixer.all { it.isNeutral } && curves.all { curveIsIdentity(it) }

    val geometryIsIdentity: Boolean
        get() = quarterTurns % 4 == 0 && !flip && straighten == 0f && crop.isFull

    /** This edit without its geometry: the part a preset carries. */
    val look: EditRecipe
        get() = copy(quarterTurns = 0, flip = false, straighten = 0f, crop = CropRect.Full)
            .copy(curves = curves.map { if (curveIsIdentity(it)) emptyList() else it })

    /** [look]'s adjustments with this edit's geometry. */
    fun withLook(look: EditRecipe): EditRecipe =
        look.copy(quarterTurns = quarterTurns, flip = flip, straighten = straighten, crop = crop)

    /**
     * This edit's look at [amount] strength (0 = none, 1 = as saved), for preset
     * strength. Hues and sizes stay; amounts, sliders and curve bends scale.
     */
    fun lookAtStrength(amount: Float): EditRecipe {
        val a = amount.coerceIn(0f, 1f)
        if (a == 1f) return look
        return EditRecipe(
            exposure = exposure * a,
            brightness = brightness * a,
            contrast = contrast * a,
            highlights = highlights * a,
            shadows = shadows * a,
            whites = whites * a,
            blacks = blacks * a,
            clarity = clarity * a,
            vignette = vignette * a,
            temperature = temperature * a,
            tint = tint * a,
            vibrance = vibrance * a,
            saturation = saturation * a,
            mixer = mixer.map { it.scaled(a) },
            shadowHue = shadowHue,
            shadowAmount = shadowAmount * a,
            highlightHue = highlightHue,
            highlightAmount = highlightAmount * a,
            gradingBalance = gradingBalance * a,
            sharpen = sharpen * a,
            sharpenRadius = sharpenRadius,
            noiseLuma = noiseLuma * a,
            noiseColor = noiseColor * a,
            grain = grain * a,
            grainSize = grainSize,
            curves = curves.map { points ->
                if (curveIsIdentity(points) || a == 0f) {
                    emptyList()
                } else {
                    points.map { CurvePoint(it.x, it.x + (it.y - it.x) * a) }
                }
            },
        )
    }

    /** The edit as the engine's flat parameter list (see engine_jni.cpp, read_recipe). */
    fun packParams(): FloatArray {
        val p = FloatArray(PARAM_COUNT)
        p[0] = exposure
        p[1] = brightness
        p[2] = contrast
        p[3] = highlights
        p[4] = shadows
        p[5] = whites
        p[6] = blacks
        p[7] = clarity
        p[8] = vignette
        p[9] = temperature
        p[10] = tint
        p[11] = vibrance
        p[12] = saturation
        mixer.forEachIndexed { i, band ->
            p[13 + i * 3] = band.hue
            p[14 + i * 3] = band.saturation
            p[15 + i * 3] = band.luminance
        }
        p[37] = shadowHue
        p[38] = shadowAmount
        p[39] = highlightHue
        p[40] = highlightAmount
        p[41] = gradingBalance
        p[42] = sharpen
        p[43] = sharpenRadius
        p[44] = noiseLuma
        p[45] = noiseColor
        p[46] = grain
        p[47] = grainSize
        p[48] = (((quarterTurns % 4) + 4) % 4).toFloat()
        p[49] = if (flip) 1f else 0f
        p[50] = straighten
        p[51] = crop.left
        p[52] = crop.top
        p[53] = crop.right
        p[54] = crop.bottom
        return p
    }

    /** Curves as `[count, x0, y0, x1, y1, ...]` for each channel in order. */
    fun packCurves(): FloatArray {
        val size = curves.sumOf { 1 + it.size * 2 }
        val out = FloatArray(size)
        var at = 0
        for (points in curves) {
            out[at++] = points.size.toFloat()
            for (point in points) {
                out[at++] = point.x
                out[at++] = point.y
            }
        }
        return out
    }

    companion object {
        const val PARAM_COUNT = 55
        const val DEFAULT_SHADOW_HUE = 220f
        const val DEFAULT_HIGHLIGHT_HUE = 40f
        const val DEFAULT_SHARPEN_RADIUS = 1f
        const val DEFAULT_GRAIN_SIZE = 0.25f

        val NeutralMixer: List<MixerAdjust> = List(MixerBand.entries.size) { MixerAdjust() }
        val NeutralCurves: List<List<CurvePoint>> = List(CurveChannel.entries.size) { emptyList() }
        val Neutral = EditRecipe()

        /**
         * True for an empty curve or the straight line from (0,0) to (1,1), as the
         * engine decides it. A curve stays flat beyond its first and last points,
         * so points on the diagonal that stop short of the ends still change the photo.
         */
        fun curveIsIdentity(points: List<CurvePoint>): Boolean {
            if (points.any { kotlin.math.abs(it.x - it.y) > 1e-4f }) return false
            if (points.size < 2) return true
            return points.minOf { it.x } <= 1e-4f && points.maxOf { it.x } >= 1f - 1e-4f
        }
    }
}
