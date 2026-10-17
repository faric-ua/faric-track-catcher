package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

class LoudnessTest {
    /** Стерео синус [hz] з піком [dbfs] (обидва канали), [sec] секунд. */
    private fun tone(dbfs: Double, sec: Double, hz: Double = 1000.0): ByteArray {
        val frames = (sec * Pcm.SAMPLE_RATE).toInt()
        val amp = 32767.0 * 10.0.pow(dbfs / 20.0)
        val out = ByteArray(frames * Pcm.BYTES_PER_FRAME)
        for (f in 0 until frames) {
            val s = (amp * sin(2 * PI * hz * f / Pcm.SAMPLE_RATE)).roundToInt()
            for (c in 0 until 2) {
                out[f * 4 + c * 2] = s.toByte()
                out[f * 4 + c * 2 + 1] = (s shr 8).toByte()
            }
        }
        return out
    }

    private fun measure(vararg parts: ByteArray): Loudness.Result? =
        Loudness().apply { parts.forEach { p -> var i = 0; while (i < p.size) { val n = minOf(9000, p.size - i); feed(p.copyOfRange(i, i + n), n); i += n } } }.result()

    @Test fun ebuReferenceToneIsMinus23() {
        // EBU Tech 3341, випадок 1: стерео 1 кГц −23 dBFS → −23,0 LUFS (±0,1).
        val r = measure(tone(-23.0, 20.0))!!
        assertEquals(-23.0, r.lufs, 0.1)
        assertEquals(5.0, r.gainDb, 0.1)
        assertEquals(10.0.pow(-23.0 / 20), r.peak, 0.001)
    }

    @Test fun silenceIsGatedOut() {
        val r = measure(tone(-23.0, 10.0), ByteArray(10 * Pcm.SAMPLE_RATE * Pcm.BYTES_PER_FRAME))!!
        assertEquals(-23.0, r.lufs, 0.1)
    }

    @Test fun quietPartBelowRelativeGateIsIgnored() {
        // EBU Tech 3341, випадок 3-подібно: −20 і −40 — тихе на 20 LU нижче, відкидається відносним порогом.
        val r = measure(tone(-20.0, 10.0), tone(-40.0, 10.0))!!
        assertEquals(-20.0, r.lufs, 0.15)
    }

    @Test fun silenceOrTooShortGivesNoTag() {
        assertNull(measure(ByteArray(5 * Pcm.SAMPLE_RATE * Pcm.BYTES_PER_FRAME)))
        assertNull(measure(tone(-10.0, 0.3)))
    }

    @Test fun tagsUseDotAndUnits() {
        val t = Loudness.Result(-9.5, 0.98765432).tags().toMap()
        assertEquals("-8.50 dB", t["REPLAYGAIN_TRACK_GAIN"])
        assertEquals("0.987654", t["REPLAYGAIN_TRACK_PEAK"])
        assertEquals("-18.0 LUFS", t["REPLAYGAIN_REFERENCE_LOUDNESS"])
    }

    @Test fun id3UserFrameIsLatinTxxx() {
        val f = Id3v2.userFrame("REPLAYGAIN_TRACK_GAIN", "-8.50 dB")
        assertEquals("TXXX", String(f, 0, 4, Charsets.US_ASCII))
        val body = "\u0000REPLAYGAIN_TRACK_GAIN\u0000-8.50 dB"
        assertEquals(body.length, (f[4].toInt() shl 24) or (f[5].toInt() shl 16) or (f[6].toInt() shl 8) or f[7].toInt())
        assertEquals(body, String(f, 10, f.size - 10, Charsets.ISO_8859_1))
    }
}
