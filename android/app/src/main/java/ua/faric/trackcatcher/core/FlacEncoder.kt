package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * FLAC — звук без втрат, файл ~ удвічі менший за WAV (T55, faric 16.10: «щоб писав без втрат; щоб додаток не ріс
 * і систему не навантажував»). Свій кодувальник на чистому Kotlin, без бібліотек і без кодеків телефона:
 * однаково на всіх телефонах і перевіряється тестами.
 *
 * Як стискає: блоки по 4096 кадрів; для кожного каналу передбачення «фіксованим» многочленом (порядок 0–4,
 * як у `flac -0…-2`) і код Райса для залишку; стерео — найкраще з L/R, L/різниця, різниця/R, середнє/різниця.
 * LPC (як `flac -5`) не робимо: виграш ~2–4 % місця, а роботи процесору в рази більше.
 *
 * Формат — https://xiph.org/flac/format.html (RFC 9639). Вхід — стерео PCM 16 біт little-endian, як у WAV.
 *
 * @param totalFrames скільки кадрів буде (для STREAMINFO — тривалість одразу видно в плеєрі).
 * @param md5 MD5 усього PCM (16 байт) або null — «невідомо» (дозволено форматом).
 */
class FlacEncoder(
    private val out: OutputStream,
    totalFrames: Long,
    md5: ByteArray?,
    tags: List<Pair<String, String>>,
    pictureJpeg: ByteArray? = null,
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
) {
    private val left = IntArray(BLOCK)
    private val right = IntArray(BLOCK)
    private val mid = IntArray(BLOCK)
    private val side = IntArray(BLOCK)
    private val residual = Array(4) { IntArray(BLOCK) }
    private var fill = 0
    private var frameNumber = 0L
    private val frame = BitWriter(64 * 1024)
    private var finished = false

    init {
        require(md5 == null || md5.size == 16) { "MD5 — 16 байт" }
        out.write(header(totalFrames, md5, tags, pictureJpeg, sampleRate))
    }

    /** Стерео PCM 16 біт LE; довжина кратна 4 байтам. */
    fun encode(pcm: ByteArray, offset: Int, lengthBytes: Int) {
        check(!finished) { "кодувальник уже закрито" }
        var pos = offset
        val end = offset + lengthBytes - lengthBytes % Pcm.BYTES_PER_FRAME
        while (pos < end) {
            left[fill] = (pcm[pos + 1].toInt() shl 8) or (pcm[pos].toInt() and 0xff)
            right[fill] = (pcm[pos + 3].toInt() shl 8) or (pcm[pos + 2].toInt() and 0xff)
            pos += Pcm.BYTES_PER_FRAME
            if (++fill == BLOCK) {
                writeFrame(BLOCK)
                fill = 0
            }
        }
    }

    fun finish() {
        check(!finished) { "кодувальник уже закрито" }
        finished = true
        if (fill > 0) writeFrame(fill)
        fill = 0
    }

    // ---------- кадр ----------

    private fun writeFrame(n: Int) {
        for (i in 0 until n) {
            side[i] = left[i] - right[i]
            mid[i] = (left[i] + right[i]) shr 1
        }
        val l = analyze(left, n, 16, 0)
        val r = analyze(right, n, 16, 1)
        val m = analyze(mid, n, 16, 2)
        val s = analyze(side, n, 17, 3)
        // Канали: 1 — окремо L і R; 8 — L і різниця; 9 — різниця і R; 10 — середнє і різниця.
        val options = listOf(
            Triple(CH_INDEPENDENT, l, r),
            Triple(CH_LEFT_SIDE, l, s),
            Triple(CH_SIDE_RIGHT, s, r),
            Triple(CH_MID_SIDE, m, s),
        )
        val (assignment, first, second) = options.minByOrNull { it.second.bits + it.third.bits }!!

        val w = frame
        w.reset()
        w.bits(0x3FFE, 14) // синхро
        w.bits(0, 1)
        w.bits(0, 1) // блоки однакового розміру
        val sizeCode = when {
            n == BLOCK -> 12
            n <= 256 -> 6
            else -> 7
        }
        w.bits(sizeCode, 4)
        w.bits(rateCode(sampleRate), 4)
        w.bits(assignment, 4)
        w.bits(0b100, 3) // 16 біт
        w.bits(0, 1)
        for (b in utf8(frameNumber)) w.bits(b.toInt() and 0xff, 8)
        when (sizeCode) {
            6 -> w.bits(n - 1, 8)
            7 -> w.bits(n - 1, 16)
        }
        w.bits(crc8(w.buffer(), w.byteCount()), 8)
        writeSubframe(w, first, n)
        writeSubframe(w, second, n)
        w.alignToByte()
        val crc = crc16(w.buffer(), w.byteCount())
        w.bits(crc, 16)
        out.write(w.buffer(), 0, w.byteCount())
        frameNumber++
    }

    /** Найкращий спосіб записати один канал: тиша/стала, як є, або передбачення порядку 0–4 + Райс. */
    private class Plan(
        val samples: IntArray,
        val bps: Int,
        val kind: Int,
        val order: Int,
        val residual: IntArray?,
        val partitionOrder: Int,
        val params: IntArray,
        val method: Int,
        val bits: Long,
    )

    private fun analyze(x: IntArray, n: Int, bps: Int, slot: Int): Plan {
        val verbatimBits = 8L + n.toLong() * bps
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) return Plan(x, bps, KIND_CONSTANT, 0, null, 0, IntArray(0), 0, 8L + bps)

        // Порядок передбачення — за найменшою сумою |залишку| (як libFLAC для «фіксованих»).
        val maxOrder = minOf(4, n - 1)
        var bestOrder = 0
        var bestSum = Long.MAX_VALUE
        for (order in 0..maxOrder) {
            var sum = 0L
            for (i in order until n) sum += Math.abs(predictError(x, i, order).toLong())
            if (sum < bestSum) { bestSum = sum; bestOrder = order }
        }
        val res = residual[slot]
        for (i in bestOrder until n) res[i] = predictError(x, i, bestOrder)
        val rice = Rice.choose(res, n, bestOrder)
        val fixedBits = 8L + bestOrder.toLong() * bps + rice.bits
        return if (fixedBits < verbatimBits) {
            Plan(x, bps, KIND_FIXED, bestOrder, res, rice.partitionOrder, rice.params, rice.method, fixedBits)
        } else {
            Plan(x, bps, KIND_VERBATIM, 0, null, 0, IntArray(0), 0, verbatimBits)
        }
    }

    private fun writeSubframe(w: BitWriter, p: Plan, n: Int) {
        when (p.kind) {
            KIND_CONSTANT -> {
                w.bits(0, 8)
                w.signed(p.samples[0], p.bps)
            }
            KIND_VERBATIM -> {
                w.bits(0b00000010, 8)
                for (i in 0 until n) w.signed(p.samples[i], p.bps)
            }
            else -> {
                w.bits((0b001000 or p.order) shl 1, 8)
                for (i in 0 until p.order) w.signed(p.samples[i], p.bps)
                val res = p.residual!!
                w.bits(p.method, 2)
                w.bits(p.partitionOrder, 4)
                val paramBits = if (p.method == 0) 4 else 5
                val parts = 1 shl p.partitionOrder
                val size = n shr p.partitionOrder
                var i = p.order
                for (part in 0 until parts) {
                    val k = p.params[part]
                    w.bits(k, paramBits)
                    val end = (part + 1) * size
                    while (i < end) {
                        val v = res[i++]
                        val u = (v shl 1) xor (v shr 31)
                        w.zeros(u ushr k)
                        w.bits(1, 1)
                        if (k > 0) w.bits(u and ((1 shl k) - 1), k)
                    }
                }
            }
        }
    }

    companion object {
        const val BLOCK = 4096
        /** Хто записав — з версією ([AppInfo]). */
        val VENDOR: String get() = AppInfo.encoder
        /** Молодші 32 біти (через зсув, а не шістнадцятковим числом — аудит UI сприймає такі числа за кольори). */
        const val MASK32 = (1L shl 32) - 1
        private const val KIND_CONSTANT = 0
        private const val KIND_VERBATIM = 1
        private const val KIND_FIXED = 2
        private const val CH_INDEPENDENT = 0b0001
        private const val CH_LEFT_SIDE = 0b1000
        private const val CH_SIDE_RIGHT = 0b1001
        private const val CH_MID_SIDE = 0b1010

        fun predictError(x: IntArray, i: Int, order: Int): Int = when (order) {
            0 -> x[i]
            1 -> x[i] - x[i - 1]
            2 -> x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }

        fun rateCode(rate: Int): Int = when (rate) {
            44_100 -> 0b1001
            48_000 -> 0b1010
            96_000 -> 0b1011
            else -> 0 // взяти зі STREAMINFO
        }

        /** Номер кадру «як UTF-8» (до 36 біт). */
        fun utf8(v: Long): ByteArray {
            require(v >= 0)
            if (v < 0x80) return byteArrayOf(v.toByte())
            val n = when {
                v < 0x800 -> 2
                v < 0x10000 -> 3
                v < 0x200000 -> 4
                v < 0x4000000 -> 5
                v < (1L shl 31) -> 6
                else -> 7
            }
            val out = ByteArray(n)
            var rest = v
            for (i in n - 1 downTo 1) {
                out[i] = (0x80 or (rest and 0x3F).toInt()).toByte()
                rest = rest shr 6
            }
            val lead = if (n == 7) 0xFE else (0xFF shl (8 - n)) and 0xFF
            out[0] = (lead or rest.toInt()).toByte()
            return out
        }

        fun crc8(b: ByteArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                crc = crc xor (b[i].toInt() and 0xff)
                repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xff else (crc shl 1) and 0xff }
            }
            return crc
        }

        fun crc16(b: ByteArray, len: Int): Int {
            var crc = 0
            for (i in 0 until len) {
                crc = crc xor ((b[i].toInt() and 0xff) shl 8)
                repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xffff else (crc shl 1) and 0xffff }
            }
            return crc
        }

        /** "fLaC" + STREAMINFO + VORBIS_COMMENT (теги) + PICTURE (обкладинка, якщо є). */
        fun header(totalFrames: Long, md5: ByteArray?, tags: List<Pair<String, String>>, jpeg: ByteArray?, sampleRate: Int = Pcm.SAMPLE_RATE): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.US_ASCII))
            val hasPicture = jpeg != null && jpeg.isNotEmpty()

            val info = BitWriter(64)
            info.bits(BLOCK, 16)
            info.bits(BLOCK, 16)
            info.bits(0, 24) // найменший кадр — невідомо
            info.bits(0, 24) // найбільший — невідомо
            info.bits(sampleRate, 20)
            info.bits(Pcm.CHANNELS - 1, 3)
            info.bits(15, 5) // 16 біт
            info.bits((totalFrames ushr 32).toInt() and 0xF, 4)
            info.bits((totalFrames and MASK32).toInt(), 32)
            val sum = md5 ?: ByteArray(16)
            for (b in sum) info.bits(b.toInt() and 0xff, 8)
            block(out, 0, info.toByteArray(), last = false)

            block(out, 4, vorbisComment(tags), last = !hasPicture)
            if (hasPicture) block(out, 6, picture(jpeg!!), last = true)
            return out.toByteArray()
        }

        private fun block(out: ByteArrayOutputStream, type: Int, body: ByteArray, last: Boolean) {
            out.write((if (last) 0x80 else 0) or type)
            out.write((body.size shr 16) and 0xff)
            out.write((body.size shr 8) and 0xff)
            out.write(body.size and 0xff)
            out.write(body)
        }

        /** Теги Vorbis (як у OGG): числа little-endian, текст UTF-8 «КЛЮЧ=значення». */
        fun vorbisComment(tags: List<Pair<String, String>>): ByteArray {
            val out = ByteArrayOutputStream()
            fun le32(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
            val vendor = VENDOR.toByteArray(Charsets.UTF_8)
            le32(vendor.size)
            out.write(vendor)
            le32(tags.size)
            for ((k, v) in tags) {
                val bytes = "$k=$v".toByteArray(Charsets.UTF_8)
                le32(bytes.size)
                out.write(bytes)
            }
            return out.toByteArray()
        }

        /** Обкладинка: тип 3 (передня), JPEG; розмір картинки — з самого JPEG. */
        fun picture(jpeg: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            fun be32(v: Int) { out.write((v shr 24) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 8) and 0xff); out.write(v and 0xff) }
            val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
            val (w, h) = jpegSize(jpeg) ?: (0 to 0)
            be32(3)
            be32(mime.size); out.write(mime)
            be32(0) // опис — порожньо
            be32(w); be32(h)
            be32(24); be32(0)
            be32(jpeg.size); out.write(jpeg)
            return out.toByteArray()
        }

        /** Ширина й висота JPEG з маркера SOF; не JPEG — null. */
        fun jpegSize(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 4 || (b[0].toInt() and 0xff) != 0xFF || (b[1].toInt() and 0xff) != 0xD8) return null
            var i = 2
            while (i + 9 < b.size) {
                if ((b[i].toInt() and 0xff) != 0xFF) { i++; continue }
                val marker = b[i + 1].toInt() and 0xff
                if (marker == 0xFF) { i++; continue }
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue }
                val len = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
                val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (sof) {
                    val h = ((b[i + 5].toInt() and 0xff) shl 8) or (b[i + 6].toInt() and 0xff)
                    val w = ((b[i + 7].toInt() and 0xff) shl 8) or (b[i + 8].toInt() and 0xff)
                    return w to h
                }
                if (len < 2) return null
                i += 2 + len
            }
            return null
        }

        /** Теги для FLAC з того, що дає шторка. */
        fun tagsFor(meta: TrackMeta): List<Pair<String, String>> = buildList {
            if (meta.title.isNotBlank()) add("TITLE" to meta.title.trim())
            if (meta.artist.isNotBlank()) add("ARTIST" to meta.artist.trim())
            if (meta.album.isNotBlank()) add("ALBUM" to meta.album.trim())
            // T68: виконавець альбому — щоб плеєр не розбивав альбом; номер і рік.
            if (meta.artist.isNotBlank() && meta.album.isNotBlank()) add("ALBUMARTIST" to meta.artist.trim())
            if (meta.trackNo > 0) add("TRACKNUMBER" to meta.trackNo.toString())
            if (meta.year > 0) add("DATE" to meta.year.toString())
            add("ENCODER" to VENDOR)
        }
    }
}

