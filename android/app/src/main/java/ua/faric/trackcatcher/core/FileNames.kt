package ua.faric.trackcatcher.core

/** Назва файлу «Виконавець — Назва»: заборонені символи → «_», повтор → «(2)». */
object FileNames {
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")
    const val FALLBACK = "Без назви"

    fun sanitize(raw: String, maxLength: Int = 120): String {
        var s = forbidden.replace(raw, "_")
        s = spaces.replace(s, " ").trim().trim('.', ' ')
        if (s.length > maxLength) s = s.substring(0, maxLength).trim().trimEnd('.', ' ')
        return s.ifBlank { FALLBACK }
    }

    /**
     * [plain] — без смайликів і невидимих символів (T36, faric 14.10 08:18: «за замовчуванням усі прибирай»).
     * Теги всередині файлу лишаються як є — їх це не стосується.
     */
    fun baseName(meta: TrackMeta, plain: Boolean = false): String {
        if (!plain) return sanitize(meta.displayName())
        val artist = stripSymbols(meta.artist)
        val title = stripSymbols(meta.title)
        // Назва складалась лише зі смайликів — краще зі смайликами, ніж «Без назви».
        if (artist.isBlank() && title.isBlank()) return sanitize(meta.displayName())
        return sanitize(TrackMeta(artist, title).displayName())
    }

    /**
     * Прибрати смайлики, знаки-символи й невидимі службові символи (з'єднувачі, варіанти, відтінки шкіри).
     * Літери будь-якої мови, цифри й розділові знаки лишаються.
     */
    fun stripSymbols(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val t = Character.getType(cp)
            val drop = t == Character.OTHER_SYMBOL.toInt() || t == Character.FORMAT.toInt() ||
                t == Character.PRIVATE_USE.toInt() || t == Character.UNASSIGNED.toInt() ||
                t == Character.SURROGATE.toInt() || t == Character.ENCLOSING_MARK.toInt() ||
                cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp in 0x1F3FB..0x1F3FF || cp in 0xE0000..0xE007F
            if (drop) sb.append(' ') else sb.appendCodePoint(cp)
        }
        return spaces.replace(sb, " ").trim()
    }

    const val ROOT_DIR = "Music/Track Catcher/"

    /** Папка для файлу: «Music/Track Catcher/Альбом/» або просто «Music/Track Catcher/», якщо альбому нема. */
    fun folderFor(meta: TrackMeta, albumFolders: Boolean, plain: Boolean = false): String {
        if (!albumFolders || meta.album.isBlank()) return ROOT_DIR
        val album = if (plain) stripSymbols(meta.album).ifBlank { meta.album } else meta.album
        return ROOT_DIR + sanitize(album, 80) + "/"
    }

    /**
     * Своя папка (T29): id документа Android → слова.
     * «primary:Records/Neon» → «Пам'ять телефона › Records › Neon»; «1A2B-3C4D:Music/X» → «Карта пам'яті › Music › X».
     */
    fun documentLabel(documentId: String): String {
        val volume = documentId.substringBefore(':')
        val path = documentId.substringAfter(':', "").trim('/')
        val head = if (volume == "primary") "Пам'ять телефона" else "Карта пам'яті"
        return (listOf(head) + path.split('/').filter { it.isNotBlank() }).joinToString(" › ")
    }

    /** «Music/Track Catcher/Neon/» → «Музика › Track Catcher › Neon». */
    fun folderLabel(relativeDir: String): String {
        val parts = relativeDir.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "Музика › Track Catcher"
        val first = if (parts[0] == "Music") "Музика" else parts[0]
        return (listOf(first) + parts.drop(1)).joinToString(" › ")
    }

    fun unique(base: String, ext: String, exists: (String) -> Boolean): String {
        val first = "$base.$ext"
        if (!exists(first)) return first
        for (n in 2..999) {
            val candidate = "$base ($n).$ext"
            if (!exists(candidate)) return candidate
        }
        return "$base (${System.currentTimeMillis()}).$ext"
    }
}
