package ua.faric.trackcatcher.capture

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import ua.faric.trackcatcher.core.Holes
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow

/**
 * T88: перевірити вже записаний файл на «тріски» (дірки чистої тиші посеред музики). Лише файл без втрат
 * (FLAC / WAV): у MP3 нулі після кодування вже не рівно нулі — там точно не знайти. Файл лише читається.
 */
object HoleScan {
    /** Лише MP3 — нема чого перевіряти точно. */
    class OnlyMp3 : IOException("лише MP3 — у ньому тріски точно не знайти (перевіряється FLAC / WAV)")

    fun canCheck(r: RecordingRow): Boolean = r.wavUri != null && r.wavName != null

    /** Перевірити й записати в історію. Повертає знайдені місця (порожньо — чисто). */
    fun check(app: Context, r: RecordingRow): List<Holes.Place> {
        if (!canCheck(r)) throw OnlyMp3()
        val uri = r.wavUri!!
        val name = r.wavName.orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val dir = File(app.cacheDir, "holes").apply { mkdirs() }
        try {
            val copy = File(dir, "file.$ext")
            app.contentResolver.openInputStream(Uri.parse(uri))?.use { i -> copy.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                ?: throw IOException("файл не відкривається")
            val pcm = TrimApply.decode(app, copy, ext, File(dir, "pcm.raw"))
            copy.delete()
            val h = Holes(pcm.rate)
            RandomAccessFile(pcm.file, "r").use { f ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = f.read(buf)
                    if (n <= 0) break
                    h.feed(buf, 0, n - n % 4)
                }
            }
            val places = Holes.places(h.result())
            HistoryDb.get(app).updateHoles(r.id, Holes.encode(places))
            return places
        } finally {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Рядок для списку «перезаписати»: «Назва — 1:55 (27 дірок, 0,37 с) · 2:06». */
    fun line(r: RecordingRow, places: List<Holes.Place>): String =
        r.title.ifBlank { r.displayName } + " — " + Holes.describe(places)
}
