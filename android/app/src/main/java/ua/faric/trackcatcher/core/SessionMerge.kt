package ua.faric.trackcatcher.core

/**
 * Об'єднати кілька сесій в одну (T110, faric 31.10 04:56: «записав одну пісню з Ютуба — сесія закінчилась;
 * як мені їх потім в одну сесію з'єднати»). Лишається найраніша сесія; її початок — найраніший, кінець — найпізніший,
 * усі пісні інших сесій переходять у неї, порожні сесії зникають. Файли не чіпаються.
 */
object SessionMerge {
    data class Session(val id: Long, val startedAt: Long, val stoppedAt: Long)

    data class Plan(val target: Long, val others: List<Long>, val startedAt: Long, val stoppedAt: Long)

    /** null — нема чого об'єднувати (менше двох різних сесій). */
    fun plan(sessions: List<Session>): Plan? {
        val s = sessions.distinctBy { it.id }
        if (s.size < 2) return null
        val t = s.minWithOrNull(compareBy<Session>({ it.startedAt }, { it.id }))!!
        return Plan(t.id, s.filter { it.id != t.id }.map { it.id }.sorted(), s.minOf { it.startedAt }, s.maxOf { it.stoppedAt })
    }
}
