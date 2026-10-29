package com.brekwol.daybreak.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProSettingsTest {
    private val caps = CameraCapabilities(
        manualSensor = true,
        isoRange = 50..6400,
        shutterRangeNs = 100_000L..1_000_000_000L,
    )
    private val live = LiveExposure(iso = 50, shutterNs = 312_500L)

    @Test
    fun brightensATooDarkManualExposure() {
        // ISO 50 at 1/3200 s indoors, about 8 stops under.
        val dark = ProSettings(iso = 50, shutterNs = 312_500L)
        val fixed = dark.balanced(meterEv = -8f, caps = caps, live = live)
        val before = 50.0 * 312_500L
        val after = fixed.iso!!.toDouble() * fixed.shutterNs!!
        val stops = Math.log(after / before) / Math.log(2.0)
        assertEquals(8.0, stops, 0.5)
        assertTrue("shutter stays handheld", fixed.shutterNs!! <= ProSettings.HANDHELD_LIMIT_NS)
    }

    @Test
    fun keepsTheShutterTheUserChose() {
        val chosen = ProSettings(shutterNs = 8_000_000L) // 1/125 s, ISO automatic
        val fixed = chosen.balanced(meterEv = -2f, caps = caps, live = LiveExposure(iso = 400, shutterNs = 8_000_000L))
        assertEquals(8_000_000L, fixed.shutterNs)
        assertEquals(1600, fixed.iso)
    }

    @Test
    fun keepsTheIsoTheUserChose() {
        val chosen = ProSettings(iso = 100)
        val fixed = chosen.balanced(meterEv = 1f, caps = caps, live = LiveExposure(iso = 100, shutterNs = 10_000_000L))
        assertEquals(100, fixed.iso)
        assertEquals(5_000_000L, fixed.shutterNs) // one stop shorter: 1/200 s
    }

    @Test
    fun leavesAutoAndNearlyRightAlone() {
        val auto = ProSettings()
        assertSame(auto, auto.balanced(-5f, caps, live))
        val fine = ProSettings(iso = 100, shutterNs = 10_000_000L)
        assertSame(fine, fine.balanced(0.1f, caps, live))
        assertTrue(ProSettings().allAuto)
    }

    @Test
    fun focusModes() {
        assertEquals(FocusMode.Multi, ProSettings().focusMode)
        assertEquals(FocusMode.Center, ProSettings(focusCenter = true).focusMode)
        // A distance set by hand wins over center focus.
        assertEquals(FocusMode.Manual, ProSettings(focusDiopters = 2f, focusCenter = true).focusMode)
        assertTrue(ProSettings().allAuto)
        assertTrue(!ProSettings(focusCenter = true).allAuto)
    }

    @Test
    fun centerFocusNeedsFocusAreas() {
        assertTrue(!caps.centerFocus)
        assertTrue(caps.copy(afRegions = true, activeArrayWidth = 4000, activeArrayHeight = 3000).centerFocus)
    }

    @Test
    fun aChosenLowIsoIndoorsRaisesIsoNotTheShutter() {
        // ISO 50 indoors: the automatic shutter stops at 1/15 s and the photo is 3 stops dark.
        val chosen = ProSettings(iso = 50)
        val live = LiveExposure(iso = 50, shutterNs = ProSettings.HANDHELD_LIMIT_NS)
        val fixed = chosen.balanced(meterEv = -3f, caps = caps, live = live)
        assertTrue("shutter stays handheld", fixed.shutterNs!! <= ProSettings.HANDHELD_LIMIT_NS)
        assertEquals(400, fixed.iso)
    }
}
