package ua.faric.trackcatcher.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * Гучність пісні «за відчуттям» (EBU R128 / ITU-R BS.1770-4) — для мітки ReplayGain 2.0 (T63).
 *
 * Звук **не змінюється**: застосунок лише міряє й дописує в теги файлу, наскільки пісня голосна.
 * faric 17.10: «не хочу нормалізації — самий кайф цих хвиль»; тому це вимкнена опція, а вирівнює
 * (або ні) уже плеєр, у якому ввімкнено ReplayGain — однаково на всю пісню, піки не зрізаються.
 *
 * Як міряє: К-фільтр (полиця +4 дБ над ~1,7 кГц і зріз низу під ~38 Гц — так вухо чує гучність),
 * блоки по 400 мс з кроком 100 мс; тиша (< −70 LUFS) і тихі місця (на 10 LU нижче середнього) не рахуються.
 * Коефіцієнти — з BS.1770 для 48 кГц (застосунок пише лише 48 кГц, [Pcm.SAMPLE_RATE]).
 */
class Loudness {
    private val ch = Array(Pcm.CHANNELS) { Channel() }
    private var sub = DoubleArray(1024) // енергія кожних 100 мс (сума каналів)
    private var subCount = 0
    private var acc = 0.0
    private var accFrames = 0
    private var peak = 0

    /** [n] байт 16-біт стерео (як у файлі шматка). */
    fun feed(buf: ByteArray, n: Int) {
        var i = 0
        val whole = n - n % Pcm.BYTES_PER_FRAME
        while (i < whole) {
            var e = 0.0
            for (c in 0 until Pcm.CHANNELS) {
                val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                i += 2
                val a = abs(s)
                if (a > peak) peak = a
                val y = ch[c].filter(s / FULL)
                e += y * y
            }
            acc += e
            if (++accFrames == STEP_FRAMES) {
                if (subCount == sub.size) sub = sub.copyOf(sub.size * 2)
                sub[subCount++] = acc / STEP_FRAMES
                acc = 0.0
                accFrames = 0
            }
        }
    }

    /** null — пісня коротша за 0,4 с або сама тиша: мітку не пишемо. */
    fun result(): Result? {
        val blocks = subCount - BLOCK_STEPS + 1
        if (blocks <= 0) return null
        val z = DoubleArray(blocks) { j -> (0 until BLOCK_STEPS).sumOf { sub[j + it] } / BLOCK_STEPS }
        val loud = z.filter { lufs(it) > ABSOLUTE_GATE }
        if (loud.isEmpty()) return null
        val relative = lufs(loud.average()) - RELATIVE_GATE_LU
        val gated = loud.filter { lufs(it) > relative }
        return Result(lufs(gated.average()), peak / FULL)
    }

    data class Result(val lufs: Double, val peak: Double) {
        /** ReplayGain 2.0: на скільки дБ плеєр має змінити гучність, щоб пісня звучала як −18 LUFS. */
        val gainDb: Double get() = REFERENCE_LUFS - lufs

        /** Теги FLAC (Vorbis) і MP3 (ID3 TXXX) — ті самі назви, їх читають foobar2000, AIMP, Poweramp, VLC. */
        fun tags(): List<Pair<String, String>> = listOf(
            "REPLAYGAIN_TRACK_GAIN" to String.format(Locale.ROOT, "%.2f dB", gainDb),
            "REPLAYGAIN_TRACK_PEAK" to String.format(Locale.ROOT, "%.6f", peak),
            "REPLAYGAIN_REFERENCE_LOUDNESS" to String.format(Locale.ROOT, "%.1f LUFS", REFERENCE_LUFS),
        )
    }

    /** Два біквади К-фільтра одного каналу. */
    private class Channel {
        private var x1 = 0.0; private var x2 = 0.0; private var m1 = 0.0; private var m2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0

        fun filter(x: Double): Double {
            val m = S_B0 * x + S_B1 * x1 + S_B2 * x2 - S_A1 * m1 - S_A2 * m2
            x2 = x1; x1 = x
            val y = m - 2.0 * m1 + m2 - H_A1 * y1 - H_A2 * y2
            m2 = m1; m1 = m
            y2 = y1; y1 = y
            return y
        }
    }

    companion object {
        const val REFERENCE_LUFS = -18.0
        const val ABSOLUTE_GATE = -70.0
        const val RELATIVE_GATE_LU = 10.0
        private const val FULL = 32768.0
        private const val STEP_FRAMES = Pcm.SAMPLE_RATE / 10 // 100 мс
        private const val BLOCK_STEPS = 4 // 400 мс

        // BS.1770-4, 48 кГц: полиця (етап 1) і зріз низу (етап 2; b = 1, −2, 1).
        private const val S_B0 = 1.53512485958697
        private const val S_B1 = -2.69169618940638
        private const val S_B2 = 1.19839281085285
        private const val S_A1 = -1.69065929318241
        private const val S_A2 = 0.73248077421585
        private const val H_A1 = -1.99004745483398
        private const val H_A2 = 0.99007225036621

        fun lufs(meanSquare: Double): Double = -0.691 + 10.0 * log10(max(meanSquare, 1e-20))
    }
}
