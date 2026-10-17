package ua.faric.trackcatcher.core

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * WAV → FLAC з обкладинкою (T69, faric 17.10 20:23: «інші альбоми писав у WAV — там не було картинки; як їх повернути?»).
 * Звук той самий до біта (FLAC без втрат), а в заголовок ідуть теги й обкладинка, яку Track Catcher зберіг в історії.
 * WAV не чіпаємо: FLAC з'являється поруч.
 */
object WavToFlac {
    /** Чому цей WAV не перетворити (null — можна). Наш кодувальник — 16 біт стерео. */
    fun problem(info: FileTags.FileInfo): String? = when {
        info.format != "WAV" -> "це не WAV"
        info.bits != 16 -> "WAV ${info.bits} біт — вмію лише 16"
        info.channels != 2 -> "WAV ${if (info.channels == 1) "моно" else "${info.channels} канали"} — вмію лише стерео"
        info.sampleRate !in 8_000..192_000 -> "незвична частота ${info.sampleRate} Гц"
        info.dataBytes <= 0 -> "у WAV нема звуку"
        else -> null
    }

    /**
     * Два проходи по звуку, як і під час запису (T55): спершу MD5 ([md5Pass]), потім кодування ([encodePass]).
     * Кожен потік — WAV з самого початку. [onProgress] — частка 0…1.
     */
    fun encode(
        info: FileTags.FileInfo,
        md5Pass: InputStream,
        encodePass: InputStream,
        tags: List<Pair<String, String>>,
        jpeg: ByteArray?,
        out: OutputStream,
        onProgress: (Float) -> Unit = {},
    ) {
        problem(info)?.let { throw IllegalArgumentException(it) }
        val frames = info.dataBytes / Pcm.BYTES_PER_FRAME
        val bytes = frames * Pcm.BYTES_PER_FRAME
        val md5 = MessageDigest.getInstance("MD5")
        pcm(md5Pass, info.dataOffset, bytes) { b, n -> md5.update(b, 0, n) }
        val enc = FlacEncoder(out, frames, md5.digest(), tags, jpeg, info.sampleRate)
        var done = 0L
        pcm(encodePass, info.dataOffset, bytes) { b, n ->
            enc.encode(b, 0, n)
            done += n
            onProgress(done.toFloat() / bytes)
        }
        enc.finish()
    }

    private fun pcm(input: InputStream, offset: Long, bytes: Long, sink: (ByteArray, Int) -> Unit) {
        var skip = offset
        while (skip > 0) {
            val k = input.skip(skip)
            if (k <= 0) { if (input.read() < 0) throw java.io.EOFException("WAV коротший, ніж у заголовку"); skip-- } else skip -= k
        }
        val buf = ByteArray(64 * 1024)
        var left = bytes
        while (left > 0) {
            var got = 0
            val want = minOf(left, buf.size.toLong()).toInt()
            while (got < want) {
                val r = input.read(buf, got, want - got)
                if (r < 0) break
                got += r
            }
            val whole = got - got % Pcm.BYTES_PER_FRAME
            if (whole > 0) sink(buf, whole)
            left -= got
            if (got < want) throw java.io.EOFException("WAV коротший, ніж у заголовку")
        }
    }
}
