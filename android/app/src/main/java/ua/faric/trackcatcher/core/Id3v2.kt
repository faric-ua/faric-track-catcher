package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream

/**
 * Тег ID3v2.3 на початку MP3: назва, виконавець, альбом, тривалість, обкладинка.
 * Текст — UTF-16 з BOM, щоб кирилиця читалась у будь-якому плеєрі.
 */
object Id3v2 {
    fun build(meta: TrackMeta, lengthMs: Long, artworkJpeg: ByteArray?): ByteArray {
        val frames = ByteArrayOutputStream()
        if (meta.title.isNotBlank()) frames.write(textFrame("TIT2", meta.title.trim()))
        if (meta.artist.isNotBlank()) frames.write(textFrame("TPE1", meta.artist.trim()))
        if (meta.album.isNotBlank()) frames.write(textFrame("TALB", meta.album.trim()))
        if (lengthMs > 0) frames.write(latinFrame("TLEN", lengthMs.toString()))
        frames.write(latinFrame("TSSE", "Track Catcher"))
        if (artworkJpeg != null && artworkJpeg.isNotEmpty()) frames.write(apicFrame(artworkJpeg))
        val body = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.US_ASCII))
        out.write(3); out.write(0) // версія 2.3.0
        out.write(0) // прапорці
        out.write(syncsafe(body.size))
        out.write(body)
        return out.toByteArray()
    }

    fun syncsafe(size: Int): ByteArray = byteArrayOf(
        ((size shr 21) and 0x7f).toByte(),
        ((size shr 14) and 0x7f).toByte(),
        ((size shr 7) and 0x7f).toByte(),
        (size and 0x7f).toByte(),
    )

    fun textFrame(id: String, text: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(1) // UTF-16 з BOM
        payload.write(0xFF); payload.write(0xFE)
        payload.write(text.toByteArray(Charsets.UTF_16LE))
        payload.write(0); payload.write(0)
        return frame(id, payload.toByteArray())
    }

    private fun latinFrame(id: String, text: String): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0)
        payload.write(text.toByteArray(Charsets.ISO_8859_1))
        return frame(id, payload.toByteArray())
    }

    private fun apicFrame(jpeg: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream()
        payload.write(0)
        payload.write("image/jpeg".toByteArray(Charsets.ISO_8859_1)); payload.write(0)
        payload.write(3) // обкладинка (лицьова сторона)
        payload.write(0) // порожній опис
        payload.write(jpeg)
        return frame("APIC", payload.toByteArray())
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.US_ASCII))
        val n = payload.size
        out.write((n shr 24) and 0xff); out.write((n shr 16) and 0xff)
        out.write((n shr 8) and 0xff); out.write(n and 0xff)
        out.write(0); out.write(0)
        out.write(payload)
        return out.toByteArray()
    }
}
