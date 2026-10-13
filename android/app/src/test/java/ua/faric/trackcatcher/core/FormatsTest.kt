package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.faric.trackcatcher.encode.Mp3Encoder

class FormatsTest {
    private val meta = TrackMeta("Океан Ельзи", "Обійми", "Земля", 240_000)

    @Test
    fun sanitizesFileNames() {
        assertEquals("AC_DC — Back In Black", FileNames.sanitize("AC/DC — Back In Black"))
        assertEquals("Що_ Де_ Коли_", FileNames.sanitize("Що? Де: Коли*"))
        assertEquals(FileNames.FALLBACK, FileNames.sanitize("  ..  "))
        assertEquals(120, FileNames.sanitize("я".repeat(300)).length)
        assertEquals("Океан Ельзи — Обійми", FileNames.baseName(meta))
    }

    @Test
    fun uniqueAddsNumber() {
        val taken = setOf("A — B.mp3", "A — B (2).mp3")
        assertEquals("A — B (3).mp3", FileNames.unique("A — B", "mp3") { it in taken })
        assertEquals("A — B.wav", FileNames.unique("A — B", "wav") { it in taken })
    }

    @Test
    fun wavHeaderIsConsistent() {
        val dataBytes = 48_000L * 4 * 3
        val h = WavHeader.build(dataBytes, info = WavHeader.infoFor(meta))
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals(h.size - 8 + dataBytes, bb.getInt(4).toLong())
        assertEquals(1, bb.getShort(20).toInt())        // PCM
        assertEquals(2, bb.getShort(22).toInt())        // стерео
        assertEquals(48_000, bb.getInt(24))
        assertEquals(192_000, bb.getInt(28))
        assertEquals("data", String(h, h.size - 8, 4, Charsets.US_ASCII))
        assertEquals(dataBytes, bb.getInt(h.size - 4).toLong())
        assertTrue(String(h, Charsets.UTF_8).contains("Обійми"))
    }

    @Test
    fun id3HasCyrillicTitleAndCorrectSize() {
        val art = ByteArray(1000) { 7 }
        val tag = Id3v2.build(meta, 238_000, art)
        assertEquals("ID3", String(tag, 0, 3, Charsets.US_ASCII))
        assertEquals(3, tag[3].toInt())
        val size = (tag[6].toInt() shl 21) or (tag[7].toInt() shl 14) or (tag[8].toInt() shl 7) or tag[9].toInt()
        assertEquals(tag.size - 10, size)
        val title = Id3v2.textFrame("TIT2", "Обійми")
        assertTrue(indexOf(tag, title) > 0)
        assertTrue(indexOf(tag, "APIC".toByteArray()) > 0)
    }

    @Test
    fun syncsafeEncodesSevenBitBytes() {
        assertArrayEquals(byteArrayOf(0, 0, 2, 1), Id3v2.syncsafe(257))
    }

    @Test
    fun mp3EncoderProducesFrames() {
        val pcm = toBytes(TestAudio.tone(TestAudio.ms(2_000)))
        val out = ByteArrayOutputStream()
        Mp3Encoder(bitrateKbps = 192).use { enc ->
            assertEquals(192, enc.effectiveBitrate)
            enc.encode(pcm, 0, pcm.size, out)
            enc.finish(out)
        }
        val mp3 = out.toByteArray()
        // 2 с × 192 кбіт/с ≈ 48 КБ
        assertTrue("розмір ${mp3.size}", mp3.size in 40_000..56_000)
        // синхрослово першого кадру MPEG-1 Layer III
        assertEquals(0xFF, mp3[0].toInt() and 0xFF)
        assertEquals(0xE0, mp3[1].toInt() and 0xE0)
    }

    /** Пише зразки у build/media-selftest — CI перевіряє їх через ffprobe. */
    @Test
    fun writesSelfTestMedia() {
        val dir = File("build/media-selftest").apply { mkdirs() }
        val pcm = toBytes(TestAudio.tone(TestAudio.ms(3_000), 440.0))
        File(dir, "selftest.wav").outputStream().use {
            it.write(WavHeader.build(pcm.size.toLong(), info = WavHeader.infoFor(meta)))
            it.write(pcm)
        }
        File(dir, "selftest.mp3").outputStream().buffered().use { os ->
            os.write(Id3v2.build(meta, 3_000, null))
            Mp3Encoder(bitrateKbps = 320).use { enc ->
                enc.encode(pcm, 0, pcm.size, os)
                enc.finish(os)
            }
        }
        assertTrue(File(dir, "selftest.mp3").length() > 100_000)
    }

    @Test
    fun formatsTimeAndSizeInUkrainian() {
        assertEquals("3:38", TimeText.clock(218_000))
        assertEquals("1:02:03", TimeText.clock(3_723_000))
        assertEquals("9,2 МБ", TimeText.size(9_600_960))
        assertEquals("1 пісня", TimeText.plural(1, "пісня", "пісні", "пісень"))
        assertEquals("3 пісні", TimeText.plural(3, "пісня", "пісні", "пісень"))
        assertEquals("12 пісень", TimeText.plural(12, "пісня", "пісні", "пісень"))
        assertEquals("21 пісня", TimeText.plural(21, "пісня", "пісні", "пісень"))
    }

    private fun toBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        Pcm.shortsToBytes(s, 0, s.size, b)
        return b
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
