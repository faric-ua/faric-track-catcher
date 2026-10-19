package ua.faric.trackcatcher.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TrackMeta

data class RecordingRow(
    val id: Long = 0,
    val sessionId: Long,
    val createdAt: Long,
    val artist: String,
    val title: String,
    val album: String,
    val durationMs: Long,
    val lengthMs: Long,
    val status: RecStatus,
    val reason: String,
    val sourcePackage: String,
    val artworkPath: String?,
    val mp3Uri: String?,
    val mp3Name: String?,
    val mp3Size: Long,
    val mp3Bitrate: Int,
    val wavUri: String?,
    val wavName: String?,
    val wavSize: Long,
    val cutBySilence: Boolean,
    /** Скільки місць, де звук упирався в стелю (піки обрізано). -1 — не рахувалось (записи до v0.1.11). */
    val clips: Int = -1,
) {
    val displayName: String
        get() = when {
            artist.isBlank() && title.isBlank() -> "Невідома пісня"
            artist.isBlank() -> title
            title.isBlank() -> artist
            else -> "$artist — $title"
        }
    val hasFile: Boolean get() = mp3Uri != null || wavUri != null
}

data class SessionRow(
    val id: Long,
    val startedAt: Long,
    val stoppedAt: Long,
    val formatLabel: String,
)

