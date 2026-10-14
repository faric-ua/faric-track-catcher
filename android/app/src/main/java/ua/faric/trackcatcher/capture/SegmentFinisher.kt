package ua.faric.trackcatcher.capture

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import ua.faric.trackcatcher.core.AudioStats
import ua.faric.trackcatcher.core.Classifier
import ua.faric.trackcatcher.core.ClipCounter
import ua.faric.trackcatcher.core.EdgeFade
import ua.faric.trackcatcher.core.FileNames
import ua.faric.trackcatcher.core.Id3v2
import ua.faric.trackcatcher.core.LevelAnalysis
import ua.faric.trackcatcher.core.Pcm
import ua.faric.trackcatcher.core.RecStatus
import ua.faric.trackcatcher.core.RepeatPolicy
import ua.faric.trackcatcher.core.Verdict
import ua.faric.trackcatcher.core.WavHeader
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.encode.Mp3Encoder

/**
 * Шматок → файл(и) + запис в історії.
 * 1) рівні звуку по 10 мс → обрізати тишу; 2) повний/неповний/невдало/пропущено;
 * 3) WAV і/або MP3 з назвою й тегами; 4) історія; 5) тимчасовий файл прибрати.
 */
class SegmentFinisher(context: Context) {
    private val app = context.applicationContext
    private val mediaStore = MediaStoreWriter(app)
    private val db = HistoryDb.get(app)

