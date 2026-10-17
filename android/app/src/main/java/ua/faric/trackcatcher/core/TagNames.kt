package ua.faric.trackcatcher.core

/**
 * ── ЧАСТИНА СПІЛЬНОЇ ПАНЕЛІ «ПРО ФАЙЛ» (версія 2) ── копіюється в Audio Converter разом з [FileTags].
 *
 * Теги словами (faric 18.10 01:00 / 02:01: «TIT2, TPE1, TALB — нахрена скорочувати? Напиши повними словами»).
 * У самому MP3 коди лишаються — так влаштований ID3: кожен тег має код з 4 літер, і плеєри шукають саме його
 * (`TIT2` — назва; напишеш `TITLE` — жоден плеєр назви не покаже). Тож міняємо не файл, а те, що бачить людина:
 * код → українська назва + повне англійське слово (те саме, що у FLAC) + група («Пісня», «Автор і права»…).
 * Без Android — лише таблиці; тест `TagNamesTest`.
 */
object TagNames {
    /** Групи в «Усі теги у файлі» — у такому порядку. */
    enum class Group(val title: String) {
        SONG("Пісня"),
        RIGHTS("Автор і права"),
        PICTURES("Картинки"),
        SOUND("Гучність"),
        SERVICE("Службові"),
        OTHER("Інші"),
    }

    /** [label] — українською; [word] — повне англійське слово (як у FLAC), не код. */
    data class Name(val label: String, val word: String, val group: Group)

    private fun n(label: String, word: String, group: Group) = Name(label, word, group)

    /** Повні слова — ключі FLAC / Ogg / Opus (Vorbis), а також описи TXXX у MP3 і «----» в M4A. */
    private val WORDS: Map<String, Name> = mapOf(
        "TITLE" to n("Назва", "TITLE", Group.SONG),
        "ARTIST" to n("Виконавець", "ARTIST", Group.SONG),
        "ALBUM" to n("Альбом", "ALBUM", Group.SONG),
        "ALBUMARTIST" to n("Виконавець альбому", "ALBUM ARTIST", Group.SONG),
        "ALBUM ARTIST" to n("Виконавець альбому", "ALBUM ARTIST", Group.SONG),
        "ALBUM_ARTIST" to n("Виконавець альбому", "ALBUM ARTIST", Group.SONG),
        "TRACKNUMBER" to n("Номер пісні", "TRACK", Group.SONG),
        "TRACK" to n("Номер пісні", "TRACK", Group.SONG),
        "TRACKTOTAL" to n("Пісень в альбомі", "TRACK TOTAL", Group.SONG),
        "TOTALTRACKS" to n("Пісень в альбомі", "TRACK TOTAL", Group.SONG),
        "DISCNUMBER" to n("Номер диска", "DISC", Group.SONG),
        "DISC" to n("Номер диска", "DISC", Group.SONG),
        "DISCTOTAL" to n("Дисків", "DISC TOTAL", Group.SONG),
        "TOTALDISCS" to n("Дисків", "DISC TOTAL", Group.SONG),
        "DATE" to n("Рік", "DATE", Group.SONG),
        "YEAR" to n("Рік", "YEAR", Group.SONG),
        "ORIGINALDATE" to n("Рік першого виходу", "ORIGINAL DATE", Group.SONG),
        "RELEASEDATE" to n("Дата виходу", "RELEASE DATE", Group.SONG),
        "GENRE" to n("Жанр", "GENRE", Group.SONG),
        "BPM" to n("Темп (ударів за хвилину)", "BPM", Group.SONG),
        "KEY" to n("Тональність", "KEY", Group.SONG),
        "LANGUAGE" to n("Мова", "LANGUAGE", Group.SONG),
        "GROUPING" to n("Група пісень", "GROUPING", Group.SONG),
        "SUBTITLE" to n("Підзаголовок", "SUBTITLE", Group.SONG),
        "LYRICS" to n("Текст пісні", "LYRICS", Group.SONG),
        "UNSYNCEDLYRICS" to n("Текст пісні", "LYRICS", Group.SONG),
        "ISRC" to n("Код запису (ISRC)", "ISRC", Group.SONG),
        "COMPOSER" to n("Автор", "COMPOSER", Group.RIGHTS),
        "LYRICIST" to n("Автор слів", "LYRICIST", Group.RIGHTS),
        "CONDUCTOR" to n("Диригент", "CONDUCTOR", Group.RIGHTS),
        "REMIXER" to n("Ремікс", "REMIXER", Group.RIGHTS),
        "COPYRIGHT" to n("Власник прав", "COPYRIGHT", Group.RIGHTS),
        "PUBLISHER" to n("Видавець", "PUBLISHER", Group.RIGHTS),
        "ORGANIZATION" to n("Видавець", "PUBLISHER", Group.RIGHTS),
        "LABEL" to n("Видавець", "LABEL", Group.RIGHTS),
        "URL" to n("Посилання", "URL", Group.RIGHTS),
        "CONTACT" to n("Посилання", "URL", Group.RIGHTS),
        "WEBSITE" to n("Посилання", "URL", Group.RIGHTS),
        "LICENSE" to n("Ліцензія", "LICENSE", Group.RIGHTS),
        "COMMENT" to n("Коментар", "COMMENT", Group.RIGHTS),
        "DESCRIPTION" to n("Коментар", "COMMENT", Group.RIGHTS),
        "PICTURE" to n("Картинка", "PICTURE", Group.PICTURES),
        "METADATA_BLOCK_PICTURE" to n("Картинка", "PICTURE", Group.PICTURES),
        "COVERART" to n("Картинка", "PICTURE", Group.PICTURES),
        "REPLAYGAIN_TRACK_GAIN" to n("Гучність пісні (ReplayGain)", "TRACK GAIN", Group.SOUND),
        "REPLAYGAIN_TRACK_PEAK" to n("Пік пісні (ReplayGain)", "TRACK PEAK", Group.SOUND),
        "REPLAYGAIN_ALBUM_GAIN" to n("Гучність альбому (ReplayGain)", "ALBUM GAIN", Group.SOUND),
        "REPLAYGAIN_ALBUM_PEAK" to n("Пік альбому (ReplayGain)", "ALBUM PEAK", Group.SOUND),
        "R128_TRACK_GAIN" to n("Гучність пісні (R128)", "R128 TRACK GAIN", Group.SOUND),
        "R128_ALBUM_GAIN" to n("Гучність альбому (R128)", "R128 ALBUM GAIN", Group.SOUND),
        "ENCODER" to n("Програма-кодувальник", "ENCODER", Group.SERVICE),
        "ENCODED_BY" to n("Хто кодував", "ENCODED BY", Group.SERVICE),
        "ENCODEDBY" to n("Хто кодував", "ENCODED BY", Group.SERVICE),
        "ENCODER_OPTIONS" to n("Налаштування кодувальника", "ENCODER OPTIONS", Group.SERVICE),
        "CONVERTER" to n("Конвертер", "CONVERTER", Group.SERVICE),
        "LENGTH" to n("Тривалість", "LENGTH", Group.SERVICE),
        "SOFTWARE" to n("Програма", "SOFTWARE", Group.SERVICE),
    )

