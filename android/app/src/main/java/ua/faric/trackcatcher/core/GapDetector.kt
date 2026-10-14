package ua.faric.trackcatcher.core

/**
 * Чи не загубився звук. Android дає рівно 48 000 кадрів за секунду; якщо потік запису
 * не встиг їх забрати (телефон «задумався»), Android викидає зайве — у файлі «чирк».
 *
 * Рахуємо «відставання» = скільки часу минуло − скільки звуку отримано. Звичайні коливання
 * (порції по 0,1 с) — дрібні; мінімум за 5 с стабільний. Якщо мінімум наступних 5 с
 * більший за попередній на 40+ мс — звук губився.
 */
class GapDetector(
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
    private val windowMs: Long = 5_000,
    private val thresholdMs: Long = 40,
) {
    private var startMs = -1L
    private var frames = 0L
    private var windowStart = 0L
    private var windowMin = Long.MAX_VALUE
    private var baseline = Long.MIN_VALUE

    /** Скільки разів губився звук. */
    var gaps = 0
        private set

    /** Загублено приблизно (мс). */
    var lostMs = 0L
        private set

    /** [nowMs] — монотонний час (мс) одразу після читання [readFrames] кадрів. true — щойно знайдено пропуск. */
    fun onRead(nowMs: Long, readFrames: Int): Boolean {
        if (startMs < 0) {
            startMs = nowMs
            windowStart = nowMs
        }
        frames += readFrames
        val lag = (nowMs - startMs) - frames * 1000L / sampleRate
        if (lag < windowMin) windowMin = lag
        if (nowMs - windowStart < windowMs) return false
        var found = false
        if (baseline != Long.MIN_VALUE && windowMin - baseline >= thresholdMs) {
            gaps++
            lostMs += windowMin - baseline
            found = true
        }
        baseline = if (baseline == Long.MIN_VALUE || found || windowMin < baseline) windowMin else baseline
        windowMin = Long.MAX_VALUE
        windowStart = nowMs
        return found
    }
}
