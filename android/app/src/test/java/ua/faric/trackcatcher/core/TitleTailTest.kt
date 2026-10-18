package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T71: зайвий хвіст «TRACK 04 — PHOTON HIGHWAY» у назвах (faric 18.10 13:34–14:13). */
class TitleTailTest {
    private val t4 = "🎵 TRACK 04 — PHOTON HIGHWAY. ⚡"
    private val album = listOf(
        "🎵 TRACK 01 — STAR GATE. ⚡", "🎵 TRACK 02 — NEBULA. ⚡", "🎵 TRACK 03 — ORBIT. ⚡",
        "$t4 $t4", "🎵 BONUS 01 — AURORA. ⚡ $t4", "🎵 BONUS 02 — COMET RIDE. ⚡ $t4", "🎵 BONUS 03 — LAST LIGHT. ⚡ $t4",
    )

    @Test fun doubledTitleIsOneTitle() {
        assertEquals(t4, TitleTail.doubled("$t4 $t4"))
        assertNull(TitleTail.doubled(t4))
        assertNull(TitleTail.doubled("Go Go")) // коротке — не хвіст, а назва
    }

    @Test fun detectsTailInAlbum() {
        assertEquals(t4, TitleTail.detect(album))
        val cleaned = album.map { TitleTail.strip(it, t4) ?: it }
        assertEquals(listOf("🎵 TRACK 01 — STAR GATE. ⚡", "🎵 TRACK 02 — NEBULA. ⚡", "🎵 TRACK 03 — ORBIT. ⚡", t4,
            "🎵 BONUS 01 — AURORA. ⚡", "🎵 BONUS 02 — COMET RIDE. ⚡", "🎵 BONUS 03 — LAST LIGHT. ⚡"), cleaned)
    }

    @Test fun detectsSharedTailWithoutDoubled() {
        val titles = album.filter { !it.startsWith("$t4 ") }
        assertEquals(t4, TitleTail.detect(titles))
    }

    @Test fun cleanAlbumHasNoTail() {
        assertNull(TitleTail.detect(listOf("🎵 TRACK 01 — STAR GATE. ⚡", "🎵 TRACK 02 — NEBULA. ⚡", "Love Song", "Sad Song")))
    }

    @Test fun stripNeedsSpaceAndOwnTitle() {
        assertNull(TitleTail.strip(t4, t4)) // без хвоста нічого не лишається
        assertNull(TitleTail.strip("SUPERHIGHWAY", "HIGHWAY"))
        assertEquals("A", TitleTail.strip("A — PHOTON HIGHWAY", "PHOTON HIGHWAY"))
    }

    @Test fun cleanWhileRecording() {
        assertEquals(t4, TitleTail.clean("$t4 $t4", emptyList()))
        assertEquals("🎵 BONUS 01 — AURORA. ⚡", TitleTail.clean("🎵 BONUS 01 — AURORA. ⚡ $t4", listOf(t4)))
        assertEquals("Highway Star", TitleTail.clean("Highway Star", listOf(t4))) // хвоста нема — назва як була
    }

    @Test fun fileNameFollowsTitle() {
        val old = "$t4 $t4"
        // Без смайликів (за замовчуванням): «Saney — TRACK 04 — PHOTON HIGHWAY. TRACK 04 — PHOTON HIGHWAY.flac»
        val plainOld = FileNames.baseName(TrackMeta("Saney", old), plain = true) + ".flac"
        assertEquals(FileNames.baseName(TrackMeta("Saney", t4), plain = true) + ".flac", TitleTail.renamed(plainOld, "Saney", old, t4))
        val second = FileNames.baseName(TrackMeta("Saney", old), plain = true) + " (2).mp3"
        assertEquals(FileNames.baseName(TrackMeta("Saney", t4), plain = true) + " (2).mp3", TitleTail.renamed(second, "Saney", old, t4))
        val emoji = FileNames.baseName(TrackMeta("Saney", old), plain = false) + ".flac"
        assertEquals(FileNames.baseName(TrackMeta("Saney", t4), plain = false) + ".flac", TitleTail.renamed(emoji, "Saney", old, t4))
        assertNull(TitleTail.renamed("моя назва.flac", "Saney", old, t4)) // ім'я не наше — не чіпаємо
    }
}
