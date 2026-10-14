package ua.faric.trackcatcher.capture

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream

/** Збереження у «Музику»: Music/Track Catcher/. Файли видно будь-якому плеєру. */
class MediaStoreWriter(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    data class Saved(val uri: String, val name: String, val size: Long)

    fun exists(name: String): Boolean =
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(name, RELATIVE_DIR),
            null,
        )?.use { it.moveToFirst() } ?: false

    fun write(name: String, mime: String, body: (OutputStream) -> Unit): Saved {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR)
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

    /** Скільки наших файлів уже лежить у Music/Track Catcher/ (0 — папки ще нема). */
    fun count(): Int = try {
        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(RELATIVE_DIR),
            null,
        )?.use { it.count } ?: 0
    } catch (e: Exception) {
        0
    }

    fun delete(uri: String): Boolean = try {
        resolver.delete(Uri.parse(uri), null, null) > 0
    } catch (e: Exception) {
        false
    }

    companion object {
        const val RELATIVE_DIR = "Music/Track Catcher/"
        const val FOLDER_LABEL = "Музика › Track Catcher"
    }
}
