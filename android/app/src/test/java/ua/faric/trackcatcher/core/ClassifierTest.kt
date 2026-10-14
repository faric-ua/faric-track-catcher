package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {
    private val song = TrackMeta("A", "B", durationMs = 240_000)

    @Test
    fun fullSongIsComplete() {
        val v = Classifier.classify(song, 238_500, 238_000, 0, EndReason.TRACK_CHANGE, 30)
        assertEquals(RecStatus.COMPLETE, v.status)
    }

    @Test
    fun startedInTheMiddleIsPartial() {
        val v = Classifier.classify(song, 170_000, 170_000, 70_000, EndReason.TRACK_CHANGE, 30)
        assertEquals(RecStatus.PARTIAL, v.status)
        assertTrue(v.reasonText, v.reasonText.contains("почато посеред (з 1:10)"))
        assertTrue(v.reasons.none { it.contains("перемкнуто") })
    }

    @Test
    fun stoppedEarlyIsPartial() {
        val v = Classifier.classify(song, 60_000, 60_000, 0, EndReason.STOP, 30)
        assertEquals(RecStatus.PARTIAL, v.status)
        assertEquals("зупинено посеред", v.reasonText)
    }

    @Test
    fun skippedEarlyIsPartial() {
        val v = Classifier.classify(song, 60_000, 60_000, 0, EndReason.TRACK_CHANGE, 30)
        assertEquals("перемкнуто до кінця пісні", v.reasonText)
    }

    @Test
    fun silenceIsFailed() {
        val v = Classifier.classify(song, 60_000, 0, 0, EndReason.TRACK_CHANGE, 30)
        assertEquals(RecStatus.FAILED, v.status)
    }

    @Test
    fun silenceBeforeMusicIsSkippedNotFailed() {
        val v = Classifier.classify(TrackMeta(), 12_000, 0, null, EndReason.TRACK_CHANGE, 30)
        assertEquals(RecStatus.SKIPPED, v.status)
        assertEquals("тиша — нічого не грало", v.reasonText)
    }

    @Test
    fun shortIsSkipped() {
        val ad = TrackMeta("", "Реклама", durationMs = 15_000)
        val v = Classifier.classify(ad, 15_000, 15_000, 0, EndReason.TRACK_CHANGE, 30)
        assertEquals(RecStatus.SKIPPED, v.status)
        assertEquals("коротше 30 с", v.reasonText)
    }

    @Test
    fun unknownDurationTrackChangeIsComplete() {
        val v = Classifier.classify(TrackMeta("A", "B"), 200_000, 200_000, 0, EndReason.TRACK_CHANGE, 30)
        assertEquals(RecStatus.COMPLETE, v.status)
    }

    @Test
    fun trimSkipsLeadingAndTrailingSilence() {
        val db = DoubleArray(1000) { i -> if (i in 100..899) -20.0 else -120.0 }
        val st = LevelAnalysis.trim(db, 1000L * 480)
        assertEquals((100 - 20) * 480L, st.startFrame)
        assertEquals((900 + 20) * 480L, st.endFrame)
        assertEquals(8_000L, st.audibleMs)
    }

    @Test
    fun silentSegmentIsNotTrimmedAway() {
        val st = LevelAnalysis.trim(DoubleArray(500) { -120.0 }, 500L * 480)
        assertEquals(0L, st.startFrame)
        assertEquals(500L * 480, st.endFrame)
        assertEquals(0L, st.audibleMs)
    }
}
