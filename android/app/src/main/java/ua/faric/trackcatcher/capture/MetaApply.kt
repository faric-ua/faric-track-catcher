package ua.faric.trackcatcher.capture

import android.content.Context
import ua.faric.trackcatcher.core.ErrorText
import ua.faric.trackcatcher.core.FileTags
import ua.faric.trackcatcher.core.MetaEdit
import ua.faric.trackcatcher.core.Retag
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.RecordingRow

/**
 * T78: застосувати [MetaEdit.Spec] до запису — теги FLAC / MP3 (безпечно, [Renumber.safeRetag]), ім'я файлу
 * (у тій самій папці) і історія. Звук не змінюється, файли не переносяться й не видаляються.
 */
object MetaApply {
    private val FIELD = mapOf(
        "TITLE" to FileTags.Field.TITLE, "ARTIST" to FileTags.Field.ARTIST, "ALBUM" to FileTags.Field.ALBUM,
        "ALBUMARTIST" to FileTags.Field.ALBUM_ARTIST, "DATE" to FileTags.Field.YEAR, "TRACKNUMBER" to FileTags.Field.TRACK,
    )

    /** Файли запису, що на місці: (uri, ім'я, це MP3). */
    private fun files(app: Context, r: RecordingRow): List<Triple<String, String, Boolean>> {
        val store = MediaStoreWriter(app)
        return listOfNotNull(
            r.mp3Uri?.let { u -> r.mp3Name?.let { Triple(u, it, true) } },
            r.wavUri?.let { u -> r.wavName?.let { Triple(u, it, false) } },
        ).filter { store.stillThere(it.first) }
    }

    /** Що зараз: історія + теги першого файлу, де теги є (FLAC / MP3). */
    fun current(app: Context, r: RecordingRow): MetaEdit.Song {
        val base = MetaEdit.Song(r.artist, r.title, r.album)
        val f = files(app, r).firstOrNull { Retag.kindOf(it.second) != null } ?: return base
        val t = runCatching { Renumber.readTags(app, f.first, f.second) }.getOrNull() ?: return base
        return MetaEdit.Song(
            artist = r.artist.ifBlank { t[FileTags.Field.ARTIST].orEmpty() },
            title = r.title.ifBlank { t[FileTags.Field.TITLE].orEmpty() },
            album = r.album.ifBlank { t[FileTags.Field.ALBUM].orEmpty() },
            albumArtist = t[FileTags.Field.ALBUM_ARTIST].orEmpty(),
            year = t[FileTags.Field.YEAR].orEmpty(),
            track = t[FileTags.Field.TRACK].orEmpty(),
        )
    }

    /** Застосувати до одного запису. Повертає «файл\tпричина» того, що не вийшло (порожньо — усе гаразд). */
    fun apply(app: Context, r: RecordingRow, spec: MetaEdit.Spec, plainNames: Boolean): List<String> {
        val files = files(app, r)
        if (files.isEmpty()) return emptyList()
        val old = current(app, r)
        val new = MetaEdit.apply(old, spec)
        val store = MediaStoreWriter(app)
        val problems = ArrayList<String>()
        var mp3: Pair<String, String>? = null
        var wav: Pair<String, String>? = null
        var tagsDone = true
        for ((uri, name, isMp3) in files) {
            try {
                if (Retag.kindOf(name) != null) {
                    val mine = Renumber.readTags(app, uri, name)
                    val fileSong = MetaEdit.Song(
                        mine[FileTags.Field.ARTIST].orEmpty(), mine[FileTags.Field.TITLE].orEmpty(), mine[FileTags.Field.ALBUM].orEmpty(),
                        mine[FileTags.Field.ALBUM_ARTIST].orEmpty(), mine[FileTags.Field.YEAR].orEmpty(), mine[FileTags.Field.TRACK].orEmpty(),
                    )
                    val set = MetaEdit.tagChanges(fileSong, new)
                    if (set.isNotEmpty()) {
                        Renumber.safeRetag(app, uri, name, set) { after ->
                            set.all { (k, v) ->
                                val got = after[FIELD.getValue(k)]?.trim().orEmpty()
                                if (k == "TRACKNUMBER") got.substringBefore('/').trim().toIntOrNull() == v.substringBefore('/').trim().toIntOrNull() else got == v.trim()
                            }
                        }
                    }
                }
                val newName = MetaEdit.newName(name, new, spec.name, plainNames)
                if (newName != null) {
                    val saved = store.rename(uri, newName)
                    if (isMp3) mp3 = saved.uri to saved.name else wav = saved.uri to saved.name
                }
            } catch (e: Exception) {
                tagsDone = false
                problems += "$name\t${ErrorText.human(e)}"
            }
        }
        val changedHistory = new.artist != r.artist || new.title != r.title || new.album != r.album
        if (tagsDone && (changedHistory || mp3 != null || wav != null)) {
            val wavNote = if (files.any { !it.third && Retag.kindOf(it.second) == null } && MetaEdit.tagChanges(old, new).isNotEmpty()) " (у WAV теги всередині лишились старі)" else ""
            HistoryDb.get(app).updateMeta(r.id, new.artist, new.title, new.album, mp3, wav, "теги змінено ${TimeText.dateTime(System.currentTimeMillis())}$wavNote")
        } else if (mp3 != null || wav != null) {
            HistoryDb.get(app).updateMeta(r.id, r.artist, r.title, r.album, mp3, wav, "")
        }
        return problems
    }
}
