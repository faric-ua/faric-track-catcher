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

    /**
     * Номери для цілого списку назв по порядку запису. Та сама пісня вдруге (неповна, потім повна; альбом по колу) —
     * той самий номер і далі не зсуває (faric 18.10 03:35: «записував 10 треків, а BONUS 3 під номером 16»).
     */
    fun all(titles: List<String>): List<Int> {
        val seen = HashMap<String, Int>()
        val fresh = ArrayList<Int>() // номери перших появ — від них рахується «наступний»
        return titles.map { t ->
            val key = t.trim().lowercase()
            seen[key] ?: next(t, fresh).also { n -> seen[key] = n; fresh += n }
        }
    }
}
