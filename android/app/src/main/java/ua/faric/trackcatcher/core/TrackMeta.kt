package ua.faric.trackcatcher.core

/** Що грає — з повідомлення плеєра у шторці (MediaSession). */
data class TrackMeta(
    val artist: String = "",
    val title: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val sourcePackage: String = "",
    val artworkPath: String? = null,
) {
    val isUnknown: Boolean get() = artist.isBlank() && title.isBlank()

    /** Та сама пісня? Альбом і обкладинку не порівнюємо — плеєри часто дописують їх пізніше. */
    fun sameTrack(other: TrackMeta?): Boolean =
        other != null && norm(artist) == norm(other.artist) && norm(title) == norm(other.title)

    fun displayName(): String = when {
        isUnknown -> UNKNOWN_NAME
        artist.isBlank() -> title.trim()
        title.isBlank() -> artist.trim()
        else -> "${artist.trim()} — ${title.trim()}"
    }

    companion object {
        const val UNKNOWN_NAME = "Невідома пісня"
        private fun norm(s: String) = s.trim().lowercase()
    }
}
