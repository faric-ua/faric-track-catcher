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
}
