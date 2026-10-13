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
