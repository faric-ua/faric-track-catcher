package ua.faric.trackcatcher.core

/**
 * Кільцевий буфер останніх кадрів звуку з абсолютною нумерацією кадрів.
 * Потрібен, щоб після сигналу «нова пісня» знайти тишу трохи раніше й трохи пізніше.
 */
class ShortRing(val capacityFrames: Int, val channels: Int) {
    private val data = ShortArray(capacityFrames * channels)

    /** Номер кадру одразу після останнього записаного. */
    var endFrame: Long = 0L
        private set

    val startFrame: Long get() = maxOf(0L, endFrame - capacityFrames)

    fun append(src: ShortArray, offset: Int, lengthShorts: Int) {
        var frames = lengthShorts / channels
        var srcOff = offset
        if (frames > capacityFrames) {
            val skip = frames - capacityFrames
            srcOff += skip * channels
            endFrame += skip
            frames = capacityFrames
        }
        var remaining = frames
        while (remaining > 0) {
            val pos = (endFrame % capacityFrames).toInt()
            val chunk = minOf(remaining, capacityFrames - pos)
            System.arraycopy(src, srcOff, data, pos * channels, chunk * channels)
            srcOff += chunk * channels
            endFrame += chunk
            remaining -= chunk
        }
    }

    fun copy(fromFrame: Long, toFrame: Long): ShortArray {
        require(fromFrame in startFrame..toFrame && toFrame <= endFrame) {
            "кадри $fromFrame..$toFrame поза буфером $startFrame..$endFrame"
        }
        val frames = (toFrame - fromFrame).toInt()
        val out = ShortArray(frames * channels)
        var f = fromFrame
        var outOff = 0
        while (f < toFrame) {
            val pos = (f % capacityFrames).toInt()
            val chunk = minOf((toFrame - f).toInt(), capacityFrames - pos)
            System.arraycopy(data, pos * channels, out, outOff, chunk * channels)
            outOff += chunk * channels
            f += chunk
        }
        return out
    }
}
