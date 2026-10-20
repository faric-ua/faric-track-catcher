package ua.faric.trackcatcher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import ua.faric.trackcatcher.BuildConfig
import ua.faric.trackcatcher.capture.Artwork
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.state.RecorderHub

/**
 * Демо-дані для знімків екрана в CI (емулятор). Працює ЛИШЕ в debug-збірці (.dev),
 * у релізі для телефона цей код ніколи не виконується.
 */
object DemoData {
    fun apply(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.getBooleanExtra("demo_seed", false)) seed(context)
        if (intent.getStringExtra("demo_state") == "recording") recording(context)
    }

    private fun art(context: Context, artist: String, title: String, a: Int, b: Int): String? {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val p = Paint().apply { shader = LinearGradient(0f, 0f, 300f, 300f, a, b, Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRect(0f, 0f, 300f, 300f, p)
        return Artwork.save(context, artist, title, "", bmp)
    }

    private fun seed(context: Context) {
        val db = HistoryDb.get(context)
        if (db.list(null, "").isNotEmpty()) return
        val start = System.currentTimeMillis() - 40 * 60_000L
        val session = db.startSession(start, "MP3 320")
        val rows = listOf(
            Demo("Демо-гурт", "Нічне місто", "Касета", 245_000, 172_000, RecStatus.PARTIAL, "почато посеред (з 1:13)", Palette.accent, Palette.rec, mp3 = true),
            Demo("Тиха Хвиля", "Світло на воді", "Береги", 213_000, 212_400, RecStatus.COMPLETE, "", Palette.partial, Palette.bg, mp3 = true),
            Demo("Тиха Хвиля", "Дуже довга назва пісні, щоб перевірити перенесення рядків", "Береги", 301_000, 300_100, RecStatus.COMPLETE, "", Palette.success, Palette.surfaceAlt, mp3 = true, wav = true),
            Demo("", "Реклама", "", 15_000, 15_000, RecStatus.SKIPPED, "коротше 30 с", null, null),
            Demo("Демо-гурт", "Ранок", "Касета", 198_000, 61_000, RecStatus.FAILED, "тиша — цей плеєр, схоже, не дає себе записувати", Palette.warning, Palette.dangerFill),
            Demo("Демо-гурт", "Вечір", "Касета", 230_000, 95_000, RecStatus.PARTIAL, "зупинено посеред", Palette.rec, Palette.accentFill, mp3 = true),
        )
        rows.forEachIndexed { i, d ->
            val created = start + (i + 1) * 4 * 60_000L
            val artPath = if (d.a != null && d.b != null) art(context, d.artist, d.title, d.a, d.b) else null
            val base = TrackMeta(d.artist, d.title).displayName()
            db.insert(
                RecordingRow(
                    sessionId = session, createdAt = created, artist = d.artist, title = d.title, album = d.album,
                    durationMs = d.duration, lengthMs = d.length, status = d.status, reason = d.reason,
                    sourcePackage = "com.google.android.youtube", artworkPath = artPath,
                    mp3Uri = if (d.mp3) "content://media/external/audio/media/${900 + i}" else null,
                    mp3Name = if (d.mp3) "$base.mp3" else null, mp3Size = if (d.mp3) d.length * 40 else 0, mp3Bitrate = if (d.mp3) 320 else 0,
                    wavUri = if (d.wav) "content://media/external/audio/media/${950 + i}" else null,
                    wavName = if (d.wav) "$base.wav" else null, wavSize = if (d.wav) d.length * 192 else 0,
                    cutBySilence = i % 2 == 0,
                ),
            )
        }
        realFile(context, session, start + 28 * 60_000L)
        db.endSession(session, start + 30 * 60_000L)
    }

    /**
     * Запис зі справжнім файлом (WAV 3 с, тон 440 Гц) — щоб на емуляторі відкривалось «Про файл» і можна було
     * перевірити поворот (faric 21.10 01:17: «"Про файл" після перевороту пропадає»). Це останній запис (id 7).
     */
    private fun realFile(context: Context, session: Long, created: Long) {
        val frames = 3 * ua.faric.trackcatcher.core.Pcm.SAMPLE_RATE
        val pcm = java.nio.ByteBuffer.allocate(frames * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val v = (Math.sin(2 * Math.PI * 440 * i / ua.faric.trackcatcher.core.Pcm.SAMPLE_RATE) * 8000).toInt().toShort()
            pcm.putShort(v); pcm.putShort(v)
        }
        val meta = TrackMeta("Демо-гурт", "Справжній файл", "Касета")
        val saved = runCatching {
            ua.faric.trackcatcher.capture.MediaStoreWriter(context).write("Демо-гурт — Справжній файл.wav", "audio/x-wav", ua.faric.trackcatcher.capture.MediaStoreWriter.RELATIVE_DIR) { out ->
                out.write(ua.faric.trackcatcher.core.WavHeader.build(frames * 4L, info = ua.faric.trackcatcher.core.WavHeader.infoFor(meta)))
                out.write(pcm.array())
            }
        }.getOrNull() ?: return
        HistoryDb.get(context).insert(
            RecordingRow(
                sessionId = session, createdAt = created, artist = meta.artist, title = meta.title, album = meta.album,
                durationMs = 3_000, lengthMs = 3_000, status = RecStatus.COMPLETE, reason = "",
                sourcePackage = "com.google.android.youtube", artworkPath = null,
                mp3Uri = null, mp3Name = null, mp3Size = 0, mp3Bitrate = 0,
                wavUri = saved.uri, wavName = saved.name, wavSize = saved.size,
                cutBySilence = true,
            ),
        )
    }

    private fun recording(context: Context) {
        val meta = TrackMeta(
            "Демо-гурт", "Нічне місто", "Касета", 245_000, "com.google.android.youtube",
            art(context, "Демо-гурт", "Нічне місто", Palette.accent, Palette.rec),
        )
        RecorderHub.update {
            RecorderHub.State(
                phase = RecorderHub.Phase.RECORDING,
                sessionId = 99,
                startedAt = System.currentTimeMillis() - 754_000,
                meta = meta,
                playing = true,
                levelL = 0.42f,
                levelR = 0.35f,
                tracks = 4,
                complete = 2,
                partial = 1,
                queued = 1,
                recent = listOf(
                    RecorderHub.Result("Тиха Хвиля — Світло на воді", RecStatus.COMPLETE, ""),
                    RecorderHub.Result("Демо-гурт — Ранок", RecStatus.PARTIAL, "почато посеред (з 0:41)"),
                    RecorderHub.Result("Реклама", RecStatus.SKIPPED, "коротше 30 с"),
                ),
            )
        }
    }

    private data class Demo(
        val artist: String, val title: String, val album: String, val duration: Long, val length: Long,
        val status: RecStatus, val reason: String, val a: Int?, val b: Int?, val mp3: Boolean = false, val wav: Boolean = false,
    )
}
