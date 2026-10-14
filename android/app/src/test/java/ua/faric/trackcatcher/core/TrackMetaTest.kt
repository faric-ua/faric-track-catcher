package ua.faric.trackcatcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Плейлист «Neon» в AIMP (14.10): три різні «Velocity» — 3:43, 3:43, 3:04. */
class TrackMetaTest {
    private val v1 = TrackMeta("faric", "Velocity", "Neon", 223_000, mediaId = "10")
    private val v2 = TrackMeta("faric", "Velocity", "Neon", 223_000, mediaId = "11")
    private val v3 = TrackMeta("faric", "Velocity", "Neon", 184_000, mediaId = "12")

    @Test
    fun sameTitleDifferentDurationIsAnotherSong() {
        assertFalse(v1.copy(mediaId = "").sameTrack(v3.copy(mediaId = "")))
    }

    @Test
    fun sameTitleDifferentPlayerIdIsAnotherSong() {
        assertFalse(v1.sameTrack(v2))
    }

    @Test
    fun detailsArrivingLaterStillSameSong() {
        val bare = TrackMeta("faric", "Velocity")
        assertTrue(bare.sameTrack(v1))
        assertTrue(v1.sameTrack(bare))
        assertTrue(v1.sameTrack(v1.copy(durationMs = 224_500, album = "", mediaId = "")))
        assertTrue(TrackMeta(" FARIC ", "velocity ").sameTrack(v1))
    }

    @Test
    fun differentTitleIsAnotherSong() {
        assertFalse(v1.sameTrack(v1.copy(title = "Horizon")))
    }
}
