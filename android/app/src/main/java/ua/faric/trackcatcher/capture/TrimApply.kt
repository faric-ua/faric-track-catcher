package ua.faric.trackcatcher.capture

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import ua.faric.trackcatcher.core.ErrorText
import ua.faric.trackcatcher.core.FileNames
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.FlacEncoder
import ua.faric.trackcatcher.core.Lossless
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.core.TrimFade
import ua.faric.trackcatcher.core.WavHeader
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.encode.Mp3Encoder

/**
 * T79: обрізати / наростання / затухання в уже записаному файлі. Файл розкодовується в тимчасовий PCM (WAV — як є,
 * FLAC і MP3 — розкодувальником Android), обрізається й кодується в той самий формат; теги й обкладинка — зі старого
 * файлу. Збереження — як вибрав faric ([TrimFade.Save]). Заміна безпечна: копія старого в кеші, збій — повертаємо.
 */
object TrimApply {
    private const val ORIGINALS = "Оригінали"

    internal class Decoded(val file: File, val rate: Int, val frames: Long)

    /** Застосувати до всіх файлів запису. Повертає «файл\tпричина» того, що не вийшло. */
    fun apply(app: Context, r: RecordingRow, plan: TrimFade.Plan): List<String> {
        val store = MediaStoreWriter(app)
        val problems = ArrayList<String>()
        var mp3: Pair<String, String>? = null
        var wav: Pair<String, String>? = null
        val notes = ArrayList<String>()
        for ((uri, name, isMp3) in listOfNotNull(
            r.mp3Uri?.let { u -> r.mp3Name?.let { Triple(u, it, true) } },
            r.wavUri?.let { u -> r.wavName?.let { Triple(u, it, false) } },
        )) {
            if (!store.stillThere(uri)) continue
            try {
                val (saved, note) = one(app, r, uri, name, plan)
                if (saved != null) { if (isMp3) mp3 = saved else wav = saved }
                notes += note
            } catch (e: Exception) {
                problems += "$name\t${ErrorText.human(e)}"
            } finally {
                File(app.cacheDir, "trim").listFiles()?.forEach { it.delete() }
            }
        }
        if (notes.isNotEmpty()) {
            val db = HistoryDb.get(app)
            db.updateMeta(r.id, r.artist, r.title, r.album, mp3, wav, "${TimeText.dateTime(System.currentTimeMillis())}: " + notes.joinToString("; "))
            db.updateLength(r.id, (r.lengthMs - plan.cutStartMs.coerceAtLeast(0) - plan.cutEndMs.coerceAtLeast(0)).coerceAtLeast(0))
            if (r.holes != null) db.updateHoles(r.id, null) // місця тріск зсунулись — перевірити наново
        }
        return problems
    }

