package ua.faric.trackcatcher.capture

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.IOException
import java.io.OutputStream
import ua.faric.trackcatcher.core.FileNames

/**
 * Своя папка (T29, faric 14.10: «вибір папки на флешці чи деінде»). Власник вибирає її у вікні
 * Android; Android дає застосунку постійний дозвіл саме на цю папку — інших файлів ми не бачимо.
 * Вибрана папка — це «корінь» замість «Музика › Track Catcher»; альбоми — папками всередині.
 */
class TreeWriter(context: Context, private val tree: Uri) : AudioStore {
    private val resolver = context.applicationContext.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val rootDoc: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    private data class Child(val id: String, val name: String, val isDir: Boolean)

    private fun children(parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Child(c.getString(0), c.getString(1) ?: "", c.getString(2) == Document.MIME_TYPE_DIR))
            }
        } ?: emptyList()
    }

    /** Підпапки з «Music/Track Catcher/Альбом/» → [«Альбом»] всередині вибраної папки. */
    private fun parts(dir: String): List<String> =
        dir.removePrefix(FileNames.ROOT_DIR).trim('/').split('/').filter { it.isNotBlank() }

    /** id папки для [dir]; [create] — створити, якщо нема. null — нема (або не вдалося). */
    private fun folderId(dir: String, create: Boolean): String? {
        var id = rootId
        for (name in parts(dir)) {
            val found = children(id).firstOrNull { it.isDir && it.name == name }
            id = when {
                found != null -> found.id
                create -> {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val made = DocumentsContract.createDocument(resolver, parent, Document.MIME_TYPE_DIR, name)
                        ?: return null
                    DocumentsContract.getDocumentId(made)
                }
                else -> return null
            }
        }
        return id
    }

    override fun exists(name: String, dir: String): Boolean {
        val id = folderId(dir, create = false) ?: return false
        return children(id).any { !it.isDir && it.name == name }
    }

    override fun write(name: String, mime: String, dir: String, body: (OutputStream) -> Unit): MediaStoreWriter.Saved {
        val parentId = folderId(dir, create = true) ?: throw IOException("не вдалося створити папку у своїй папці")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("не вдалося створити файл у своїй папці")
        try {
            val stream = resolver.openOutputStream(doc, "w") ?: throw IOException("файл не відкривається на запис")
            stream.buffered(256 * 1024).use { body(it) }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
        var actualName = name
        var size = 0L
        resolver.query(doc, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                actualName = c.getString(0) ?: name
                size = c.getLong(1)
            }
        }
        return MediaStoreWriter.Saved(doc.toString(), actualName, size)
    }

    /** Чи є щось у папці (для «Відкрити папку»). */
    fun isEmpty(): Boolean = runCatching { children(rootId).isEmpty() }.getOrDefault(true)

    /** Папки альбомів усередині вибраної папки. */
    fun albumFolders(): List<String> = runCatching {
        children(rootId).filter { it.isDir }.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }.getOrDefault(emptyList())

    /** Документ самої папки — щоб відкрити її в «Моїх файлах». */
    fun rootDocument(): Uri = rootDoc

    /** Чи досі є дозвіл і чи папка доступна (карту могли вийняти). */
    fun available(): Boolean = runCatching {
        resolver.query(rootDoc, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    companion object {
        fun labelForTree(tree: Uri): String =
            runCatching { FileNames.documentLabel(DocumentsContract.getTreeDocumentId(tree)) }.getOrDefault("Своя папка")
    }
}
