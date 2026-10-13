package ua.faric.trackcatcher.core

/** Синтетичний звук для тестів: тон або тиша, стерео 48 кГц. */
object TestAudio {
    fun tone(frames: Int, freq: Double = 440.0, amp: Int = 12_000, startFrame: Long = 0): ShortArray {
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val t = (startFrame + i) / Pcm.SAMPLE_RATE.toDouble()
            val v = (amp * Math.sin(2 * Math.PI * freq * t)).toInt().toShort()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
        return out
    }

    fun silence(frames: Int): ShortArray = ShortArray(frames * 2)

    fun ms(ms: Int): Int = Pcm.SAMPLE_RATE / 1000 * ms
}

/** Пише шматки в пам'ять — щоб перевірити різак без диска. */
class MemorySink : SegmentSink {
    class Seg(val meta: TrackMeta, val start: SegmentStart) : SegmentHandle {
        val data = ArrayList<Short>()
        var end: SegmentEnd? = null
        override val frames: Long get() = data.size / 2L
        override fun write(samples: ShortArray, offset: Int, lengthShorts: Int) {
            for (i in offset until offset + lengthShorts) data.add(samples[i])
        }
        override fun truncateFrames(frames: Long) {
            while (data.size > frames * 2) data.removeAt(data.size - 1)
        }
        override fun close(end: SegmentEnd) {
            this.end = end
        }
    }

    val segments = mutableListOf<Seg>()
    override fun open(meta: TrackMeta, start: SegmentStart): SegmentHandle = Seg(meta, start).also { segments += it }
}
