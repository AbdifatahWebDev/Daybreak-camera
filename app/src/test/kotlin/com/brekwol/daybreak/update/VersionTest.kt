package com.brekwol.daybreak.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    private fun v(text: String): Version = requireNotNull(Version.parse(text)) { "could not parse $text" }

    @Test
    fun parsesReleaseAndPreRelease() {
        assertEquals(Version(0, 1, 0), v("0.1.0"))
        assertEquals(Version(0, 2, 0, listOf("rc", "1")), v("v0.2.0-rc.1"))
        assertEquals("1.10.3-beta.2", v("1.10.3-beta.2").toString())
        assertTrue(v("0.2.0-rc.1").isPreRelease)
    }

    @Test
    fun rejectsOtherText() {
        assertNull(Version.parse("latest"))
        assertNull(Version.parse("1.2"))
        assertNull(Version.parse("1.2.3.4"))
        assertNull(Version.parse(""))
    }

    @Test
    fun ordersLikeSemanticVersioning() {
        val ordered = listOf(
            "0.1.0-rc.2", "0.1.0-rc.3", "0.1.0-rc.10", "0.1.0", "0.1.1",
            "0.2.0-alpha", "0.2.0-alpha.1", "0.2.0-beta", "0.2.0-rc.1", "0.2.0", "0.10.0", "1.0.0",
        ).map(::v)
        assertEquals(ordered, ordered.shuffled(kotlin.random.Random(7)).sorted())
    }

    @Test
    fun equalVersionsCompareEqual() {
        assertEquals(0, v("0.1.0").compareTo(v("v0.1.0")))
        assertEquals(0, v("0.1.0-rc.1").compareTo(v("0.1.0-rc.1")))
    }
}
