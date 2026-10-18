package ua.faric.trackcatcher.core

/**
 * Номер пісні в альбомі (T68, faric 17.10 20:31: «якщо можна номер витягнути — пишемо номер; у назві буде
 * TRACK 01, а буде й BONUS 01 — напевно, порядковий писати»).
 *
 * Правило: «TRACK 10» (чи «Track 3», «ТРЕК 07») у назві — це номер 10. Інакше (BONUS 01, звичайна назва) —
 * наступний по порядку після попередніх пісень цього альбому в цьому записі: після TRACK 12 бонус стане 13.
 */
object TrackNumber {
    private val TRACK = Regex("""(?<![\p{L}\d])(?:TRACK|TRK|ТРЕК|ТРЕК№)\s*№?\s*0*(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

    fun fromTitle(title: String): Int? = TRACK.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 }

    /** [previous] — номери попередніх пісень того самого альбому в цьому записі, по порядку. */
    fun next(title: String, previous: List<Int>): Int = fromTitle(title) ?: ((previous.lastOrNull() ?: 0) + 1)

    private val BONUS = Regex("""(?<![\p{L}\d])(?:BONUS|БОНУС)\s*(?:TRACK\s*)?№?\s*0*(\d{1,3})(?!\d)""", RegexOption.IGNORE_CASE)

    /** «BONUS 03» → 3. */
    fun bonusOf(title: String): Int? = BONUS.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..999 }

    /**
     * Номери для альбому — назви по порядку запису, з повторами (faric 18.10 04:11: «де TRACK 10, хоч він двічі
     * повторюється, — номер 10; BONUS 03 — 13; пробіжиться й передивиться»):
     * «TRACK 10» → 10; «BONUS 03» → найбільший TRACK альбому + 3; інша назва → наступний по порядку.
     * Та сама назва вдруге — той самий номер, нічого не зсуває.
     */
    fun all(titles: List<String>): List<Int> {
        val maxTrack = titles.mapNotNull { fromTitle(it) }.maxOrNull() ?: 0
        val seen = HashMap<String, Int>()
        var last = 0
        return titles.map { t ->
            val key = t.trim().lowercase()
            seen[key] ?: run {
                val n = fromTitle(t) ?: bonusOf(t)?.takeIf { maxTrack > 0 }?.let { maxTrack + it } ?: (last + 1)
                seen[key] = n
                last = n
                n
            }
        }
    }
}
