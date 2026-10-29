package ua.faric.trackcatcher.core

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.faric.trackcatcher.state.WorkLog

class LogTextTest {
    @Test
    fun lineHasLevelTagThreadAndIndentedTail() {
        val l = LogText.line(0L, 'W', "номери", "wav-to-flac", "без руху 60 с\nat a.b(C.kt:1)")
        assertTrue(l, l.contains(" W [номери] {wav-to-flac} без руху 60 с\n    at a.b(C.kt:1)\n"))
    }

    @Test
    fun keepTailStartsOnFullLine() {
        val text = (1..100).joinToString("") { "рядок номер $it\n" }
        val kept = LogText.keepTail(text, 200)
        assertTrue(kept.toByteArray().size <= 200)
        assertTrue(kept, kept.startsWith("рядок номер "))
        assertTrue(kept.endsWith("рядок номер 100\n"))
        assertEquals(text, LogText.keepTail(text, 1_000_000))
    }

    @Test
    fun stuckOnlyAfterQuietAndNotTooOften() {
        val t0 = 1_000_000L
        assertFalse(LogText.stuck(t0 + 59_000, t0, 0)) // ще рухається
        assertTrue(LogText.stuck(t0 + 60_000, t0, 0))
        assertFalse(LogText.stuck(t0 + 120_000, t0, t0 + 60_000)) // щойно писали
        assertTrue(LogText.stuck(t0 + 60_000 + LogText.STUCK_REPEAT_MS, t0, t0 + 60_000))
        assertFalse(LogText.stuck(t0 + 600_000, 0, 0)) // роботи нема
    }

    @Test
    fun workLogWritesTrimsAndTails() {
        val dir = Files.createTempDirectory("tc-log").toFile()
        try {
            WorkLog.install(dir)
            var alarms = 0
            WorkLog.onAlarm = { alarms++ }
            WorkLog.i("старт", "Track Catcher")
            WorkLog.step("номери", "№1 · Zeravix")
            WorkLog.e("номери", "не вдалося", IllegalStateException("бах"))
            assertEquals(1, alarms)
            assertEquals("№1 · Zeravix", WorkLog.stepWhat)
            val text = WorkLog.read()
            assertTrue(text, text.contains("I [старт]") && text.contains("№1 · Zeravix") && text.contains("IllegalStateException: бах"))
            val big = "x".repeat(1000)
            repeat(700) { WorkLog.i("шум", big) }
            assertTrue(File(dir, "journal.txt").length() <= LogText.MAX_BYTES)
            val tail = WorkLog.tail(10_000)
            assertTrue(tail.isNotEmpty() && tail.last().contains("[шум]"))
        } finally {
            WorkLog.onAlarm = null
            dir.deleteRecursively()
        }
    }
}
