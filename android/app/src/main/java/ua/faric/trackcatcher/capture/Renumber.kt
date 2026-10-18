package ua.faric.trackcatcher.capture

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.Retag
import ua.faric.trackcatcher.core.TrackNumber
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow

/**
 * Перевірка номерів пісень в альбомі (T70, faric 18.10 04:11: «після запису пробіжиться й передивиться, чи номери
 * треків правильно записані: де TRACK 10 — 10, хоч двічі повторюється; BONUS 03 — 13»).
 *
 * Номер рахується на весь альбом ([TrackNumber.all]) з усіх записів цього альбому в історії. Файл, де номер не той
 * (або нема виконавця альбому чи року), переписується **лише в тегах** ([Retag]) і лише безпечно ([fixFile]).
 */
object Renumber {
    /** Усі записи «того самого альбому» з файлами, по порядку запису. Без альбому — лише ця сесія. */
    fun scope(db: HistoryDb, r: RecordingRow): List<RecordingRow> {
        val album = r.album.trim().lowercase()
        val rows = if (album.isBlank()) db.sessionRows(r.sessionId).filter { it.album.isBlank() }
        else db.all().filter { it.album.trim().lowercase() == album }.sortedWith(compareBy({ it.createdAt }, { it.id }))
        return rows.filter { it.hasFile || it.id == r.id }
    }

    /** Ключ альбому для групування перевірки. */
    fun scopeKey(r: RecordingRow): String = r.album.trim().lowercase().ifBlank { "#session-${r.sessionId}" }

    /** id запису → правильний номер. */
    fun numbers(rows: List<RecordingRow>): Map<Long, Int> = rows.zip(TrackNumber.all(rows.map { it.title })).associate { (r, n) -> r.id to n }

    /** Файли запису, які можна переписати (FLAC, MP3): uri → назва. WAV пропускаємо — його краще зробити FLAC. */
    fun files(r: RecordingRow): List<Pair<String, String>> = listOfNotNull(
        r.mp3Uri?.let { u -> r.mp3Name?.let { u to it } },
        r.wavUri?.let { u -> r.wavName?.takeIf { Retag.kindOf(it) != null }?.let { u to it } },
    )

    /** Що вписати у файл запису [r] з номером [number]: номер завжди, виконавця альбому й рік — лише якщо їх нема. */
    fun wanted(r: RecordingRow, number: Int, current: FileTags.FileInfo): Map<String, String> = buildMap {
        if (current[FileTags.Field.TRACK]?.substringBefore('/')?.trim()?.toIntOrNull() != number) put("TRACKNUMBER", number.toString())
        if (current[FileTags.Field.ALBUM_ARTIST] == null && r.artist.isNotBlank() && r.album.isNotBlank()) put("ALBUMARTIST", r.artist.trim())
        if (current[FileTags.Field.YEAR] == null) put("DATE", java.util.Calendar.getInstance().apply { timeInMillis = r.createdAt }.get(java.util.Calendar.YEAR).toString())
    }

    /**
     * Перевірити й, якщо треба, виправити один файл. true — переписано.
     * Безпечно: копія оригіналу → новий файл → звук той самий (MD5 після тегів) і теги ті, що треба → заміна →
     * знову перевірка; збій під час заміни — оригінал записується назад.
     */
    fun fixFile(app: Context, r: RecordingRow, uri: String, name: String, number: Int): Boolean {
        val current = readTags(app, uri, name)
        val set = wanted(r, number, current)
        if (set.isEmpty()) return false
        safeRetag(app, uri, name, set) { it[FileTags.Field.TRACK]?.substringBefore('/')?.trim()?.toIntOrNull() == number }
        return true
    }

    /** Теги файлу (лише заголовок). */
    fun readTags(app: Context, uri: String, name: String): FileTags.FileInfo {
        val size = MediaStoreWriter(app).sizeOf(uri)
        return app.contentResolver.openInputStream(Uri.parse(uri))?.buffered(64 * 1024)?.use { FileTags.read(it, size, name) }
            ?: throw IOException("файл не відкривається — можливо, його прибрали")
    }

    /**
     * Переписати теги [set] у FLAC / MP3 безпечно (T70, T71): копія оригіналу → новий файл → звук той самий
     * (MD5 після тегів) і [check] нового тегу пройдено → заміна → знову перевірка; збій під час заміни —
     * оригінал записується назад. Кидає помилку з поясненням; файл тоді лишається, як був.
     */
    fun safeRetag(app: Context, uri: String, name: String, set: Map<String, String>, check: (FileTags.FileInfo) -> Boolean) {
        val kind = Retag.kindOf(name) ?: throw IOException("теги вмію лише у FLAC і MP3")
        val resolver = app.contentResolver
        val u = Uri.parse(uri)
        val dir = File(app.cacheDir, "retag").apply { mkdirs() }
        val backup = File(dir, "original.bak")
        val fresh = File(dir, "new.tmp")
        try {
            resolver.openInputStream(u)?.use { i -> backup.outputStream().use { o -> i.copyTo(o, 256 * 1024) } } ?: throw IOException("файл не відкривається")
            backup.inputStream().buffered(256 * 1024).use { i -> fresh.outputStream().buffered(256 * 1024).use { o -> Retag.rewrite(kind, i, o, set) } }
            val sound = backup.inputStream().buffered(256 * 1024).use { Retag.audioDigest(kind, it) }
            if (fresh.inputStream().buffered(256 * 1024).use { Retag.audioDigest(kind, it) } != sound) throw IOException("перевірка звуку не зійшлась — файл не чіпаю")
            val after = fresh.inputStream().buffered(64 * 1024).use { FileTags.read(it, fresh.length(), name) }
            if (!check(after)) throw IOException("новий тег не записався — файл не чіпаю")
            try {
                write(app, u, fresh)
                val written = resolver.openInputStream(u)?.buffered(256 * 1024)?.use { Retag.audioDigest(kind, it) }
                if (written != sound) throw IOException("після заміни звук не той")
            } catch (e: Exception) {
                runCatching { write(app, u, backup) }
                throw IOException("не вдалося переписати (${e.message}) — повернув файл, як був")
            }
        } finally {
            backup.delete()
            fresh.delete()
        }
    }

    private fun write(app: Context, u: Uri, from: File) {
        val resolver = app.contentResolver
        val out = runCatching { resolver.openOutputStream(u, "wt") }.getOrNull() ?: resolver.openOutputStream(u, "w")
            ?: throw IOException("файл не відкривається на запис")
        out.use { o -> from.inputStream().use { it.copyTo(o, 256 * 1024) } }
    }
}
