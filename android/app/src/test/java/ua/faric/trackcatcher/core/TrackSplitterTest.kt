package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSplitterTest {
    private val a = TrackMeta("Виконавець 1", "Пісня A", durationMs = 10_000)
    private val b = TrackMeta("Виконавець 2", "Пісня B", durationMs = 10_000)
    private val c = TrackMeta("Виконавець 3", "Пісня C", durationMs = 10_000)

    /** Подає звук порціями по 100 мс, як справжній запис. */
    private fun feed(s: TrackSplitter, audio: ShortArray) {
        var off = 0
        val step = TestAudio.ms(100) * 2
        while (off < audio.size) {
            val n = minOf(step, audio.size - off)
            s.onAudio(audio.copyOfRange(off, off + n), n)
            off += n
        }
    }

    @Test
    fun cutsAtSilenceNearSignal() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(5_000)))
        feed(s, TestAudio.silence(TestAudio.ms(400)))       // тиша 5,0–5,4 с
        feed(s, TestAudio.tone(TestAudio.ms(700), 660.0))   // B почалась раніше за сигнал
        s.onTrackChange(b, 700)                              // сигнал на 6,1 с
        feed(s, TestAudio.tone(TestAudio.ms(4_000), 660.0))
        s.stop()

        assertEquals(2, sink.segments.size)
        val first = sink.segments[0]
        assertEquals(EndReason.TRACK_CHANGE, first.end!!.reason)
        assertTrue("розріз по тиші", first.end!!.cutBySilence)
        // розріз посеред тиші: ≈ 5,2 с
        val cutMs = Pcm.framesToMs(first.frames)
        assertTrue("розріз $cutMs мс", cutMs in 5_100..5_300)
        val second = sink.segments[1]
        assertEquals(b, second.meta)
        assertEquals(first.frames, second.start.startFrame)
        // уся решта звуку — у другому шматку, нічого не загубилось
        val total = TestAudio.ms(5_000 + 400 + 700 + 4_000).toLong()
        assertEquals(total, first.frames + second.frames)
        assertEquals(EndReason.STOP, second.end!!.reason)
    }

    @Test
    fun sameTitleDifferentDurationIsSplit() {
        // AIMP, плейлист «Neon»: «Velocity» 3:43, потім інша «Velocity» 3:04.
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(TrackMeta("faric", "Velocity", durationMs = 223_000), 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.onTrackChange(TrackMeta("faric", "Velocity", durationMs = 184_000), 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000), 660.0))
        s.stop()
        assertEquals(2, sink.segments.size)
        assertEquals(184_000L, sink.segments[1].meta.durationMs)
    }

    @Test
    fun lateReaderCutsWhereSignalReallyWas() {
        // Потік запису відстає на 1,5 с: сигнал приходить, коли дочитано лише 5,0 с,
        // а справжня тиша між піснями — на 6,0–6,4 с. Розріз має бути в тиші, а не на 5,0.
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(5_000)))
        s.onTrackChange(b, 0, aheadFrames = TestAudio.ms(1_500).toLong())
        feed(s, TestAudio.tone(TestAudio.ms(1_000)))          // A ще грає до 6,0 с
        feed(s, TestAudio.silence(TestAudio.ms(400)))         // тиша 6,0–6,4
        feed(s, TestAudio.tone(TestAudio.ms(4_000), 660.0))   // B
        s.stop()
        assertEquals(2, sink.segments.size)
        val cutMs = Pcm.framesToMs(sink.segments[0].frames)
        assertTrue("розріз $cutMs мс", cutMs in 6_050..6_350)
        assertTrue(sink.segments[0].end!!.cutBySilence)
    }

    @Test
    fun stopAfterLimitDropsStartOfNextSong() {
        // «Зупинитись після N пісень»: розріз між A і B — по тиші, а початок B у файл не йде.
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        feed(s, TestAudio.silence(TestAudio.ms(300)))
        s.onTrackChange(b, 0)
        feed(s, TestAudio.tone(TestAudio.ms(500), 660.0))
        s.stop(discardCurrent = true)
        assertEquals(2, sink.segments.size)
        assertEquals(EndReason.TRACK_CHANGE, sink.segments[0].end!!.reason)
        assertTrue(sink.segments[1].discarded)
    }

    @Test
    fun cutsAtSignalWhenNoSilence() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.onTrackChange(b, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000), 660.0))
        s.stop()
        assertEquals(2, sink.segments.size)
        assertEquals(TestAudio.ms(3_000).toLong(), sink.segments[0].frames)
        assertTrue(!sink.segments[0].end!!.cutBySilence)
    }

    @Test
    fun quickSkipResolvesImmediately() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.onTrackChange(b, 0)
        feed(s, TestAudio.tone(TestAudio.ms(500), 660.0))
        s.onTrackChange(c, 0)                                 // перемкнули B через 0,5 с
        feed(s, TestAudio.tone(TestAudio.ms(3_000), 880.0))
        s.stop()
        assertEquals(listOf(a, b, c), sink.segments.map { it.meta })
        assertEquals(TestAudio.ms(500).toLong(), sink.segments[1].frames)
        val total = TestAudio.ms(6_500).toLong()
        assertEquals(total, sink.segments.sumOf { it.frames })
    }

    @Test
    fun sameTrackMetadataRefreshDoesNotSplit() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(1_000)))
        s.onTrackChange(a.copy(album = "Альбом", artworkPath = "/x.jpg"), 1_000)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.stop()
        assertEquals(1, sink.segments.size)
    }

    @Test
    fun forcedRestartOfSameTrackSplits() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.onTrackChange(a, 0, force = true)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.stop()
        assertEquals(2, sink.segments.size)
    }

    @Test
    fun stopRightAfterSignalStillSplits() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        s.onTrackChange(b, 0)
        feed(s, TestAudio.tone(TestAudio.ms(300), 660.0))
        s.stop()
        assertEquals(2, sink.segments.size)
        assertEquals(EndReason.STOP, sink.segments[1].end!!.reason)
        assertEquals(TestAudio.ms(3_300).toLong(), sink.segments.sumOf { it.frames })
    }

    @Test
    fun positionShiftsWithCut() {
        val sink = MemorySink()
        val s = TrackSplitter(sink)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(3_000)))
        feed(s, TestAudio.silence(TestAudio.ms(400)))
        feed(s, TestAudio.tone(TestAudio.ms(1_000), 660.0))
        s.onTrackChange(b, 1_000)
        feed(s, TestAudio.tone(TestAudio.ms(3_000), 660.0))
        s.stop()
        // розріз ≈ 3,2 с, сигнал на 4,4 с з позицією 1,0 с → позиція в точці розрізу ≈ 0 (не мінус)
        assertEquals(0L, sink.segments[1].start.positionMs)
    }

    /** T103: AIMP з інтернету — сигнал зі шторки на 10 с раніше за звук; зсув +10 с ставить розріз туди, де справді нова пісня. */
    @Test
    fun bigPositiveShiftCutsLater() {
        val sink = MemorySink()
        val s = TrackSplitter(sink, shiftMs = 10_000)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(5_000)))
        s.onTrackChange(b, 0)                                  // сигнал на 5 с, а стара пісня грає ще 10 с
        feed(s, TestAudio.tone(TestAudio.ms(9_800)))
        feed(s, TestAudio.silence(TestAudio.ms(400)))           // справжня межа ≈ 15 с
        feed(s, TestAudio.tone(TestAudio.ms(6_000), 660.0))
        s.stop()
        assertEquals(2, sink.segments.size)
        val cutMs = Pcm.framesToMs(sink.segments[0].frames)
        assertTrue("розріз $cutMs мс", cutMs in 14_800..15_300)
    }

    /** T103: від'ємний зсув −8 с — розріз раніше за сигнал (звук назад тримається в пам'яті). */
    @Test
    fun bigNegativeShiftCutsEarlier() {
        val sink = MemorySink()
        val s = TrackSplitter(sink, shiftMs = -8_000)
        s.onTrackChange(a, 0)
        feed(s, TestAudio.tone(TestAudio.ms(4_000)))
        feed(s, TestAudio.silence(TestAudio.ms(400)))           // справжня межа ≈ 4,2 с
        feed(s, TestAudio.tone(TestAudio.ms(7_800), 660.0))
        s.onTrackChange(b, 0)                                  // сигнал запізнився на 8 с (≈ 12,2 с)
        feed(s, TestAudio.tone(TestAudio.ms(4_000), 660.0))
        s.stop()
        assertEquals(2, sink.segments.size)
        val cutMs = Pcm.framesToMs(sink.segments[0].frames)
        assertTrue("розріз $cutMs мс", cutMs in 4_000..4_500)
    }
}
