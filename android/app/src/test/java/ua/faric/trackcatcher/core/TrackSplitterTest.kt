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
}
