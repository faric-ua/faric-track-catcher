package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeFadeTest {
    private fun pcm(frames: Int, value: Short): ByteArray {
        val shorts = ShortArray(frames * 2) { value }
        val bytes = ByteArray(frames * 4)
        Pcm.shortsToBytes(shorts, 0, shorts.size, bytes)
        return bytes
    }

    private fun sample(buf: ByteArray, frame: Int, ch: Int = 0): Int {
        val b = frame * 4 + ch * 2
        return ((buf[b + 1].toInt() shl 8) or (buf[b].toInt() and 0xff)).toShort().toInt()
    }

    @Test
    fun startsAndEndsAtZeroMiddleUntouched() {
        val total = 4_800 // 0,1 с
        val buf = pcm(total, 20_000)
        EdgeFade.apply(buf, buf.size, 0, total.toLong())
        assertEquals(0, sample(buf, 0))
        assertEquals(0, sample(buf, total - 1, 1))
        assertEquals(20_000, sample(buf, 2_400))
        assertTrue(sample(buf, 240) in 9_900..10_100) // середина входу — пів гучності
    }

    @Test
    fun worksAcrossChunks() {
        val total = 4_800L
        val whole = pcm(4_800, 12_000)
        EdgeFade.apply(whole, whole.size, 0, total)
        val a = pcm(1_000, 12_000)
        val b = pcm(3_800, 12_000)
        EdgeFade.apply(a, a.size, 0, total)
        EdgeFade.apply(b, b.size, 1_000, total)
        assertTrue((a + b).contentEquals(whole))
    }

    @Test
    fun tinyFileDoesNotCrash() {
        val buf = pcm(3, 1_000)
        EdgeFade.apply(buf, buf.size, 0, 3)
        assertEquals(0, sample(buf, 0))
    }
}