    /** Коди MP3 (ID3v2.3 / 2.4) → повне слово з [WORDS] або своя назва. */
    private val ID3: Map<String, Name> = mapOf(
        "TIT2" to w("TITLE"), "TPE1" to w("ARTIST"), "TALB" to w("ALBUM"), "TPE2" to w("ALBUMARTIST"),
        "TRCK" to w("TRACKNUMBER"), "TPOS" to w("DISCNUMBER"), "TYER" to w("YEAR"), "TDRC" to w("DATE"),
        "TDOR" to w("ORIGINALDATE"), "TORY" to w("ORIGINALDATE"), "TDRL" to w("RELEASEDATE"),
        "TCON" to w("GENRE"), "TBPM" to w("BPM"), "TKEY" to w("KEY"), "TLAN" to w("LANGUAGE"),
        "TIT1" to w("GROUPING"), "TIT3" to w("SUBTITLE"), "USLT" to w("LYRICS"), "TSRC" to w("ISRC"),
        "TCOM" to w("COMPOSER"), "TEXT" to w("LYRICIST"), "TPE3" to w("CONDUCTOR"), "TPE4" to w("REMIXER"),
        "TCOP" to w("COPYRIGHT"), "TPUB" to w("PUBLISHER"), "COMM" to w("COMMENT"),
        "WOAR" to n("Посилання", "URL", Group.RIGHTS), "WXXX" to n("Посилання", "URL", Group.RIGHTS),
        "WCOP" to n("Посилання про права", "COPYRIGHT URL", Group.RIGHTS), "WOAS" to n("Посилання на джерело", "SOURCE URL", Group.RIGHTS),
        "WPUB" to n("Посилання видавця", "PUBLISHER URL", Group.RIGHTS), "TOPE" to n("Перший виконавець", "ORIGINAL ARTIST", Group.RIGHTS),
        "APIC" to w("PICTURE"), "PIC" to w("PICTURE"),
        "TSSE" to w("ENCODER"), "TENC" to w("ENCODED_BY"), "TLEN" to w("LENGTH"),
        "TDAT" to n("Дата (день і місяць)", "DATE", Group.SONG), "TIME" to n("Час запису", "TIME", Group.SONG),
        "TDEN" to n("Коли закодовано", "ENCODING TIME", Group.SERVICE), "TDTG" to n("Коли записано теги", "TAGGING TIME", Group.SERVICE),
        "TFLT" to n("Тип файлу", "FILE TYPE", Group.SERVICE), "TMED" to n("Носій", "MEDIA", Group.SERVICE),
        "TSOA" to n("Альбом для сортування", "ALBUM SORT", Group.SERVICE), "TSOP" to n("Виконавець для сортування", "ARTIST SORT", Group.SERVICE),
        "TSOT" to n("Назва для сортування", "TITLE SORT", Group.SERVICE), "TSO2" to n("Виконавець альбому для сортування", "ALBUM ARTIST SORT", Group.SERVICE),
        "TCMP" to n("Збірка різних виконавців", "COMPILATION", Group.SONG),
        "RVA2" to n("Поправка гучності", "VOLUME ADJUSTMENT", Group.SOUND), "RVAD" to n("Поправка гучності", "VOLUME ADJUSTMENT", Group.SOUND),
        "PCNT" to n("Скільки разів слухали", "PLAY COUNT", Group.SERVICE), "POPM" to n("Оцінка", "RATING", Group.SERVICE),
        "PRIV" to n("Службові дані програми", "PRIVATE", Group.SERVICE), "UFID" to n("Номер файлу в базі", "UNIQUE FILE ID", Group.SERVICE),
        "GEOB" to n("Вкладений файл", "OBJECT", Group.SERVICE), "MCDI" to n("Зміст диска", "CD TOC", Group.SERVICE),
    )