/** Вибір параметрів Райса: порядок поділу на частини й параметр кожної частини — за найменшою кількістю біт. */
internal object Rice {
    class Choice(val method: Int, val partitionOrder: Int, val params: IntArray, val bits: Long)

    private const val MAX_PARTITION_ORDER = 8

    fun choose(res: IntArray, n: Int, order: Int): Choice {
        var maxP = 0
        while (maxP < MAX_PARTITION_ORDER && n % (2 shl maxP) == 0 && (n shr (maxP + 1)) > order) maxP++
        // Суми |залишку| (у вигляді без знаку) у найдрібніших частинах, далі — зливаємо попарно.
        val finest = 1 shl maxP
        val size = n shr maxP
        val sums = LongArray(finest)
        val counts = IntArray(finest)
        for (part in 0 until finest) {
            val start = if (part == 0) order else part * size
            val end = (part + 1) * size
            var s = 0L
            for (i in start until end) {
                val v = res[i]
                s += ((v shl 1) xor (v shr 31)).toLong() and FlacEncoder.MASK32
            }
            sums[part] = s
            counts[part] = end - start
        }
        var best: Choice? = null
        var levelSums = sums
        var levelCounts = counts
        for (p in maxP downTo 0) {
            val parts = 1 shl p
            val params = IntArray(parts)
            var bits = 2L + 4L
            var needWide = false
            var partBits = 0L
            for (j in 0 until parts) {
                val (k, b) = bestParam(levelSums[j], levelCounts[j])
                params[j] = k
                if (k > 14) needWide = true
                partBits += b
            }
            bits += partBits + parts.toLong() * (if (needWide) 5 else 4)
            if (best == null || bits < best.bits) best = Choice(if (needWide) 1 else 0, p, params, bits)
            if (p > 0) {
                val nextSums = LongArray(parts / 2)
                val nextCounts = IntArray(parts / 2)
                for (j in 0 until parts / 2) {
                    nextSums[j] = levelSums[2 * j] + levelSums[2 * j + 1]
                    nextCounts[j] = levelCounts[2 * j] + levelCounts[2 * j + 1]
                }
                levelSums = nextSums
                levelCounts = nextCounts
            }
        }
        return best!!
    }

