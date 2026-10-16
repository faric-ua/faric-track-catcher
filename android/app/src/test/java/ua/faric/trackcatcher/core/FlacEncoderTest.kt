package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T55: FLAC без втрат. Розкодовуємо власним простим декодером — має вийти рівно той самий PCM. */
class FlacEncoderTest {
    private val meta = TrackMeta("Океан Ельзи", "Обійми", "Земля", 240_000)

    @Test fun silenceRoundTrip() = roundTrip(ShortArray(2 * 10_000))

    @Test fun toneRoundTrip() = roundTrip(TestAudio.tone(TestAudio.ms(1_500), 440.0))

    @Test fun differentChannelsAndNoiseRoundTrip() = roundTrip(music(20_000, seed = 7))

    @Test fun extremesRoundTrip() {
        // Найгучніше: −32768 і 32767 поперемінно, L і R у протифазі — різниця потребує 17 біт.
        val n = 5_000
        val s = ShortArray(2 * n)
        for (i in 0 until n) {
            s[2 * i] = if (i % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE
            s[2 * i + 1] = if (i % 2 == 0) Short.MIN_VALUE else Short.MAX_VALUE
        }
        roundTrip(s)
    }

    @Test fun oddLengthsRoundTrip() {
        for (frames in listOf(1, 2, 5, 17, 255, 256, 257, 4095, 4096, 4097, 8193)) roundTrip(music(frames, seed = frames.toLong()))
    }

    @Test fun compressesMusicLikeSignal() {
        val pcm = toBytes(music(TestAudio.ms(5_000), seed = 3, noise = 300))
        val flac = encode(pcm, md5 = null)
        assertTrue("FLAC ${flac.size} має бути помітно менший за PCM ${pcm.size}", flac.size < pcm.size * 0.8)
    }

    @Test fun silenceIsTiny() {
        val pcm = ByteArray(Pcm.BYTES_PER_FRAME * TestAudio.ms(5_000))
        assertTrue(encode(pcm, md5 = null).size < 2_000)
    }

    @Test fun headerHasTagsAndStreamInfo() {
        val pcm = toBytes(TestAudio.tone(10_000))
        val md5 = MessageDigest.getInstance("MD5").digest(pcm)
        val flac = encode(pcm, md5)
        val d = MiniFlacDecoder(flac)
        assertEquals(10_000L, d.totalSamples)
        assertEquals(48_000, d.sampleRate)
        assertArrayEquals(md5, d.md5)
        assertEquals("Обійми", d.tags["TITLE"])
        assertEquals("Океан Ельзи", d.tags["ARTIST"])
        assertEquals("Земля", d.tags["ALBUM"])
    }

    @Test fun pictureBlockKeepsJpeg() {
        val jpeg = fakeJpeg(640, 480)
        val out = ByteArrayOutputStream()
        val enc = FlacEncoder(out, 0, null, FlacEncoder.tagsFor(meta), jpeg)
        enc.finish()
        val d = MiniFlacDecoder(out.toByteArray())
        assertArrayEquals(jpeg, d.picture)
        assertEquals(640 to 480, FlacEncoder.jpegSize(jpeg))
    }

    @Test fun utf8FrameNumbers() {
        assertArrayEquals(byteArrayOf(0x7F), FlacEncoder.utf8(0x7F))
        assertArrayEquals(byteArrayOf(0xC2.toByte(), 0x80.toByte()), FlacEncoder.utf8(0x80))
        assertArrayEquals(byteArrayOf(0xE1.toByte(), 0x80.toByte(), 0x80.toByte()), FlacEncoder.utf8(0x1000))
    }

    @Test fun crcKnownValues() {
        // CRC-8 (x^8+x^2+x+1) і CRC-16 (0x8005) від "123456789": 0xF4 і 0xFEE8.
        val b = "123456789".toByteArray()
        assertEquals(0xF4, FlacEncoder.crc8(b, b.size))
        assertEquals(0xFEE8, FlacEncoder.crc16(b, b.size))
    }

    /** Зразок для CI: ffmpeg розкодовує FLAC і порівнює з тим самим PCM (selftest-flac.raw). */
    @Test fun writesSelfTestFlac() {
        val dir = File("build/media-selftest").apply { mkdirs() }
        val pcm = toBytes(music(TestAudio.ms(3_000), seed = 11, noise = 400))
        File(dir, "selftest-flac.raw").writeBytes(pcm)
        File(dir, "selftest.flac").writeBytes(encode(pcm, MessageDigest.getInstance("MD5").digest(pcm), jpeg = fakeJpeg(32, 32)))
    }

    // ---------- допоміжне ----------

    private fun roundTrip(samples: ShortArray) {
        val pcm = toBytes(samples)
        val md5 = MessageDigest.getInstance("MD5").digest(pcm)
        val flac = encode(pcm, md5, chunk = 1_000) // шматками не кратними блоку — як з файлу
        val d = MiniFlacDecoder(flac)
        assertEquals(samples.size / 2L, d.totalSamples)
        assertArrayEquals(samples, d.decodeAll())
    }

    private fun encode(pcm: ByteArray, md5: ByteArray?, jpeg: ByteArray? = null, chunk: Int = 64 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = FlacEncoder(out, pcm.size / 4L, md5, FlacEncoder.tagsFor(meta), jpeg)
        var pos = 0
        while (pos < pcm.size) {
            val n = minOf(chunk - chunk % 4, pcm.size - pos)
            enc.encode(pcm, pos, n)
            pos += n
        }
        enc.finish()
        return out.toByteArray()
    }

    /** Схоже на музику: два тони, різні в L і R, + шум. */
    private fun music(frames: Int, seed: Long, noise: Int = 2_000): ShortArray {
        val rnd = java.util.Random(seed)
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = i / 48_000.0
            val a = 9_000 * Math.sin(2 * Math.PI * 220 * t) + 4_000 * Math.sin(2 * Math.PI * 1_760 * t)
            val b = 8_000 * Math.sin(2 * Math.PI * 330 * t + 0.5) + 3_000 * Math.sin(2 * Math.PI * 990 * t)
            out[2 * i] = (a + rnd.nextGaussian() * noise).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i + 1] = (b + rnd.nextGaussian() * noise).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    private fun toBytes(s: ShortArray): ByteArray {
        val b = ByteArray(s.size * 2)
        for (i in s.indices) {
            b[2 * i] = (s[i].toInt() and 0xff).toByte()
            b[2 * i + 1] = (s[i].toInt() shr 8).toByte()
        }
        return b
    }

    /** Мінімальний «JPEG»: SOI + SOF0 з розміром + EOI (для блоку PICTURE досить). */
    private fun fakeJpeg(w: Int, h: Int): ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(),
        0xFF.toByte(), 0xE0.toByte(), 0, 4, 0, 0,
        0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 1, 1, 0x11, 0,
        0xFF.toByte(), 0xD9.toByte(),
    )
}

