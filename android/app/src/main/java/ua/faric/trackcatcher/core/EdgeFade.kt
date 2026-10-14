package ua.faric.trackcatcher.core

/**
 * Плавне наростання на початку й затухання в кінці файлу: файл, що починається чи
 * закінчується не в нулі, на слух дає «чирк». v0.1.10 — 10 мс; faric 14.10 05:16 попросив
 * «з нуля до гучності й до нуля» на пів секунди — 500 мс (на початку файлу ще 0,2 с тиші-запасу).
 */
object EdgeFade {
    const val FADE_MS = 500

    fun frames(sampleRate: Int = Pcm.SAMPLE_RATE, fadeMs: Int = FADE_MS) = (sampleRate.toLong() * fadeMs / 1000).toInt()

    /**
     * Обробляє шматок 16-бітного стерео PCM (little-endian) у [buf] довжиною [lengthBytes].
     * [firstFrame] — номер першого кадру шматка від початку файлу, [totalFrames] — кадрів у файлі.
     */
    fun apply(
        buf: ByteArray,
        lengthBytes: Int,
        firstFrame: Long,
        totalFrames: Long,
        fadeFrames: Int = frames(),
        channels: Int = Pcm.CHANNELS,
    ) {
        if (fadeFrames <= 0 || totalFrames <= 0) return
        val frameBytes = channels * Pcm.BYTES_PER_SAMPLE
        val count = lengthBytes / frameBytes
        val fade = minOf(fadeFrames.toLong(), totalFrames / 2).coerceAtLeast(1L)
        for (i in 0 until count) {
            val f = firstFrame + i
            val fromEnd = totalFrames - 1 - f
            val gain = when {
                f < fade -> f.toDouble() / fade
                fromEnd < fade -> fromEnd.toDouble() / fade
                else -> continue
            }
            for (c in 0 until channels) {
                val b = i * frameBytes + c * Pcm.BYTES_PER_SAMPLE
                val v = ((buf[b + 1].toInt() shl 8) or (buf[b].toInt() and 0xff)).toShort().toInt()
                val out = Math.round(v * gain).toInt().coerceIn(-32768, 32767)
                buf[b] = (out and 0xff).toByte()
                buf[b + 1] = ((out shr 8) and 0xff).toByte()
            }
        }
    }
}
