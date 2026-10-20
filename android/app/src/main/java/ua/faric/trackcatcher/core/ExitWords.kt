package ua.faric.trackcatcher.core

/**
 * Чому Android закрив Track Catcher минулого разу — словами (faric 20.10 17:10, M30s: «вмикаю запис, іду в Suno,
 * вертаюсь — кнопка вимкнена»). Коди — `ApplicationExitInfo.REASON_*` (Android 11+); тут без Android, щоб був тест.
 */
object ExitWords {
    const val EXIT_SELF = 1
    const val SIGNALED = 2
    const val LOW_MEMORY = 3
    const val CRASH = 4
    const val CRASH_NATIVE = 5
    const val ANR = 6
    const val INITIALIZATION_FAILURE = 7
    const val PERMISSION_CHANGE = 8
    const val EXCESSIVE_RESOURCE_USAGE = 9
    const val USER_REQUESTED = 10
    const val USER_STOPPED = 11
    const val DEPENDENCY_DIED = 12
    const val OTHER = 13
    const val FREEZER = 14
    const val PACKAGE_STATE_CHANGE = 15
    const val PACKAGE_UPDATED = 16

    /** Причина словами; [description] — те, що дописав Android (англійською), у дужках. */
    fun words(reason: Int, description: String?): String {
        val text = when (reason) {
            LOW_MEMORY -> "Android закрив Track Catcher, бо телефону не вистачило пам'яті (відкрито забагато застосунків)"
            SIGNALED -> "Android сам зупинив Track Catcher (так робить система чи «оптимізація» Samsung)"
            EXCESSIVE_RESOURCE_USAGE -> "Android закрив Track Catcher за «забагато роботи у фоні»"
            CRASH, CRASH_NATIVE -> "Track Catcher закрився з помилкою (це помилка застосунку — надішли цей текст)"
            ANR -> "Track Catcher завис, і Android його закрив"
            USER_REQUESTED, USER_STOPPED -> "Track Catcher закрили вручну («Останні програми» → змахнули, або «Примусово зупинити»)"
            PERMISSION_CHANGE -> "Змінились дозволи Track Catcher — Android його перезапустив"
            PACKAGE_UPDATED, PACKAGE_STATE_CHANGE -> "Track Catcher оновлювали чи вимикали — Android його закрив"
            DEPENDENCY_DIED -> "Закрилась служба, від якої залежав Track Catcher"
            FREEZER -> "Android «заморозив» Track Catcher у фоні"
            INITIALIZATION_FAILURE -> "Track Catcher не зміг запуститись"
            EXIT_SELF -> "Track Catcher закрився сам"
            else -> "Android закрив Track Catcher (причина: інша)"
        }
        val d = description?.trim().orEmpty()
        return if (d.isEmpty()) text else "$text ($d)"
    }

    /** Текст на екран «Запис», коли запис обірвався разом із застосунком. */
    fun recordingCut(why: String?, crash: String?): String = buildString {
        append("Минулий запис обірвався: ")
        append(why ?: "Android закрив Track Catcher, причину не сказав")
        append(". Записане до цього збережено в «Історії».")
        if (!crash.isNullOrBlank()) append("\n\n").append(crash.trim())
    }
}
