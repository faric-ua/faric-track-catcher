package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * Обрізати початок / кінець і зробити наростання / затухання в уже записаному файлі (T79, faric 19.10 20:02:
 * «перші 5 секунд — якась інша пісня, потім обрив; хочу в цьому файлі обрізати початок на 3–5 с, або кінець,
 * або затухання спереду й ззаду по секунді, по пів — поштучно чи пачкою»).
 */
object TrimFade {
    /** Як зберегти результат (faric 19.10: «вибір дій з усіх можливих»). */
    enum class Save(val code: String, val label: String) {
        REPLACE_KEEP("keep", "Замінити, а оригінал — у «Оригінали»"),
        NEW_FILE("new", "Новий файл поруч"),
        REPLACE("replace", "Просто замінити");

        companion object {
            fun of(code: String?) = entries.firstOrNull { it.code == code } ?: REPLACE_KEEP
        }
    }

    data class Plan(val cutStartMs: Long = 0, val cutEndMs: Long = 0, val fadeInMs: Long = 0, val fadeOutMs: Long = 0, val save: Save = Save.REPLACE_KEEP) {
        val isEmpty get() = cutStartMs <= 0 && cutEndMs <= 0 && fadeInMs <= 0 && fadeOutMs <= 0

        fun toMap(): Map<String, String> = mapOf(
            "cutStart" to cutStartMs.toString(), "cutEnd" to cutEndMs.toString(),
            "fadeIn" to fadeInMs.toString(), "fadeOut" to fadeOutMs.toString(), "save" to save.code,
        )

        companion object {
            fun fromMap(m: Map<String, String>) = Plan(
                m["cutStart"]?.toLongOrNull() ?: 0, m["cutEnd"]?.toLongOrNull() ?: 0,
                m["fadeIn"]?.toLongOrNull() ?: 0, m["fadeOut"]?.toLongOrNull() ?: 0, Save.of(m["save"]),
            )
        }
    }

    /** Найкоротше, що може лишитись. */
    const val MIN_LEFT_MS = 1_000L

    class TooShort(message: String) : Exception(message)

    /** Які кадри лишаються: [first, first + count). */
    fun range(totalFrames: Long, plan: Plan, sampleRate: Int): Pair<Long, Long> {
        val first = plan.cutStartMs.coerceAtLeast(0) * sampleRate / 1000
        val last = totalFrames - plan.cutEndMs.coerceAtLeast(0) * sampleRate / 1000
        val count = last - first
        if (count < MIN_LEFT_MS * sampleRate / 1000) {
            throw TooShort("після обрізання лишиться менше секунди (пісня ${TimeText.clock(totalFrames * 1000 / sampleRate)})")
        }
        return first to count
    }

    /**
     * Наростання й затухання в шматку 16-бітного PCM [buf] ([lengthBytes] байт, [channels] каналів);
     * [firstFrame] — номер першого кадру шматка у файлі, що виходить ([outFrames] кадрів).
     */
    fun shape(buf: ByteArray, lengthBytes: Int, firstFrame: Long, outFrames: Long, fadeInFrames: Long, fadeOutFrames: Long, channels: Int) {
        if (fadeInFrames <= 0 && fadeOutFrames <= 0) return
        val frameBytes = channels * 2
        val fin = minOf(fadeInFrames, outFrames / 2)
        val fout = minOf(fadeOutFrames, outFrames / 2)
        for (i in 0 until lengthBytes / frameBytes) {
            val f = firstFrame + i
            val fromEnd = outFrames - 1 - f
            var gain = 1.0
            if (fin > 0 && f < fin) gain = f.toDouble() / fin
            if (fout > 0 && fromEnd < fout) gain = minOf(gain, fromEnd.coerceAtLeast(0).toDouble() / fout)
            if (gain >= 1.0) continue
            for (c in 0 until channels) {
                val b = i * frameBytes + c * 2
                val v = ((buf[b + 1].toInt() shl 8) or (buf[b].toInt() and 0xff)).toShort().toInt()
                val out = Math.round(v * gain).toInt().coerceIn(-32768, 32767)
                buf[b] = (out and 0xff).toByte()
                buf[b + 1] = ((out shr 8) and 0xff).toByte()
            }
        }
    }

    // ---------- FLAC: теги й обкладинка зі старого файлу ----------

    /** Блоки метаданих FLAC (тип, вміст) з початку [input]; потік лишається на першому кадрі звуку. */
    fun flacBlocks(input: InputStream): List<Pair<Int, ByteArray>> {
        val magic = readN(input, 4)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") throw IllegalArgumentException("це не FLAC")
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        while (true) {
            val h = u8(input)
            val len = (u8(input) shl 16) or (u8(input) shl 8) or u8(input)
            blocks += (h and 0x7f) to readN(input, len)
            if (h and 0x80 != 0) break
        }
        return blocks
    }

    /**
     * Новий FLAC [encoded] (з новим STREAMINFO), але з усіма іншими блоками старого файлу [oldBlocks]
     * (теги, обкладинка, ReplayGain…) — у [out].
     */
    fun flacWithOldMeta(encoded: InputStream, oldBlocks: List<Pair<Int, ByteArray>>, out: OutputStream) {
        val fresh = flacBlocks(encoded)
        val info = fresh.first { it.first == 0 }
        val blocks = listOf(info) + oldBlocks.filter { it.first != 0 && it.first != 1 } // без старого STREAMINFO й PADDING
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        blocks.forEachIndexed { i, (type, body) ->
            out.write((if (i == blocks.lastIndex) 0x80 else 0) or type)
            out.write((body.size shr 16) and 0xff); out.write((body.size shr 8) and 0xff); out.write(body.size and 0xff)
            out.write(body)
        }
        encoded.copyTo(out, 256 * 1024)
    }

    /** ID3v2 з початку MP3 (разом із заголовком) — щоб перенести в новий файл; порожньо — тегу нема. */
    fun id3Head(input: InputStream): ByteArray {
        val head = ByteArray(10)
        var got = 0
        while (got < 10) { val r = input.read(head, got, 10 - got); if (r < 0) return ByteArray(0); got += r }
        if (String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return ByteArray(0)
        val size = ((head[6].toInt() and 0x7f) shl 21) or ((head[7].toInt() and 0x7f) shl 14) or ((head[8].toInt() and 0x7f) shl 7) or (head[9].toInt() and 0x7f)
        return ByteArrayOutputStream().apply { write(head); write(readN(input, size)) }.toByteArray()
    }

    private fun u8(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException() }

    private fun readN(input: InputStream, n: Int): ByteArray {
        if (n < 0 || n > 32 * 1024 * 1024) throw IllegalArgumentException("блок $n байт — файл пошкоджений?")
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw EOFException()
            got += r
        }
        return b
    }
}
