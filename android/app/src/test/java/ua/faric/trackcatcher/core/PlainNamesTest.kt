package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** T36: смайлики й невидимі символи — геть з назви файлу. Приклади — з записів faric (Suno, 14.10). */
class PlainNamesTest {
    @Test
    fun sunoTitles() {
        assertEquals(
            "Saney — TRACK 02 — VELVET TEMPLE",
            FileNames.baseName(TrackMeta("Saney", "\uD83C\uDFB5 TRACK 02 — VELVET TEMPLE. \u200D\uD83C\uDFDB\uFE0F"), plain = true),
        )
        assertEquals(
            "Saney — TRACK 01 — ANCIENT SIGNAL",
            FileNames.baseName(TrackMeta("Saney", "\uD83C\uDFB5 TRACK 01 — ANCIENT SIGNAL. \uD83D\uDCE1\uD83C\uDF11"), plain = true),
        )
    }

    @Test
    fun lettersOfAnyLanguageStay() {
        assertEquals("Океан Ельзи — Обійми (live)", FileNames.baseName(TrackMeta("Океан Ельзи", "Обійми (live) \u2764\uFE0F"), plain = true))
        assertEquals("Björk — Jóga", FileNames.baseName(TrackMeta("Björk", "Jóga"), plain = true))
    }

    @Test
    fun onlyEmojiKeepsOriginal() {
        val meta = TrackMeta("", "\uD83C\uDFB5\uD83C\uDFB5")
        assertEquals(FileNames.baseName(meta), FileNames.baseName(meta, plain = true))
    }

    @Test
    fun switchedOffKeepsEmoji() {
        assertEquals("Saney — \uD83C\uDFB5 X", FileNames.baseName(TrackMeta("Saney", "\uD83C\uDFB5 X"), plain = false))
    }

    @Test
    fun albumFolderToo() {
        assertEquals("Music/Track Catcher/Neon/", FileNames.folderFor(TrackMeta("a", "b", "\uD83C\uDF03 Neon"), true, plain = true))
    }
}
