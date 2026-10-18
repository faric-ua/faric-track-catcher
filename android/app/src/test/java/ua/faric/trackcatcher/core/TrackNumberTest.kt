package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackNumberTest {
    @Test fun numberFromTitle() {
        assertEquals(10, TrackNumber.fromTitle("🎵 TRACK 10 — DAWN OF THE RITUAL. 🌅"))
        assertEquals(1, TrackNumber.fromTitle("Track 01 - Intro"))
        assertEquals(7, TrackNumber.fromTitle("ТРЕК 07 Ранок"))
        assertNull(TrackNumber.fromTitle("BONUS 01 — Night"))
        assertNull(TrackNumber.fromTitle("Soundtrack 5")) // «track» усередині слова — не номер
        assertNull(TrackNumber.fromTitle("Neon Horizon"))
    }

    @Test fun bonusContinuesAfterLastTrack() {
        assertEquals(listOf(11, 12, 13, 14), TrackNumber.all(listOf("TRACK 11 — A", "TRACK 12 — B", "BONUS 01 — C", "BONUS 02 — D")))
    }

    @Test fun plainTitlesAreCountedInOrder() {
        assertEquals(listOf(1, 2, 3), TrackNumber.all(listOf("Ранок", "Вечір", "Ніч")))
    }

    @Test fun repeatedSongKeepsItsNumber() {
        // Бонуси записались двічі (по колу): 13-й лишається 13-м, а не 16-м.
        val t = listOf("TRACK 10 — A", "BONUS 01 — B", "BONUS 02 — C", "BONUS 03 — D", "BONUS 01 — B", "BONUS 02 — C", "BONUS 03 — D")
        assertEquals(listOf(10, 11, 12, 13, 11, 12, 13), TrackNumber.all(t))
        // Неповний запис, потім повний тієї самої пісні — один номер.
        assertEquals(listOf(1, 2, 2, 3), TrackNumber.all(listOf("Ранок", "Вечір", "Вечір", "Ніч")))
    }

    @Test fun bonusIsMaxTrackPlusItsOwnNumber() {
        // BONUS 03 — завжди 13 після TRACK 10, навіть якщо бонуси записано не по порядку чи без BONUS 01.
        assertEquals(listOf(13, 1, 10, 11), TrackNumber.all(listOf("BONUS 03 — D", "TRACK 01 — A", "TRACK 10 — J", "BONUS 01 — B")))
        assertEquals(3, TrackNumber.bonusOf("⚡ BONUS 03 — TWO VOICES, ONE HORIZON"))
    }

    @Test fun bonusWithoutTracksIsCountedInOrder() {
        assertEquals(listOf(1, 2), TrackNumber.all(listOf("BONUS 01 — A", "BONUS 02 — B")))
    }
}
