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

    fun baseName(meta: TrackMeta): String = sanitize(meta.displayName())

    const val ROOT_DIR = "Music/Track Catcher/"

    /** Папка для файлу: «Music/Track Catcher/Альбом/» або просто «Music/Track Catcher/», якщо альбому нема. */
    fun folderFor(meta: TrackMeta, albumFolders: Boolean): String {
        if (!albumFolders || meta.album.isBlank()) return ROOT_DIR
        return ROOT_DIR + sanitize(meta.album, 80) + "/"
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
