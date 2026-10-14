package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GapDetectorTest {
    /** Порції по 100 мс; [jitter] — нерівномірність повернення read(); [lostAtMs] — коли Android викинув [lostMs]. */
    private fun run(seconds: Int, jitter: (Int) -> Long = { 0L }, lostAtMs: Long = -1, lostMs: Long = 0): GapDetector {
        val d = GapDetector()
        var t = 1_000_000L
        var lost = false
        for (i in 0 until seconds * 10) {
            t += 100
            val now = t + jitter(i)
            if (!lost && lostAtMs >= 0 && i * 100L >= lostAtMs) {
                lost = true
                // Звук за lostMs пропав: час минув, а кадрів не прийшло.
                t += lostMs
            }
            d.onRead(now, 4_800)
        }
        return d
    }

    @Test
    fun steadyStreamHasNoGaps() {
        assertEquals(0, run(120).gaps)
    }

    @Test
    fun jitterIsNotAGap() {
        assertEquals(0, run(120, jitter = { i -> if (i % 7 == 0) 80L else (i % 3).toLong() * 10 }).gaps)
    }

    @Test
    fun lostAudioIsCounted() {
        val d = run(60, lostAtMs = 20_000, lostMs = 300)
        assertEquals(1, d.gaps)
        assertEquals(true, d.lostMs in 250L..350L)
    }
}
