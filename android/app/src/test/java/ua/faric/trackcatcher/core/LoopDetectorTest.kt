package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopDetectorTest {
    @Test
    fun silentPlayerLoopsAreCountedByDuration() {
        val d = LoopDetector()
        val dur = 222_000L // пісня 3:42
        assertNull(d.check(200_000, dur))
        assertNull(d.check(223_000, dur))         // ще в межах 1,5 с запасу
        assertEquals(1_600L, d.check(223_600, dur)) // новий повтор
        assertNull(d.check(300_000, dur))          // всередині другого повтору
        assertEquals(1_500L, d.check(445_500, dur)) // третій
    }

    @Test
    fun resetAfterFreshPosition() {
        val d = LoopDetector()
        assertEquals(1_600L, d.check(223_600, 222_000))
        d.reset()
        assertNull(d.check(5_000, 222_000))
    }

    @Test
    fun shortOrUnknownDurationIgnored() {
        assertNull(LoopDetector().check(60_000, 0))
        assertNull(LoopDetector().check(60_000, 10_000))
    }

    @Test
    fun wrappedPosition() {
        assertTrue(LoopDetector.wrapped(221_000, 400))
        assertFalse(LoopDetector.wrapped(10_000, 400))   // перемотав назад на початку — не повтор
        assertFalse(LoopDetector.wrapped(221_000, 30_000))
        assertFalse(LoopDetector.wrapped(null, 0))
    }
}
