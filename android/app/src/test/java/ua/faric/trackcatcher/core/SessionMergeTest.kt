package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMergeTest {
    private fun s(id: Long, a: Long, b: Long) = SessionMerge.Session(id, a, b)

    @Test
    fun earliestSessionKeepsAllAndSpansWholeTime() {
        val p = SessionMerge.plan(listOf(s(19, 300, 350), s(17, 100, 150), s(18, 200, 260), s(17, 100, 150)))!!
        assertEquals(17L, p.target)
        assertEquals(listOf(18L, 19L), p.others)
        assertEquals(100L, p.startedAt)
        assertEquals(350L, p.stoppedAt)
    }

    @Test
    fun oneSessionIsNothingToMerge() {
        assertNull(SessionMerge.plan(listOf(s(5, 1, 2), s(5, 1, 2))))
        assertNull(SessionMerge.plan(emptyList()))
    }
}