    fun finish(job: SegmentJob, settings: Settings.Values): RecordingRow {
        val totalFrames = job.pcmFile.length() / Pcm.BYTES_PER_FRAME
        val clips = ClipCounter()
        val trimmed = LevelAnalysis.trim(blockLevels(job.pcmFile, clips), totalFrames)
        // Статус — за записаним звуком; «Зрізати краї» лише вкорочує файл.
        var verdict = Classifier.classify(job.meta, Pcm.framesToMs(trimmed.keptFrames), trimmed.audibleMs, job.startPositionMs, job.endReason, settings.minLengthSec)
        val stats = if (settings.trimEdges) LevelAnalysis.trimEdges(trimmed, Settings.TRIM_EDGE_MS) else trimmed
        val lengthMs = Pcm.framesToMs(stats.keptFrames)
        if (job.recovered && verdict.status == RecStatus.PARTIAL || job.recovered && verdict.status == RecStatus.COMPLETE) {
            verdict = Verdict(RecStatus.PARTIAL, verdict.reasons + "застосунок закрився під час запису")
        }
        val savable = verdict.status == RecStatus.COMPLETE || verdict.status == RecStatus.PARTIAL
        if (savable && settings.repeat == RepeatPolicy.SKIP && !job.meta.isUnknown &&
            db.hasComplete(job.meta.artist, job.meta.title)
        ) {
            verdict = Verdict(RecStatus.SKIPPED, listOf("вже є повний запис цієї пісні"))
        }

        var mp3: MediaStoreWriter.Saved? = null
        var wav: MediaStoreWriter.Saved? = null
        var bitrate = 0
        if (verdict.status == RecStatus.COMPLETE || verdict.status == RecStatus.PARTIAL) {
            val base = FileNames.baseName(job.meta)
            val dir = FileNames.folderFor(job.meta, settings.albumFolders)
            val fade = EdgeFade.frames(fadeMs = settings.fadeMs)
            fun saveTo(store: AudioStore) {
                if (settings.format.wav) wav = writeWav(store, job, stats, base, dir, fade)
                if (settings.format.mp3) {
                    bitrate = settings.mp3Bitrate
                    mp3 = writeMp3(store, job, stats, base, dir, fade, settings.mp3Bitrate, lengthMs)
                }
            }
            // Своя папка (T29). Недоступна (карту вийняли, дозвіл забрали) — пісня не губиться: іде в «Музику».
            val tree = settings.treeUri.takeIf { it.isNotBlank() }?.let { runCatching { TreeWriter(app, Uri.parse(it)) }.getOrNull() }
            var note: String? = null
            try {
                if (tree != null && tree.available()) {
                    try {
                        saveTo(tree)
                    } catch (e: Exception) {
                        listOfNotNull(wav, mp3).forEach { mediaStore.delete(it.uri) }
                        wav = null
                        mp3 = null
                        note = "не вдалося записати у свою папку — збережено в «Музику»"
                        saveTo(mediaStore)
                    }
                } else {
                    if (settings.treeUri.isNotBlank()) note = "своя папка недоступна — збережено в «Музику»"
                    saveTo(mediaStore)
                }
                note?.let { verdict = verdict.copy(reasons = verdict.reasons + it) }
            } catch (e: Exception) {
                verdict = Verdict(RecStatus.FAILED, listOf(saveError(e)))
            }
        }

        val row = RecordingRow(
            sessionId = job.sessionId,
            createdAt = job.closedAt,
            artist = job.meta.artist,
            title = job.meta.title,
            album = job.meta.album,
            durationMs = job.meta.durationMs,
            lengthMs = lengthMs,
            status = verdict.status,
            reason = verdict.reasonText,
            sourcePackage = job.meta.sourcePackage,
            artworkPath = job.meta.artworkPath,
            mp3Uri = mp3?.uri, mp3Name = mp3?.name, mp3Size = mp3?.size ?: 0L, mp3Bitrate = bitrate,
            wavUri = wav?.uri, wavName = wav?.name, wavSize = wav?.size ?: 0L,
            cutBySilence = job.cutBySilence,
            clips = clips.events.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        // Порожній шматок (менше секунди тиші — напр. «Стоп» одразу після «Почати») в історію не пишемо.
        val empty = verdict.status == RecStatus.SKIPPED && lengthMs < 1_000 && stats.audibleMs == 0L
        val id = if (empty) 0L else db.insert(row)
        job.pcmFile.delete()
        job.sidecar.delete()
        return row.copy(id = id)
    }

    /** Запис в історії, коли збереження впало зовсім. */
    fun failure(job: SegmentJob, error: Throwable): RecordingRow {
        val row = RecordingRow(
            sessionId = job.sessionId, createdAt = job.closedAt,
            artist = job.meta.artist, title = job.meta.title, album = job.meta.album,
            durationMs = job.meta.durationMs, lengthMs = Pcm.framesToMs(job.pcmFile.length() / Pcm.BYTES_PER_FRAME),
            status = RecStatus.FAILED, reason = saveError(error), sourcePackage = job.meta.sourcePackage,
            artworkPath = job.meta.artworkPath, mp3Uri = null, mp3Name = null, mp3Size = 0, mp3Bitrate = 0,
            wavUri = null, wavName = null, wavSize = 0, cutBySilence = job.cutBySilence,
        )
        val id = runCatching { db.insert(row) }.getOrDefault(0L)
        job.pcmFile.delete()
        job.sidecar.delete()
        return row.copy(id = id)
    }

    private fun writeWav(store: AudioStore, job: SegmentJob, stats: AudioStats, base: String, dir: String, fade: Int): MediaStoreWriter.Saved {
        val name = FileNames.unique(base, "wav") { store.exists(it, dir) }
        val dataBytes = stats.keptFrames * Pcm.BYTES_PER_FRAME
        return store.write(name, "audio/x-wav", dir) { out ->
            out.write(WavHeader.build(dataBytes, info = WavHeader.infoFor(job.meta)))
            copyRange(job.pcmFile, stats, fade) { buf, n -> out.write(buf, 0, n) }
        }
    }

    private fun writeMp3(store: AudioStore, job: SegmentJob, stats: AudioStats, base: String, dir: String, fade: Int, kbps: Int, lengthMs: Long): MediaStoreWriter.Saved {
        val name = FileNames.unique(base, "mp3") { store.exists(it, dir) }
        return store.write(name, "audio/mpeg", dir) { out: OutputStream ->
            out.write(Id3v2.build(job.meta, lengthMs, Artwork.readJpeg(job.meta.artworkPath)))
            Mp3Encoder(bitrateKbps = kbps).use { enc ->
                copyRange(job.pcmFile, stats, fade) { buf, n -> enc.encode(buf, 0, n, out) }
                enc.finish(out)
            }
        }
    }

    private fun copyRange(file: File, stats: AudioStats, fadeFrames: Int, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(Mp3Encoder.CHUNK_FRAMES * Pcm.BYTES_PER_FRAME)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(stats.startFrame * Pcm.BYTES_PER_FRAME)
            var left = stats.keptFrames * Pcm.BYTES_PER_FRAME
            var frame = 0L
            while (left > 0) {
                val want = minOf(left, buf.size.toLong()).toInt()
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                val whole = n - n % Pcm.BYTES_PER_FRAME
                if (whole > 0) {
                    // Плавне наростання й затухання (налаштування, типово 0,5 с) — без «чирку» на краях файлу.
                    EdgeFade.apply(buf, whole, frame, stats.keptFrames, fadeFrames)
                    sink(buf, whole)
                    frame += whole / Pcm.BYTES_PER_FRAME
                }
                left -= n
            }
        }
    }

    /** Рівень кожних 10 мс (dBFS). */
    private fun blockLevels(file: File, clips: ClipCounter): DoubleArray {
        val blockFrames = LevelAnalysis.blockFrames()
        val blockBytes = blockFrames * Pcm.BYTES_PER_FRAME
        val total = (file.length() / blockBytes).toInt() + 1
        val out = DoubleArray(total)
        val bytes = ByteArray(blockBytes)
        val shorts = ShortArray(blockFrames * Pcm.CHANNELS)
        var count = 0
        BufferedInputStream(FileInputStream(file), 256 * 1024).use { input ->
            while (true) {
                var read = 0
                while (read < blockBytes) {
                    val n = input.read(bytes, read, blockBytes - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < Pcm.BYTES_PER_FRAME) break
                val samples = Pcm.bytesToShorts(bytes, 0, read - read % 2, shorts)
                out[count++] = Pcm.rmsDb(shorts, 0, samples)
                clips.feed(shorts, samples - samples % Pcm.CHANNELS)
                if (read < blockBytes) break
            }
        }
        return out.copyOf(count)
    }

    private fun saveError(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return if (e is IOException && (msg.contains("ENOSPC") || msg.contains("No space"))) {
            "немає місця на телефоні"
        } else {
            "помилка збереження: $msg"
        }
    }
}
