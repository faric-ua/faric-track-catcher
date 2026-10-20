package ua.faric.trackcatcher.core

/**
 * «Тріски» — дірки чистої тиші посеред музики (T88, faric 20.10 14:13–14:36: «записала погано», «ти правильно знайшов
 * ті тріски»). Коли плеєр не встигає віддати звук, Android на мить підставляє нулі: у файлі — шматочки по 1–30 мс,
 * де обидва канали рівно 0, а навколо гучна музика. Такого в живій музиці не буває (навіть тиша в пісні — не рівно
 * нуль на всі 48 кадрів поспіль посеред гучного), тож шукач не плутає їх з паузами чи ударами.
 *
 * Потоком: [feed] шматками 16-біт стерео LE, потім [result].
 */
class Holes(private val sampleRate: Int = Pcm.SAMPLE_RATE) {
    data class Hole(val startMs: Long, val ms: Int)

    /** Кілька дірок поруч (до 2 с між ними) — одне місце. */
    data class Place(val startMs: Long, val count: Int, val ms: Int)

    private val block = sampleRate / 100 // 10 мс
    private val blockEnergy = ArrayList<Float>()
    private var blockSum = 0.0
    private var blockFill = 0
    private var frame = 0L
    private var zeroStart = -1L
    private val runs = ArrayList<LongArray>() // [початок, довжина] у кадрах

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        var i = offset
        val end = offset + length - 3
        while (i <= end) {
            val l = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            val r = ((bytes[i + 3].toInt() shl 8) or (bytes[i + 2].toInt() and 0xff)).toShort().toInt()
            onFrame(l, r)
            i += 4
        }
    }

    private fun onFrame(l: Int, r: Int) {
        if (l == 0 && r == 0) {
            if (zeroStart < 0) zeroStart = frame
        } else if (zeroStart >= 0) {
            closeRun()
        }
        val m = (l + r) / 2.0
        blockSum += m * m
        if (++blockFill == block) {
            blockEnergy += (blockSum / block).toFloat()
            blockSum = 0.0
            blockFill = 0
        }
        frame++
    }

    private fun closeRun() {
        val len = frame - zeroStart
        if (len >= MIN_FRAMES) runs += longArrayOf(zeroStart, len)
        zeroStart = -1
    }

    /** Середня гучність (дБ) блоків 10 мс від [fromFrame] до [toFrame]; -200 — нема чого міряти. */
    private fun levelDb(fromFrame: Long, toFrame: Long): Double {
        val a = (fromFrame / block).toInt().coerceAtLeast(0)
        val b = (toFrame / block).toInt().coerceAtMost(blockEnergy.size)
        if (b <= a) return -200.0
        var s = 0.0
        for (k in a until b) s += blockEnergy[k]
        val rms = Math.sqrt(s / (b - a)) / 32768.0
        return if (rms <= 1e-9) -200.0 else 20 * Math.log10(rms)
    }

    /** Дірки посеред гучної музики (з обох боків гучніше [LOUD_DB] за 100 мс). */
    fun result(): List<Hole> {
        if (zeroStart >= 0) closeRun()
        val around = sampleRate / 10L
        return runs.filter { (start, len) ->
            levelDb(start - around, start) > LOUD_DB && levelDb(start + len, start + len + around) > LOUD_DB
        }.map { (start, len) -> Hole(start * 1000 / sampleRate, (len * 1000 / sampleRate).toInt().coerceAtLeast(1)) }
    }

    companion object {
        /** Найкоротша дірка — 1 мс (48 кадрів поспіль рівно нуль в обох каналах). */
        const val MIN_FRAMES = 48L
        const val LOUD_DB = -45.0

        fun places(holes: List<Hole>, gapMs: Long = 2_000): List<Place> {
            val out = ArrayList<Place>()
            var last = Long.MIN_VALUE
            for (h in holes.sortedBy { it.startMs }) {
                if (out.isNotEmpty() && h.startMs - last <= gapMs) {
                    val p = out.removeAt(out.lastIndex)
                    out += p.copy(count = p.count + 1, ms = p.ms + h.ms)
                } else {
                    out += Place(h.startMs, 1, h.ms)
                }
                last = h.startMs + h.ms
            }
            return out
        }

        /** Для бази: «115610:27:369;126780:1:5»; порожньо — перевірено, тріскок нема. */
        fun encode(places: List<Place>): String = places.joinToString(";") { "${it.startMs}:${it.count}:${it.ms}" }

        fun decode(s: String?): List<Place> = s.orEmpty().split(';').mapNotNull { p ->
            val f = p.split(':')
            if (f.size != 3) null else Place(f[0].toLongOrNull() ?: return@mapNotNull null, f[1].toIntOrNull() ?: 1, f[2].toIntOrNull() ?: 0)
        }

        /** «1:55 (27 дірок, 0,37 с) · 2:06 · 5:12». */
        fun describe(places: List<Place>): String = places.joinToString(" · ") { p ->
            TimeText.clock(p.startMs) + if (p.count > 1) " (${TimeText.plural(p.count, "дірка", "дірки", "дірок")}, ${"%.2f".format(p.ms / 1000.0).replace('.', ',')} с)" else ""
        }
    }
}
