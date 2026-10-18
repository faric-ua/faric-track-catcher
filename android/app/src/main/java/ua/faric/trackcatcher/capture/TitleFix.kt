package ua.faric.trackcatcher.capture

import android.content.Context
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.Retag
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TitleTail
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow

/**
 * «Прибрати зайве з назв» (T71, faric 18.10 14:13: «пробігти по імені і по тегах і повідрізати ненужний текст»).
 * Для кожного запису з хвостом: назва в тегах FLAC / MP3 (лише теги, безпечно — [Renumber.safeRetag]),
 * ім'я файлу (лише якщо воно зроблене з назви) і назва в історії. Звук не змінюється, файли не видаляються.
 *
 * Підтверджений хвіст запам'ятовується ([learn]): нові записи з ним очищаються самі ([known] → [TitleTail.clean]).
 */
object TitleFix {
    private const val PREFS = "title_tails"
    private const val KEY = "tails"
    private const val MAX = 20

    /** Хвости, які Track Catcher уже бачив чи faric підтвердив. */
    fun known(app: Context): List<String> =
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun learn(app: Context, tail: String) {
        val t = tail.trim()
        if (t.isEmpty()) return
        val list = (listOf(t) + known(app).filter { it != t }).take(MAX)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    /** Що станеться з записом: нова назва або null (хвоста нема). */
    fun newTitle(r: RecordingRow, tail: String): String? = TitleTail.strip(r.title, tail)

    /**
     * Виправити один запис. Повертає перелік того, що не вийшло (порожній — усе гаразд).
     * Файл, де тег не переписався, не перейменовується; назва в історії міняється, лише коли всі файли гаразд.
     */
    fun fix(app: Context, r: RecordingRow, tail: String): List<String> {
        val title = newTitle(r, tail) ?: return emptyList()
        val store = MediaStoreWriter(app)
        val problems = ArrayList<String>()
        fun one(uri: String?, name: String?): Pair<String, String>? {
            if (uri == null || name == null) return null
            if (!store.stillThere(uri)) return null // файла нема на місці (перенесли) — пропускаємо
            try {
                if (Retag.kindOf(name) != null) {
                    val tags = Renumber.readTags(app, uri, name)
                    if (tags[FileTags.Field.TITLE]?.trim() != title) {
                        Renumber.safeRetag(app, uri, name, mapOf("TITLE" to title)) { it[FileTags.Field.TITLE]?.trim() == title }
                    }
                }
                val newName = TitleTail.renamed(name, r.artist, r.title, title) ?: return null
                val saved = store.rename(uri, newName)
                return saved.uri to saved.name
            } catch (e: Exception) {
                problems += "$name\t${ua.faric.trackcatcher.core.ErrorText.human(e)}"
                return null
            }
        }
        val mp3 = one(r.mp3Uri, r.mp3Name)
        val wav = one(r.wavUri, r.wavName)
        if (problems.isEmpty()) {
            val wavNote = if (r.wavName != null && Retag.kindOf(r.wavName) == null) " (у WAV назва всередині лишилась стара — WAV тегів так не переписати)" else ""
            HistoryDb.get(app).updateTitle(r.id, title, mp3, wav, "назву очищено ${TimeText.dateTime(System.currentTimeMillis())}: прибрано «${tail.trim()}»$wavNote")
        } else if (mp3 != null || wav != null) {
            // Частина файлів перейменована — історія має знати нові імена, назву лишаємо стару (її ще не скрізь змінено).
            HistoryDb.get(app).updateTitle(r.id, r.title, mp3, wav, "")
        }
        return problems
    }
}
