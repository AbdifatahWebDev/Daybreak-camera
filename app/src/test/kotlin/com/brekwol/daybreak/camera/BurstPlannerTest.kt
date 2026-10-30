package com.brekwol.daybreak.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log2

class BurstPlannerTest {
    private val manual = CameraCapabilities(
        manualSensor = true,
        isoRange = 50..6400,
        shutterRangeNs = 100_000L..1_000_000_000L,
        evRange = -12..12,
        evStep = 1f / 6f,
    )

    @Test
    fun hdrFramesAreDarkerAndTheFinishBringsThemBack() {
        // Daylight: ISO 100 at 1/500 s.
        val plan = BurstPlanner.plan(BurstKind.Hdr, manual, LiveExposure(iso = 100, shutterNs = 2_000_000L), 0f)
        val before = 100.0 * 2_000_000L
        val after = plan.iso!!.toDouble() * plan.shutterNs!!
        assertEquals(-BurstPlanner.HDR_UNDER.toDouble(), log2(after / before), 0.1)
        assertEquals(BurstPlanner.HDR_FRAMES, plan.frames)
        // The normal virtual exposure is back at auto exposure's brightness.
        assertEquals(before / after, plan.finish.gain.toDouble(), 0.05)
        // The short virtual exposure is the frames as taken.
        assertEquals(1.0, plan.finish.gain.toDouble() / Math.pow(2.0, plan.finish.under.toDouble()), 0.01)
    }

    @Test
    fun hdrInBrightLightShortensTheShutterInsteadOfGoingBelowBaseIso() {
        val plan = BurstPlanner.plan(BurstKind.Hdr, manual, LiveExposure(iso = 50, shutterNs = 1_000_000L), 0f)
        assertEquals(50, plan.iso)
        assertTrue(plan.shutterNs!! < 1_000_000L)
    }

    @Test
    fun nightKeepsEachFrameSharpAndIsoModerate() {
        // A dim room: auto exposure chose ISO 3200 at 1/15 s.
        val plan = BurstPlanner.plan(BurstKind.Night, manual, LiveExposure(iso = 3200, shutterNs = 66_666_667L), 0f)
        assertTrue(plan.shutterNs!! <= BurstPlanner.NIGHT_SHUTTER_NS)
        assertTrue(plan.iso!! <= BurstPlanner.NIGHT_MAX_ISO)
        // Brightness the frames lack comes back in the finish.
        val wanted = 3200.0 * 66_666_667L
        val frames = plan.iso!!.toDouble() * plan.shutterNs!!
        assertEquals(wanted / frames, plan.finish.gain.toDouble(), 0.05)
        assertTrue(plan.frames >= BurstPlanner.NIGHT_FRAMES)
    }

    @Test
    fun nightBrightensWhenAutoExposureRanOutOfRange() {
        val auto = LiveExposure(iso = 6400, shutterNs = 66_666_667L)
        val normal = BurstPlanner.plan(BurstKind.Night, manual, auto, 0f)
        val dark = BurstPlanner.plan(BurstKind.Night, manual, auto, -3f)
        assertTrue(dark.finish.gain > normal.finish.gain)
        assertEquals(BurstPlanner.NIGHT_DARK_FRAMES, dark.frames)
        assertTrue(dark.finish.gain <= BurstPlanner.NIGHT_MAX_GAIN)
    }

    @Test
    fun withoutManualExposureHdrUsesCompensation() {
        val auto = manual.copy(manualSensor = false)
        val plan = BurstPlanner.plan(BurstKind.Hdr, auto, LiveExposure(iso = 100, shutterNs = 2_000_000L), 0f)
        assertNull(plan.iso)
        assertNotNull(plan.evIndex)
        assertEquals(-9, plan.evIndex) // -1.5 stops in 1/6 steps
        assertEquals(Math.pow(2.0, 1.5), plan.finish.gain.toDouble(), 0.01)
    }

    @Test
    fun beforeAnyFrameItStillPlans() {
        val plan = BurstPlanner.plan(BurstKind.Night, manual, null, null)
        assertNull(plan.iso)
        assertEquals(BurstPlanner.NIGHT_FRAMES, plan.frames)
        assertEquals(1f, plan.finish.gain)
    }
}
