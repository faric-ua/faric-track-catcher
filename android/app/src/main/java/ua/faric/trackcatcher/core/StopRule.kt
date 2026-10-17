package ua.faric.trackcatcher.core

/**
 * «Зупинитись через N хвилин» (T60, faric 17.10: «у Suno в кінці альбому пише, скільки хвилин буде грати»).
 * Зупинка — на зміні пісні, а не посеред: перша нова пісня, що почалась не раніше ніж за [TOLERANCE_MS]
 * до кінця часу, вже не пишеться (плеєр — на паузу, запис — стоп). Запас потрібен, бо Suno округлює
 * тривалість альбому до хвилини: остання пісня альбому може скінчитись трохи раніше за «47 хв».
 * Рахується записаний звук (паузи плеєра не враховуються).
 */
object StopRule {
    const val TOLERANCE_MS = 45_000L

    fun limitMs(minutes: Int): Long = minutes * 60_000L

    /** Почалась нова пісня на [recordedMs] записаного звуку: зупинятись? */
    fun stopAtTrackChange(recordedMs: Long, minutes: Int): Boolean =
        minutes > 0 && recordedMs >= limitMs(minutes) - TOLERANCE_MS

    /** Час уже вийшов (для напису «дограю цю пісню й зупинюсь»). */
    fun timeUp(recordedMs: Long, minutes: Int): Boolean = minutes > 0 && recordedMs >= limitMs(minutes)
}
