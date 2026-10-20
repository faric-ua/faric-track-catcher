package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T88: «тріски» — дірки чистої тиші посеред музики (VOL. 7 TRACK 04 (1), 1:55 — 27 дірок, faric 20.10 14:31 «правильно знайшов»). */
class HolesTest {
    private val sr = 48_000

    /** Стерео 16 біт LE: [ms] мілісекунд «музики» (синус) з дірками нулів у [holes] (початок мс → довжина мс). */
    private fun pcm(ms: Int, holes: Map<Int, Int> = emptyMap(), loud: Boolean = true): ByteArray {
        val frames = ms * sr / 1000
        val b = ByteArray(frames * 4)
        for (f in 0 until frames) {
            val t = f * 1000 / sr
            val inHole = holes.any { (s, l) -> t >= s && t < s + l }
            val v = if (inHole || !loud) 0 else (Math.sin(f * 2 * Math.PI * 220 / sr) * 8000).toInt().let { if (it == 0) 1 else it }
            b[f * 4] = (v and 0xff).toByte(); b[f * 4 + 1] = (v shr 8).toByte()
            b[f * 4 + 2] = (v and 0xff).toByte(); b[f * 4 + 3] = (v shr 8).toByte()
        }
        return b
    }

    @Test fun findsHolesInMusic() {
        val data = pcm(5_000, mapOf(1_000 to 10, 1_200 to 20, 3_600 to 5))
        val h = Holes(sr).apply { feed(data, 0, data.size) }.result()
        assertEquals(listOf(1_000L, 1_200L, 3_600L), h.map { it.startMs })
        val p = Holes.places(h)
        assertEquals(2, p.size)
        assertEquals(2, p[0].count)
        assertEquals("0:01 (2 дірки, 0,03 с) · 0:03", Holes.describe(p))
        assertEquals(p, Holes.decode(Holes.encode(p)))
    }

    @Test fun silenceAtStartOrEndIsNotAHole() {
        val silent = pcm(500, loud = false)
        val music = pcm(1_000)
        val all = silent + music + silent
        assertTrue(Holes(sr).apply { feed(all, 0, all.size) }.result().isEmpty())
    }

    @Test fun shortZeroCrossingsAreIgnored() {
        // Менше 1 мс нулів (переходи через нуль, тиха нота) — не дірка.
        val data = pcm(2_000, mapOf(1_000 to 0))
        assertTrue(Holes(sr).apply { feed(data, 0, data.size) }.result().isEmpty())
    }

    @Test fun feedInPiecesGivesTheSame() {
        val data = pcm(3_000, mapOf(1_500 to 15))
        val h = Holes(sr)
        var i = 0
        while (i < data.size) { val n = minOf(4_000, data.size - i); h.feed(data, i, n); i += n }
        assertEquals(listOf(1_500L), h.result().map { it.startMs })
    }
}
