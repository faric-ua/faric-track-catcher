package ua.faric.trackcatcher.capture

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import ua.faric.trackcatcher.core.FileNames
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.Relink
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow
import ua.faric.trackcatcher.data.Settings

/**
 * T77: знайти перейменовані файли й зв'язати з історією (faric 18.10 16:18). Дивимось лише в папки, де лежали
 * зниклі файли; беремо лише файли, яких нема в історії; збіг — [Relink.match]. Файли не чіпаємо — міняється
 * лише історія (і, якщо faric вибере, назва з імені файлу — тоді через [TitleFix.fixTo]).
 */
object RelinkScan {
    class Result(
        val matches: List<Relink.Match>,
        /** Скільки файлів записів не на місці. */
        val lost: Int,
        /** Нових файлів у тих папках (нема в історії), що ні з чим не збіглися. */
        val strangers: Int,
        /** id запису → назва з нового імені файлу (лише де вона інша, ніж в історії). */
        val titles: Map<Long, String>,
    )

    /** Останній результат — щоб вікно пережило поворот. */
    @Volatile var last: Result? = null

    /** Пошук іде (вікно «Шукаю…» має пережити поворот — faric 21.10 01:17: «передивись усі вікна»). */
    @Volatile var running = false

    /** Помилка останнього пошуку (словами). */
    @Volatile var lastError: String? = null

    private const val MAX_FILES = 400
    private val AUDIO = setOf("flac", "mp3", "wav")

    fun scan(app: Context, missing: Collection<Long>): Result {
        val db = HistoryDb.get(app)
        val all = db.all()
        val byId = all.associateBy { it.id }
        val store = MediaStoreWriter(app)
        val settings = Settings(app).snapshot()
        val tree = settings.treeUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val known = all.flatMap { listOfNotNull(it.mp3Uri, it.wavUri) }.map { key(app, it) }.toSet()

        val lost = ArrayList<Relink.Lost>()
        val folders = LinkedHashMap<String, () -> List<Pair<String, String>>>() // ключ папки → (uri, ім'я) файлів
        for (id in missing) {
            val r = byId[id] ?: continue
            for ((uri, name, mp3) in listOf(Triple(r.mp3Uri, r.mp3Name, true), Triple(r.wavUri, r.wavName, false))) {
                if (uri == null || name == null || store.stillThere(uri)) continue
                lost += Relink.Lost(r.id, mp3, name, r.lengthMs, r.title)
                folderOf(app, uri, r, settings, tree)?.let { (k, list) -> folders.putIfAbsent(k, list) }
            }
        }
        val fresh = ArrayList<Relink.OnDisk>()
        val seen = HashSet<String>()
        for (list in folders.values) {
            for ((uri, name) in runCatching { list() }.getOrDefault(emptyList())) {
                if (fresh.size >= MAX_FILES) break
                if (name.substringAfterLast('.', "").lowercase() !in AUDIO) continue
                val k = key(app, uri)
                if (k in known || !seen.add(k)) continue
                val info = runCatching {
                    app.contentResolver.openInputStream(Uri.parse(uri))?.buffered(64 * 1024)?.use { FileTags.read(it, store.sizeOf(uri), name) }
                }.getOrNull() ?: continue
                fresh += Relink.OnDisk(uri, name, info.durationMs, info[FileTags.Field.TITLE])
            }
        }
        val matches = Relink.match(lost, fresh)
        val titles = LinkedHashMap<Long, String>()
        for (m in matches) {
            val r = byId[m.lost.rowId] ?: continue
            if (r.id in titles) continue
            val t = Relink.titleFromName(m.found.name, r.artist)
            if (Relink.titleChanged(r.title, t)) titles[r.id] = t
        }
        return Result(matches, lost.size, fresh.size - matches.size, titles).also { last = it }
    }

    /** Записати в історію нові імена й адреси файлів. Повертає id записів, які зв'язано. */
    fun apply(app: Context, result: Result): List<Long> {
        val db = HistoryDb.get(app)
        val done = ArrayList<Long>()
        for ((id, group) in result.matches.groupBy { it.lost.rowId }) {
            val r = db.get(id) ?: continue
            val mp3 = group.firstOrNull { it.lost.mp3 }?.found?.let { it.uri to it.name }
            val wav = group.firstOrNull { !it.lost.mp3 }?.found?.let { it.uri to it.name }
            val was = group.joinToString(", ") { it.lost.name }
            db.updateTitle(r.id, r.title, mp3, wav, "файл знайдено після перейменування ${TimeText.dateTime(System.currentTimeMillis())} (було: $was)")
            done += id
        }
        return done
    }

    /** Порівнюваний ключ файлу: для своєї папки — id документа (адреси дерева бувають різні), інакше — адреса. */
    private fun key(app: Context, uri: String): String = runCatching {
        val u = Uri.parse(uri)
        if (DocumentsContract.isDocumentUri(app, u)) "doc:" + DocumentsContract.getDocumentId(u) else uri
    }.getOrDefault(uri)

    /** Папка, де лежав файл, і як прочитати її вміст. */
    private fun folderOf(app: Context, uri: String, r: RecordingRow, settings: Settings.Values, tree: Uri?): Pair<String, () -> List<Pair<String, String>>>? {
        val u = Uri.parse(uri)
        val resolver = app.contentResolver
        if (runCatching { DocumentsContract.isDocumentUri(app, u) }.getOrDefault(false)) {
            val t = tree ?: return null
            val docId = DocumentsContract.getDocumentId(u)
            val parent = docId.substringBeforeLast('/', "").takeIf { it.isNotEmpty() } ?: return null
            return ("doc:$parent") to {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(t, parent)
                val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                resolver.query(children, cols, null, null, null)?.use { c ->
                    buildList { while (c.moveToNext()) add(DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0)).toString() to (c.getString(1) ?: "")) }
                } ?: emptyList()
            }
        }
        // «Музика»: запис про файл зник разом з ним — папку беремо з правила, за яким Track Catcher її називає.
        val dir = FileNames.folderFor(TrackMeta(r.artist, r.title, r.album), settings.albumFolders, settings.plainNames)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ("ms:$dir") to {
            resolver.query(
                collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(dir), null,
            )?.use { c ->
                buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0)).toString() to (c.getString(1) ?: "")) }
            } ?: emptyList()
        }
    }
}
