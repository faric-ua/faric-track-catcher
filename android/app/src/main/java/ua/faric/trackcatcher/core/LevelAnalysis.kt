package ua.faric.trackcatcher.core

data class AudioStats(
    val totalFrames: Long,
    val startFrame: Long,
    val endFrame: Long,
    val audibleMs: Long,
    val peakDb: Double,
) {
    val keptFrames: Long get() = endFrame - startFrame
}

/** Обрізає цифрову тишу на початку й у кінці шматка (лишає 0,2 с запасу). */
object LevelAnalysis {
    const val BLOCK_MS = 10
    const val SILENCE_DB = -60.0
    const val PAD_MS = 200

    fun blockFrames(sampleRate: Int = Pcm.SAMPLE_RATE) = sampleRate * BLOCK_MS / 1000

    fun trim(blockDb: DoubleArray, totalFrames: Long, sampleRate: Int = Pcm.SAMPLE_RATE): AudioStats {
        val bf = blockFrames(sampleRate).toLong()
        val peak = blockDb.maxOrNull() ?: Pcm.SILENT_DB
        val first = blockDb.indexOfFirst { it > SILENCE_DB }
        if (first < 0) return AudioStats(totalFrames, 0L, totalFrames, 0L, peak)
        val last = blockDb.indexOfLast { it > SILENCE_DB }
        val pad = PAD_MS / BLOCK_MS
        val start = maxOf(0, first - pad) * bf
        val endBlock = last + 1 + pad
        val end = if (endBlock >= blockDb.size) totalFrames else minOf(totalFrames, endBlock * bf)
        val audible = blockDb.count { it > SILENCE_DB } * BLOCK_MS.toLong()
        return AudioStats(totalFrames, start, end, audible, peak)
    }

    /**
     * Зрізати ще [edgeMs] з початку й кінця (налаштування «Зрізати краї»). Якщо після цього
     * лишиться менше 1 с — не зрізаємо (короткий шматок і так піде в «пропущено»).
     */
    fun trimEdges(stats: AudioStats, edgeMs: Int, sampleRate: Int = Pcm.SAMPLE_RATE): AudioStats {
        if (edgeMs <= 0) return stats
        val edge = sampleRate.toLong() * edgeMs / 1000
        val start = stats.startFrame + edge
        val end = stats.endFrame - edge
        if (end - start < sampleRate) return stats
        return stats.copy(startFrame = start, endFrame = end)
    }
}
