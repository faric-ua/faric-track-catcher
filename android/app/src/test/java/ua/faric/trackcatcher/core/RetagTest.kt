package ua.faric.trackcatcher.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class RetagTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    private val meta = TrackMeta("Saney", "⚡ BONUS 03 — TWO VOICES", "VOL. 2", trackNo = 16, year = 2026)

    private fun read(b: ByteArray) = FileTags.read(ByteArrayInputStream(b), b.size.toLong())
    private fun rewrite(kind: Retag.Kind, b: ByteArray, set: Map<String, String>) =
        ByteArrayOutputStream().also { Retag.rewrite(kind, ByteArrayInputStream(b), it, set) }.toByteArray()
    private fun digest(kind: Retag.Kind, b: ByteArray) = Retag.audioDigest(kind, ByteArrayInputStream(b))

    @Test fun flacNumberChangesSoundAndCoverStay() {
        val frames = 48_000L
        val pcm = ByteArray((frames * 4).toInt()) { (it * 13 % 251).toByte() }
        val out = ByteArrayOutputStream()
        FlacEncoder(out, frames, MessageDigest.getInstance("MD5").digest(pcm), FlacEncoder.tagsFor(meta), jpeg).apply { encode(pcm, 0, pcm.size); finish() }
        val before = out.toByteArray()
        assertEquals("16", read(before)[FileTags.Field.TRACK])

        val after = rewrite(Retag.Kind.FLAC, before, mapOf("TRACKNUMBER" to "13"))
        val t = read(after)
        assertEquals("13", t[FileTags.Field.TRACK])
        assertEquals(1, t.raw.count { it.key == "TRACKNUMBER" }) // старий номер не лишився поруч
        assertEquals("⚡ BONUS 03 — TWO VOICES", t[FileTags.Field.TITLE])
        assertEquals("Saney", t[FileTags.Field.ALBUM_ARTIST])
        assertArrayEquals(jpeg, t.picture!!.bytes)
        assertEquals(digest(Retag.Kind.FLAC, before), digest(Retag.Kind.FLAC, after))
        // І звук розкодовується той самий.
        val dec = MiniFlacDecoder(after).decodeAll()
        val back = ByteArray(dec.size * 2)
        for (i in dec.indices) { back[i * 2] = dec[i].toInt().toByte(); back[i * 2 + 1] = (dec[i].toInt() shr 8).toByte() }
        assertArrayEquals(pcm, back)
    }

    @Test fun mp3NumberChangesOtherFramesAndSoundStay() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE4.toByte(), 0x00) + ByteArray(2000) { (it % 97).toByte() }
        val before = Id3v2.build(meta, 300_000, jpeg) + audio
        val after = rewrite(Retag.Kind.MP3, before, mapOf("TRACKNUMBER" to "13"))
        val t = read(after)
        assertEquals("13", t[FileTags.Field.TRACK])
        assertEquals(1, t.raw.count { it.key == "TRCK" })
        assertEquals("⚡ BONUS 03 — TWO VOICES", t[FileTags.Field.TITLE])
        assertEquals("2026", t[FileTags.Field.YEAR])
        assertArrayEquals(jpeg, t.picture!!.bytes)
        assertEquals(digest(Retag.Kind.MP3, before), digest(Retag.Kind.MP3, after))
        assertArrayEquals(audio, after.copyOfRange(after.size - audio.size, after.size))
    }

    @Test fun mp3WithoutTagGetsOne() {
        val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0xE4.toByte(), 0x00) + ByteArray(500)
        val after = rewrite(Retag.Kind.MP3, audio, mapOf("TRACKNUMBER" to "7"))
        assertEquals("7", read(after)[FileTags.Field.TRACK])
        assertEquals(digest(Retag.Kind.MP3, audio), digest(Retag.Kind.MP3, after))
    }
}
