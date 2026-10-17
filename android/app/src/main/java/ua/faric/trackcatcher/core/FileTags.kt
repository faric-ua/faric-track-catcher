package ua.faric.trackcatcher.core

import java.io.EOFException
import java.io.InputStream

/**
 * Що насправді лежить у файлі (T68, вікно «Про файл»): теги, обкладинка, параметри звуку.
 * Читає FLAC (STREAMINFO, Vorbis, PICTURE), MP3 (ID3v2.3/2.4 + перший кадр MPEG) і WAV (fmt, LIST/INFO, id3).
 * Лише заголовки: звук не розкодовується, тож навіть великий файл читається миттєво.
 * Показує й чужі поля: усе, чого ми не знаємо, лишається в [FileInfo.raw] — «Усі теги у файлі».
 */
object FileTags {
    enum class Field(val label: String) {
        TITLE("Назва"), ARTIST("Виконавець"), ALBUM("Альбом"), ALBUM_ARTIST("Виконавець альбому"),
        TRACK("Номер"), YEAR("Рік"), GENRE("Жанр"), COMMENT("Коментар"), COMPOSER("Автор"),
        COPYRIGHT("Власник прав"), ENCODER("Створив"),
    }

    data class Picture(val mime: String, val width: Int, val height: Int, val bytes: ByteArray, val type: Int) {
        val typeLabel: String get() = when (type) { 3 -> "лицьова"; 4 -> "зворот"; 0 -> "інша"; else -> "тип $type" }
    }

    data class Raw(val key: String, val value: String)

    data class FileInfo(
        val format: String,
        val sampleRate: Int = 0,
        val channels: Int = 0,
        val bits: Int = 0,
        val durationMs: Long = 0L,
        val bitrateKbps: Int = 0,
        val fields: Map<Field, String> = emptyMap(),
        val raw: List<Raw> = emptyList(),
        val picture: Picture? = null,
        /** FLAC: у заголовку є контрольна сума звуку (MD5). */
        val hasMd5: Boolean = false,
        /** WAV: де починається звук і скільки його (для WAV → FLAC, T69). */
        val dataOffset: Long = 0L,
        val dataBytes: Long = 0L,
    ) {
        operator fun get(f: Field): String? = fields[f]?.takeIf { it.isNotBlank() }
        val replayGain: String? get() = raw.firstOrNull { it.key.substringAfter(':').equals("REPLAYGAIN_TRACK_GAIN", true) }?.value
    }

    class NotAudio(message: String) : Exception(message)

    /** [size] — довжина файлу (для тривалості MP3 і WAV); [name] — лише щоб вибрати читач, коли підпис незвичний. */
    fun read(input: InputStream, size: Long, name: String = ""): FileInfo {
        val s = Stream(input)
        val head = s.bytes(4)
        val magic = String(head, Charsets.ISO_8859_1)
        val info = when {
            magic == "fLaC" -> flac(s)
            magic.startsWith("ID3") -> mp3(s, head, size)
            magic == "RIFF" -> wav(s)
            (head[0].toInt() and 0xff) == 0xff && (head[1].toInt() and 0xe0) == 0xe0 -> mp3Frame(s, head, size, 0, FileInfo("MP3"))
            else -> throw NotAudio("Незнайомий формат${if (name.isNotBlank()) " («${name.substringAfterLast('.')}»)" else ""}")
        }
        // FLAC: середній бітрейт — з розміру й тривалості.
        return if (info.bitrateKbps == 0 && info.durationMs > 0 && size > 0) info.copy(bitrateKbps = (size * 8 / info.durationMs).toInt()) else info
    }

    // ---------- FLAC ----------

    private val VORBIS = mapOf(
        "TITLE" to Field.TITLE, "ARTIST" to Field.ARTIST, "ALBUM" to Field.ALBUM, "ALBUMARTIST" to Field.ALBUM_ARTIST,
        "ALBUM ARTIST" to Field.ALBUM_ARTIST, "TRACKNUMBER" to Field.TRACK, "DATE" to Field.YEAR, "YEAR" to Field.YEAR,
        "GENRE" to Field.GENRE, "COMMENT" to Field.COMMENT, "DESCRIPTION" to Field.COMMENT, "COMPOSER" to Field.COMPOSER,
        "COPYRIGHT" to Field.COPYRIGHT, "ENCODER" to Field.ENCODER,
    )

