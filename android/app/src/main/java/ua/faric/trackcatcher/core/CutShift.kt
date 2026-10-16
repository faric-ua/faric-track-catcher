package ua.faric.trackcatcher.core

import kotlin.math.abs

/**
 * «Зсув розрізу» (T42, faric 14.10 19:45: «знайшли середину — робимо еталоном, і я тоді ставлю по нулях»).
 * На телефоні найкраще різало з +0,5 с — тепер це «0»: різак завжди додає [BASE_MS] до сигналу шторки,
 * а кнопки −1 … +1 с рахуються від нового нуля.
 */
object CutShift {
    const val BASE_MS = 500
    val CHOICES = listOf(-1_000, -500, -200, 0, 200, 500, 1_000)

    /** Скільки насправді зсувати сигнал шторки, мс. */
    fun effective(userMs: Int): Int = BASE_MS + userMs

    /**
     * Збережене до 0.1.25 значення → нове, щоб розріз лишився там самим (було +0,5 → «0»; було 0 → «−0,5»).
     * Значення поза кнопками — до найближчої кнопки.
     */
    fun migrate(oldUserMs: Int): Int = nearest(oldUserMs - BASE_MS)

    fun nearest(ms: Int): Int = CHOICES.minByOrNull { abs(it - ms) } ?: 0
}