    /** Один файл. Повертає (нова адреса й ім'я, якщо змінились; примітка для історії). */
    private fun one(app: Context, r: RecordingRow, uri: String, name: String, plan: TrimFade.Plan): Pair<Pair<String, String>?, String> {
        val dir = File(app.cacheDir, "trim").apply { mkdirs() }
        val resolver = app.contentResolver
        val u = Uri.parse(uri)
        val ext = name.substringAfterLast('.', "").lowercase()
        val original = File(dir, "original.$ext")
        resolver.openInputStream(u)?.use { i -> original.outputStream().use { o -> i.copyTo(o, 256 * 1024) } } ?: throw IOException("файл не відкривається")
        val pcm = decode(app, original, ext, File(dir, "pcm.raw"))
        val (first, count) = TrimFade.range(pcm.frames, plan, pcm.rate)
        val fadeIn = plan.fadeInMs * pcm.rate / 1000
        val fadeOut = plan.fadeOutMs * pcm.rate / 1000
        val result = File(dir, "result.$ext")
        result.outputStream().buffered(256 * 1024).use { out ->
            when (ext) {
                "wav" -> {
                    out.write(WavHeader.build(count * 4, pcm.rate, 2, 16, WavHeader.infoFor(TrackMeta(r.artist, r.title, r.album))))
                    shaped(pcm, first, count, fadeIn, fadeOut) { b, n -> out.write(b, 0, n) }
                }
                "flac" -> {
                    val md5 = MessageDigest.getInstance("MD5")
                    shaped(pcm, first, count, fadeIn, fadeOut) { b, n -> md5.update(b, 0, n) }
                    val sum = md5.digest()
                    val bare = File(dir, "bare.flac")
                    bare.outputStream().buffered(256 * 1024).use { o ->
                        val enc = FlacEncoder(o, count, sum, emptyList(), null, pcm.rate)
                        shaped(pcm, first, count, fadeIn, fadeOut) { b, n -> enc.encode(b, 0, n) }
                        enc.finish()
                    }
                    val oldBlocks = original.inputStream().buffered(64 * 1024).use { TrimFade.flacBlocks(it) }
                    bare.inputStream().buffered(256 * 1024).use { TrimFade.flacWithOldMeta(it, oldBlocks, out) }
                }
                "mp3" -> {
                    out.write(original.inputStream().buffered(64 * 1024).use { TrimFade.id3Head(it) })
                    Mp3Encoder(sampleRate = pcm.rate, bitrateKbps = r.mp3Bitrate.takeIf { it > 0 } ?: 320).use { enc ->
                        shaped(pcm, first, count, fadeIn, fadeOut) { b, n -> enc.encode(b, 0, n, out) }
                        enc.finish(out)
                    }
                }
                else -> throw IOException("обрізати вмію FLAC, MP3 і WAV")
            }
        }
        // Перевірка: новий файл читається і тривалість та, що треба (±0,2 с; MP3 — оцінка).
        val check = result.inputStream().buffered(64 * 1024).use { FileTags.read(it, result.length(), name) }
        val want = count * 1000 / pcm.rate
        if (kotlin.math.abs(check.durationMs - want) > (if (ext == "mp3") 1_500 else 200)) throw IOException("новий файл не той за довжиною — нічого не змінено")
        val what = describe(plan)
        return when (plan.save) {
            TrimFade.Save.NEW_FILE -> {
                val saved = writeNew(app, uri, name, result)
                (saved.uri to saved.name) to "$what — новий файл «${saved.name}», старий лишився «$name»"
            }
            TrimFade.Save.REPLACE_KEEP -> {
                val kept = keepOriginal(app, uri, name, original)
                replace(app, u, result, original)
                null to "$what; оригінал — «$kept»"
            }
            TrimFade.Save.REPLACE -> {
                replace(app, u, result, original)
                null to "$what (без копії)"
            }
        }
    }

    fun describe(plan: TrimFade.Plan): String = buildList {
        if (plan.cutStartMs > 0) add("обрізано початок ${sec(plan.cutStartMs)}")
        if (plan.cutEndMs > 0) add("кінець ${sec(plan.cutEndMs)}")
        if (plan.fadeInMs > 0) add("наростання ${sec(plan.fadeInMs)}")
        if (plan.fadeOutMs > 0) add("затухання ${sec(plan.fadeOutMs)}")
    }.joinToString(", ")

    fun sec(ms: Long): String = if (ms % 1000 == 0L) "${ms / 1000} с" else "%.1f с".format(ms / 1000.0).replace('.', ',')

    /** PCM [first, first+count) кадрів шматками, з наростанням / затуханням. */
    private fun shaped(pcm: Decoded, first: Long, count: Long, fadeIn: Long, fadeOut: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(Mp3Encoder.CHUNK_FRAMES * 4)
        RandomAccessFile(pcm.file, "r").use { f ->
            f.seek(first * 4)
            var done = 0L
            while (done < count) {
                val want = minOf(buf.size.toLong(), (count - done) * 4).toInt()
                f.readFully(buf, 0, want)
                TrimFade.shape(buf, want, done, count, fadeIn, fadeOut, 2)
                sink(buf, want)
                done += want / 4
            }
        }
    }

