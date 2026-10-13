package ua.faric.trackcatcher.core

data class CutResult(val frame: Int, val bySilence: Boolean)

/**
 * Де різати між піснями. Сигнал зі шторки буває на ~0,5 с раніше чи пізніше
 * за справжній кінець, тому шукаємо тиху ділянку біля сигналу і ріжемо посередині неї.
 * Немає тиші (пісні плавно перетікають) — ріжемо точно по сигналу.
 */
object SilenceCut {
    const val QUIET_DB = -45.0
    const val BLOCK_MS = 10
    const val MIN_QUIET_MS = 60

    fun findCut(
        samples: ShortArray,
        channels: Int,
        sampleRate: Int,
        targetFrame: Int,
        quietDb: Double = QUIET_DB,
        minQuietMs: Int = MIN_QUIET_MS,
    ): CutResult {
        val frames = samples.size / channels
        val target = targetFrame.coerceIn(0, frames)
        val blockFrames = maxOf(1, sampleRate * BLOCK_MS / 1000)
        val blocks = frames / blockFrames
        if (blocks == 0) return CutResult(target, false)
        val quiet = BooleanArray(blocks) { b ->
            Pcm.rmsDb(samples, b * blockFrames * channels, (b + 1) * blockFrames * channels) < quietDb
        }
        val minBlocks = maxOf(1, minQuietMs / BLOCK_MS)
        var bestStart = -1
        var bestEnd = -1
        var bestDist = Long.MAX_VALUE
        var b = 0
        while (b < blocks) {
            if (!quiet[b]) {
                b++
                continue
            }
            val start = b
            while (b < blocks && quiet[b]) b++
            val end = b
            if (end - start < minBlocks) continue
            val s = start * blockFrames
            val e = end * blockFrames
            val dist = when {
                target < s -> (s - target).toLong()
                target > e -> (target - e).toLong()
                else -> 0L
            }
            val longer = end - start > bestEnd - bestStart
            if (dist < bestDist || (dist == bestDist && longer)) {
                bestStart = start
                bestEnd = end
                bestDist = dist
            }
        }
        if (bestStart < 0) return CutResult(target, false)
        val center = (bestStart + bestEnd) * blockFrames / 2
        return CutResult(center.coerceIn(0, frames), true)
    }
}
