package ua.faric.trackcatcher.encode

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.OutputStream
import ua.faric.trackcatcher.core.Pcm

/**
 * MP3 через jump3r (Java-порт LAME 3.98.4). Обгортка повторює ініціалізацію
 * з de.sciss.jump3r.lowlevel.LameEncoder, але без javax.sound — його в Android немає.
 * CBR, joint stereo, без VBR-тегу (тривалість рахується з бітрейту).
 */
class Mp3Encoder(
    sampleRate: Int = Pcm.SAMPLE_RATE,
    bitrateKbps: Int = 320,
    quality: Int = 5,
) : Closeable {
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private val left = IntArray(CHUNK_FRAMES)
    private val right = IntArray(CHUNK_FRAMES)
    private val out = ByteArray((CHUNK_FRAMES * 5) / 4 + 7200)
    private var closed = false

    init {
        val gaud = GetAudio()
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, presets)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init()
        gfp.num_channels = 2
        gfp.in_samplerate = sampleRate
        gfp.out_samplerate = sampleRate
        gfp.mode = MPEGMode.JOINT_STEREO
        gfp.brate = bitrateKbps
        gfp.quality = quality
        id3.id3tag_init(gfp)
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false
        gfp.bWriteVbrTag = false
        val rc = lame.lame_init_params(gfp)
        require(rc >= 0) { "LAME не прийняв параметри ($rc)" }
    }

    val effectiveBitrate: Int get() = gfp.brate

    /** Кодує стерео PCM 16 біт little-endian. Довжина — кратна 4 байтам. */
    fun encode(pcm: ByteArray, offset: Int, lengthBytes: Int, sink: OutputStream) {
        check(!closed) { "кодувальник уже закрито" }
        var pos = offset
        var framesLeft = lengthBytes / Pcm.BYTES_PER_FRAME
        while (framesLeft > 0) {
            val n = minOf(framesLeft, CHUNK_FRAMES)
            for (i in 0 until n) {
                left[i] = sample(pcm, pos) shl 16
                right[i] = sample(pcm, pos + 2) shl 16
                pos += Pcm.BYTES_PER_FRAME
            }
            val m = lame.lame_encode_buffer_int(gfp, left, right, n, out, 0, out.size)
            check(m >= 0) { "помилка MP3-кодування ($m)" }
            if (m > 0) sink.write(out, 0, m)
            framesLeft -= n
        }
    }

    fun finish(sink: OutputStream) {
        check(!closed) { "кодувальник уже закрито" }
        val m = lame.lame_encode_flush(gfp, out, 0, out.size)
        if (m > 0) sink.write(out, 0, m)
    }

    override fun close() {
        if (closed) return
        closed = true
        lame.lame_close(gfp)
    }

    private fun sample(b: ByteArray, i: Int): Int = (b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xff)

    companion object {
        const val CHUNK_FRAMES = 4608
        val BITRATES = listOf(128, 192, 256, 320)
    }
}
