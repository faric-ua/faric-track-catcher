package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T78: редактор тегів і імен файлів (faric 19.10 19:46). */
class MetaEditTest {
    private val song = MetaEdit.Song("Saney", "🎵 TRACK 02 — VELVET TEMPLE. ⚡", "VOL. 3", "Saney", "2026", "2")

    @Test fun emptyFieldsChangeNothing() {
        val spec = MetaEdit.Spec()
        assertTrue(spec.isEmpty)
        assertEquals(song, MetaEdit.apply(song, spec))
        assertTrue(MetaEdit.tagChanges(song, MetaEdit.apply(song, spec)).isEmpty())
    }

    @Test fun batchArtistAndReplaceInTitles() {
        val spec = MetaEdit.Spec(artist = "faric", find = "TRACK ", replace = "")
        val n = MetaEdit.apply(song, spec)
        assertEquals("faric", n.artist)
        assertEquals("🎵 02 — VELVET TEMPLE. ⚡", n.title)
        assertEquals(mapOf("ARTIST" to "faric", "TITLE" to "🎵 02 — VELVET TEMPLE. ⚡"), MetaEdit.tagChanges(song, n))
    }

    @Test fun trackNumberComparedAsNumber() {
        assertTrue(MetaEdit.tagChanges(song, song.copy(track = "02")).isEmpty())
        assertEquals(mapOf("TRACKNUMBER" to "12"), MetaEdit.tagChanges(song, song.copy(track = "12")))
    }

    @Test fun fileNameStyles() {
        val old = "Saney — TRACK 02 — VELVET TEMPLE.flac"
        assertNull(MetaEdit.newName(old, song, MetaEdit.NameStyle.KEEP, true))
        assertNull(MetaEdit.newName(old, song, MetaEdit.NameStyle.ARTIST_TITLE, true)) // уже так
        assertEquals("TRACK 02 — VELVET TEMPLE.flac", MetaEdit.newName(old, song, MetaEdit.NameStyle.TITLE, true))
        assertEquals("02. TRACK 02 — VELVET TEMPLE.flac", MetaEdit.newName(old, song, MetaEdit.NameStyle.NUMBER_TITLE, true))
        assertEquals("TRACK 02 — VELVET TEMPLE.mp3", MetaEdit.newName("x.mp3", song.copy(track = ""), MetaEdit.NameStyle.NUMBER_TITLE, true))
    }

    @Test fun specSurvivesTheTrip() {
        val spec = MetaEdit.Spec(album = "VOL. 4", year = "2027", find = "a", replace = "b", name = MetaEdit.NameStyle.TITLE)
        assertEquals(spec, MetaEdit.Spec.fromMap(spec.toMap()))
    }
}
