package ua.faric.trackcatcher.core

enum class EndReason { TRACK_CHANGE, STOP }

data class SegmentStart(val index: Int, val startFrame: Long, val positionMs: Long?)

data class SegmentEnd(val reason: EndReason, val cutBySilence: Boolean)

/** Один шматок (майбутній файл) — пишеться на диск, щоб нічого не загубилось. */
interface SegmentHandle {
    val frames: Long
    fun write(samples: ShortArray, offset: Int, lengthShorts: Int)
    fun truncateFrames(frames: Long)
    fun close(end: SegmentEnd)

    /** Плеєр дописав подробиці (альбом, обкладинку, тривалість) уже під час пісні. */
    fun updateMeta(meta: TrackMeta) {}
}

fun interface SegmentSink {
    fun open(meta: TrackMeta, start: SegmentStart): SegmentHandle
}

/**
 * Серце Track Catcher: звук іде без перерв, а пісні ріжуться по сигналу зі шторки.
 *
 * 1. Сигнал «нова пісня» на кадрі F — запам'ятовуємо, але пишемо далі в старий шматок.
 * 2. Коли після F набралось [windowMs] звуку — шукаємо тишу в [F-window, F+window].
 * 3. Старий шматок обрізаємо по точці розрізу, новий починаємо з неї (звук з буфера).
 * Швидке перемикання (нова назва до кінця вікна) — ріжемо одразу по сигналу.
 *
 * Не потокобезпечний: усі виклики — з одного потоку запису.
 */
class TrackSplitter(
    private val sink: SegmentSink,
    private val sampleRate: Int = Pcm.SAMPLE_RATE,
    private val channels: Int = Pcm.CHANNELS,
    windowMs: Int = 2_000,
) {
    private val windowFrames = (sampleRate.toLong() * windowMs / 1000).toInt()
    private val ring = ShortRing(windowFrames * 3 + sampleRate, channels)
    private var current: SegmentHandle? = null
    private var currentMeta: TrackMeta? = null
    private var currentStart = 0L
    private var pending: Pending? = null
    private var nextIndex = 0

    var totalFrames = 0L
        private set

    val segmentsOpened: Int get() = nextIndex

    val currentTrack: TrackMeta? get() = pending?.meta ?: currentMeta

    private data class Pending(val eventFrame: Long, val meta: TrackMeta, val positionMs: Long?)

    /**
     * [force] — та сама пісня почалась знову (повтор) — теж новий файл.
     * [aheadFrames] — на скільки кадрів сигнал пізніший за вже отриманий звук: потік запису
     * може відставати від Android, і тоді сигнал зі шторки стосується звуку, який ще не дочитано.
     * Без цієї поправки розріз ставав раніше, ніж треба, — «нова пісня почалась, а стара ще грає» (14.10).
     */
    fun onTrackChange(meta: TrackMeta, positionMs: Long?, force: Boolean = false, aheadFrames: Long = 0L) {
        if (current == null) {
            pending = null
            open(meta, totalFrames, positionMs)
            return
        }
        val p = pending
        if (p != null) {
            if (!force && p.meta.sameTrack(meta)) return
            resolve(p, useSilence = false)
        } else if (!force && currentMeta?.sameTrack(meta) == true) {
            return
        }
        pending = Pending(totalFrames + aheadFrames.coerceAtLeast(0L), meta, positionMs)
    }

    /** Подробиці тієї самої пісні — оновлюємо, не ріжемо. */
    fun onDetails(meta: TrackMeta) {
        val p = pending
        if (p != null) {
            if (p.meta.sameTrack(meta)) pending = p.copy(meta = meta)
            return
        }
        if (currentMeta?.sameTrack(meta) == true) {
            currentMeta = meta
            current?.updateMeta(meta)
        }
    }

    fun onAudio(samples: ShortArray, lengthShorts: Int) {
        val len = lengthShorts - lengthShorts % channels
        if (len <= 0) return
        current?.write(samples, 0, len)
        ring.append(samples, 0, len)
        totalFrames += len / channels
        val p = pending
        if (p != null && totalFrames >= p.eventFrame + windowFrames) resolve(p, useSilence = true)
    }

    fun stop() {
        pending?.let { resolve(it, useSilence = true) }
        current?.close(SegmentEnd(EndReason.STOP, false))
        current = null
        currentMeta = null
    }

    private fun resolve(p: Pending, useSilence: Boolean) {
        pending = null
        val cur = current
        if (cur == null) {
            open(p.meta, totalFrames, p.positionMs)
            return
        }
        val lowest = maxOf(currentStart, ring.startFrame)
        var cut = p.eventFrame.coerceAtMost(totalFrames)
        var bySilence = false
        if (useSilence) {
            val from = maxOf(p.eventFrame - windowFrames, lowest)
            val to = totalFrames
            if (to - from >= sampleRate / 10 && p.eventFrame in from..to) {
                val window = ring.copy(from, to)
                val r = SilenceCut.findCut(window, channels, sampleRate, (p.eventFrame - from).toInt())
                cut = from + r.frame
                bySilence = r.bySilence
            }
        }
        cut = cut.coerceIn(lowest, totalFrames)
        cur.truncateFrames(cut - currentStart)
        cur.close(SegmentEnd(EndReason.TRACK_CHANGE, bySilence))
        val pos = p.positionMs?.let { maxOf(0L, it + Pcm.framesToMs(cut - p.eventFrame, sampleRate)) }
        open(p.meta, cut, pos)
        if (cut < totalFrames) {
            val tail = ring.copy(cut, totalFrames)
            current?.write(tail, 0, tail.size)
        }
    }

    private fun open(meta: TrackMeta, startFrame: Long, positionMs: Long?) {
        current = sink.open(meta, SegmentStart(nextIndex++, startFrame, positionMs))
        currentMeta = meta
        currentStart = startFrame
    }
}
