package ua.faric.trackcatcher.core

import java.io.ByteArrayOutputStream

/** Заголовок WAV (PCM) з розділом LIST/INFO: назва, виконавець, альбом. */
object WavHeader {
    fun build(
        dataBytes: Long,
        sampleRate: Int = Pcm.SAMPLE_RATE,
        channels: Int = Pcm.CHANNELS,
        bitsPerSample: Int = 16,
        info: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val list = infoChunk(info)
        val blockAlign = channels * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val riffSize = 4L + (8 + 16) + list.size + 8 + dataBytes + (dataBytes and 1L)
        val out = ByteArrayOutputStream()
        out.ascii("RIFF"); out.le32(riffSize); out.ascii("WAVE")
        out.ascii("fmt "); out.le32(16); out.le16(1); out.le16(channels)
        out.le32(sampleRate.toLong()); out.le32(byteRate.toLong()); out.le16(blockAlign); out.le16(bitsPerSample)
        out.write(list)
        out.ascii("data"); out.le32(dataBytes)
        return out.toByteArray()
    }

    fun infoFor(meta: TrackMeta): List<Pair<String, String>> = buildList {
        if (meta.title.isNotBlank()) add("INAM" to meta.title.trim())
        if (meta.artist.isNotBlank()) add("IART" to meta.artist.trim())
        if (meta.album.isNotBlank()) add("IPRD" to meta.album.trim())
        if (meta.trackNo > 0) add("ITRK" to meta.trackNo.toString())
        if (meta.year > 0) add("ICRD" to meta.year.toString())
        add("ISFT" to "Track Catcher")
    }

    private fun infoChunk(info: List<Pair<String, String>>): ByteArray {
        if (info.isEmpty()) return ByteArray(0)
        val body = ByteArrayOutputStream()
        body.ascii("INFO")
        for ((id, value) in info) {
            require(id.length == 4) { "ідентифікатор INFO має 4 символи: $id" }
            val bytes = value.toByteArray(Charsets.UTF_8)
            body.ascii(id)
            body.le32(bytes.size + 1L)
            body.write(bytes)
            body.write(0)
            if ((bytes.size + 1) % 2 == 1) body.write(0)
        }
        val b = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.ascii("LIST"); out.le32(b.size.toLong()); out.write(b)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xff); write((v shr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.le32(v: Long) {
        write((v and 0xff).toInt()); write(((v shr 8) and 0xff).toInt())
        write(((v shr 16) and 0xff).toInt()); write(((v shr 24) and 0xff).toInt())
    }
}
