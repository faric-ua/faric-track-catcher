package ua.faric.trackcatcher.core

import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Рядки журналу роботи (T108, faric 29.10 16:57: «давай додамо журнал помилок, щоб ти сам міг перевіряти, що
 * відбувається, бо ми так будемо довго розбиратися»). Без Android — перевіряється юніт-тестом.
 *
 * Рядок: `29.10 16:50:12.345 I [номери] {wav-to-flac} текст`; багаторядковий текст — з відступом.
 */
object LogText {
    /** Файл журналу більший — лишити лише [KEEP_BYTES] найновішого. */
    const val MAX_BYTES = 512 * 1024
    const val KEEP_BYTES = 256 * 1024

    /** Скільки найновішого журналу кладеться в автокопію (її Claude читає з Диска). */
    const val BACKUP_BYTES = 96 * 1024

    /** Робота у фоні стоїть без жодного кроку стільки — це «зависло»: у журнал іде, де саме стоїть потік. */
    const val STUCK_MS = 60_000L

    /** Повторити запис про «зависло» не частіше, ніж раз на стільки. */
    const val STUCK_REPEAT_MS = 5 * 60_000L

    private val format = ThreadLocal.withInitial { SimpleDateFormat("dd.MM HH:mm:ss.SSS", Locale.ROOT) }

    fun line(time: Long, level: Char, tag: String, thread: String, message: String): String {
        val body = message.trimEnd().replace("\n", "\n    ")
        return "${format.get()!!.format(Date(time))} $level [$tag] {$thread} $body\n"
    }

    /** Лишити не більше [keepBytes] з кінця тексту, починаючи з повного рядка. */
    fun keepTail(text: String, keepBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= keepBytes) return text
        val cut = String(bytes, bytes.size - keepBytes, keepBytes, Charsets.UTF_8)
        val nl = cut.indexOf('\n')
        return if (nl in 0 until cut.length - 1) cut.substring(nl + 1) else cut
    }

    /** Останні рядки (не більше [maxBytes]) — для автокопії. */
    fun tailLines(text: String, maxBytes: Int): List<String> =
        keepTail(text, maxBytes).split('\n').filter { it.isNotEmpty() }

    fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().lines().take(40).joinToString("\n")
    }

    /** Де зараз стоїть потік (для «зависло»). */
    fun threadStack(frames: Array<StackTraceElement>, max: Int = 30): String =
        if (frames.isEmpty()) "(потік не дає стану)" else frames.take(max).joinToString("\n") { "at $it" }

    /** Чи пора записати «зависло»: тиша довша за [STUCK_MS] і минулий запис був давно. */
    fun stuck(now: Long, lastStepAt: Long, lastWarnAt: Long): Boolean =
        lastStepAt > 0 && now - lastStepAt >= STUCK_MS && (lastWarnAt == 0L || now - lastWarnAt >= STUCK_REPEAT_MS)
}
