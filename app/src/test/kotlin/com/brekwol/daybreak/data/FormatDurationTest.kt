package com.brekwol.daybreak.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatDurationTest {
    @Test
    fun readsLikeAClock() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("0:08", formatDuration(7_600))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
    }

    @Test
    fun onlyMp4IsVideo() {
        assertEquals(listOf(PhotoFormat.Mp4), PhotoFormat.entries.filter { it.isVideo })
        assertEquals(false, PhotoFormat.Mp4 in PhotoFormat.processed)
    }
}
