package ua.faric.trackcatcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopRuleTest {
    @Test fun noLimit() = assertFalse(StopRule.stopAtTrackChange(10 * 60 * 60_000L, 0))

    @Test fun suno47MinutesRoundedUp() {
        // Альбом реально 46:31, Suno пише «47 хв»: остання пісня скінчилась на 46:31 → стоп.
        assertTrue(StopRule.stopAtTrackChange(46 * 60_000L + 31_000, 47))
    }

    @Test fun suno47MinutesRoundedDown() {
        // Реально 47:50, Suno пише «47 хв»: зміна пісні на 47:50 → стоп.
        assertTrue(StopRule.stopAtTrackChange(47 * 60_000L + 50_000, 47))
    }

    @Test fun earlierSongChangeKeepsRecording() {
        // Передостання межа альбому — за 2+ хв до кінця: пишемо далі.
        assertFalse(StopRule.stopAtTrackChange(44 * 60_000L + 30_000, 47))
    }

    @Test fun timeUpOnlyAfterFullTime() {
        assertFalse(StopRule.timeUp(47 * 60_000L - 1, 47))
        assertTrue(StopRule.timeUp(47 * 60_000L, 47))
    }
}
