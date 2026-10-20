package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitWordsTest {
    @Test
    fun reasonsInWords() {
        assertTrue(ExitWords.words(ExitWords.LOW_MEMORY, null).contains("пам'яті"))
        assertEquals(
            "Android сам зупинив Track Catcher (так робить система чи «оптимізація» Samsung) (kill background)",
            ExitWords.words(ExitWords.SIGNALED, " kill background "),
        )
        assertTrue(ExitWords.words(ExitWords.CRASH, "").contains("помилкою"))
        assertTrue(ExitWords.words(99, null).contains("інша"))
    }

    @Test
    fun recordingCutText() {
        val t = ExitWords.recordingCut(null, null)
        assertTrue(t.startsWith("Минулий запис обірвався: Android закрив Track Catcher"))
        assertTrue(ExitWords.recordingCut("X", "java.lang.IllegalStateException: boom").endsWith("boom"))
    }
}