    /** Розкодувати в 16-бітне стерео PCM. */
    internal fun decode(app: Context, file: File, ext: String, out: File): Decoded {
        if (ext == "wav") {
            val info = file.inputStream().buffered(64 * 1024).use { FileTags.read(it, file.length(), file.name) }
            if (info.bits != 16 || info.channels != 2) throw IOException("WAV не 16 біт стерео — такий обрізати не вмію")
            RandomAccessFile(file, "r").use { f ->
                f.seek(info.dataOffset)
                out.outputStream().buffered(256 * 1024).use { o ->
                    val buf = ByteArray(256 * 1024)
                    var left = minOf(info.dataBytes, f.length() - info.dataOffset)
                    while (left > 0) {
                        val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        o.write(buf, 0, n); left -= n
                    }
                }
            }
            return Decoded(out, info.sampleRate, out.length() / 4)
        }
        val ex = MediaExtractor()
        ex.setDataSource(file.absolutePath)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IOException("у файлі нема звуку")
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        try {
            out.outputStream().buffered(256 * 1024).use { o ->
                var chunk = ByteArray(64 * 1024)
                while (true) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val j = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        j == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        j >= 0 -> {
                            if (info.size > 0) {
                                val b = codec.getOutputBuffer(j)!!
                                b.position(info.offset); b.limit(info.offset + info.size)
                                if (chunk.size < info.size) chunk = ByteArray(info.size)
                                b.get(chunk, 0, info.size)
                                writeStereo(o, chunk, info.size, channels)
                            }
                            codec.releaseOutputBuffer(j, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
        if (channels !in 1..2) throw IOException("$channels каналів — обрізати вмію моно й стерео")
        return Decoded(out, rate, out.length() / 4)
    }

    /** 16-біт PCM → стерео (моно — той самий звук в обидва канали). */
    private fun writeStereo(o: OutputStream, b: ByteArray, n: Int, channels: Int) {
        if (channels == 2) { o.write(b, 0, n); return }
        if (channels != 1) throw IOException("$channels каналів — обрізати вмію моно й стерео")
        val s = ByteArray(n * 2)
        var k = 0
        var i = 0
        while (i + 1 < n) {
            s[k] = b[i]; s[k + 1] = b[i + 1]; s[k + 2] = b[i]; s[k + 3] = b[i + 1]
            k += 4; i += 2
        }
        o.write(s, 0, k)
    }

    /** Записати [from] на місце файлу [u]; збій — повернути [backup]. */
    private fun replace(app: Context, u: Uri, from: File, backup: File) {
        val resolver = app.contentResolver
        fun write(src: File) {
            val out = runCatching { resolver.openOutputStream(u, "wt") }.getOrNull() ?: resolver.openOutputStream(u, "w")
                ?: throw IOException("файл не відкривається на запис")
            out.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
        }
        try {
            write(from)
            // Звірка: у файлі тепер рівно стільки байтів, скільки в новому (рахуємо самі, а не з бази Android).
            var n = 0L
            resolver.openInputStream(u)?.use { i -> val buf = ByteArray(256 * 1024); while (true) { val k = i.read(buf); if (k < 0) break; n += k } }
            if (n != from.length()) throw IOException("після заміни файл не той")
        } catch (e: Exception) {
            runCatching { write(backup) }
            throw IOException("не вдалося замінити (${e.message}) — повернув файл, як був")
        }
    }

    /** Сховище й папка, де лежить файл [uri] (своя папка чи «Музика»). */
    private fun storeFor(app: Context, uri: String): Pair<AudioStore, String> {
        val ms = MediaStoreWriter(app)
        if (!ms.isDocumentUri(uri)) return ms to (ms.relativePath(uri) ?: FileNames.ROOT_DIR)
        val tree = ua.faric.trackcatcher.data.Settings(app).snapshot().treeUri.takeIf { it.isNotBlank() }
            ?: throw IOException("своя папка недоступна — «Налаштування» → «Куди зберігається»")
        val t = TreeWriter(app, Uri.parse(tree))
        // Папка файлу відносно кореня своєї папки: «Music/Track Catcher/<підпапки>/».
        val docId = android.provider.DocumentsContract.getDocumentId(Uri.parse(uri))
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree))
        val rel = docId.removePrefix(rootId).trim('/').substringBeforeLast('/', "")
        return t to (FileNames.ROOT_DIR + if (rel.isEmpty()) "" else "$rel/")
    }

    /** Копія оригіналу в «Оригінали» поруч з коренем Track Catcher. Повертає ім'я копії. */
    private fun keepOriginal(app: Context, uri: String, name: String, original: File): String {
        val (store, _) = storeFor(app, uri)
        val dir = FileNames.ROOT_DIR + ORIGINALS + "/"
        val ext = name.substringAfterLast('.', "")
        val copy = FileNames.unique(name.removeSuffix(".$ext"), ext) { store.exists(it, dir) }
        val saved = store.write(copy, mimeOf(ext), dir) { o -> original.inputStream().use { it.copyTo(o, 256 * 1024) } }
        if (saved.size != 0L && saved.size != original.length()) throw IOException("копія оригіналу не та — нічого не змінено")
        return "$ORIGINALS/${saved.name}"
    }

    private fun writeNew(app: Context, uri: String, name: String, result: File): MediaStoreWriter.Saved {
        val (store, dir) = storeFor(app, uri)
        val ext = name.substringAfterLast('.', "")
        val base = name.removeSuffix(".$ext") + " (обрізано)"
        val newName = FileNames.unique(base, ext) { store.exists(it, dir) }
        return store.write(newName, mimeOf(ext), dir) { o -> result.inputStream().use { it.copyTo(o, 256 * 1024) } }
    }

    private fun mimeOf(ext: String) = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> Lossless.FLAC.mime
        else -> "audio/x-wav"
    }
}
