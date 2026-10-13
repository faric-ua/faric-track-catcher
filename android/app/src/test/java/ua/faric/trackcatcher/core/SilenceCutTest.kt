package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceCutTest {
    @Test
    fun picksQuietRunClosestToTarget() {
        val audio = TestAudio.tone(TestAudio.ms(1_000)) + TestAudio.silence(TestAudio.ms(200)) +
            TestAudio.tone(TestAudio.ms(1_000)) + TestAudio.silence(TestAudio.ms(200)) + TestAudio.tone(TestAudio.ms(1_000))
        val r = SilenceCut.findCut(audio, 2, Pcm.SAMPLE_RATE, TestAudio.ms(2_300))
        assertTrue(r.bySilence)
        assertEquals(TestAudio.ms(2_300).toDouble(), r.frame.toDouble(), TestAudio.ms(20).toDouble())
    }

    @Test
    fun ignoresTooShortDips() {
        val audio = TestAudio.tone(TestAudio.ms(1_000)) + TestAudio.silence(TestAudio.ms(30)) + TestAudio.tone(TestAudio.ms(1_000))
        val r = SilenceCut.findCut(audio, 2, Pcm.SAMPLE_RATE, TestAudio.ms(500))
        assertFalse(r.bySilence)
        assertEquals(TestAudio.ms(500), r.frame)
    }

    @Test
    fun quietMusicIsNotSilence() {
        val audio = TestAudio.tone(TestAudio.ms(2_000), amp = 600) // ≈ -37 dBFS
        val r = SilenceCut.findCut(audio, 2, Pcm.SAMPLE_RATE, TestAudio.ms(1_000))
        assertFalse(r.bySilence)
    }
}
