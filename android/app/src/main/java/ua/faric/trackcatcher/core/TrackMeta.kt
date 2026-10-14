package ua.faric.trackcatcher.core

/** Що грає — з повідомлення плеєра у шторці (MediaSession). */
data class TrackMeta(
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sourcePackage: String = "",
    val artworkPath: String? = null,
    /** Ідентифікатор пісні в плеєрі (якщо плеєр його дає) — лише для порівняння, у файл не пишеться. */
    val mediaId: String = "",
) {
    val isUnknown: Boolean get() = artist.isBlank() && title.isBlank()

    /**
     * Та сама пісня? Альбом і обкладинку не порівнюємо — плеєри часто дописують їх пізніше.
     * Однакова назва ще не означає ту саму пісню: в альбомі буває кілька «Velocity» різної
     * тривалості (AIMP, 14.10) — тож різна тривалість або різний ID у плеєрі = різні пісні.
     */
    fun sameTrack(other: TrackMeta?): Boolean {
        if (other == null) return false
        if (norm(artist) != norm(other.artist) || norm(title) != norm(other.title)) return false
        if (mediaId.isNotBlank() && other.mediaId.isNotBlank() && mediaId != other.mediaId) return false
        if (durationMs > 0 && other.durationMs > 0 && Math.abs(durationMs - other.durationMs) > DURATION_TOLERANCE_MS) return false
        return true
    }

    fun displayName(): String = when {
        isUnknown -> UNKNOWN_NAME
        artist.isBlank() -> title.trim()
        title.isBlank() -> artist.trim()
        else -> "${artist.trim()} — ${title.trim()}"
    }

    companion object {
        const val UNKNOWN_NAME = "Невідома пісня"
        const val DURATION_TOLERANCE_MS = 2_000L
        private fun norm(s: String) = s.trim().lowercase()
    }
}
