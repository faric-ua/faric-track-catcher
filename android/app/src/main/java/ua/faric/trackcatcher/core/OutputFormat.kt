package ua.faric.trackcatcher.core

/** Файл без втрат: WAV (як є) або FLAC (стиснутий без втрат, ~ удвічі менший, T55). */
enum class Lossless(val ext: String, val mime: String, val label: String) {
    WAV("wav", "audio/x-wav", "WAV"),
    FLAC("flac", "audio/flac", "FLAC");

    companion object {
        /** Який файл без втрат — за розширенням імені (в історії він лежить у полях wav_*). */
        fun ofName(name: String?): Lossless = if (name != null && name.lowercase().endsWith(".flac")) FLAC else WAV
    }
}

/**
 * Формат запису. [lossless] — файл без втрат (WAV або FLAC) або null; [mp3] — ще й MP3.
 * Порядок — як кнопки в «Налаштуваннях». Типово — FLAC (faric 16.10: «щоб писав без втрат»).
 */
enum class OutputFormat(val code: String, val label: String, val mp3: Boolean, val lossless: Lossless?) {
    FLAC("flac", "FLAC", false, Lossless.FLAC),
    MP3("mp3", "MP3", true, null),
    WAV("wav", "WAV", false, Lossless.WAV),
    MP3_FLAC("mp3flac", "MP3 + FLAC", true, Lossless.FLAC),
    BOTH("both", "MP3 + WAV", true, Lossless.WAV);

    companion object {
        val DEFAULT = FLAC
        fun fromCode(code: String?): OutputFormat = entries.firstOrNull { it.code == code } ?: DEFAULT
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
    /** FLAC — залежить від музики, звичайно 50–70 % WAV; для підказки беремо 60 %. */
    fun flacBytesPerMinute(): Long = wavBytesPerMinute() * 6 / 10
    fun losslessBytesPerMinute(l: Lossless): Long = if (l == Lossless.FLAC) flacBytesPerMinute() else wavBytesPerMinute()
}
