package ua.faric.trackcatcher.core

/**
 * Редактор тегів і імен файлів (T78, faric 19.10 19:46: «додай можливість редагувати метадані — поштучно, по сесіях,
 * по альбомах; і перейменовувати файли: записалось "автор — ім'я", а я хочу саме ім'я; вибрав сесію — змінив,
 * поставив, що на що замінити, — пакетна обробка й поштучна»).
 *
 * [Spec] — що змінити: поле `null` — не чіпати; «знайти → замінити» — у назвах; [NameStyle] — як назвати файл.
 */
object MetaEdit {
    enum class NameStyle(val code: String, val label: String) {
        KEEP("keep", "Не міняти"),
        ARTIST_TITLE("artist_title", "Виконавець — Назва"),
        TITLE("title", "Лише назва"),
        NUMBER_TITLE("number_title", "Номер. Назва");

        companion object {
            fun of(code: String?) = entries.firstOrNull { it.code == code } ?: KEEP
        }
    }

    /** Пісня — те, що в тегах і в історії. Рядки; порожній — нема. */
    data class Song(
        val artist: String = "",
        val title: String = "",
        val album: String = "",
        val albumArtist: String = "",
        val year: String = "",
        val track: String = "",
    )

    data class Spec(
        val artist: String? = null,
        val title: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val year: String? = null,
        val track: String? = null,
        val find: String = "",
        val replace: String = "",
        val name: NameStyle = NameStyle.KEEP,
    ) {
        val isEmpty: Boolean
            get() = listOf(artist, title, album, albumArtist, year, track).all { it == null } && find.isEmpty() && name == NameStyle.KEEP

        /** Для передачі у фонову службу. */
        fun toMap(): Map<String, String> = buildMap {
            artist?.let { put("artist", it) }
            title?.let { put("title", it) }
            album?.let { put("album", it) }
            albumArtist?.let { put("albumArtist", it) }
            year?.let { put("year", it) }
            track?.let { put("track", it) }
            if (find.isNotEmpty()) { put("find", find); put("replace", replace) }
            put("name", name.code)
        }

        companion object {
            fun fromMap(m: Map<String, String>) = Spec(
                m["artist"], m["title"], m["album"], m["albumArtist"], m["year"], m["track"],
                m["find"].orEmpty(), m["replace"].orEmpty(), NameStyle.of(m["name"]),
            )
        }
    }

    private val spaces = Regex("\\s+")

    fun apply(s: Song, spec: Spec): Song {
        var t = spec.title?.trim() ?: s.title
        if (spec.find.isNotEmpty() && t.contains(spec.find)) t = spaces.replace(t.replace(spec.find, spec.replace), " ").trim()
        return Song(
            artist = spec.artist?.trim() ?: s.artist,
            title = t,
            album = spec.album?.trim() ?: s.album,
            albumArtist = spec.albumArtist?.trim() ?: s.albumArtist,
            year = spec.year?.trim() ?: s.year,
            track = spec.track?.trim() ?: s.track,
        )
    }

    /** Теги, які треба переписати (назви полів Vorbis; для MP3 Retag перекладає в ID3). Порожнє нове значення — не пишемо. */
    fun tagChanges(old: Song, new: Song): Map<String, String> = buildMap {
        fun put(key: String, a: String, b: String) { if (b.isNotBlank() && a.trim() != b.trim()) put(key, b.trim()) }
        put("TITLE", old.title, new.title)
        put("ARTIST", old.artist, new.artist)
        put("ALBUM", old.album, new.album)
        put("ALBUMARTIST", old.albumArtist, new.albumArtist)
        put("DATE", old.year, new.year)
        if (new.track.isNotBlank() && old.track.substringBefore('/').trim().toIntOrNull() != new.track.substringBefore('/').trim().toIntOrNull()) put("TRACKNUMBER", new.track.trim())
    }

    /** Нове ім'я файлу за [style] (те саме розширення); null — лишити як є. */
    fun newName(oldName: String, s: Song, style: NameStyle, plain: Boolean): String? {
        val ext = oldName.substringAfterLast('.', "").takeIf { it.isNotEmpty() } ?: return null
        val base = when (style) {
            NameStyle.KEEP -> return null
            NameStyle.ARTIST_TITLE -> FileNames.baseName(TrackMeta(s.artist, s.title), plain)
            NameStyle.TITLE -> FileNames.baseName(TrackMeta("", s.title), plain)
            NameStyle.NUMBER_TITLE -> {
                val n = s.track.substringBefore('/').trim().toIntOrNull()
                val t = FileNames.baseName(TrackMeta("", s.title), plain)
                if (n != null && n > 0) FileNames.sanitize("%02d. %s".format(n, t)) else t
            }
        }
        val name = "$base.$ext"
        return if (name == oldName) null else name
    }
}
