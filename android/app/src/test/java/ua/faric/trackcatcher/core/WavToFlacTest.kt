package ua.faric.trackcatcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.sin

class WavToFlacTest {
    @Test fun sameSoundTagsAndCover() {
        val frames = 48_000 * 3 + 123
        val pcm = ByteArray(frames * 4)
        for (f in 0 until frames) for (c in 0 until 2) {
            val v = (9000 * sin(f * (0.03 + c * 0.01)) + (f * 7919 % 301) - 150).toInt()
            pcm[f * 4 + c * 2] = v.toByte(); pcm[f * 4 + c * 2 + 1] = (v shr 8).toByte()
        }
        val meta = TrackMeta("Saney", "TRACK 04 — NEON", "Ритуал", trackNo = 4, year = 2026)
        val wav = WavHeader.build(pcm.size.toLong(), info = WavHeader.infoFor(meta)) + pcm
        val info = FileTags.read(ByteArrayInputStream(wav), wav.size.toLong())
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val out = ByteArrayOutputStream()
        WavToFlac.encode(info, ByteArrayInputStream(wav), ByteArrayInputStream(wav), FlacEncoder.tagsFor(meta), jpeg, out)
        val flac = out.toByteArray()

        val dec = MiniFlacDecoder(flac)
        val samples = dec.decodeAll()
        val back = ByteArray(samples.size * 2)
        for (i in samples.indices) { back[i * 2] = samples[i].toInt().toByte(); back[i * 2 + 1] = (samples[i].toInt() shr 8).toByte() }
        assertArrayEquals(pcm, back)

        val t = FileTags.read(ByteArrayInputStream(flac), flac.size.toLong())
        assertEquals("4", t[FileTags.Field.TRACK]); assertEquals("Ритуал", t[FileTags.Field.ALBUM])
        assertNotNull(t.picture)
        assertArrayEquals(jpeg, t.picture!!.bytes)
    }

    @Test fun monoIsRefusedWithReason() {
        val wav = WavHeader.build(400, channels = 1) + ByteArray(400)
        val info = FileTags.read(ByteArrayInputStream(wav), wav.size.toLong())
        assertEquals("WAV моно — вмію лише стерео", WavToFlac.problem(info))
    }
}
