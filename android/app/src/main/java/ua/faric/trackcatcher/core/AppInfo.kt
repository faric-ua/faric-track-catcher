package ua.faric.trackcatcher.core

/**
 * Хто записав файл — з версією (faric 20.10 15:27: «пиши в метаданих не лише Track Catcher, а й свою версію, щоб
 * розуміти, хто напортачив і чи записано вже виправленою версією»). Версію ставить застосунок при старті;
 * у тестах ядра її нема — тоді просто «Track Catcher».
 */
object AppInfo {
    const val NAME = "Track Catcher"

    @Volatile var versionName: String = ""
    @Volatile var versionCode: Int = 0

    /** «Track Catcher 0.1.49 (build 50)» — латиницею: тег MP3 (TSSE) пишеться в Latin-1. */
    val encoder: String
        get() = encoderFor(versionName, versionCode)

    fun encoderFor(name: String, code: Int): String = when {
        name.isBlank() -> NAME
        code > 0 -> "$NAME $name (build $code)"
        else -> "$NAME $name"
    }
}
