package ua.faric.trackcatcher.core

/**
 * Скільки разів звук упирався в стелю (±32767): пісня надто гучна, піки обрізано —
 * на слух це «чирк» у гучних місцях. Рахуємо місця (серії поспіль), а не окремі семпли.
 */
class ClipCounter(private val channels: Int = Pcm.CHANNELS) {
    private val inClip = BooleanArray(channels)

    var events = 0L
        private set

    fun feed(samples: ShortArray, lengthShorts: Int) {
        var ch = 0
        for (i in 0 until lengthShorts) {
            val v = samples[i].toInt()
            val clipped = v >= CEILING || v <= -CEILING - 1
            if (clipped && !inClip[ch]) events++
            inClip[ch] = clipped
            ch++
            if (ch == channels) ch = 0
        }
    }

    companion object {
        const val CEILING = 32_767
    }
}
