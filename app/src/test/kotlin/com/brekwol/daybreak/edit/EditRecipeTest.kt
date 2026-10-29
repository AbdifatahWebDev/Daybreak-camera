package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.CropRect
import com.brekwol.daybreak.engine.CurveChannel
import com.brekwol.daybreak.engine.CurvePoint
import com.brekwol.daybreak.engine.EditRecipe
import com.brekwol.daybreak.engine.MixerAdjust
import com.brekwol.daybreak.engine.MixerBand
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditRecipeTest {

    private val busy = EditRecipe(
        exposure = 0.5f,
        brightness = -0.1f,
        contrast = 0.2f,
        highlights = -0.3f,
        shadows = 0.4f,
        whites = 0.05f,
        blacks = -0.05f,
        clarity = 0.15f,
        vignette = -0.25f,
        temperature = 0.1f,
        tint = -0.02f,
        vibrance = 0.3f,
        saturation = -0.1f,
        shadowHue = 200f,
        shadowAmount = 0.2f,
        highlightHue = 50f,
        highlightAmount = 0.1f,
        gradingBalance = 0.3f,
        sharpen = 0.4f,
        sharpenRadius = 1.5f,
        noiseLuma = 0.2f,
        noiseColor = 0.3f,
        grain = 0.25f,
        grainSize = 0.5f,
        quarterTurns = 3,
        flip = true,
        straighten = -2.5f,
        crop = CropRect(0.1f, 0.2f, 0.9f, 0.8f),
    ).withMixer(MixerBand.Aqua, MixerAdjust(hue = 0.2f, saturation = -0.4f, luminance = 0.1f))
        .withMixer(MixerBand.Red, MixerAdjust(saturation = 0.5f))
        .withCurve(CurveChannel.Rgb, listOf(CurvePoint(0f, 0.05f), CurvePoint(0.5f, 0.55f), CurvePoint(1f, 1f)))
        .withCurve(CurveChannel.Blue, listOf(CurvePoint(0f, 0f), CurvePoint(0.3f, 0.2f), CurvePoint(1f, 1f)))

    @Test
    fun defaultIsIdentity() {
        assertTrue(EditRecipe().isIdentity)
        assertFalse(EditRecipe(exposure = 0.1f).isIdentity)
        assertFalse(EditRecipe(quarterTurns = 1).isIdentity)
        assertTrue(EditRecipe(quarterTurns = 4).isIdentity)
        assertTrue(EditRecipe().withCurve(CurveChannel.Luma, CurveSpline.Identity).isIdentity)
    }

    @Test
    fun curveIdentityMatchesEngine() {
        assertTrue(EditRecipe.curveIsIdentity(emptyList()))
        assertTrue(EditRecipe.curveIsIdentity(listOf(CurvePoint(0.4f, 0.4f))))
        assertTrue(EditRecipe.curveIsIdentity(listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))))
        // On the diagonal but stopping short: flat beyond the ends, so not the identity.
        assertFalse(EditRecipe.curveIsIdentity(listOf(CurvePoint(0.2f, 0.2f), CurvePoint(1f, 1f))))
        assertFalse(EditRecipe.curveIsIdentity(listOf(CurvePoint(0f, 0.1f), CurvePoint(1f, 1f))))
    }

    @Test
    fun packsParamsInEngineOrder() {
        val p = busy.packParams()
        assertEquals(EditRecipe.PARAM_COUNT, p.size)
        assertEquals(0.5f, p[0])
        assertEquals(-0.25f, p[8])
        assertEquals(0.1f, p[9])
        assertEquals(-0.1f, p[12])
        // Red is band 0, Aqua band 4.
        assertEquals(0.5f, p[14])
        assertEquals(0.2f, p[13 + 4 * 3])
        assertEquals(-0.4f, p[14 + 4 * 3])
        assertEquals(0.1f, p[15 + 4 * 3])
        assertEquals(200f, p[37])
        assertEquals(0.3f, p[41])
        assertEquals(0.4f, p[42])
        assertEquals(0.5f, p[47])
        assertEquals(3f, p[48])
        assertEquals(1f, p[49])
        assertEquals(-2.5f, p[50])
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.9f, 0.8f), p.copyOfRange(51, 55), 0f)
    }

    @Test
    fun negativeTurnsPackAsClockwise() {
        assertEquals(3f, EditRecipe(quarterTurns = -1).packParams()[48])
    }

    @Test
    fun packsCurvesWithCounts() {
        val c = busy.packCurves()
        // RGB: 3 points, R: 0, G: 0, B: 3 points, Luma: 0.
        assertEquals(1 + 6 + 1 + 1 + 1 + 6 + 1, c.size)
        assertEquals(3f, c[0])
        assertEquals(0.05f, c[2])
        assertEquals(0f, c[7])
        assertEquals(0f, c[8])
        assertEquals(3f, c[9])
        assertEquals(0.3f, c[12])
        assertEquals(0f, c[16])
    }

    @Test
    fun jsonRoundTrip() {
        val json = EditJson.toJson(busy).toString()
        val back = EditJson.fromJson(JSONObject(json))
        assertEquals(busy, back)
    }

    @Test
    fun jsonOfNeutralIsEmpty() {
        assertEquals(0, EditJson.toJson(EditRecipe.Neutral).length())
        assertEquals(EditRecipe.Neutral, EditJson.fromJson(JSONObject("{}")))
    }

    @Test
    fun jsonIgnoresJunk() {
        val recipe = EditJson.fromJson(
            JSONObject("""{"exposure":"lots","contrast":0.3,"future":1,"crop":[0.9,0,0.1,1],"curves":{"rgb":[[0,0],"x",[1,1]]}}"""),
        )
        assertEquals(0f, recipe.exposure)
        assertEquals(0.3f, recipe.contrast)
        assertTrue(recipe.crop.isFull)
        assertEquals(2, recipe.curve(CurveChannel.Rgb).size)
    }

    @Test
    fun lookDropsGeometry() {
        val look = busy.look
        assertEquals(0, look.quarterTurns)
        assertFalse(look.flip)
        assertEquals(0f, look.straighten)
        assertTrue(look.crop.isFull)
        assertEquals(busy.contrast, look.contrast)

        val applied = EditRecipe(quarterTurns = 1, crop = CropRect(0f, 0f, 0.5f, 1f)).withLook(look)
        assertEquals(1, applied.quarterTurns)
        assertEquals(0.5f, applied.crop.right)
        assertEquals(busy.vibrance, applied.vibrance)
    }

    @Test
    fun strengthScalesAmountsButKeepsHues() {
        val half = busy.lookAtStrength(0.5f)
        assertEquals(0.25f, half.exposure, 1e-6f)
        assertEquals(busy.shadowHue, half.shadowHue)
        assertEquals(0.1f, half.shadowAmount, 1e-6f)
        assertEquals(busy.sharpenRadius, half.sharpenRadius)
        assertEquals(0.25f, half.mixer(MixerBand.Red).saturation, 1e-6f)
        // Curve bends halve: (0, 0.05) becomes (0, 0.025).
        assertEquals(0.025f, half.curve(CurveChannel.Rgb)[0].y, 1e-6f)
        assertTrue(busy.lookAtStrength(0f).isIdentity)
        assertTrue(EditRecipe(shadowHue = 10f, sharpenRadius = 2f).isIdentity)
        assertTrue(EditRecipe(contrast = -0f).isIdentity)
        assertEquals(busy.look, busy.lookAtStrength(1f))
    }

    @Test
    fun cropTurnsAndMirrors() {
        val crop = CropRect(0.1f, 0.2f, 0.6f, 0.9f)
        val turned = crop.turnedCounterclockwise()
        assertCrop(CropRect(0.2f, 0.4f, 0.9f, 0.9f), turned)
        // Four turns come back to the start.
        assertCrop(crop, turned.turnedCounterclockwise().turnedCounterclockwise().turnedCounterclockwise())
        assertCrop(CropRect(0.4f, 0.2f, 0.9f, 0.9f), crop.mirrored())
        assertCrop(crop, crop.mirrored().mirrored())
    }

    private fun assertCrop(expected: CropRect, actual: CropRect) {
        assertEquals(expected.left, actual.left, 1e-6f)
        assertEquals(expected.top, actual.top, 1e-6f)
        assertEquals(expected.right, actual.right, 1e-6f)
        assertEquals(expected.bottom, actual.bottom, 1e-6f)
    }

    @Test
    fun splineMatchesPointsAndStaysMonotone() {
        val spline = CurveSpline(listOf(CurvePoint(0f, 0f), CurvePoint(0.25f, 0.15f), CurvePoint(0.75f, 0.85f), CurvePoint(1f, 1f)))
        assertEquals(0.15f, spline(0.25f), 1e-5f)
        assertEquals(0.85f, spline(0.75f), 1e-5f)
        var last = -1f
        for (i in 0..100) {
            val y = spline(i / 100f)
            assertTrue(y >= last - 1e-6f)
            last = y
        }
        // Flat beyond the ends.
        val short = CurveSpline(listOf(CurvePoint(0.2f, 0.3f), CurvePoint(0.8f, 0.7f)))
        assertEquals(0.3f, short(0f), 1e-6f)
        assertEquals(0.7f, short(1f), 1e-6f)
    }

    @Test
    fun presetFileRoundTrip() {
        val preset = Preset("x", "Golden hour", busy)
        val text = Preset.toFile(preset)
        val back = Preset.fromFile(text, "y")
        assertNotNull(back)
        assertEquals("Golden hour", back!!.name)
        assertEquals(busy.look, back.recipe)
        assertNull(Preset.fromFile("""{"exposure":1}""", "z"))
        assertNull(Preset.fromFile("not json", "z"))
    }

    @Test
    fun builtInPresetsAreLooksOnly() {
        assertTrue(BuiltInPresets.Original.recipe.isIdentity)
        BuiltInPresets.all.forEach { preset ->
            assertTrue(preset.name, preset.recipe.geometryIsIdentity)
            assertEquals(preset.name, preset.recipe, EditJson.fromJson(EditJson.toJson(preset.recipe)))
        }
    }
}
