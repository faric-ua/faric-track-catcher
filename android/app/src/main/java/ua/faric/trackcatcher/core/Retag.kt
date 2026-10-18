package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Переписати теги у готовому файлі, **звук не чіпаючи** (T70, faric 18.10 04:11: «пробіжиться й передивиться,
 * чи номери треків правильно записані»). Копіює файл: заголовок з тегами — новий, усе після нього — байт у байт.
 * FLAC: блок Vorbis (інші блоки — STREAMINFO, обкладинка — як були). MP3: ID3v2.3 / 2.4 (кадри, яких не міняємо, — як були).
 * Перевірку «звук той самий» дає [audioDigest]: MD5 усього, що після тегів.
 */
object Retag {
    enum class Kind { FLAC, MP3 }

    class Unsupported(message: String) : Exception(message)

    fun kindOf(name: String?): Kind? = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "flac" -> Kind.FLAC
        "mp3" -> Kind.MP3
        else -> null
    }

    /**
     * Скопіювати [input] у [out] з новими полями. [set] — назва поля Vorbis (TRACKNUMBER, ALBUMARTIST, DATE…) → значення;
     * для MP3 перекладається в кадри ID3 (TRCK, TPE2, TYER). Поля, яких нема в [set], лишаються.
     */
    fun rewrite(kind: Kind, input: InputStream, out: OutputStream, set: Map<String, String>) = when (kind) {
        Kind.FLAC -> flac(input, out, set)
        Kind.MP3 -> mp3(input, out, set)
    }

    /**
     * Звичний порядок тегів — як їх пише сам Track Catcher: назва, виконавець, альбом, виконавець альбому, номер, рік;
     * решта — після них, у своєму порядку (faric 18.10 15:02: «TITLE заїхав кудись униз — після оновлення
     * помінялась черговість»). Переписаний тег стає на своє місце, а не в кінець.
     */
    private val ORDER = listOf(
        setOf("TITLE", "TIT2"), setOf("ARTIST", "TPE1"), setOf("ALBUM", "TALB"), setOf("ALBUMARTIST", "TPE2"),
        setOf("TRACKNUMBER", "TRCK"), setOf("DATE", "TYER", "TDRC"),
    )

    /** Місце тегу [key] (назва поля Vorbis чи кадру ID3) у звичному порядку; інші — після. */
    fun rank(key: String): Int = ORDER.indexOfFirst { key.uppercase() in it }.let { if (it < 0) ORDER.size else it }

    /** Чи теги стоять у звичному порядку (ключі — як у [FileTags.Raw]). */
    fun inOrder(keys: List<String>): Boolean = keys.map { rank(it) }.zipWithNext().all { (a, b) -> a <= b }

    /** MD5 і довжина звуку — усього, що після тегів (FLAC — після блоків метаданих, MP3 — після ID3). */
    fun audioDigest(kind: Kind, input: InputStream): Pair<Long, String> {
        when (kind) {
            Kind.FLAC -> {
                expect(input, "fLaC")
                while (true) {
                    val h = u8(input)
                    val len = (u8(input) shl 16) or (u8(input) shl 8) or u8(input)
                    skip(input, len.toLong())
                    if (h and 0x80 != 0) break
                }
            }
            Kind.MP3 -> {
                val head = readN(input, 10)
                if (String(head, 0, 3, Charsets.ISO_8859_1) == "ID3") skip(input, syncsafe(head, 6).toLong())
                else return digestRest(input, head)
            }
        }
        return digestRest(input, ByteArray(0))
    }

    // ---------- FLAC ----------

    private fun flac(input: InputStream, out: OutputStream, set: Map<String, String>) {
        expect(input, "fLaC")
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        while (true) {
            val h = u8(input)
            val len = (u8(input) shl 16) or (u8(input) shl 8) or u8(input)
            blocks += (h and 0x7f) to readN(input, len)
            if (h and 0x80 != 0) break
        }
        if (blocks.firstOrNull()?.first != 0) throw Unsupported("FLAC без STREAMINFO")
        val vi = blocks.indexOfFirst { it.first == 4 }
        val (vendor, items) = if (vi >= 0) parseVorbis(blocks[vi].second) else (FlacEncoder.VENDOR to emptyList())
        val keys = set.keys.map { it.uppercase() }.toSet()
        val kept = items.filter { it.substringBefore('=').uppercase() !in keys }
        val fresh = set.filterValues { it.isNotBlank() }.map { (k, v) -> "${k.uppercase()}=$v" }
        val vorbis = buildVorbis(vendor, (kept + fresh).sortedBy { rank(it.substringBefore('=')) }) // сортування стабільне
        if (vi >= 0) blocks[vi] = 4 to vorbis else blocks.add(1, 4 to vorbis)
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        blocks.forEachIndexed { i, (type, body) ->
            out.write((if (i == blocks.lastIndex) 0x80 else 0) or type)
            out.write((body.size shr 16) and 0xff); out.write((body.size shr 8) and 0xff); out.write(body.size and 0xff)
            out.write(body)
        }
        input.copyTo(out, 256 * 1024)
    }

    private fun parseVorbis(b: ByteArray): Pair<String, List<String>> {
        var p = 0
        val vl = le32(b, p); p += 4
        val vendor = String(b, p, vl, Charsets.UTF_8); p += vl
        val n = le32(b, p); p += 4
        val items = ArrayList<String>(n)
        repeat(n) {
            val l = le32(b, p); p += 4
            items += String(b, p, l, Charsets.UTF_8); p += l
        }
        return vendor to items
    }

    private fun buildVorbis(vendor: String, items: List<String>): ByteArray {
        val o = ByteArrayOutputStream()
        fun le(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
        val vb = vendor.toByteArray(Charsets.UTF_8)
        le(vb.size); o.write(vb)
        le(items.size)
        for (it in items) { val x = it.toByteArray(Charsets.UTF_8); le(x.size); o.write(x) }
        return o.toByteArray()
    }

    // ---------- MP3 (ID3v2) ----------

    private val ID3_OF = mapOf("TRACKNUMBER" to "TRCK", "ALBUMARTIST" to "TPE2", "DATE" to "TYER", "TITLE" to "TIT2", "ARTIST" to "TPE1", "ALBUM" to "TALB")

    private fun mp3(input: InputStream, out: OutputStream, set: Map<String, String>) {
        val head = readN(input, 10)
        val hasTag = String(head, 0, 3, Charsets.ISO_8859_1) == "ID3"
        val ver = if (hasTag) head[3].toInt() else 3
        if (hasTag && ver !in 3..4) throw Unsupported("ID3v2.$ver — вмію лише 2.3 і 2.4")
        if (hasTag && head[5].toInt() and 0xC0 != 0) throw Unsupported("ID3 з розсинхронізацією чи розширеним заголовком")
        val body = if (hasTag) readN(input, syncsafe(head, 6)) else ByteArray(0)
        val wanted = set.mapNotNull { (k, v) -> ID3_OF[k.uppercase()]?.let { it to v } }.toMap()
        // Кадри старого тегу: ті, що міняємо, — геть (і TDRC, якщо пишемо рік), решта — як були.
        val drop = wanted.keys + if ("TYER" in wanted) setOf("TDRC") else emptySet()
        val frames = ArrayList<Pair<String, ByteArray>>()
        var p = 0
        while (p + 10 <= body.size) {
            val id = String(body, p, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val len = if (ver >= 4) syncsafe(body, p + 4) else be32(body, p + 4)
            if (len <= 0 || p + 10 + len > body.size) break
            if (id !in drop) frames += id to body.copyOfRange(p, p + 10 + len)
            p += 10 + len
        }
        for ((id, text) in wanted) if (text.isNotBlank()) frames += id to textFrame(id, text, ver)
        val f = ByteArrayOutputStream().apply { frames.sortedBy { rank(it.first) }.forEach { write(it.second) } }.toByteArray()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1)); out.write(ver); out.write(0); out.write(0)
        out.write(Id3v2.syncsafe(f.size))
        out.write(f)
        if (!hasTag) out.write(head) // у файлі не було тегу — ці 10 байт уже звук
        input.copyTo(out, 256 * 1024)
    }

    /** Текстовий кадр: UTF-16 з BOM (кирилиця в будь-якому плеєрі); розмір — як у версії тегу. */
    private fun textFrame(id: String, text: String, ver: Int): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(1); payload.write(0xFF); payload.write(0xFE)
        payload.write(text.toByteArray(Charsets.UTF_16LE)); payload.write(0); payload.write(0)
        val b = payload.toByteArray()
        val o = ByteArrayOutputStream()
        o.write(id.toByteArray(Charsets.ISO_8859_1))
        if (ver >= 4) o.write(Id3v2.syncsafe(b.size)) else { o.write((b.size shr 24) and 0xff); o.write((b.size shr 16) and 0xff); o.write((b.size shr 8) and 0xff); o.write(b.size and 0xff) }
        o.write(0); o.write(0)
        o.write(b)
        return o.toByteArray()
    }

    // ---------- байти ----------

    private fun digestRest(input: InputStream, first: ByteArray): Pair<Long, String> {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(first)
        var total = first.size.toLong()
        val buf = ByteArray(256 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md5.update(buf, 0, n)
            total += n
        }
        return total to md5.digest().joinToString("") { "%02x".format(it) }
    }

    private fun expect(input: InputStream, magic: String) {
        if (String(readN(input, magic.length), Charsets.ISO_8859_1) != magic) throw Unsupported("це не $magic")
    }

    private fun u8(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException() }

    private fun readN(input: InputStream, n: Int): ByteArray {
        if (n < 0 || n > 32 * 1024 * 1024) throw Unsupported("блок $n байт — файл пошкоджений?")
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw EOFException()
            got += r
        }
        return b
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k > 0) left -= k else { u8(input); left-- }
        }
    }

    private fun u(b: Byte) = b.toInt() and 0xff
    private fun le32(b: ByteArray, p: Int) = u(b[p]) or (u(b[p + 1]) shl 8) or (u(b[p + 2]) shl 16) or (u(b[p + 3]) shl 24)
    private fun be32(b: ByteArray, p: Int) = (u(b[p]) shl 24) or (u(b[p + 1]) shl 16) or (u(b[p + 2]) shl 8) or u(b[p + 3])
    private fun syncsafe(b: ByteArray, p: Int) = ((u(b[p]) and 0x7f) shl 21) or ((u(b[p + 1]) and 0x7f) shl 14) or ((u(b[p + 2]) and 0x7f) shl 7) or (u(b[p + 3]) and 0x7f)
}