/**
 * Простий декодер FLAC лише для тестів: STREAMINFO, теги, обкладинка; кадри — CONSTANT/VERBATIM/FIXED,
 * Райс 4/5 біт, усі 4 види стерео; перевіряє CRC-8 і CRC-16 кожного кадру.
 */
class MiniFlacDecoder(private val b: ByteArray) {
    var sampleRate = 0
    var totalSamples = 0L
    var md5 = ByteArray(16)
    val tags = HashMap<String, String>()
    var picture: ByteArray? = null
    private var framesStart = 0

    init {
        require(String(b, 0, 4, Charsets.US_ASCII) == "fLaC") { "немає fLaC" }
        var p = 4
        while (true) {
            val last = b[p].toInt() and 0x80 != 0
            val type = b[p].toInt() and 0x7F
            val len = ((b[p + 1].toInt() and 0xff) shl 16) or ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)
            val body = b.copyOfRange(p + 4, p + 4 + len)
            when (type) {
                0 -> {
                    val r = BitReader(body, 0)
                    r.read(16); r.read(16); r.read(24); r.read(24)
                    sampleRate = r.read(20).toInt()
                    require(r.read(3) == 1L) { "не стерео" }
                    require(r.read(5) == 15L) { "не 16 біт" }
                    totalSamples = r.read(36)
                    md5 = body.copyOfRange(18, 34)
                }
                4 -> {
                    var q = 0
                    fun le32(): Int { val v = (body[q].toInt() and 0xff) or ((body[q + 1].toInt() and 0xff) shl 8) or ((body[q + 2].toInt() and 0xff) shl 16) or ((body[q + 3].toInt() and 0xff) shl 24); q += 4; return v }
                    val vl = le32(); q += vl
                    repeat(le32()) {
                        val l = le32()
                        val s = String(body, q, l, Charsets.UTF_8); q += l
                        val eq = s.indexOf('=')
                        tags[s.substring(0, eq)] = s.substring(eq + 1)
                    }
                }
                6 -> {
                    val r = BitReader(body, 0)
                    r.read(32)
                    val ml = r.read(32).toInt(); repeat(ml) { r.read(8) }
                    val dl = r.read(32).toInt(); repeat(dl) { r.read(8) }
                    repeat(4) { r.read(32) }
                    val pl = r.read(32).toInt()
                    picture = body.copyOfRange(body.size - pl, body.size)
                }
            }
            p += 4 + len
            if (last) break
        }
        framesStart = p
    }

    fun decodeAll(): ShortArray {
        val out = ArrayList<Short>()
        val r = BitReader(b, framesStart)
        var expectedFrame = 0L
        while (r.bytePos() < b.size) {
            val start = r.bytePos()
            require(r.read(14) == 0x3FFEL) { "немає синхро" }
            r.read(1); require(r.read(1) == 0L)
            val sizeCode = r.read(4).toInt()
            r.read(4)
            val ch = r.read(4).toInt()
            require(r.read(3) == 0b100L); r.read(1)
            val first = r.read(8).toInt()
            var extra = 0
            var mask = 0x80
            while (first and mask != 0) { extra++; mask = mask shr 1 }
            var num = (first and (mask - 1)).toLong()
            repeat(if (extra == 0) 0 else extra - 1) { num = (num shl 6) or (r.read(8) and 0x3F) }
            require(num == expectedFrame) { "номер кадру $num ≠ $expectedFrame" }
            val n = when (sizeCode) {
                12 -> 4096
                6 -> r.read(8).toInt() + 1
                7 -> r.read(16).toInt() + 1
                else -> error("розмір $sizeCode")
            }
            val headerEnd = r.bytePos()
            require(r.read(8).toInt() == FlacEncoder.crc8(b.copyOfRange(start, headerEnd), headerEnd - start)) { "CRC-8" }
            val bpsA = if (ch == 9) 17 else 16
            val bpsB = if (ch == 8 || ch == 10) 17 else 16
            val a = subframe(r, n, bpsA)
            val c = subframe(r, n, bpsB)
            r.align()
            val end = r.bytePos()
            require(r.read(16).toInt() == FlacEncoder.crc16(b.copyOfRange(start, end), end - start)) { "CRC-16" }
            for (i in 0 until n) {
                val (l, rr) = when (ch) {
                    1 -> a[i] to c[i]
                    8 -> a[i] to a[i] - c[i]
                    9 -> c[i] + a[i] to c[i]
                    10 -> { val m = (a[i] shl 1) or (c[i] and 1); ((m + c[i]) shr 1) to ((m - c[i]) shr 1) }
                    else -> error("канали $ch")
                }
                out.add(l.toShort()); out.add(rr.toShort())
            }
            expectedFrame++
        }
        return out.toShortArray()
    }

    private fun subframe(r: BitReader, n: Int, bps: Int): IntArray {
        require(r.read(1) == 0L)
        val type = r.read(6).toInt()
        require(r.read(1) == 0L) { "wasted bits" }
        val x = IntArray(n)
        when {
            type == 0 -> { val v = r.signed(bps); x.fill(v) }
            type == 1 -> for (i in 0 until n) x[i] = r.signed(bps)
            type and 0b111000 == 0b001000 -> {
                val order = type and 7
                for (i in 0 until order) x[i] = r.signed(bps)
                val method = r.read(2).toInt()
                val pb = if (method == 0) 4 else 5
                val po = r.read(4).toInt()
                val size = n shr po
                var i = order
                for (part in 0 until (1 shl po)) {
                    val k = r.read(pb).toInt()
                    val end = (part + 1) * size
                    while (i < end) {
                        var q = 0
                        while (r.read(1) == 0L) q++
                        val u = (q.toLong() shl k) or (if (k > 0) r.read(k) else 0L)
                        val res = ((u ushr 1) xor -(u and 1)).toInt()
                        x[i] = res + when (order) {
                            0 -> 0
                            1 -> x[i - 1]
                            2 -> 2 * x[i - 1] - x[i - 2]
                            3 -> 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
                            else -> 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
                        }
                        i++
                    }
                }
            }
            else -> error("тип підкадру $type")
        }
        return x
    }
}

class BitReader(private val b: ByteArray, startByte: Int) {
    private var bit = startByte.toLong() * 8
    fun read(n: Int): Long {
        var v = 0L
        repeat(n) {
            val byte = b[(bit ushr 3).toInt()].toInt()
            v = (v shl 1) or ((byte shr (7 - (bit and 7).toInt())) and 1).toLong()
            bit++
        }
        return v
    }
    fun signed(n: Int): Int { val v = read(n); return ((v shl (64 - n)) shr (64 - n)).toInt() }
    fun align() { bit = (bit + 7) and 7L.inv() }
    fun bytePos(): Int = (bit ushr 3).toInt()
}
