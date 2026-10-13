package ua.faric.trackcatcher.data

import android.content.Context
import ua.faric.trackcatcher.core.OutputFormat
import ua.faric.trackcatcher.core.RepeatPolicy

/** Налаштування власника. Значення за замовчуванням: MP3 320, не зберігати коротше 30 с. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    data class Values(
        val format: OutputFormat,
        val mp3Bitrate: Int,
        val minLengthSec: Int,
        val repeat: RepeatPolicy,
    ) {
        val label: String
            get() = when (format) {
                OutputFormat.MP3 -> "MP3 $mp3Bitrate"
                OutputFormat.WAV -> "WAV"
                OutputFormat.BOTH -> "MP3 $mp3Bitrate + WAV"
            }
    }

    var format: OutputFormat
        get() = OutputFormat.fromCode(prefs.getString(KEY_FORMAT, null))
        set(v) = prefs.edit().putString(KEY_FORMAT, v.code).apply()

    var mp3Bitrate: Int
        get() = prefs.getInt(KEY_BITRATE, 320)
        set(v) = prefs.edit().putInt(KEY_BITRATE, v).apply()

    var minLengthSec: Int
        get() = prefs.getInt(KEY_MIN_LENGTH, 30)
        set(v) = prefs.edit().putInt(KEY_MIN_LENGTH, v).apply()

    var repeat: RepeatPolicy
        get() = RepeatPolicy.fromCode(prefs.getString(KEY_REPEAT, null))
        set(v) = prefs.edit().putString(KEY_REPEAT, v.code).apply()

    fun snapshot() = Values(format, mp3Bitrate, minLengthSec, repeat)

    companion object {
        private const val KEY_FORMAT = "format"
        private const val KEY_BITRATE = "mp3_bitrate"
        private const val KEY_MIN_LENGTH = "min_length_sec"
        private const val KEY_REPEAT = "repeat"
        val MIN_LENGTH_CHOICES = listOf(0, 15, 30, 45, 60)
    }
}
