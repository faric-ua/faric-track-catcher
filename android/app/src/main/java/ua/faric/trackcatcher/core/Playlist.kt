package ua.faric.trackcatcher.core

/**
 * Перехід між записами в плеєрі подробиць (faric 15.10 15:36: «зайшли в одну пісню — а є інші,
 * можемо слухати як у плеєрі, переходити туди-сюди»).
 *
 * Історія показує новіші згори. «Наступна» (⏭) — записана **після** цієї, тобто як в альбомі:
 * BONUS 02 → BONUS 03. «Попередня» (⏮) — записана перед нею. Записи без файлу (пропущені,
 * видалені) перескакуються — їх нема що слухати.
 */
object Playlist {
    /**
     * [newestFirst] — id записів у порядку списку історії (новіші згори), [hasFile] — чи є файл.
     * Повертає id сусіда або null (край списку, або [current] у списку нема).
     */
    fun neighbor(newestFirst: List<Long>, hasFile: (Long) -> Boolean, current: Long, next: Boolean): Long? {
        val playable = newestFirst.filter { it == current || hasFile(it) }
        val i = playable.indexOf(current)
        if (i < 0) return null
        // Новіші згори: наступна за часом — вище в списку (менший індекс).
        val j = if (next) i - 1 else i + 1
        return playable.getOrNull(j)?.takeIf { it != current }
    }

    /**
     * Групи історії (T61, faric 17.10: «пишу альбомами — хочу альбоми прослухати; групувати по сесіях, по альбомах, по всьому»).
     * Порядок груп — як першої появи в [items] (новіші згори → найсвіжіший альбом угорі), усередині — як у [items].
     */
    fun <T> groupInOrder(items: List<T>, key: (T) -> String): List<Pair<String, List<T>>> {
        val groups = LinkedHashMap<String, MutableList<T>>()
        for (it in items) groups.getOrPut(key(it)) { ArrayList() } += it
        return groups.map { (k, v) -> k to v }
    }

    /** Ключ групи без різниці у великих літерах і пробілах по краях: «Neon» = «neon ». */
    fun groupKey(name: String): String = name.trim().lowercase()
}

