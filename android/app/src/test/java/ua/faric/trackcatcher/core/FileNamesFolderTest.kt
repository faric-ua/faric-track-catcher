package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesFolderTest {
    @Test
    fun albumGetsOwnFolder() {
        assertEquals("Music/Track Catcher/Neon/", FileNames.folderFor(TrackMeta("faric", "Velocity", "Neon"), true))
    }

    @Test
    fun noAlbumOrSwitchedOffGoesToRoot() {
        assertEquals("Music/Track Catcher/", FileNames.folderFor(TrackMeta("faric", "Velocity", ""), true))
        assertEquals("Music/Track Catcher/", FileNames.folderFor(TrackMeta("faric", "Velocity", "Neon"), false))
    }

    @Test
    fun albumNameIsMadeSafe() {
        assertEquals("Music/Track Catcher/AC_DC _ Live/", FileNames.folderFor(TrackMeta("x", "y", " AC/DC : Live. "), true))
    }

    @Test
    fun folderLabelInWords() {
        assertEquals("Музика › Track Catcher › Neon", FileNames.folderLabel("Music/Track Catcher/Neon/"))
        assertEquals("Музика › Track Catcher", FileNames.folderLabel("Music/Track Catcher/"))
    }
}
