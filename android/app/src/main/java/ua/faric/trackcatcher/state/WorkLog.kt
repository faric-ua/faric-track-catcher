package ua.faric.trackcatcher.state

import java.io.File
import ua.faric.trackcatcher.core.LogText

/**
 * Журнал роботи (T108, faric 29.10 16:57). Файл `journal.txt` усередині застосунку: старт, запис, збереження пісень,
 * фонова робота з файлами (кожен крок і скільки тривав), помилки й збої. Найновіше — щоразу в автокопії на Диску
 * (поле `journal`), звідки Claude читає сам; у «Налаштуваннях» → «Про застосунок» → «Журнал роботи» — переглянути
 * й скопіювати.
 *
 * Пише лише в цей файл (жодної мережі): записи лишаються на телефоні, поки власник сам не вибрав автокопію.
 */
object WorkLog {
    @Volatile private var file: File? = null
    private val lock = Any()

    /** Попередження чи помилка — автокопія піде раніше (ставить [ua.faric.trackcatcher.TrackCatcherApp]). */
    @Volatile var onAlarm: (() -> Unit)? = null

    /** Останній крок фонової роботи (для сторожа «зависло» в [ua.faric.trackcatcher.capture.FlacBatchService]). */
    @Volatile var stepAt: Long = 0L
        private set
    @Volatile var stepWhat: String = ""
        private set

    fun install(dir: File) {
        file = File(dir, "journal.txt")
    }

    fun i(tag: String, message: String) = put('I', tag, message)

    fun w(tag: String, message: String) {
        put('W', tag, message)
        alarm()
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        put('E', tag, if (t == null) message else message + "\n" + LogText.stack(t))
        alarm()
    }

    /** Крок фонової роботи: у журнал і «живий» для сторожа. */
    fun step(tag: String, what: String) {
        alive(what)
        put('I', tag, what)
    }

    /** Робота рухається (поступ), без рядка в журналі. */
    fun alive(what: String) {
        stepAt = System.currentTimeMillis()
        stepWhat = what
    }

    /** Фонова робота скінчилась — сторож більше не дивиться. */
    fun idle() {
        stepAt = 0L
    }

    fun read(): String = synchronized(lock) { runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty() }

    fun tail(maxBytes: Int = LogText.BACKUP_BYTES): List<String> = LogText.tailLines(read(), maxBytes)

    private fun alarm() {
        runCatching { onAlarm?.invoke() }
    }

    private fun put(level: Char, tag: String, message: String) {
        val f = file ?: return
        val line = LogText.line(System.currentTimeMillis(), level, tag, Thread.currentThread().name, message)
        synchronized(lock) {
            runCatching {
                f.appendText(line, Charsets.UTF_8)
                if (f.length() > LogText.MAX_BYTES) f.writeText(LogText.keepTail(f.readText(), LogText.KEEP_BYTES), Charsets.UTF_8)
            }
        }
    }
}
