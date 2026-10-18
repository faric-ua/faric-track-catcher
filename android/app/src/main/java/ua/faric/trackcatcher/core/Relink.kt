package ua.faric.trackcatcher.core

import kotlin.math.abs

/**
 * Перейменовані файли (T77, faric 18.10 16:18: «у Suno було неправильне ім'я — я файли перейменував, а Track Catcher
 * тепер каже, що їх не бачить. Хай скаже: є файли, яких нема, є нові — що з ними зробити; змінити в історії»).
 *
 * Запис, чийого файла нема на місці, шукаємо серед **нових** файлів тієї самої папки (яких нема в історії):
 * те саме розширення і та сама тривалість (±1,5 с); з кількох — той, у кого назва в тегах та сама, що в історії.
 * Розмір не годиться: після «Перевірити номери» / «Прибрати зайве з назв» теги змінились, а з ними й розмір.
 */
object Relink {
    const val TOLERANCE_MS = 1_500L

    /** Файл у папці, якого нема в історії. */
    data class OnDisk(val uri: String, val name: String, val durationMs: Long, val title: String?)

    /** Файл запису, якого нема на місці. [mp3] — це MP3 запису (інакше — файл без втрат). */
    data class Lost(val rowId: Long, val mp3: Boolean, val name: String, val lengthMs: Long, val title: String)

    data class Match(val lost: Lost, val found: OnDisk)

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    private fun near(l: Lost, f: OnDisk) = ext(l.name) == ext(f.name) && f.durationMs > 0 && abs(f.durationMs - l.lengthMs) <= TOLERANCE_MS

    fun match(lost: List<Lost>, fresh: List<OnDisk>): List<Match> {
        val used = HashSet<String>()
        val done = HashSet<Lost>()
        val out = ArrayList<Match>()
        // 1) Та сама тривалість і та сама назва в тегах — найнадійніше.
        for (l in lost) {
            val pick = fresh.filter { it.uri !in used && near(l, it) && it.title?.trim() == l.title.trim() }.singleOrNull() ?: continue
            used += pick.uri; done += l; out += Match(l, pick)
        }
        // 2) Лише тривалість — коли пара однозначна в обидва боки (назву в тегах могли змінити разом з іменем).
        for (l in lost) {
            if (l in done) continue
            val pick = fresh.filter { it.uri !in used && near(l, it) }.singleOrNull() ?: continue
            if (lost.count { it !in done && near(it, pick) } != 1) continue
            used += pick.uri; done += l; out += Match(l, pick)
        }
        return out
    }

    /** Назва пісні з імені файлу: «Виконавець — Назва (2).flac» → «Назва». */
    fun titleFromName(name: String, artist: String): String {
        val base = name.substringBeforeLast('.').replace(Regex("""\s\(\d{1,3}\)$"""), "").trim()
        for (a in listOf(artist.trim(), FileNames.stripSymbols(artist))) {
            if (a.isNotBlank() && base.startsWith("$a — ")) return base.removePrefix("$a — ").trim()
        }
        return base
    }

    /** Чи ім'я файлу каже іншу назву, ніж історія (смайлики й заборонені знаки в імені не рахуються). */
    fun titleChanged(historyTitle: String, fromName: String): Boolean {
        val n = fromName.trim()
        return n.isNotEmpty() && n != FileNames.sanitize(historyTitle) && n != FileNames.sanitize(FileNames.stripSymbols(historyTitle)) && n != historyTitle.trim()
    }
}
