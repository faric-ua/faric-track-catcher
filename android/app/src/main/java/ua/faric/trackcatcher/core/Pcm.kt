package ua.faric.trackcatcher.core

/** Формат запису: 48 кГц, 16 біт, стерео (рідна частота мікшера Android). */
object Pcm {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val BYTES_PER_FRAME = CHANNELS * BYTES_PER_SAMPLE

    fun framesToMs(frames: Long, sampleRate: Int = SAMPLE_RATE): Long = frames * 1000L / sampleRate

    fun msToFrames(ms: Long, sampleRate: Int = SAMPLE_RATE): Long = ms * sampleRate / 1000L

    /** Рівень шматка PCM у dBFS (RMS). Тиша = -120. */
    fun rmsDb(samples: ShortArray, fromShort: Int, toShort: Int): Double {
        if (toShort <= fromShort) return SILENT_DB
        var sum = 0.0
        for (i in fromShort until toShort) {
            val v = samples[i] / 32768.0
            sum += v * v
        }
        val rms = Math.sqrt(sum / (toShort - fromShort))
        return if (rms <= 1e-6) SILENT_DB else 20.0 * Math.log10(rms)
    }

    /** Пік каналу (0..1) для індикатора рівня. */
    fun channelPeak(samples: ShortArray, lengthShorts: Int, channel: Int, channels: Int = CHANNELS): Float {
        var peak = 0
        var i = channel
        while (i < lengthShorts) {
            val v = Math.abs(samples[i].toInt())
            if (v > peak) peak = v
            i += channels
        }
        return (peak / 32768f).coerceIn(0f, 1f)
    }

    /** 16-бітні семпли (little-endian) з масиву байтів. */
    fun bytesToShorts(src: ByteArray, offset: Int, lengthBytes: Int, dst: ShortArray): Int {
        val count = lengthBytes / 2
        var b = offset
        for (i in 0 until count) {
            dst[i] = ((src[b + 1].toInt() shl 8) or (src[b].toInt() and 0xff)).toShort()
            b += 2
        }
        return count
    }

    fun shortsToBytes(src: ShortArray, offset: Int, lengthShorts: Int, dst: ByteArray): Int {
        var b = 0
        for (i in offset until offset + lengthShorts) {
            val v = src[i].toInt()
            dst[b] = (v and 0xff).toByte()
            dst[b + 1] = ((v shr 8) and 0xff).toByte()
            b += 2
        }
        return b
    }

    const val SILENT_DB = -120.0
}
