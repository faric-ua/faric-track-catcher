package ua.faric.trackcatcher.core

enum class OutputFormat(val code: String, val label: String, val mp3: Boolean, val wav: Boolean) {
    MP3("mp3", "MP3", true, false),
    WAV("wav", "WAV", false, true),
    BOTH("both", "MP3 + WAV", true, true);

    companion object {
        fun fromCode(code: String?): OutputFormat = entries.firstOrNull { it.code == code } ?: MP3
    }
}

enum class RepeatPolicy(val code: String, val label: String) {
    AGAIN("again", "Записати ще раз"),
    SKIP("skip", "Пропустити");

    companion object {
        fun fromCode(code: String?): RepeatPolicy = entries.firstOrNull { it.code == code } ?: AGAIN
    }
}

/** Приблизний розмір 4-хвилинної пісні — для підказки в налаштуваннях. */
object SizeEstimate {
    fun wavBytesPerMinute(): Long = Pcm.SAMPLE_RATE.toLong() * Pcm.BYTES_PER_FRAME * 60
    fun mp3BytesPerMinute(kbps: Int): Long = kbps * 1000L / 8 * 60
}
