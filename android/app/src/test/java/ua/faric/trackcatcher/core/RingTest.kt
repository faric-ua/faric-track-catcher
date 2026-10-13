package ua.faric.trackcatcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RingTest {
    @Test
    fun keepsLastFramesWithAbsoluteIndex() {
        val r = ShortRing(capacityFrames = 4, channels = 2)
        r.append(shortArrayOf(1, 1, 2, 2, 3, 3), 0, 6)
        r.append(shortArrayOf(4, 4, 5, 5, 6, 6), 0, 6)
        assertEquals(6L, r.endFrame)
        assertEquals(2L, r.startFrame)
        assertArrayEquals(shortArrayOf(3, 3, 4, 4, 5, 5, 6, 6), r.copy(2, 6))
        assertArrayEquals(shortArrayOf(5, 5), r.copy(4, 5))
    }

    @Test
    fun hugeAppendKeepsTail() {
        val r = ShortRing(capacityFrames = 2, channels = 2)
        r.append(shortArrayOf(1, 1, 2, 2, 3, 3), 0, 6)
        assertEquals(3L, r.endFrame)
        assertArrayEquals(shortArrayOf(2, 2, 3, 3), r.copy(1, 3))
    }
}
