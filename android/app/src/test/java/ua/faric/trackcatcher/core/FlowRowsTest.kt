package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowRowsTest {
    @Test fun allFitInOneRow() {
        assertEquals(listOf(listOf(0, 1)), flowRows(listOf(100, 80), maxW = 300, gap = 8))
    }

    @Test fun wrapsWhenNotFit() {
        assertEquals(listOf(listOf(0, 1), listOf(2)), flowRows(listOf(100, 100, 100), maxW = 250, gap = 8))
    }

    @Test fun tooWideItemOwnRow() {
        assertEquals(listOf(listOf(0), listOf(1)), flowRows(listOf(400, 50), maxW = 300, gap = 8))
    }

    @Test fun endOffsetPutsCloseAtRightEdge() {
        // «Інше посилання» 150 + «Закрити» 100 + проміжок 8 у рядку 300 → починати з 42, «Закрити» закінчується рівно на 300 (T53).
        val off = flowEndOffset(listOf(150, 100), maxW = 300, gap = 8)
        assertEquals(42, off)
        assertEquals(300, off + 150 + 8 + 100)
    }

    @Test fun endOffsetSingleButton() {
        assertEquals(200, flowEndOffset(listOf(100), maxW = 300, gap = 8))
    }

    @Test fun endOffsetNeverNegative() {
        assertEquals(0, flowEndOffset(listOf(400), maxW = 300, gap = 8))
    }

    /** faric 19.10 20:09: дві плитки — порівну на рядок; одна — на весь рядок; не влазять у рівну частку — вільне порівну. */
    @Test fun fillStretchesRows() {
        assertEquals(listOf(496), flowFill(listOf(200), 496, 8))
        assertEquals(listOf(244, 244), flowFill(listOf(200, 120), 496, 8))
        assertEquals(listOf(334, 154), flowFill(listOf(300, 120), 496, 8)) // 496 − 8 − 420 = 68 вільних → по 34
        assertEquals(496 - 8, flowFill(listOf(300, 120), 496, 8).sum())
        assertEquals(listOf(300, 400), flowFill(listOf(300, 400), 100, 8)) // місця нема — як було
    }
}
