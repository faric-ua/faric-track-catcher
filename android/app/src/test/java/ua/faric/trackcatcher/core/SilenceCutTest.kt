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

    @Test
    fun findsPlayerHardSwitch() {
        // Як у файлі faric 14.10: стара пісня гучна й обривається, 12 мс майже тиші, нова пісня.
        // Сигнал зі шторки — на 130 мс раніше за обрив.
        val audio = TestAudio.tone(TestAudio.ms(1_000), amp = 8_000) + TestAudio.silence(TestAudio.ms(12)) +
            TestAudio.tone(TestAudio.ms(1_000), 660.0, amp = 3_000)
        val r = SilenceCut.findCut(audio, 2, Pcm.SAMPLE_RATE, TestAudio.ms(870))
        assertTrue(r.bySilence)
        assertEquals(TestAudio.ms(1_006).toDouble(), r.frame.toDouble(), TestAudio.ms(4).toDouble())
    }

    @Test
    fun longSilenceStillPreferredOverHardGap() {
        val audio = TestAudio.tone(TestAudio.ms(500)) + TestAudio.silence(TestAudio.ms(10)) + TestAudio.tone(TestAudio.ms(500)) +
            TestAudio.silence(TestAudio.ms(200)) + TestAudio.tone(TestAudio.ms(500))
        val r = SilenceCut.findCut(audio, 2, Pcm.SAMPLE_RATE, TestAudio.ms(505))
        assertTrue(r.bySilence)
        assertEquals(TestAudio.ms(1_110).toDouble(), r.frame.toDouble(), TestAudio.ms(20).toDouble())
    }
}