    /** Параметр k і приблизна кількість біт частини: count·(k+1) + sum/2^k. */
    fun bestParam(sum: Long, count: Int): Pair<Int, Long> {
        if (count == 0) return 0 to 0L
        var bestK = 0
        var bestBits = Long.MAX_VALUE
        for (k in 0..30) {
            val b = count.toLong() * (k + 1) + (sum shr k)
            if (b < bestBits) { bestBits = b; bestK = k }
            if ((sum shr k) == 0L) break
        }
        return bestK to bestBits
    }
}

/** Запис бітів старшим наперед (як у FLAC). */
class BitWriter(capacity: Int) {
    private var buf = ByteArray(capacity)
    private var pos = 0
    private var acc = 0L
    private var accBits = 0

    fun reset() { pos = 0; acc = 0; accBits = 0 }

    /** Молодші [n] біт [value] (n ≤ 32). */
    fun bits(value: Int, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (value.toLong() and ((1L shl n) - 1))
        accBits += n
        while (accBits >= 8) {
            accBits -= 8
            put(((acc shr accBits) and 0xff).toInt())
        }
        acc = acc and ((1L shl accBits) - 1)
    }

    fun signed(value: Int, n: Int) = bits(value, n)

    fun zeros(count: Int) {
        var left = count
        while (left > 0) {
            val n = minOf(left, 32)
            bits(0, n)
            left -= n
        }
    }

    fun alignToByte() { if (accBits > 0) bits(0, 8 - accBits) }

    /** Скільки цілих байтів записано (для CRC: викликати на межі байта). */
    fun byteCount(): Int = pos

    fun buffer(): ByteArray = buf

    fun toByteArray(): ByteArray { alignToByte(); return buf.copyOf(pos) }

    private fun put(b: Int) {
        if (pos == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[pos++] = b.toByte()
    }
}
