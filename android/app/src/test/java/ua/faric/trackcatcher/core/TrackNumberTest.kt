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
}
