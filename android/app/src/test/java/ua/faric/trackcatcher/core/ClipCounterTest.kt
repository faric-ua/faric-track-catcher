package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipCounterTest {
    @Test
    fun countsPlacesNotSamples() {
        val c = ClipCounter()
        // L: 3 семпли поспіль у стелі = 1 місце; R: два окремі = 2 місця.
        val s = shortArrayOf(32767, 32767, 32767, 0, 32767, 32767, 100, -32768, 0, 0)
        c.feed(s, s.size)
        assertEquals(3L, c.events)
    }

    @Test
    fun quietHasNone() {
        val c = ClipCounter()
        val s = ShortArray(9_600) { (it % 2000 - 1000).toShort() }
        c.feed(s, s.size)
        assertEquals(0L, c.events)
    }

    @Test
    fun seriesContinuesAcrossChunks() {
        val c = ClipCounter()
        c.feed(shortArrayOf(32767, 0), 2)
        c.feed(shortArrayOf(32767, 0), 2)
        assertEquals(1L, c.events)
    }
}
