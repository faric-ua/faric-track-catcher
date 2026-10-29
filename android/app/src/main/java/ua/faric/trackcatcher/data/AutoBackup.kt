package ua.faric.trackcatcher.data

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import ua.faric.trackcatcher.BuildConfig

/**
 * Автокопія (T67, faric 17.10: «хай кожен вказує, де зберігати, — додаток туди все складає, а потім звідти гребе»).
 *
 * Власник один раз вибирає **файл** у вікні Android «Зберегти як» — у «Моїх файлах» або на своєму Google Диску
 * (папку на Диску Android вибрати не дає, а файл — так; так само працює «Опублікувати на Диск»). Далі застосунок
 * сам переписує цей файл: історія + налаштування, через кілька секунд після кожного збереженого запису й коли
 * застосунок ховається. Без входу в Google: кожен пише лише туди, куди сам дозволив. На іншому телефоні —
 * «Відновити з копії» → цей файл.
 */
object AutoBackup {
    private const val PREFS = "auto_backup"
    private const val KEY_URI = "uri"
    private const val KEY_NAME = "name"
    private const val KEY_LAST_AT = "last_at"
    private const val KEY_ERROR = "error"
    private const val DELAY_SEC = 5L

    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "track-catcher-autobackup") }
    private val scheduled = AtomicBoolean(false)

    data class State(val uri: String?, val name: String, val lastAt: Long, val error: String?) {
        val on: Boolean get() = uri != null
    }

    fun state(context: Context): State {
        val p = prefs(context)
        return State(p.getString(KEY_URI, null), p.getString(KEY_NAME, "") ?: "", p.getLong(KEY_LAST_AT, 0L), p.getString(KEY_ERROR, null))
    }

    fun enable(context: Context, uri: Uri, name: String) {
        prefs(context).edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, name)
            .remove(KEY_LAST_AT).remove(KEY_ERROR).apply()
    }

    /** Вимкнути: застосунок перестає писати; сам файл лишається там, де був (нічого не видаляємо). */
    fun disable(context: Context) {
        val p = prefs(context)
        p.getString(KEY_URI, null)?.let { u ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(u),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        p.edit().clear().apply()
    }

    /** Попросити копію «скоро»: кілька записів підряд дають один запис файлу. */
    fun request(context: Context) {
        val app = context.applicationContext
        if (prefs(app).getString(KEY_URI, null) == null) return
        if (scheduled.getAndSet(true)) return
        executor.schedule({
            scheduled.set(false)
            runCatching { writeNow(app) } // помилка лишається в стані — її видно в «Налаштуваннях»
        }, DELAY_SEC, TimeUnit.SECONDS)
    }

    /** Записати зараз (у фоновому потоці). Повертає кількість записів або кидає помилку з поясненням. */
    fun writeNow(context: Context): Int {
        val app = context.applicationContext
        val p = prefs(app)
        val uri = Uri.parse(p.getString(KEY_URI, null) ?: return 0)
        return try {
            val resolver = app.contentResolver
            val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
                ?: resolver.openOutputStream(uri, "w")
                ?: throw IOException("файл копії не відкривається на запис")
            val journal = linkedMapOf<String, Any?>(
                "phone" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "writtenAt" to System.currentTimeMillis(),
                "lines" to ua.faric.trackcatcher.state.WorkLog.tail(),
            )
            val n = out.use { HistoryBackup.export(HistoryDb.get(app), it, BuildConfig.VERSION_NAME, Settings(app).exportMap(), journal) }
            p.edit().putLong(KEY_LAST_AT, System.currentTimeMillis()).remove(KEY_ERROR).apply()
            n
        } catch (e: SecurityException) {
            p.edit().putString(KEY_ERROR, "Android забрав доступ до файлу — вибери файл ще раз").apply()
            throw e
        } catch (e: Exception) {
            ua.faric.trackcatcher.state.WorkLog.i("автокопія", "не вдалося: ${e.message ?: e.javaClass.simpleName}") // не W — інакше знову автокопія
            p.edit().putString(KEY_ERROR, e.message ?: e.javaClass.simpleName).apply()
            throw e
        }
    }

    /** Те саме, що [writeNow], але в черзі автокопії — з екрана (не з головного потоку). */
    fun writeSoon(context: Context, done: (Result<Int>) -> Unit) {
        val app = context.applicationContext
        executor.execute { done(runCatching { writeNow(app) }) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