    private fun flac(s: Stream): FileInfo {
        var info = FileInfo("FLAC")
        val fields = LinkedHashMap<Field, String>()
        val raw = ArrayList<Raw>()
        var picture: Picture? = null
        while (true) {
            val h = s.u8()
            val last = h and 0x80 != 0
            val type = h and 0x7f
            val len = s.u24()
            when (type) {
                0 -> {
                    val b = s.bytes(len)
                    val rate = (b[10].u() shl 12) or (b[11].u() shl 4) or (b[12].u() shr 4)
                    val ch = ((b[12].u() shr 1) and 7) + 1
                    val bps = (((b[12].u() and 1) shl 4) or (b[13].u() shr 4)) + 1
                    val total = ((b[13].u() and 0x0f).toLong() shl 32) or (b[14].u().toLong() shl 24) or
                        (b[15].u().toLong() shl 16) or (b[16].u().toLong() shl 8) or b[17].u().toLong()
                    val md5 = (18 until 34).any { b[it].toInt() != 0 }
                    info = info.copy(sampleRate = rate, channels = ch, bits = bps, durationMs = if (rate > 0) total * 1000 / rate else 0, hasMd5 = md5)
                }
                4 -> {
                    val b = s.bytes(len)
                    var p = 0
                    val vendor = le32(b, p); p += 4 + vendor
                    val n = le32(b, p); p += 4
                    repeat(n) {
                        val l = le32(b, p); p += 4
                        val item = String(b, p, l, Charsets.UTF_8); p += l
                        val key = item.substringBefore('=')
                        val value = item.substringAfter('=', "")
                        raw += Raw(key.uppercase(), value)
                        VORBIS[key.uppercase()]?.let { f -> if (f !in fields) fields[f] = value }
                    }
                }
                6 -> {
                    val b = s.bytes(len)
                    val pic = flacPicture(b)
                    if (pic != null && (picture == null || pic.type == 3)) picture = pic
                    if (pic != null) raw += Raw("PICTURE", "${pic.typeLabel} · ${pic.mime} · ${pic.width}×${pic.height} · ${TimeText.size(pic.bytes.size.toLong())}")
                }
                else -> s.skip(len.toLong())
            }
            if (last) break
        }
        return info.copy(fields = fields, raw = raw, picture = picture)
    }

    private fun flacPicture(b: ByteArray): Picture? = runCatching {
        var p = 0
        val type = be32(b, p); p += 4
        val ml = be32(b, p); p += 4
        val mime = String(b, p, ml, Charsets.US_ASCII); p += ml
        val dl = be32(b, p); p += 4 + dl
        val w = be32(b, p); val h = be32(b, p + 4); p += 16
        val n = be32(b, p); p += 4
        val img = b.copyOfRange(p, p + n)
        val (iw, ih) = imageSize(img) ?: (w to h)
        Picture(mime.ifBlank { imageMime(img) }, iw, ih, img, type)
    }.getOrNull()

    // ---------- MP3 ----------

    private val ID3 = mapOf(
        "TIT2" to Field.TITLE, "TPE1" to Field.ARTIST, "TALB" to Field.ALBUM, "TPE2" to Field.ALBUM_ARTIST, "TRCK" to Field.TRACK,
        "TYER" to Field.YEAR, "TDRC" to Field.YEAR, "TCON" to Field.GENRE, "COMM" to Field.COMMENT, "TCOM" to Field.COMPOSER,
        "TCOP" to Field.COPYRIGHT, "TSSE" to Field.ENCODER, "TENC" to Field.ENCODER,
    )

    private fun mp3(s: Stream, head: ByteArray, size: Long): FileInfo {
        val rest = s.bytes(6)
        val ver = head[3].u()
        val tagSize = syncsafe(rest, 2)
        val body = s.bytes(tagSize)
        val parsed = id3Frames(body, ver)
        var info = FileInfo("MP3", fields = parsed.fields, raw = parsed.raw, picture = parsed.picture)
        val tlen = parsed.raw.firstOrNull { it.key == "TLEN" }?.value?.trim()?.toLongOrNull() ?: 0L
        // Кадр MPEG одразу після тегу (у наших файлах — так; у чужих може бути заповнення нулями).
        repeat(4096) {
            val b0 = runCatching { s.u8() }.getOrNull() ?: return info.copy(durationMs = tlen)
            if (b0 == 0xff) {
                val b1 = s.u8()
                if (b1 and 0xe0 == 0xe0) {
                    val h = byteArrayOf(0xff.toByte(), b1.toByte(), s.u8().toByte(), s.u8().toByte())
                    info = mp3Frame(s, h, size, 10L + tagSize, info)
                    return if (tlen > 0) info.copy(durationMs = tlen) else info
                }
            }
        }
        return info.copy(durationMs = tlen)
    }

