package ua.faric.trackcatcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** T79: обрізати / затухання в записаному файлі (faric 19.10 20:02). */
class TrimFadeTest {
    @Test fun rangeCutsStartAndEnd() {
        val (first, count) = TrimFade.range(48_000L * 10, TrimFade.Plan(cutStartMs = 3_000, cutEndMs = 2_000), 48_000)
        assertEquals(48_000L * 3, first)
        assertEquals(48_000L * 5, count)
    }

    @Test(expected = TrimFade.TooShort::class) fun cannotCutEverything() {
        TrimFade.range(48_000L * 4, TrimFade.Plan(cutStartMs = 3_500), 48_000)
    }

    @Test fun fadesInAndOutSeparately() {
        val frames = 100
        val buf = ByteArray(frames * 4)
        for (i in 0 until frames * 2) { buf[i * 2] = 0x10; buf[i * 2 + 1] = 0x27 } // 10000
        TrimFade.shape(buf, buf.size, 0, frames.toLong(), fadeInFrames = 10, fadeOutFrames = 0, channels = 2)
        fun s(f: Int) = ((buf[f * 4 + 1].toInt() shl 8) or (buf[f * 4].toInt() and 0xff)).toShort().toInt()
        assertEquals(0, s(0))
        assertEquals(5000, s(5))
        assertEquals(10000, s(10))
        assertEquals(10000, s(99)) // кінця не чіпали
    }

    @Test fun planSurvivesTheTrip() {
        val p = TrimFade.Plan(4_500, 0, 500, 1_000, TrimFade.Save.NEW_FILE)
        assertEquals(p, TrimFade.Plan.fromMap(p.toMap()))
    }

    @Test fun flacKeepsOldTagsAndCover() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val meta = TrackMeta("Saney", "BONUS 03", "VOL. 3", trackNo = 13, year = 2026)
        fun flac(frames: Long, tags: List<Pair<String, String>>, pic: ByteArray?): ByteArray {
            val pcm = ByteArray((frames * 4).toInt()) { (it * 7 % 251).toByte() }
            val out = ByteArrayOutputStream()
            FlacEncoder(out, frames, MessageDigest.getInstance("MD5").digest(pcm), tags, pic).apply { encode(pcm, 0, pcm.size); finish() }
            return out.toByteArray()
        }
        val old = flac(9_600, FlacEncoder.tagsFor(meta), jpeg)
        val fresh = flac(4_800, emptyList(), null)
        val oldBlocks = TrimFade.flacBlocks(ByteArrayInputStream(old))
        val out = ByteArrayOutputStream()
        TrimFade.flacWithOldMeta(ByteArrayInputStream(fresh), oldBlocks, out)
        val t = FileTags.read(ByteArrayInputStream(out.toByteArray()), out.size().toLong())
        assertEquals("BONUS 03", t[FileTags.Field.TITLE])
        assertEquals("13", t[FileTags.Field.TRACK])
        assertArrayEquals(jpeg, t.picture!!.bytes)
        assertEquals(100L, t.durationMs) // 4800 кадрів = 0,1 с — новий STREAMINFO
        assertEquals(4_800 * 2, MiniFlacDecoder(out.toByteArray()).decodeAll().size)
    }

    @Test fun mp3TagIsCarriedOver() {
        val tag = Id3v2.build(TrackMeta("Saney", "X"), 1000, null)
        val file = tag + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0)
        assertArrayEquals(tag, TrimFade.id3Head(ByteArrayInputStream(file)))
        assertEquals(0, TrimFade.id3Head(ByteArrayInputStream(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0))).size)
    }
}
