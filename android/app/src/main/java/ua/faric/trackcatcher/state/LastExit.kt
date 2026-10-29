package ua.faric.trackcatcher.state

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import ua.faric.trackcatcher.core.AppInfo
import ua.faric.trackcatcher.core.ExitWords
import ua.faric.trackcatcher.core.TimeText

/**
 * Чому обірвався запис (faric 20.10 17:10, M30s): коли Android закриває весь застосунок, у пам'яті нічого не лишається —
 * кнопка просто «вимкнена», без пояснення. Тому: під час запису — позначка на диску; помилка застосунку — текст на диску;
 * при наступному старті — причина від Android (Android 11+) і текст помилки на екрані «Запис».
 */
object LastExit {
    private const val PREFS = "last_exit"
    private const val KEY_RECORDING = "recording_since"
    private const val KEY_CRASH = "crash"

    fun install(app: Context) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { WorkLog.e("збій", "застосунок падає: потік «${thread.name}»", e) }
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val text = "${AppInfo.encoder} · ${TimeText.dateTime(System.currentTimeMillis())} · потік «${thread.name}»\n$sw"
                prefs.edit().putString(KEY_CRASH, text.take(4_000)).commit()
            }
            previous?.uncaughtException(thread, e)
        }
        // Попередній запуск закінчився посеред запису (позначка лишилась) — пояснити.
        val since = prefs.getLong(KEY_RECORDING, 0L)
        val crash = prefs.getString(KEY_CRASH, null)
        if (since > 0L || crash != null) {
            val why = if (since > 0L) reason(app, since) else null
            val text = if (since > 0L) ExitWords.recordingCut(why, crash)
            else "Минулого разу Track Catcher закрився з помилкою (надішли цей текст):\n\n${crash.orEmpty().trim()}"
            RecorderHub.update { it.copy(error = text) }
            prefs.edit().remove(KEY_RECORDING).remove(KEY_CRASH).apply()
        }
    }

    fun recordingStarted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_RECORDING, System.currentTimeMillis()).commit()
    }

    fun recordingEnded(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_RECORDING).apply()
    }

    private fun reason(app: Context, since: Long): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return runCatching {
            val am = app.getSystemService(ActivityManager::class.java)
            val info = am.getHistoricalProcessExitReasons(app.packageName, 0, 5).firstOrNull { it.timestamp >= since }
                ?: return@runCatching null
            ExitWords.words(info.reason, info.description) + " · " + TimeText.dateTime(info.timestamp)
        }.getOrNull()
    }
}
