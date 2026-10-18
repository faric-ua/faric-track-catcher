package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T77: faric перейменував файли в «Моїх файлах» — знайти їх за тривалістю й назвою в тегах. */
class RelinkTest {
    private fun lost(id: Long, name: String, ms: Long, title: String) = Relink.Lost(id, false, name, ms, title)
    private fun disk(name: String, ms: Long, title: String?) = Relink.OnDisk("u/$name", name, ms, title)

    @Test fun findsRenamedByDurationAndTitle() {
        val l = listOf(lost(1, "Saney — Old A.flac", 200_000, "Old A"), lost(2, "Saney — Old B.flac", 210_400, "Old B"))
        val d = listOf(disk("Saney — New B.flac", 210_000, "Old B"), disk("Saney — New A.flac", 200_900, "Old A"), disk("Інше.flac", 90_000, "X"))
        val m = Relink.match(l, d).associate { it.lost.rowId to it.found.name }
        assertEquals(mapOf(1L to "Saney — New A.flac", 2L to "Saney — New B.flac"), m)
    }

    @Test fun twoVariantsOfSameLengthNeedTitle() {
        // Два варіанти пісні майже однакової довжини, назви в тегах змінені — не вгадуємо.
        val l = listOf(lost(1, "a.flac", 180_000, "A"), lost(2, "b.flac", 180_500, "B"))
        val d = listOf(disk("x.flac", 180_200, null), disk("y.flac", 180_300, null))
        assertTrue(Relink.match(l, d).isEmpty())
        // А з назвами в тегах — однозначно.
        val d2 = listOf(disk("x.flac", 180_200, "B"), disk("y.flac", 180_300, "A"))
        assertEquals(mapOf(1L to "y.flac", 2L to "x.flac"), Relink.match(l, d2).associate { it.lost.rowId to it.found.name })
    }

    @Test fun otherFormatOrLengthIsNotAMatch() {
        val l = listOf(lost(1, "a.flac", 180_000, "A"))
        assertTrue(Relink.match(l, listOf(disk("a.mp3", 180_000, "A"), disk("b.flac", 185_000, "A"))).isEmpty())
    }

    @Test fun titleFromFileName() {
        assertEquals("BONUS 03 — BEYOND INFINITY", Relink.titleFromName("Saney — BONUS 03 — BEYOND INFINITY (2).flac", "Saney"))
        assertEquals("Моя назва", Relink.titleFromName("Моя назва.mp3", "Saney"))
        assertFalse(Relink.titleChanged("🎤 BONUS 03 — BEYOND INFINITY. ♾️💞", "BONUS 03 — BEYOND INFINITY"))
        assertTrue(Relink.titleChanged("🎤 BONUS 03 — BEYOND INFINITY.", "BONUS 03 — BEYOND THE STARS"))
    }
}
