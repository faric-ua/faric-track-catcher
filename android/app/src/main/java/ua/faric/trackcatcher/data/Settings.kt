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
        /** Кожен альбом — своя папка всередині «Track Catcher» (faric, 14.10). */
        val albumFolders: Boolean = true,
        /** Зрізати по 1 с на початку й у кінці кожного файлу (faric 14.10 05:31). */
        val trimEdges: Boolean = false,
        /** Наростання на початку й затухання в кінці, мс. */
        val fadeMs: Int = 500,
        /** Зсув сигналу «нова пісня», мс: «+» — розріз пізніше, «−» — раніше. */
        val cutShiftMs: Int = 0,
        /** Свій альбом для всіх пісень запису (T39); порожньо — як у плеєрі. */
        val customAlbum: String = "",
        /** Зупинитись після стількох пісень (T40); 0 — без обмеження. */
        val stopAfter: Int = 0,
        /** Своя папка (T29): дозвіл Android на папку; порожньо — «Музика › Track Catcher». */
        val treeUri: String = "",
        /** Без смайликів у назвах файлів і папок (T36); теги — як є. */
        val plainNames: Boolean = true,
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

    var albumFolders: Boolean
        get() = prefs.getBoolean(KEY_ALBUM_FOLDERS, true)
        set(v) = prefs.edit().putBoolean(KEY_ALBUM_FOLDERS, v).apply()

    var trimEdges: Boolean
        get() = prefs.getBoolean(KEY_TRIM_EDGES, false)
        set(v) = prefs.edit().putBoolean(KEY_TRIM_EDGES, v).apply()

    var fadeMs: Int
        get() = prefs.getInt(KEY_FADE_MS, 500)
        set(v) = prefs.edit().putInt(KEY_FADE_MS, v).apply()

    var cutShiftMs: Int
        get() = prefs.getInt(KEY_CUT_SHIFT_MS, 0)
        set(v) = prefs.edit().putInt(KEY_CUT_SHIFT_MS, v).apply()

    /** Свій альбом увімкнено (назва лишається в пам'яті, навіть коли вимкнено). */
    var useCustomAlbum: Boolean
        get() = prefs.getBoolean(KEY_USE_ALBUM, false)
        set(v) = prefs.edit().putBoolean(KEY_USE_ALBUM, v).apply()

    var customAlbum: String
        get() = prefs.getString(KEY_ALBUM, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ALBUM, v.trim()).apply()

    var stopAfter: Int
        get() = prefs.getInt(KEY_STOP_AFTER, 0)
        set(v) = prefs.edit().putInt(KEY_STOP_AFTER, v.coerceIn(0, MAX_STOP_AFTER)).apply()

    fun snapshot() = Values(
        format, mp3Bitrate, minLengthSec, repeat, albumFolders, trimEdges, fadeMs, cutShiftMs,
        customAlbum = if (useCustomAlbum) customAlbum else "",
        stopAfter = stopAfter,
        treeUri = treeUri,
        plainNames = plainNames,
    )

    var plainNames: Boolean
        get() = prefs.getBoolean(KEY_PLAIN_NAMES, true)
        set(v) = prefs.edit().putBoolean(KEY_PLAIN_NAMES, v).apply()

    var treeUri: String
        get() = prefs.getString(KEY_TREE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TREE, v).apply()

    companion object {
        private const val KEY_FORMAT = "format"
        private const val KEY_BITRATE = "mp3_bitrate"
        private const val KEY_MIN_LENGTH = "min_length_sec"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_ALBUM_FOLDERS = "album_folders"
        private const val KEY_TRIM_EDGES = "trim_edges"
        private const val KEY_FADE_MS = "fade_ms"
        private const val KEY_CUT_SHIFT_MS = "cut_shift_ms"
        private const val KEY_USE_ALBUM = "use_custom_album"
        private const val KEY_ALBUM = "custom_album"
        private const val KEY_STOP_AFTER = "stop_after"
        private const val KEY_TREE = "tree_uri"
        private const val KEY_PLAIN_NAMES = "plain_names"
        const val MAX_STOP_AFTER = 999
        const val TRIM_EDGE_MS = 1_000
        val FADE_CHOICES = listOf(0, 100, 250, 500, 1_000, 2_000)
        val SHIFT_CHOICES = listOf(-1_000, -500, -200, 0, 200, 500, 1_000)
        val MIN_LENGTH_CHOICES = listOf(0, 15, 30, 45, 60)
    }
}
