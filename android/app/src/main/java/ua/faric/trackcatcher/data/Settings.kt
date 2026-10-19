package ua.faric.trackcatcher.data

import android.content.Context
import ua.faric.trackcatcher.core.CutShift
import ua.faric.trackcatcher.core.EncodeTiming
import ua.faric.trackcatcher.core.OutputFormat
import ua.faric.trackcatcher.core.RepeatPolicy

/** Налаштування власника. Значення за замовчуванням: FLAC (з v0.1.21, T55; раніше MP3 320), не зберігати коротше 30 с. */
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
        /** Зупинитись через стільки хвилин записаного звуку — на зміні пісні (T60); 0 — без обмеження. */
        val stopAfterMin: Int = 0,
        /** Своя папка (T29): дозвіл Android на папку; порожньо — «Музика › Track Catcher». */
        val treeUri: String = "",
        /** Без смайликів у назвах файлів і папок (T36); теги — як є. */
        val plainNames: Boolean = true,
        /** Писати лише звук плеєра, чия пісня в шторці (T43); false — усе, що грає як «медіа». */
        val onlyPlayer: Boolean = true,
        /** Мітка гучності ReplayGain у тегах (T63); звук не змінюється. Типово вимкнено (faric 17.10). */
        val replayGain: Boolean = false,
        /** Коли стискати у FLAC / MP3 (T85): одразу після пісні чи після «Стоп»; «Сам вирішує» — за пам'яттю телефона. */
        val encodeTiming: EncodeTiming = EncodeTiming.AUTO,
    ) {
        val label: String
            get() = when (format) {
                OutputFormat.MP3 -> "MP3 $mp3Bitrate"
                OutputFormat.WAV -> "WAV"
                OutputFormat.FLAC -> "FLAC"
                OutputFormat.MP3_FLAC -> "MP3 $mp3Bitrate + FLAC"
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

    /** «Зсув розрізу» від еталона: 0 = колишні +0,5 с (T42, [CutShift]). */
    var cutShiftMs: Int
        get() {
            migrateCutShift()
            return prefs.getInt(KEY_CUT_SHIFT_MS, 0)
        }
        set(v) = prefs.edit().putInt(KEY_CUT_SHIFT_MS, v).putBoolean(KEY_CUT_SHIFT_V2, true).apply()

    /** Один раз після оновлення до 0.1.25: збережений зсув — від нового нуля, щоб розріз не з'їхав на +0,5 с. */
    private fun migrateCutShift() {
        if (prefs.getBoolean(KEY_CUT_SHIFT_V2, false)) return
        val e = prefs.edit().putBoolean(KEY_CUT_SHIFT_V2, true)
        if (prefs.contains(KEY_CUT_SHIFT_MS)) e.putInt(KEY_CUT_SHIFT_MS, CutShift.migrate(prefs.getInt(KEY_CUT_SHIFT_MS, 0)))
        e.apply()
    }

    /** Свій альбом увімкнено (назва лишається в пам'яті, навіть коли вимкнено). */
    var useCustomAlbum: Boolean
        get() = prefs.getBoolean(KEY_USE_ALBUM, false)
        set(v) = prefs.edit().putBoolean(KEY_USE_ALBUM, v).apply()

    var customAlbum: String
        get() = prefs.getString(KEY_ALBUM, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ALBUM, v.trim()).apply()

    /** «N пісень» і «N хвилин» — щось одне: вибір одного скидає інше. */
    var stopAfter: Int
        get() = prefs.getInt(KEY_STOP_AFTER, 0)
        set(v) = prefs.edit().putInt(KEY_STOP_AFTER, v.coerceIn(0, MAX_STOP_AFTER)).also {
            if (v > 0) it.putInt(KEY_STOP_AFTER_MIN, 0)
        }.apply()

    var stopAfterMin: Int
        get() = prefs.getInt(KEY_STOP_AFTER_MIN, 0)
        set(v) = prefs.edit().putInt(KEY_STOP_AFTER_MIN, v.coerceIn(0, MAX_STOP_AFTER_MIN)).also {
            if (v > 0) it.putInt(KEY_STOP_AFTER, 0)
        }.apply()

    fun snapshot() = Values(
        format, mp3Bitrate, minLengthSec, repeat, albumFolders, trimEdges, fadeMs, cutShiftMs,
        customAlbum = if (useCustomAlbum) customAlbum else "",
        stopAfter = stopAfter,
        stopAfterMin = stopAfterMin,
        treeUri = treeUri,
        plainNames = plainNames,
        onlyPlayer = onlyPlayer,
        replayGain = replayGain,
        encodeTiming = encodeTiming,
    )

    var encodeTiming: EncodeTiming
        get() = EncodeTiming.fromCode(prefs.getString(KEY_ENCODE_TIMING, null))
        set(v) = prefs.edit().putString(KEY_ENCODE_TIMING, v.code).apply()

    /** Як групувати історію (T61): sessions / albums / artists. */
    var historyGroup: String
        get() = prefs.getString(KEY_HISTORY_GROUP, "sessions") ?: "sessions"
        set(v) = prefs.edit().putString(KEY_HISTORY_GROUP, v).apply()

    var onlyPlayer: Boolean
        get() = prefs.getBoolean(KEY_ONLY_PLAYER, true)
        set(v) = prefs.edit().putBoolean(KEY_ONLY_PLAYER, v).apply()

    var replayGain: Boolean
        get() = prefs.getBoolean(KEY_REPLAY_GAIN, false)
        set(v) = prefs.edit().putBoolean(KEY_REPLAY_GAIN, v).apply()

    var plainNames: Boolean
        get() = prefs.getBoolean(KEY_PLAIN_NAMES, true)
        set(v) = prefs.edit().putBoolean(KEY_PLAIN_NAMES, v).apply()

    /**
     * Налаштування для копії (T67): лише те, що має сенс на іншому телефоні. Без «своєї папки» (дозвіл Android —
     * лише для цього телефона) і без плану «Цей запис» (свій альбом, «зупинитись після») — він для одного запису.
     */
    fun exportMap(): Map<String, Any?> {
        cutShiftMs // перерахунок старої шкали (T42), щоб у копію пішло вже нове значення
        val all = prefs.all
        return PORTABLE.keys.filter { all.containsKey(it) }.associateWith { all[it] }
    }

    /** Повернути налаштування з копії. Невідоме й не того типу пропускається. Повертає, скільки повернуто. */
    fun importMap(m: Map<*, *>): Int {
        val e = prefs.edit()
        var n = 0
        for ((k, v) in m) {
            val key = k as? String ?: continue
            when (PORTABLE[key]) {
                Kind.INT -> ((v as? Long) ?: (v as? Double)?.toLong())?.let { e.putInt(key, it.toInt()); n++ }
                Kind.BOOL -> (v as? Boolean)?.let { e.putBoolean(key, it); n++ }
                Kind.STRING -> (v as? String)?.let { e.putString(key, it); n++ }
                null -> {}
            }
        }
        if (m.containsKey(KEY_CUT_SHIFT_MS)) e.putBoolean(KEY_CUT_SHIFT_V2, true) // у копії — вже нова шкала
        e.apply()
        return n
    }

    private enum class Kind { INT, BOOL, STRING }

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
        private const val KEY_CUT_SHIFT_V2 = "cut_shift_v2"
        private const val KEY_ONLY_PLAYER = "only_player"
        private const val KEY_HISTORY_GROUP = "history_group"
        private const val KEY_REPLAY_GAIN = "replay_gain"
        private const val KEY_ENCODE_TIMING = "encode_timing"

        private val PORTABLE = mapOf(
            KEY_FORMAT to Kind.STRING, KEY_BITRATE to Kind.INT, KEY_MIN_LENGTH to Kind.INT, KEY_REPEAT to Kind.STRING,
            KEY_ALBUM_FOLDERS to Kind.BOOL, KEY_TRIM_EDGES to Kind.BOOL, KEY_FADE_MS to Kind.INT, KEY_CUT_SHIFT_MS to Kind.INT,
            KEY_PLAIN_NAMES to Kind.BOOL, KEY_ONLY_PLAYER to Kind.BOOL, KEY_HISTORY_GROUP to Kind.STRING, KEY_REPLAY_GAIN to Kind.BOOL,
        )
        const val MAX_STOP_AFTER = 999
        const val MAX_STOP_AFTER_MIN = 24 * 60
        private const val KEY_STOP_AFTER_MIN = "stop_after_min"
        const val TRIM_EDGE_MS = 1_000
        val FADE_CHOICES = listOf(0, 100, 250, 500, 1_000, 2_000)
        val SHIFT_CHOICES = CutShift.CHOICES
        val MIN_LENGTH_CHOICES = listOf(0, 15, 30, 45, 60)
    }
}
