package com.brekwol.daybreak.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrientationTrackerTest {
    private val g = 9.81f

    @Test
    fun readsTheFourWaysUp() {
        assertEquals(0, OrientationTracker.orientationDegrees(0f, g, 0f))
        assertEquals(90, OrientationTracker.orientationDegrees(-g, 0f, 0f)) // left side up
        assertEquals(180, OrientationTracker.orientationDegrees(0f, -g, 0f))
        assertEquals(270, OrientationTracker.orientationDegrees(g, 0f, 0f)) // right side up
    }

    @Test
    fun ignoresThePhonePointingAtTheSkyOrFloor() {
        // Pointing up at about 60°: most of gravity is along the screen's axis.
        assertNull(OrientationTracker.orientationDegrees(-1.5f, 4.0f, 8.5f))
        assertNull(OrientationTracker.orientationDegrees(0.5f, 1f, -9.7f))
    }

    @Test
    fun staysPutNearTheBoundary() {
        // 50° is past the 45° line but within the hysteresis band: still portrait.
        assertEquals(Surface.ROTATION_0, OrientationTracker.nextRotation(Surface.ROTATION_0, 50))
        assertEquals(Surface.ROTATION_0, OrientationTracker.nextRotation(Surface.ROTATION_0, 300))
    }

    @Test
    fun turnsOnceClearlyRotated() {
        assertEquals(Surface.ROTATION_270, OrientationTracker.nextRotation(Surface.ROTATION_0, 75))
        assertEquals(Surface.ROTATION_90, OrientationTracker.nextRotation(Surface.ROTATION_0, 285))
        assertEquals(Surface.ROTATION_180, OrientationTracker.nextRotation(Surface.ROTATION_270, 170))
        // And back to portrait from landscape.
        assertEquals(Surface.ROTATION_0, OrientationTracker.nextRotation(Surface.ROTATION_270, 20))
        assertEquals(Surface.ROTATION_270, OrientationTracker.nextRotation(Surface.ROTATION_270, 40))
    }
}