/** Історія записів — SQLite на телефоні. Нічого не видаляється мовчки. */
class HistoryDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "history.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE sessions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at INTEGER NOT NULL,
                stopped_at INTEGER NOT NULL DEFAULT 0,
                format_label TEXT NOT NULL DEFAULT '')""",
        )
        db.execSQL(
            """CREATE TABLE recordings(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                artist TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                album TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                length_ms INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                source_pkg TEXT NOT NULL DEFAULT '',
                artwork_path TEXT,
                mp3_uri TEXT, mp3_name TEXT, mp3_size INTEGER NOT NULL DEFAULT 0, mp3_bitrate INTEGER NOT NULL DEFAULT 0,
                wav_uri TEXT, wav_name TEXT, wav_size INTEGER NOT NULL DEFAULT 0,
                cut_by_silence INTEGER NOT NULL DEFAULT 0,
                clips INTEGER NOT NULL DEFAULT -1)""",
        )
        db.execSQL("CREATE INDEX recordings_created ON recordings(created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 (0.1.11): лічильник перевантажень. Старі записи лишаються, у них -1 — «не рахувалось».
        if (oldVersion < 2) db.execSQL("ALTER TABLE recordings ADD COLUMN clips INTEGER NOT NULL DEFAULT -1")
    }

    fun startSession(startedAt: Long, formatLabel: String): Long =
        writableDatabase.insert("sessions", null, ContentValues().apply {
            put("started_at", startedAt)
            put("format_label", formatLabel)
        })

    fun endSession(id: Long, stoppedAt: Long) {
        writableDatabase.update("sessions", ContentValues().apply { put("stopped_at", stoppedAt) }, "id=?", arrayOf(id.toString()))
        changed()
    }

    fun insert(r: RecordingRow): Long {
        val id = writableDatabase.insert("recordings", null, ContentValues().apply {
            put("session_id", r.sessionId)
            put("created_at", r.createdAt)
            put("artist", r.artist)
            put("title", r.title)
            put("album", r.album)
            put("duration_ms", r.durationMs)
            put("length_ms", r.lengthMs)
            put("status", r.status.code)
            put("reason", r.reason)
            put("source_pkg", r.sourcePackage)
            put("artwork_path", r.artworkPath)
            put("mp3_uri", r.mp3Uri)
            put("mp3_name", r.mp3Name)
            put("mp3_size", r.mp3Size)
            put("mp3_bitrate", r.mp3Bitrate)
            put("wav_uri", r.wavUri)
            put("wav_name", r.wavName)
            put("wav_size", r.wavSize)
            put("cut_by_silence", if (r.cutBySilence) 1 else 0)
            put("clips", r.clips)
        })
        changed()
        return id
    }

    fun list(status: RecStatus?, query: String, limit: Int = 500): List<RecordingRow> {
        val where = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (status != null) {
            where += "status=?"
            args += status.code
        }
        val q = query.trim()
        if (q.isNotEmpty()) {
            where += "(artist LIKE ? OR title LIKE ? OR album LIKE ?)"
            val like = "%$q%"
            args += listOf(like, like, like)
        }
        val sql = "SELECT * FROM recordings" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY created_at DESC, id DESC LIMIT $limit"
        return readableDatabase.rawQuery(sql, args.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
    }

    /** Записи однієї сесії від першого до останнього (номер пісні в альбомі, T68). */
    fun sessionRows(sessionId: Long): List<RecordingRow> =
        readableDatabase.rawQuery("SELECT * FROM recordings WHERE session_id=? ORDER BY created_at, id", arrayOf(sessionId.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    /** Замінити файл без втрат запису (T69: з WAV зроблено FLAC) і дописати примітку. */
    fun replaceLossless(id: Long, uri: String, name: String, size: Long, note: String) {
        val r = get(id) ?: return
        val values = ContentValues().apply {
            put("wav_uri", uri)
            put("wav_name", name)
            put("wav_size", size)
            put("reason", listOf(r.reason, note).filter { it.isNotBlank() }.joinToString("; "))
        }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** T71: нова назва пісні (і, якщо файли перейменовано, нові імена й uri) + примітка. */
    fun updateTitle(id: Long, title: String, mp3: Pair<String, String>?, wav: Pair<String, String>?, note: String) {
        val r = get(id) ?: return
        val values = ContentValues().apply {
            put("title", title)
            mp3?.let { (uri, name) -> put("mp3_uri", uri); put("mp3_name", name) }
            wav?.let { (uri, name) -> put("wav_uri", uri); put("wav_name", name) }
            put("reason", listOf(r.reason, note).filter { it.isNotBlank() }.joinToString("; "))
        }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** T78: виконавець, назва, альбом (і нові імена файлів, якщо перейменовано) + примітка. */
    fun updateMeta(id: Long, artist: String, title: String, album: String, mp3: Pair<String, String>?, wav: Pair<String, String>?, note: String) {
        val r = get(id) ?: return
        val values = ContentValues().apply {
            put("artist", artist)
            put("title", title)
            put("album", album)
            mp3?.let { (uri, name) -> put("mp3_uri", uri); put("mp3_name", name) }
            wav?.let { (uri, name) -> put("wav_uri", uri); put("wav_name", name) }
            put("reason", listOf(r.reason, note).filter { it.isNotBlank() }.joinToString("; "))
        }
        writableDatabase.update("recordings", values, "id=?", arrayOf(id.toString()))
        changed()
    }

    /** Усі записи (для копії історії, T64). */
    fun all(): List<RecordingRow> = list(null, "", limit = Int.MAX_VALUE)

    fun allSessions(): List<SessionRow> = sessions().values.sortedBy { it.startedAt }

    /** Сесія з таким самим початком (відновлення копії) або нова. */
    fun sessionFor(startedAt: Long, stoppedAt: Long, formatLabel: String): Long {
        readableDatabase.rawQuery("SELECT id FROM sessions WHERE started_at=? LIMIT 1", arrayOf(startedAt.toString())).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return writableDatabase.insert("sessions", null, ContentValues().apply {
            put("started_at", startedAt)
            put("stopped_at", stoppedAt)
            put("format_label", formatLabel)
        })
    }

    /** Чи вже є такий запис (той самий час, виконавець, назва) — відновлення копії не дублює. */
    fun exists(createdAt: Long, artist: String, title: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM recordings WHERE created_at=? AND artist=? AND title=? LIMIT 1",
            arrayOf(createdAt.toString(), artist, title),
        ).use { it.moveToFirst() }

    fun get(id: Long): RecordingRow? =
        readableDatabase.rawQuery("SELECT * FROM recordings WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) row(c) else null
        }

    fun counts(): Map<RecStatus, Int> =
        readableDatabase.rawQuery("SELECT status, COUNT(*) FROM recordings GROUP BY status", null).use { c ->
            buildMap { while (c.moveToNext()) put(RecStatus.fromCode(c.getString(0)), c.getInt(1)) }
        }

    fun sessions(): Map<Long, SessionRow> =
        readableDatabase.rawQuery("SELECT id, started_at, stopped_at, format_label FROM sessions", null).use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getLong(0), SessionRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3)))
            }
        }

    /**
     * Чи вже є повний запис цієї пісні. T18 (faric 13.10: в альбомі кілька різних пісень з однаковою назвою) —
     * однакова назва ще не та сама пісня: якщо тривалість відома з обох боків і різниться більш як на 2 с — інша.
     */
    fun hasComplete(artist: String, title: String, durationMs: Long = 0L): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM recordings WHERE status=? AND lower(trim(artist))=? AND lower(trim(title))=? AND (mp3_uri IS NOT NULL OR wav_uri IS NOT NULL)" +
                " AND (? <= 0 OR duration_ms <= 0 OR abs(duration_ms - ?) <= ${TrackMeta.DURATION_TOLERANCE_MS}) LIMIT 1",
            arrayOf(RecStatus.COMPLETE.code, artist.trim().lowercase(), title.trim().lowercase(), durationMs.toString(), durationMs.toString()),
        ).use { it.moveToFirst() }

    /** Видалити записи з історії (власник вибрав і підтвердив — T38). */
    fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) db.delete("recordings", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
    }

    /** Файл видалено власником: запис в історії лишається з поміткою. */
    fun markFileDeleted(id: Long, mp3: Boolean, wav: Boolean) {
        val r = get(id) ?: return
        val values = ContentValues()
        if (mp3) {
            values.putNull("mp3_uri")
        }
        if (wav) {
            values.putNull("wav_uri")
        }
        val note = "файл видалено"
        val reason = if (r.reason.contains(note)) r.reason else listOf(r.reason, note).filter { it.isNotBlank() }.joinToString("; ")
        values.put("reason", reason)
        writableDatabase.update("recordings", values, "id=?", arrayOf(id.toString()))
        changed()
    }

    private fun row(c: Cursor): RecordingRow {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
        fun sn(n: String): String? = c.getString(c.getColumnIndexOrThrow(n))
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        return RecordingRow(
            id = l("id"), sessionId = l("session_id"), createdAt = l("created_at"),
            artist = s("artist"), title = s("title"), album = s("album"),
            durationMs = l("duration_ms"), lengthMs = l("length_ms"),
            status = RecStatus.fromCode(s("status")), reason = s("reason"), sourcePackage = s("source_pkg"),
            artworkPath = sn("artwork_path"),
            mp3Uri = sn("mp3_uri"), mp3Name = sn("mp3_name"), mp3Size = l("mp3_size"), mp3Bitrate = l("mp3_bitrate").toInt(),
            wavUri = sn("wav_uri"), wavName = sn("wav_name"), wavSize = l("wav_size"),
            cutBySilence = l("cut_by_silence") != 0L,
            clips = c.getColumnIndex("clips").let { i -> if (i >= 0) c.getInt(i) else -1 },
        )
    }

    private fun changed() {
        main.post { listeners.forEach { it() } }
    }

    companion object {
        @Volatile private var instance: HistoryDb? = null
        private val main = Handler(Looper.getMainLooper())
        private val listeners = CopyOnWriteArrayList<() -> Unit>()

        fun get(context: Context): HistoryDb =
            instance ?: synchronized(this) { instance ?: HistoryDb(context).also { instance = it } }

        /** Підписка на зміни історії (викликається в головному потоці). Повертає «відписатись». */
        fun observe(listener: () -> Unit): () -> Unit {
            listeners += listener
            return { listeners -= listener }
        }
    }
}
