package ua.faric.trackcatcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class FileTagsTest {
    /** Найменший JPEG-подібний заголовок з SOF0 300×200 — для розміру картинки. */
    private val jpeg = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08, 0x00, 0xC8.toByte(), 0x01, 0x2C,
        0x03, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01, 0xFF.toByte(), 0xD9.toByte(),
    )
    private val meta = TrackMeta("Saney", "🎵 TRACK 10 — DAWN OF THE RITUAL.", "Ритуал", 326_000, trackNo = 10, year = 2026)

    private fun read(b: ByteArray, name: String = "") = FileTags.read(ByteArrayInputStream(b), b.size.toLong(), name)

    @Test fun flacFromOurEncoder() {
        val frames = 48_000L * 2
        val pcm = ByteArray((frames * 4).toInt()) { (it * 31 % 199).toByte() }
        val out = ByteArrayOutputStream()
        val tags = FlacEncoder.tagsFor(meta) + Loudness.Result(-12.0, 0.9).tags()
        FlacEncoder(out, frames, MessageDigest.getInstance("MD5").digest(pcm), tags, jpeg).apply { encode(pcm, 0, pcm.size); finish() }
        val i = read(out.toByteArray())
        assertEquals("FLAC", i.format)
        assertEquals(48_000, i.sampleRate); assertEquals(2, i.channels); assertEquals(16, i.bits)
        assertEquals(2_000L, i.durationMs)
        assertTrue(i.hasMd5)
        assertEquals("🎵 TRACK 10 — DAWN OF THE RITUAL.", i[FileTags.Field.TITLE])
        assertEquals("Saney", i[FileTags.Field.ARTIST]); assertEquals("Ритуал", i[FileTags.Field.ALBUM])
        assertEquals("Saney", i[FileTags.Field.ALBUM_ARTIST])
        assertEquals("10", i[FileTags.Field.TRACK]); assertEquals("2026", i[FileTags.Field.YEAR])
        assertEquals("-6.00 dB", i.replayGain)
        val p = i.picture!!
        assertEquals(3, p.type); assertEquals("image/jpeg", p.mime); assertEquals(300, p.width); assertEquals(200, p.height)
        assertArrayEquals(jpeg, p.bytes)
    }

    @Test fun mp3TagAndFrame() {
        val tag = Id3v2.build(meta, 326_000, jpeg, listOf("REPLAYGAIN_TRACK_GAIN" to "-3.10 dB"))
        // Кадр MPEG-1 Layer III, 320 кбіт/с, 48 кГц, стерео: FF FB E4 00.
        val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE4.toByte(), 0x00) + ByteArray(960)
        val i = read(tag + frame)
        assertEquals("MP3", i.format)
        assertEquals(48_000, i.sampleRate); assertEquals(2, i.channels); assertEquals(320, i.bitrateKbps)
        assertEquals(326_000L, i.durationMs) // з TLEN
        assertEquals("🎵 TRACK 10 — DAWN OF THE RITUAL.", i[FileTags.Field.TITLE])
        assertEquals("Saney", i[FileTags.Field.ALBUM_ARTIST])
        assertEquals("10", i[FileTags.Field.TRACK]); assertEquals("2026", i[FileTags.Field.YEAR])
        assertEquals("Track Catcher", i[FileTags.Field.ENCODER])
        assertEquals("-3.10 dB", i.replayGain)
        assertArrayEquals(jpeg, i.picture!!.bytes)
        assertEquals(300, i.picture!!.width)
    }

    @Test fun wavInfoAndDataPosition() {
        val data = ByteArray(48_000 * 4) { 1 }
        val head = WavHeader.build(data.size.toLong(), info = WavHeader.infoFor(meta))
        val i = read(head + data)
        assertEquals("WAV", i.format)
        assertEquals(48_000, i.sampleRate); assertEquals(2, i.channels); assertEquals(16, i.bits)
        assertEquals(1_000L, i.durationMs)
        assertEquals(head.size.toLong(), i.dataOffset); assertEquals(data.size.toLong(), i.dataBytes)
        assertEquals("Ритуал", i[FileTags.Field.ALBUM]); assertEquals("10", i[FileTags.Field.TRACK])
        assertNull(i.picture)
    }

    @Test(expected = FileTags.NotAudio::class)
    fun unknownFormat() {
        read("hello world, not audio".toByteArray(), "x.txt")
    }

    @Test
    fun encoderCarriesVersion() {
        assertEquals("Track Catcher 0.1.49 (build 50)", AppInfo.encoderFor("0.1.49", 50))
        assertEquals("Track Catcher", AppInfo.encoderFor("", 0))
        AppInfo.versionName = "0.1.49"; AppInfo.versionCode = 50
        try {
            val frame = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE4.toByte(), 0x00) + ByteArray(960)
            val i = read(Id3v2.build(TrackMeta("A", "T", "Al"), 1000, null) + frame)
            assertEquals("Track Catcher 0.1.49 (build 50)", i[FileTags.Field.ENCODER])
        } finally {
            AppInfo.versionName = ""; AppInfo.versionCode = 0
        }
    }
}
