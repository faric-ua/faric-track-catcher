package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Теги словами (faric 18.10: «TIT2, TPE1, TALB — напиши повними словами»). Спільний з Audio Converter. */
class TagNamesTest {
    @Test fun mp3CodesBecomeWords() {
        // Рівно те, що faric прочитав зі скріна 18.10 02:01.
        val expect = mapOf(
            "TIT2" to ("Назва" to "TITLE"), "TPE1" to ("Виконавець" to "ARTIST"), "TALB" to ("Альбом" to "ALBUM"),
            "TPE2" to ("Виконавець альбому" to "ALBUM ARTIST"), "TYER" to ("Рік" to "YEAR"), "TCOM" to ("Автор" to "COMPOSER"),
            "TCOP" to ("Власник прав" to "COPYRIGHT"), "COMM" to ("Коментар" to "COMMENT"), "APIC" to ("Картинка" to "PICTURE"),
            "TSSE" to ("Програма-кодувальник" to "ENCODER"), "TLEN" to ("Тривалість" to "LENGTH"),
        )
        for ((code, lw) in expect) {
            val n = TagNames.of(code)
            assertEquals(code, lw.first, n.label)
            assertEquals(code, lw.second, n.word)
        }
    }

    @Test fun flacWavAndMp3SayTheSame() {
        assertEquals(TagNames.of("TIT2"), TagNames.of("TITLE"))
        assertEquals(TagNames.of("TIT2"), TagNames.of("INAM"))
        assertEquals(TagNames.of("TCOP"), TagNames.of("ICOP"))
        assertEquals(TagNames.of("TPE2"), TagNames.of("ALBUMARTIST"))
        assertEquals(TagNames.of("APIC"), TagNames.of("PICTURE"))
        assertEquals(TagNames.of("TSSE"), TagNames.of("encoder"))
    }

    @Test fun txxxByDescription() {
        assertEquals("Конвертер", TagNames.of("TXXX:CONVERTER").label)
        assertEquals("Видавець", TagNames.of("TXXX:PUBLISHER").label)
        assertEquals(TagNames.Group.SERVICE, TagNames.of("TXXX:iTunNORM").group)
        assertEquals("Моє поле", TagNames.of("TXXX:Моє поле").label)
        assertEquals(TagNames.Group.OTHER, TagNames.of("TXXX:Моє поле").group)
    }

    @Test fun unknownStaysAsIs() {
        val n = TagNames.of("ZZZZ")
        assertEquals("ZZZZ", n.label)
        assertEquals(TagNames.Group.OTHER, n.group)
    }

    @Test fun valuesForPeople() {
        assertEquals("5:28", TagNames.value("TLEN", "328079"))
        assertEquals("0:24,8", TagNames.value("TLEN", "24888"))
        assertEquals("лицьова сторона (обкладинка) · JPEG · 360×360 · 26 КБ", TagNames.value("APIC", "лицьова · image/jpeg · 360×360 · 26 КБ"))
        assertEquals("Saney", TagNames.value("TCOP", "Saney"))
        assertEquals("Назва (TITLE) = TRACK 10", TagNames.line("TIT2", "TRACK 10"))
    }

    @Test fun groupsInOrder() {
        val keys = listOf("TSSE", "TIT2", "TCOP", "APIC", "TPE1", "ZZZZ")
        val g = TagNames.grouped(keys) { it }
        assertEquals(listOf(TagNames.Group.SONG, TagNames.Group.RIGHTS, TagNames.Group.PICTURES, TagNames.Group.SERVICE, TagNames.Group.OTHER), g.map { it.first })
        assertEquals(listOf("TIT2", "TPE1"), g[0].second)
    }
}
