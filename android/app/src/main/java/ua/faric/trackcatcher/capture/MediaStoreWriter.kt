package ua.faric.trackcatcher.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import ua.faric.trackcatcher.core.FileNames
import java.io.IOException
import java.io.OutputStream

/** Збереження у «Музику»: Music/Track Catcher/. Файли видно будь-якому плеєру. */
class MediaStoreWriter(context: Context) : AudioStore {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    data class Saved(val uri: String, val name: String, val size: Long)

    override fun exists(name: String, dir: String): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, dir),
            null,
        )?.use { it.moveToFirst() } ?: false

    override fun write(name: String, mime: String, dir: String, body: (OutputStream) -> Unit): Saved {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("не вдалося створити файл у «Музиці»")
        try {
            val stream = resolver.openOutputStream(uri, "w") ?: throw IOException("файл не відкривається на запис")
            stream.buffered(256 * 1024).use { body(it) }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        var actualName = name
        var size = 0L
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                actualName = c.getString(0) ?: name
                size = c.getLong(1)
            }
        }
        return Saved(uri.toString(), actualName, size)
    }

    /** Скільки наших файлів уже лежить у Music/Track Catcher/ разом з папками альбомів (0 — папки ще нема). */
    fun count(): Int = try {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_DIR%"),
            null,
        )?.use { it.count } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Папки альбомів, які вже є в «Track Catcher» (для вибору свого альбому). */
    fun albumFolders(): List<String> = try {
        val names = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_DIR%/"),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val rest = (c.getString(0) ?: continue).removePrefix(RELATIVE_DIR).trim('/')
                if (rest.isNotBlank()) names += rest.substringBefore('/')
            }
        }
        names.toList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Папка, де лежить файл, словами: «Музика › Track Catcher › Neon». */
    fun folderLabel(uri: String): String? = try {
        val u = Uri.parse(uri)
        if (isDocument(u)) {
            FileNames.documentLabel(DocumentsContract.getDocumentId(u).substringBeforeLast('/'))
        } else resolver.query(u, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { FileNames.folderLabel(it) } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun isDocument(u: Uri) = DocumentsContract.isDocumentUri(app, u)

    /** Чи файл ще є (його могли прибрати в «Моїх файлах»). Працює і для своєї папки (T29). */
    fun stillThere(uri: String): Boolean = try {
        val u = Uri.parse(uri)
        val col = if (isDocument(u)) DocumentsContract.Document.COLUMN_DISPLAY_NAME else MediaStore.MediaColumns._ID
        resolver.query(u, arrayOf(col), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) {
        false
    }

    fun delete(uri: String): Boolean = try {
        val u = Uri.parse(uri)
        if (isDocument(u)) DocumentsContract.deleteDocument(resolver, u) else resolver.delete(u, null, null) > 0
    } catch (e: Exception) {
        false
    }

    companion object {
        const val RELATIVE_DIR = FileNames.ROOT_DIR
        const val FOLDER_LABEL = "Музика › Track Catcher"
    }
}
