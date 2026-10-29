package com.brekwol.daybreak.edit

import com.brekwol.daybreak.engine.CropRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    private val full = CropRect.Full

    private fun assertInside(crop: CropRect) {
        assertTrue("left $crop", crop.left >= -1e-5f)
        assertTrue("top $crop", crop.top >= -1e-5f)
        assertTrue("right $crop", crop.right <= 1f + 1e-5f)
        assertTrue("bottom $crop", crop.bottom <= 1f + 1e-5f)
        assertTrue("width $crop", crop.width >= CropMath.MIN - 1e-5f)
        assertTrue("height $crop", crop.height >= CropMath.MIN - 1e-5f)
    }

    @Test
    fun freeCornerMovesTwoEdges() {
        val crop = CropMath.drag(full, CropHandle.TopLeft, 0.1f, 0.2f, ratio = null, aspect = 0.75f)
        assertEquals(0.1f, crop.left, 1e-6f)
        assertEquals(0.2f, crop.top, 1e-6f)
        assertEquals(1f, crop.right, 1e-6f)
        assertEquals(1f, crop.bottom, 1e-6f)
    }

    @Test
    fun freeEdgesStopAtTheFrameAndMinimum() {
        val pastEdge = CropMath.drag(full, CropHandle.Left, -0.5f, 0f, null, 1f)
        assertEquals(0f, pastEdge.left, 1e-6f)
        val crossed = CropMath.drag(full, CropHandle.Right, -2f, 0f, null, 1f)
        assertEquals(CropMath.MIN, crossed.width, 1e-6f)
    }

    @Test
    fun moveStaysInsideTheFrame() {
        val small = CropRect(0.2f, 0.2f, 0.6f, 0.5f)
        val moved = CropMath.drag(small, CropHandle.Move, 0.9f, -0.9f, null, 1f)
        assertEquals(0.6f, moved.left, 1e-6f)
        assertEquals(0f, moved.top, 1e-6f)
        assertEquals(small.width, moved.width, 1e-6f)
        assertEquals(small.height, moved.height, 1e-6f)
    }

    @Test
    fun lockedDragsKeepTheRatio() {
        val aspect = 3f / 4f // a tall photo
        val ratio = 4f / 5f
        val start = CropMath.fit(full, ratio, aspect)
        assertEquals(ratio, CropMath.ratioOf(start, aspect), 1e-4f)
        for (handle in CropHandle.entries) {
            for ((dx, dy) in listOf(0.1f to 0.1f, -0.3f to 0.2f, 0.5f to -0.5f, -2f to -2f, 2f to 2f)) {
                val crop = CropMath.drag(start, handle, dx, dy, ratio, aspect)
                assertInside(crop)
                assertEquals("$handle $dx $dy", ratio, CropMath.ratioOf(crop, aspect), 1e-3f)
            }
        }
    }

    @Test
    fun fitIsTheLargestCenteredCrop() {
        // Square on a 4:3 landscape frame: full height, 3/4 of the width.
        val square = CropMath.fit(full, 1f, 4f / 3f)
        assertEquals(0.75f, square.width, 1e-5f)
        assertEquals(1f, square.height, 1e-5f)
        assertEquals(0.125f, square.left, 1e-5f)
    }

    @Test
    fun shapesTurnWithThePhoto() {
        assertEquals(2f / 3f, CropMath.ratioFor(CropShape.ThreeTwo, aspect = 0.75f, turned = false)!!, 1e-5f)
        assertEquals(3f / 2f, CropMath.ratioFor(CropShape.ThreeTwo, aspect = 0.75f, turned = true)!!, 1e-5f)
        assertEquals(5f / 4f, CropMath.ratioFor(CropShape.FourFive, aspect = 1.5f, turned = false)!!, 1e-5f)
        assertEquals("2:3", CropMath.labelFor(CropShape.ThreeTwo, 0.75f, false))
        assertEquals("5:4", CropMath.labelFor(CropShape.FourFive, 1.5f, false))
        assertEquals("1:1", CropMath.labelFor(CropShape.Square, 1.5f, true))
        assertEquals(null, CropMath.ratioFor(CropShape.Free, 1f, false))
        assertEquals(0.75f, CropMath.ratioFor(CropShape.Original, 0.75f, false)!!, 0f)
    }
}
