package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException

class ErrorTextTest {
    /** Як на A26 18.10 15:24 (VOL. 2 — NIGHT ENERGY, 10 файлів). */
    @Test fun missingFileInOwnFolderIsOneLine() {
        val raw = IllegalArgumentException(
            "Failed to determine if 0123-4567:Music/VOL. 2 — NIGHT ENERGY/A.flac is child of 0123-4567:Music: " +
                "java.io.FileNotFoundException: Missing file for 0123-4567:Music/VOL. 2 — NIGHT ENERGY/A.flac at /storage/0123-4567/Music/VOL. 2 — NIGHT ENERGY/A.flac",
        )
        assertEquals(ErrorText.MISSING, ErrorText.human(raw))
        assertEquals(ErrorText.MISSING, ErrorText.human(java.io.IOException("x", FileNotFoundException("y"))))
    }

    @Test fun ourOwnMessagesStay() {
        assertEquals("перевірка звуку не зійшлась — файл не чіпаю", ErrorText.human(java.io.IOException("перевірка звуку не зійшлась — файл не чіпаю")))
        assertEquals("IllegalStateException", ErrorText.human(IllegalStateException()))
    }
}
