package ua.faric.trackcatcher.core

/**
 * Повтор тієї самої пісні (repeat one). Плеєри по-різному про це повідомляють:
 * 1) шлють новий стан з позицією «з початку» — [wrapped];
 * 2) мовчать (AIMP, M30s 14.10) — позиція «рахується» далі за тривалість — [check].
 */
class LoopDetector {
    private var base = 0L

    /** Плеєр прислав свіжу позицію — відлік знову від неї. */
    fun reset() {
        base = 0L
    }

    /**
     * Оцінена позиція пішла за кінець пісні (+1,5 с) — почався новий повтор.
     * Повертає позицію в новому повторі або null.
     */
    fun check(estimatedPosMs: Long, durationMs: Long): Long? {
        if (durationMs < MIN_DURATION_MS) return null
        val rel = estimatedPosMs - base
        if (rel >= durationMs + GRACE_MS) {
            base += durationMs
            return estimatedPosMs - base
        }
        return null
    }

    companion object {
        const val MIN_DURATION_MS = 15_000L
        const val GRACE_MS = 1_500L

        /** Де пісня «мала б бути» (за попереднім станом) далеко, а плеєр каже «з початку». */
        fun wrapped(predictedPrevMs: Long?, newPosMs: Long?): Boolean =
            predictedPrevMs != null && newPosMs != null && predictedPrevMs > 20_000 && newPosMs < 3_000
    }
}