    private fun mp3Frame(@Suppress("UNUSED_PARAMETER") s: Stream, h: ByteArray, size: Long, audioStart: Long, base: FileInfo): FileInfo {
        val version = (h[1].u() shr 3) and 3 // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        val brIdx = h[2].u() shr 4
        val srIdx = (h[2].u() shr 2) and 3
        val mode = h[3].u() shr 6
        val rates = intArrayOf(44100, 48000, 32000)
        if (srIdx == 3 || brIdx == 0 || brIdx == 15) return base
        val rate = rates[srIdx] / when (version) { 3 -> 1; 2 -> 2; else -> 4 }
        val kbps = if (version == 3) MPEG1_L3[brIdx] else MPEG2_L3[brIdx]
        val ms = if (kbps > 0 && size > audioStart) (size - audioStart) * 8 / kbps else 0L
        return base.copy(sampleRate = rate, channels = if (mode == 3) 1 else 2, bitrateKbps = kbps, durationMs = ms)
    }

    private val MPEG1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)

    private class Id3(val fields: Map<Field, String>, val raw: List<Raw>, val picture: Picture?)

    private fun id3Frames(b: ByteArray, ver: Int): Id3 {
        val fields = LinkedHashMap<Field, String>()
        val raw = ArrayList<Raw>()
        var picture: Picture? = null
        var p = 0
        while (p + 10 <= b.size) {
            val id = String(b, p, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val len = if (ver >= 4) syncsafe(b, p + 4) else be32(b, p + 4)
            p += 10
            if (len <= 0 || p + len > b.size) break
            val f = b.copyOfRange(p, p + len)
            p += len
            when {
                id == "APIC" -> apic(f)?.let { pic ->
                    if (picture == null || pic.type == 3) picture = pic
                    raw += Raw("APIC", "${pic.typeLabel} · ${pic.mime} · ${pic.width}×${pic.height} · ${TimeText.size(pic.bytes.size.toLong())}")
                }
                id == "TXXX" -> {
                    val enc = f[0].u()
                    val (desc, at) = cstr(f, 1, enc)
                    raw += Raw("TXXX:$desc", decode(f, at, f.size, enc))
                }
                id == "COMM" -> {
                    val enc = f[0].u()
                    val (_, at) = cstr(f, 4, enc)
                    val text = decode(f, at, f.size, enc)
                    raw += Raw(id, text)
                    if (Field.COMMENT !in fields) fields[Field.COMMENT] = text
                }
                id.startsWith("T") -> {
                    val text = decode(f, 1, f.size, f[0].u())
                    raw += Raw(id, text)
                    ID3[id]?.let { fl -> if (fl !in fields) fields[fl] = text }
                }
                else -> raw += Raw(id, "${TimeText.size(len.toLong())}")
            }
        }
        return Id3(fields, raw, picture)
    }

    private fun apic(f: ByteArray): Picture? = runCatching {
        val enc = f[0].u()
        var p = 1
        val mimeEnd = (p until f.size).first { f[it].toInt() == 0 }
        val mime = String(f, p, mimeEnd - p, Charsets.ISO_8859_1); p = mimeEnd + 1
        val type = f[p].u(); p += 1
        val (_, at) = cstr(f, p, enc)
        val img = f.copyOfRange(at, f.size)
        val (w, h) = imageSize(img) ?: (0 to 0)
        Picture(mime.ifBlank { imageMime(img) }, w, h, img, type)
    }.getOrNull()

    /** Рядок до нуля в кодуванні ID3; повертає текст і позицію після нуля. */
    private fun cstr(b: ByteArray, from: Int, enc: Int): Pair<String, Int> {
        val wide = enc == 1 || enc == 2
        var i = from
        while (i < b.size) {
            if (!wide && b[i].toInt() == 0) return decode(b, from, i, enc) to i + 1
            if (wide && i + 1 < b.size && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && (i - from) % 2 == 0) return decode(b, from, i, enc) to i + 2
            i++
        }
        return decode(b, from, b.size, enc) to b.size
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        val cs = when (enc) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
        return String(b, from, to - from, cs).trimEnd('\u0000').replace('\u0000', '/')
    }

    // ---------- WAV ----------

    private val INFO = mapOf(
        "INAM" to Field.TITLE, "IART" to Field.ARTIST, "IPRD" to Field.ALBUM, "ITRK" to Field.TRACK, "IPRT" to Field.TRACK,
        "ICRD" to Field.YEAR, "IGNR" to Field.GENRE, "ICMT" to Field.COMMENT, "ICOP" to Field.COPYRIGHT, "ISFT" to Field.ENCODER,
    )

    private fun wav(s: Stream): FileInfo {
        s.bytes(4) // розмір
        if (String(s.bytes(4), Charsets.ISO_8859_1) != "WAVE") throw NotAudio("Це не WAV")
        var info = FileInfo("WAV")
        val fields = LinkedHashMap<Field, String>()
        val raw = ArrayList<Raw>()
        var picture: Picture? = null
        while (true) {
            val id = runCatching { String(s.bytes(4), Charsets.ISO_8859_1) }.getOrNull() ?: break
            val len = runCatching { s.le32u() }.getOrNull() ?: break
            val pad = len and 1L
            when (id) {
                "fmt " -> {
                    val b = s.bytes(len.toInt())
                    info = info.copy(channels = le16(b, 2), sampleRate = le32(b, 4), bits = le16(b, 14))
                }
                "LIST" -> {
                    val b = s.bytes(len.toInt())
                    if (String(b, 0, 4, Charsets.ISO_8859_1) == "INFO") {
                        var p = 4
                        while (p + 8 <= b.size) {
                            val key = String(b, p, 4, Charsets.ISO_8859_1)
                            val l = le32(b, p + 4)
                            if (l < 0 || p + 8 + l > b.size) break
                            val value = String(b, p + 8, l, Charsets.UTF_8).trimEnd('\u0000')
                            raw += Raw(key, value)
                            INFO[key]?.let { f -> if (f !in fields) fields[f] = value }
                            p += 8 + l + (l and 1)
                        }
                    }
                }
                "id3 ", "ID3 " -> {
                    val b = s.bytes(len.toInt())
                    if (b.size > 10 && String(b, 0, 3, Charsets.ISO_8859_1) == "ID3") {
                        val t = id3Frames(b.copyOfRange(10, minOf(b.size, 10 + syncsafe(b, 6))), b[3].u())
                        t.fields.forEach { (k, v) -> fields.putIfAbsent(k, v) }
                        raw += t.raw
                        picture = picture ?: t.picture
                    }
                }
                "data" -> {
                    info = info.copy(dataOffset = s.pos, dataBytes = len)
                    s.skip(len)
                }
                else -> s.skip(len)
            }
            if (pad == 1L) runCatching { s.skip(1) }
        }
        val bytesPerSec = info.sampleRate.toLong() * info.channels * (info.bits / 8)
        return info.copy(
            fields = fields, raw = raw, picture = picture,
            durationMs = if (bytesPerSec > 0) info.dataBytes * 1000 / bytesPerSec else 0,
            bitrateKbps = (bytesPerSec * 8 / 1000).toInt(),
        )
    }

    // ---------- картинка ----------

    fun imageMime(b: ByteArray): String = when {
        b.size > 3 && b[0].u() == 0xff && b[1].u() == 0xd8 -> "image/jpeg"
        b.size > 8 && b[0].u() == 0x89 && b[1].toInt().toChar() == 'P' -> "image/png"
        else -> "image/?"
    }

    /** Ширина й висота JPEG або PNG; інше — null. */
    fun imageSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size > 24 && b[0].u() == 0x89 && b[1].toInt().toChar() == 'P') return be32(b, 16) to be32(b, 20)
        return FlacEncoder.jpegSize(b)
    }

    // ---------- байти ----------

    private class Stream(private val input: InputStream) {
        var pos = 0L
        fun u8(): Int {
            val v = input.read()
            if (v < 0) throw EOFException()
            pos++
            return v
        }
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun le32u(): Long = u8().toLong() or (u8().toLong() shl 8) or (u8().toLong() shl 16) or (u8().toLong() shl 24)
        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > MAX_BLOCK) throw NotAudio("Файл пошкоджений: блок $n байт")
            val b = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = input.read(b, got, n - got)
                if (r < 0) throw EOFException()
                got += r
            }
            pos += n
            return b
        }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                val k = input.skip(left)
                if (k <= 0) {
                    if (input.read() < 0) throw EOFException()
                    left--
                    pos++
                } else {
                    left -= k
                    pos += k
                }
            }
        }
    }

    private const val MAX_BLOCK = 32 * 1024 * 1024

    private fun Byte.u() = toInt() and 0xff
    private fun be32(b: ByteArray, p: Int) = (b[p].u() shl 24) or (b[p + 1].u() shl 16) or (b[p + 2].u() shl 8) or b[p + 3].u()
    private fun le32(b: ByteArray, p: Int) = b[p].u() or (b[p + 1].u() shl 8) or (b[p + 2].u() shl 16) or (b[p + 3].u() shl 24)
    private fun le16(b: ByteArray, p: Int) = b[p].u() or (b[p + 1].u() shl 8)
    private fun syncsafe(b: ByteArray, p: Int) = ((b[p].u() and 0x7f) shl 21) or ((b[p + 1].u() and 0x7f) shl 14) or ((b[p + 2].u() and 0x7f) shl 7) or (b[p + 3].u() and 0x7f)
}