    /** Розділи INFO у WAV. */
    private val INFO: Map<String, Name> = mapOf(
        "INAM" to w("TITLE"), "IART" to w("ARTIST"), "IPRD" to w("ALBUM"), "ITRK" to w("TRACKNUMBER"), "IPRT" to w("TRACKNUMBER"),
        "ICRD" to w("DATE"), "IGNR" to w("GENRE"), "ICMT" to w("COMMENT"), "ICOP" to w("COPYRIGHT"), "IMUS" to w("COMPOSER"),
        "ISFT" to w("SOFTWARE"), "IENG" to n("Звукоінженер", "ENGINEER", Group.RIGHTS), "ITCH" to w("ENCODED_BY"),
        "ISBJ" to n("Тема", "SUBJECT", Group.SONG), "ILNG" to w("LANGUAGE"), "IKEY" to n("Ключові слова", "KEYWORDS", Group.SONG),
    )

    private fun w(word: String): Name = WORDS.getValue(word)

    /**
     * Назва тегу для людини. [key] — як його показує [FileTags.Raw]: код MP3 («TIT2»), «TXXX:опис»,
     * ключ FLAC («TITLE») чи розділ WAV («INAM»). Незнайомий — сам ключ у групі «Інші».
     */
    fun of(key: String): Name {
        val k = key.trim()
        if (k.startsWith("TXXX:", ignoreCase = true)) {
            val desc = k.substringAfter(':').trim()
            return txxx(desc)
        }
        ID3[k]?.let { return it }
        INFO[k]?.let { return it }
        WORDS[k.uppercase()]?.let { return it }
        if (k.startsWith("----:")) return txxx(k.substringAfterLast(':'))
        return Name(k, k.uppercase(), Group.OTHER)
    }

    private fun txxx(desc: String): Name {
        WORDS[desc.uppercase()]?.let { return it }
        WORDS[desc.uppercase().replace(' ', '_')]?.let { return it }
        if (desc.startsWith("iTun", ignoreCase = true)) return Name("Службові дані iTunes", desc, Group.SERVICE)
        if (desc.startsWith("MusicBrainz", ignoreCase = true)) return Name("Номер у MusicBrainz", desc.uppercase(), Group.SERVICE)
        return Name(desc.ifBlank { "Свій тег" }, desc.uppercase().ifBlank { "TXXX" }, Group.OTHER)
    }

    /**
     * Значення по-людськи: тривалість «328079» → «5:28»; картинка «лицьова · image/jpeg · …» → «лицьова сторона · JPEG · …».
     * Решта — як у файлі.
     */
    fun value(key: String, value: String): String {
        val name = of(key)
        return when {
            name.word == "LENGTH" -> value.trim().toLongOrNull()?.takeIf { it > 0 }?.let { clock(it) } ?: value
            name.group == Group.PICTURES -> value.split(" · ").joinToString(" · ") { part ->
                when {
                    part == "лицьова" -> "лицьова сторона (обкладинка)"
                    part == "зворот" -> "зворотна сторона"
                    part.startsWith("image/") -> part.substringAfter('/').uppercase().replace("JPG", "JPEG")
                    else -> part
                }
            }
            else -> value
        }
    }

    /** Рядок для «Копіювати все»: «Назва (TITLE) = …». */
    fun line(key: String, value: String): String {
        val name = of(key)
        return "${name.label} (${name.word}) = ${value(key, value)}"
    }

    /** Розкласти теги по групах — у порядку [Group], усередині групи — як лежать у файлі. */
    fun <T> grouped(items: List<T>, key: (T) -> String): List<Pair<Group, List<T>>> =
        Group.entries.mapNotNull { g -> items.filter { of(key(it)).group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        val tenth = ms % 1000 / 100
        val base = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        return if (s < 60) "$base,$tenth" else base
    }
}
