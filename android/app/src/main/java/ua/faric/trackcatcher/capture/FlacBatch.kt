package ua.faric.trackcatcher.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import ua.faric.trackcatcher.core.FileNames
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.FlacEncoder
import ua.faric.trackcatcher.core.Lossless
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.core.TrackNumber
import ua.faric.trackcatcher.core.WavToFlac
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.data.Settings

/**
 * WAV → FLAC з обкладинками пачкою (T69). Стан живе тут, а не в екрані: faric 17.10 22:25 — «обірвалася після
 * перевороту» (екран перебудувався, вікно поступу зникло, підсумку не було). Роботу робить [FlacBatchService]
 * (повідомлення в шторці, можна згорнути й повертати), екран лише показує [state].
 */
object FlacBatch {
    data class State(
        val running: Boolean = false,
        val index: Int = 0,
        val total: Int = 0,
        val label: String = "",
        val percent: Int = 0,
        val made: Int = 0,
        val problems: List<String> = emptyList(),
        val finished: Boolean = false,
        val cancelled: Boolean = false,
    )

    @Volatile var state = State()
        private set

    /** Вікно «Зробити?» відкрите — які записи (щоб воно пережило поворот). */
    @Volatile var pendingConfirm: LongArray? = null

    @Volatile internal var cancel = false

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun observe(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    internal fun update(change: (State) -> State) {
        state = change(state)
        val s = state
        main.post { listeners.forEach { it(s) } }
    }

    fun start(context: Context, ids: LongArray) {
        if (state.running || ids.isEmpty()) return
        cancel = false
        update { State(running = true, total = ids.size) }
        context.startForegroundService(Intent(context, FlacBatchService::class.java).putExtra(FlacBatchService.EXTRA_IDS, ids))
    }

    fun stop() {
        cancel = true
    }

    /** Підсумок побачили — забути. */
    fun acknowledge() {
        if (!state.running) update { State() }
    }

    fun isWav(r: RecordingRow) = r.wavUri != null && Lossless.ofName(r.wavName) == Lossless.WAV

    /** Один WAV → FLAC поруч з ним; в історії запис показує FLAC. Кидає помилку з поясненням. */
    fun makeOne(app: Context, r: RecordingRow, settings: Settings.Values, progress: (Float) -> Unit) {
        val wavUri = r.wavUri ?: throw java.io.IOException("нема WAV")
        val resolver = app.contentResolver
        val src = Uri.parse(wavUri)
        val ms = MediaStoreWriter(app)
        val info = resolver.openInputStream(src)?.buffered(64 * 1024)?.use { FileTags.read(it, ms.sizeOf(wavUri), r.wavName ?: "") }
            ?: throw java.io.IOException("WAV не відкривається — можливо, його прибрали")
        WavToFlac.problem(info)?.let { throw IllegalArgumentException(it) }
        val meta = TrackMeta(
            r.artist, r.title, r.album, r.durationMs,
            trackNo = trackNoFor(HistoryDb.get(app), r),
            year = java.util.Calendar.getInstance().apply { timeInMillis = r.createdAt }.get(java.util.Calendar.YEAR),
        )
        // Поруч з WAV: у «Музиці» — та сама папка; своя папка — як для нових записів.
        val (store: AudioStore, dir) = if (ms.isDocumentUri(wavUri)) {
            val tree = settings.treeUri.takeIf { it.isNotBlank() }?.let { runCatching { TreeWriter(app, Uri.parse(it)) }.getOrNull() }
                ?: throw java.io.IOException("своя папка недоступна — «Налаштування» → «Куди зберігається»")
            tree to FileNames.folderFor(meta, settings.albumFolders, settings.plainNames)
        } else {
            ms to (ms.relativePath(wavUri) ?: FileNames.folderFor(meta, settings.albumFolders, settings.plainNames))
        }
        val base = (r.wavName ?: FileNames.baseName(meta, settings.plainNames)).removeSuffix(".wav").removeSuffix(".WAV")
        val name = FileNames.unique(base, Lossless.FLAC.ext) { store.exists(it, dir) }
        val jpeg = Artwork.readJpeg(r.artworkPath)
        val saved = store.write(name, Lossless.FLAC.mime, dir) { out ->
            val a = resolver.openInputStream(src) ?: throw java.io.IOException("WAV не відкривається")
            val b = resolver.openInputStream(src) ?: throw java.io.IOException("WAV не відкривається")
            a.buffered(256 * 1024).use { p1 -> b.buffered(256 * 1024).use { p2 -> WavToFlac.encode(info, p1, p2, FlacEncoder.tagsFor(meta), jpeg, out, progress) } }
        }
        HistoryDb.get(app).replaceLossless(
            r.id, saved.uri, saved.name, saved.size,
            "FLAC з WAV зроблено ${TimeText.dateTime(System.currentTimeMillis())}" + (if (jpeg != null) " з обкладинкою" else "") + "; WAV лишився: ${r.wavName}",
        )
    }

    /** Номер пісні в альбомі так само, як під час запису (T68): «TRACK 10» з назви або по порядку сесії. */
    fun trackNoFor(db: HistoryDb, r: RecordingRow): Int {
        val album = r.album.trim().lowercase()
        val same = db.sessionRows(r.sessionId).filter { it.album.trim().lowercase() == album && (it.hasFile || it.id == r.id) }
        val upTo = same.takeWhile { it.id != r.id } + r
        return TrackNumber.all(upTo.map { it.title }).last()
    }
}
