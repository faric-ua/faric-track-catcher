package ua.faric.trackcatcher.core

/**
 * Зайвий хвіст у назві пісні (T71, faric 18.10 13:34–14:13). Suno в шторці буває дописує до назви назву іншої
 * пісні — тієї, з якої почалась черга: «🎵 BONUS 01 — X. ⚡ 🎵 TRACK 04 — PHOTON HIGHWAY. ⚡», а сама TRACK 04 —
 * двічі поспіль. У самому Suno назва одна (відео 13:59). Звідси два правила:
 * - назва «X X» (та сама двічі через пробіл) — це X, а X — хвіст, який Suno дописує й до наступних пісень;
 * - хвіст обрізається, лише коли стоїть окремо (перед ним пробіл) і перед ним лишається своя назва.
 */
object TitleTail {
    /** Хвіст — щонайменше стільки літер чи цифр: «⚡» чи «(2)» хвостом не вважаються. */
    private const val MIN_TAIL = 6

    private val SEP = charArrayOf('·', '|', '•', '-', '–', '—', '/', ',', ';', ':')

    private fun letters(s: String) = s.count { it.isLetterOrDigit() }

    private fun beforeTail(s: String) = s.trimEnd().trimEnd(*SEP).trimEnd()

    /** «X X» → X (та сама назва двічі поспіль). Інакше null. */
    fun doubled(title: String): String? {
        val t = title.trim()
        for (i in t.indices) {
            if (!t[i].isWhitespace()) continue
            val a = beforeTail(t.substring(0, i))
            if (letters(a) >= MIN_TAIL && a == t.substring(i).trim()) return a
        }
        return null
    }

    /** Назва без [tail] у кінці; null — хвоста там нема (чи він не окремо, чи без нього нічого не лишається). */
    fun strip(title: String, tail: String): String? {
        val t = title.trim()
        val k = tail.trim()
        if (k.isEmpty() || t.length <= k.length || !t.endsWith(k)) return null
        val cut = t.length - k.length
        if (!t[cut - 1].isWhitespace()) return null
        return beforeTail(t.substring(0, cut)).takeIf { letters(it) > 0 }
    }

    /**
     * Хвіст у назвах альбому: спершу «X X» (найнадійніше), інакше — однаковий кінець щонайменше двох різних назв
     * (той, що в більшості; з рівних — що починається, як назва, потім найдовший). null — не знайдено.
     */
    fun detect(titles: List<String>): String? {
        val distinct = titles.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        distinct.firstNotNullOfOrNull { doubled(it) }?.let { return it }
        val counts = HashMap<String, Int>()
        for (t in distinct) {
            val seen = HashSet<String>()
            for (i in 1 until t.length) {
                if (!t[i - 1].isWhitespace() || t[i].isWhitespace()) continue
                val s = t.substring(i)
                if (letters(s) >= MIN_TAIL && strip(t, s) != null && seen.add(s)) counts[s] = (counts[s] ?: 0) + 1
            }
        }
        // З рівних — той, що починається так само, як самі назви («🎵 TRACK 04…», а не «⚡ 🎵 TRACK 04…» чи «HIGHWAY…»).
        fun firstWord(s: String) = s.trim().substringBefore(' ')
        val starts = distinct.map { firstWord(it) }.toSet()
        return counts.filter { it.value >= 2 }.entries
            .maxWithOrNull(compareBy({ it.value }, { firstWord(it.key) in starts }, { it.key.length }))?.key
    }

    /** Назва нової пісні під час запису: «X X» → X; хвіст з [known] (уже бачені чи підтверджені) — геть. */
    fun clean(title: String, known: Collection<String>): String {
        doubled(title)?.let { return it }
        for (k in known.filter { letters(it) >= MIN_TAIL }.sortedByDescending { it.length }) strip(title, k)?.let { return it }
        return title
    }

    /**
     * Нове ім'я файлу після зміни назви: лише якщо старе ім'я зроблене з цієї назви за нашим правилом
     * («Виконавець — Назва», можливо « (2)»), зі смайликами чи без. null — ім'я не наше чи не міняється.
     */
    fun renamed(oldName: String, artist: String, oldTitle: String, newTitle: String): String? {
        val ext = oldName.substringAfterLast('.', "")
        if (ext.isEmpty()) return null
        val base = oldName.removeSuffix(".$ext")
        for (plain in listOf(true, false)) {
            val was = FileNames.baseName(TrackMeta(artist, oldTitle), plain)
            val suffix = when {
                base == was -> ""
                base.startsWith("$was (") && base.endsWith(")") &&
                    base.substring(was.length + 2, base.length - 1).let { n -> n.isNotEmpty() && n.all { it.isDigit() } } -> base.substring(was.length)
                else -> continue
            }
            val now = FileNames.baseName(TrackMeta(artist, newTitle), plain)
            return if (now == was) null else "$now$suffix.$ext"
        }
        return null
    }
}
