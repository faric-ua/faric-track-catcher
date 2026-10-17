package ua.faric.trackcatcher.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import ua.faric.trackcatcher.core.JsonDoc
import ua.faric.trackcatcher.core.RecStatus

/**
 * Копія історії (T64, faric 17.10: «копія історії — підтримую»). Історія (статуси, причини, назви, де лежать
 * файли) живе в базі всередині застосунку й пропала б після перевстановлення. Копія — один JSON-файл, який
 * власник кладе куди хоче (вікно Android «Зберегти»: «Мої файли», Google Диск). Файли пісень у копію не
 * йдуть — вони й так у «Музиці». Відновлення лише **додає** записи, яких ще нема; наявні не змінює.
 */
object HistoryBackup {
    const val FORMAT = "track-catcher-history/1"

    class BadFile(message: String) : Exception(message)

    data class Result(val added: Int, val skipped: Int)

    fun export(db: HistoryDb, out: OutputStream, appVersion: String): Int {
        val rows = db.all()
        val doc = linkedMapOf(
            "format" to FORMAT,
            "app" to "Track Catcher $appVersion",
            "exportedAt" to System.currentTimeMillis(),
            "sessions" to db.allSessions().map { s ->
                linkedMapOf("id" to s.id, "startedAt" to s.startedAt, "stoppedAt" to s.stoppedAt, "format" to s.formatLabel)
            },
            "recordings" to rows.map { toMap(it) },
        )
        out.write(JsonDoc.write(doc).toByteArray(Charsets.UTF_8))
        return rows.size
    }

    fun import(db: HistoryDb, input: InputStream): Result {
        val text = input.readBytes().toString(Charsets.UTF_8)
        val doc = try {
            JsonDoc.parse(text) as? Map<*, *>
        } catch (e: JsonDoc.ParseException) {
            throw BadFile("Файл пошкоджений: ${e.message}")
        } ?: throw BadFile("Це не копія історії Track Catcher.")
        if (doc["format"] != FORMAT) throw BadFile("Це не копія історії Track Catcher (формат «${doc["format"] ?: "?"}»).")
        val sessions = (doc["sessions"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }.associateBy { long(it["id"]) }
        val newSession = HashMap<Long, Long>()
        var added = 0
        var skipped = 0
        for (m in (doc["recordings"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }) {
            val r = fromMap(m)
            if (db.exists(r.createdAt, r.artist, r.title)) {
                skipped++
                continue
            }
            val sid = newSession.getOrPut(r.sessionId) {
                val s = sessions[r.sessionId]
                db.sessionFor(long(s?.get("startedAt")).takeIf { it > 0 } ?: r.createdAt, long(s?.get("stoppedAt")), str(s?.get("format")))
            }
            db.insert(r.copy(id = 0, sessionId = sid, artworkPath = r.artworkPath?.takeIf { File(it).isFile }))
            added++
        }
        return Result(added, skipped)
    }

    private fun toMap(r: RecordingRow): Map<String, Any?> = linkedMapOf(
        "sessionId" to r.sessionId, "createdAt" to r.createdAt,
        "artist" to r.artist, "title" to r.title, "album" to r.album,
        "durationMs" to r.durationMs, "lengthMs" to r.lengthMs,
        "status" to r.status.code, "reason" to r.reason, "player" to r.sourcePackage,
        "artwork" to r.artworkPath,
        "mp3Uri" to r.mp3Uri, "mp3Name" to r.mp3Name, "mp3Size" to r.mp3Size, "mp3Bitrate" to r.mp3Bitrate,
        "losslessUri" to r.wavUri, "losslessName" to r.wavName, "losslessSize" to r.wavSize,
        "cutBySilence" to r.cutBySilence, "clips" to r.clips,
    )

    private fun fromMap(m: Map<*, *>) = RecordingRow(
        sessionId = long(m["sessionId"]), createdAt = long(m["createdAt"]),
        artist = str(m["artist"]), title = str(m["title"]), album = str(m["album"]),
        durationMs = long(m["durationMs"]), lengthMs = long(m["lengthMs"]),
        status = RecStatus.fromCode(str(m["status"])), reason = str(m["reason"]), sourcePackage = str(m["player"]),
        artworkPath = m["artwork"] as? String,
        mp3Uri = m["mp3Uri"] as? String, mp3Name = m["mp3Name"] as? String, mp3Size = long(m["mp3Size"]), mp3Bitrate = long(m["mp3Bitrate"]).toInt(),
        wavUri = m["losslessUri"] as? String, wavName = m["losslessName"] as? String, wavSize = long(m["losslessSize"]),
        cutBySilence = m["cutBySilence"] == true, clips = (m["clips"] as? Long)?.toInt() ?: -1,
    )

    private fun long(v: Any?): Long = (v as? Long) ?: (v as? Double)?.toLong() ?: 0L

    private fun str(v: Any?): String = v as? String ?: ""
}
