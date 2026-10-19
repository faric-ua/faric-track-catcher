package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T85: на слабкому телефоні стискати після «Стоп», щоб запис ішов без ривків. */
class EncodeTimingTest {
    private val gb = 1024L * 1024 * 1024

    @Test
    fun autoDefersOnWeakPhone() {
        // M30s — 3,5 ГБ: після «Стоп»; A26 — 7,2 ГБ: одразу.
        assertTrue(EncodeTiming.defer(EncodeTiming.AUTO, (3.5 * gb).toLong(), lowRamDevice = false))
        assertFalse(EncodeTiming.defer(EncodeTiming.AUTO, (7.2 * gb).toLong(), lowRamDevice = false))
        // Android сам каже «мало пам'яті» — теж після «Стоп».
        assertTrue(EncodeTiming.defer(EncodeTiming.AUTO, 8 * gb, lowRamDevice = true))
        // Невідомо скільки — як раніше (одразу).
        assertFalse(EncodeTiming.defer(EncodeTiming.AUTO, 0, lowRamDevice = false))
    }

    @Test
    fun explicitChoiceWins() {
        assertTrue(EncodeTiming.defer(EncodeTiming.AFTER, 12 * gb, lowRamDevice = false))
        assertFalse(EncodeTiming.defer(EncodeTiming.NOW, 2 * gb, lowRamDevice = true))
    }

    @Test
    fun codesAndText() {
        assertEquals(EncodeTiming.AUTO, EncodeTiming.fromCode(null))
        assertEquals(EncodeTiming.AFTER, EncodeTiming.fromCode("after"))
        assertEquals("3,5 ГБ", EncodeTiming.ramText((3.5 * gb).toLong()))
        assertEquals("невідомо скільки", EncodeTiming.ramText(0))
    }
}
