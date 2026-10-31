package ua.faric.trackcatcher.capture

import java.io.File
import java.io.RandomAccessFile
import ua.faric.trackcatcher.core.Pcm
import ua.faric.trackcatcher.core.SegmentEnd
import ua.faric.trackcatcher.core.SegmentHandle
import ua.faric.trackcatcher.core.SegmentSink
import ua.faric.trackcatcher.core.SegmentStart
import ua.faric.trackcatcher.core.TrackMeta

/**
 * Кожен шматок — тимчасовий файл .pcm у пам'яті застосунку + .json з назвою.
 * Якщо застосунок закриється, записане лишиться і буде збережене при наступному запуску.
 */
class FileSegmentSink(
    private val dir: File,
    private val sessionId: Long,
    /** Початок імені файлів шматків; свій для кожного запуску запису. */
    private val prefix: String = "s$sessionId",
    private val onClosed: (SegmentJob) -> Unit,
) : SegmentSink {

    override fun open(meta: TrackMeta, start: SegmentStart): SegmentHandle {
        dir.mkdirs()
        val base = "$prefix-" + start.index.toString().padStart(3, '0')
        return FileHandle(File(dir, "$base.pcm"), File(dir, "$base.json"), meta, start)
    }

    private inner class FileHandle(
        private val pcm: File,
        private val sidecar: File,
        private var meta: TrackMeta,
        private val start: SegmentStart,
    ) : SegmentHandle {
        private val raf = RandomAccessFile(pcm, "rw").apply { setLength(0) }
        private var bytes = ByteArray(64 * 1024)
        private var closed = false

        override var frames: Long = 0L
            private set

        init {
            writeSidecar(null, false)
        }

        override fun write(samples: ShortArray, offset: Int, lengthShorts: Int) {
            if (closed) return
            if (bytes.size < lengthShorts * 2) bytes = ByteArray(lengthShorts * 2)
            val n = Pcm.shortsToBytes(samples, offset, lengthShorts, bytes)
            raf.write(bytes, 0, n)
            frames += lengthShorts / Pcm.CHANNELS
        }

        override fun truncateFrames(frames: Long) {
            if (closed) return
            val keep = frames.coerceIn(0L, this.frames)
            raf.setLength(keep * Pcm.BYTES_PER_FRAME)
            raf.seek(keep * Pcm.BYTES_PER_FRAME)
            this.frames = keep
        }

        override fun updateMeta(meta: TrackMeta) {
            this.meta = meta
            if (!closed) writeSidecar(null, false)
        }

        override fun close(end: SegmentEnd) {
            if (closed) return
            closed = true
            runCatching { raf.fd.sync() }
            raf.close()
            writeSidecar(end, end.cutBySilence)
            onClosed(
                SegmentJob(
                    pcmFile = pcm,
                    sidecar = sidecar,
                    sessionId = sessionId,
                    index = start.index,
                    meta = meta,
                    startPositionMs = start.positionMs,
                    endReason = end.reason,
                    cutBySilence = end.cutBySilence,
                    closedAt = System.currentTimeMillis(),
                ),
            )
        }

        override fun discard() {
            if (closed) return
            closed = true
            runCatching { raf.close() }
            pcm.delete()
            sidecar.delete()
        }

        private fun writeSidecar(end: SegmentEnd?, cutBySilence: Boolean) {
            runCatching {
                sidecar.writeText(SegmentJob.sidecarJson(sessionId, start.index, meta, start.positionMs, end?.reason, cutBySilence))
            }
        }
    }
}
