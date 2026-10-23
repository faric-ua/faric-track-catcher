package ua.faric.trackcatcher.capture

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Запис поверх файлу (теги, обрізка) — з журналом (faric 24.10 00:24: у шторці «Перевіряю номери: 1 з 2» висить з
 * учора — Android закрив Track Catcher посеред роботи, інспектор бачив такі закриття через пам'ять 7 разів за вечір).
 * Якщо застосунок закрили саме тоді, коли новий вміст писався у файл, файл лишився б обрізаним, а копія оригіналу —
 * у кеші. Тепер перед записом — позначка «пишу у файл X, копія тут, має вийти N байтів»; після звірки — знімається.
 * Наступний старт бачить позначку: файл не того розміру — повертає оригінал з копії.
 */
object WriteJournal {
    private fun file(app: Context) = File(app.filesDir, "write-journal.txt")

    fun begin(app: Context, uri: String, name: String, backup: File, expectedBytes: Long) {
        file(app).writeText("$uri\n${backup.absolutePath}\n$expectedBytes\n$name")
    }

    fun end(app: Context) {
        file(app).delete()
    }

    /** Після старту застосунку (у фоні). Повертає, що зроблено (для показу), або null — нічого не треба було. */
    fun recover(app: Context): String? {
        val f = file(app)
        if (!f.exists()) return null
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        val uri = lines.getOrNull(0)
        val backup = lines.getOrNull(1)?.let { File(it) }
        val expected = lines.getOrNull(2)?.toLongOrNull()
        val name = lines.getOrNull(3).orEmpty()
        try {
            if (uri.isNullOrBlank() || backup == null || expected == null || !backup.exists()) return null
            val resolver = app.contentResolver
            val u = Uri.parse(uri)
            val now = runCatching { resolver.openInputStream(u)?.use { i -> count(i) } }.getOrNull()
            if (now == expected) return null // запис устиг дописатись — усе гаразд
            val out = runCatching { resolver.openOutputStream(u, "wt") }.getOrNull() ?: resolver.openOutputStream(u, "w")
                ?: return "Минулого разу застосунок закрили, коли він переписував «$name». Повернути оригінал не вдалося: файл не відкривається. Копія — у кеші застосунку."
            out.use { o -> backup.inputStream().use { it.copyTo(o, 256 * 1024) } }
            return "Минулого разу Android закрив Track Catcher, коли той переписував «$name». Файл повернуто, як був (з копії). Ту дію можна повторити."
        } catch (e: Exception) {
            return "Минулого разу застосунок закрили, коли він переписував «$name». Повернути оригінал не вдалося: ${e.message}"
        } finally {
            f.delete()
        }
    }

    private fun count(i: java.io.InputStream): Long {
        val buf = ByteArray(256 * 1024)
        var n = 0L
        while (true) {
            val k = i.read(buf)
            if (k < 0) return n
            n += k
        }
    }
}
