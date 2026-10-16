package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class CutShiftTest {
    @Test fun oldPlusHalfSecondBecomesZero() = assertEquals(0, CutShift.migrate(500))

    @Test fun cutPositionKeptAfterMigration() {
        for (old in listOf(-500, 0, 500, 1_000)) {
            // Колишній зсув old = новий effective(migrate(old)) — розріз не з'їжджає.
            assertEquals(old, CutShift.effective(CutShift.migrate(old)))
        }
    }

    @Test fun outsideChoicesSnaps() {
        assertEquals(-1_000, CutShift.migrate(-1_000)) // −1,5 с → найближча кнопка −1 с
        assertEquals(500, CutShift.nearest(600))
        assertEquals(-200, CutShift.migrate(200)) // +0,2 → −0,3 → найближча кнопка −0,2 (розріз на 0,1 с пізніше)
    }

    @Test fun zeroMeansBase() = assertEquals(500, CutShift.effective(0))
}
