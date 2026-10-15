package ua.faric.trackcatcher.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Плеєр усередині застосунку (faric 14.10: «щоб не відкривав зовнішній плеєр»).
 * Один на весь застосунок — переживає поворот екрана. Усі виклики — з головного потоку.
 * Свій звук Track Catcher сам не записує: ALLOW_CAPTURE_BY_NONE.
 */
object InAppPlayer {
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var audio: AudioManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()
    private var attachedViews = 0

    /** Що зараз відкрито в плеєрі (content://…), або null. */
    var uri: String? = null
        private set
    var prepared = false
        private set
    var error: String? = null
        private set

    val playing: Boolean get() = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
    val positionMs: Long get() = if (prepared) runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else 0L
    val durationMs: Long get() = if (prepared) runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L).coerceAtLeast(0L) else 0L

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()

    /**
     * Файл дограв до кінця — історія відкриває наступну пісню й грає далі (v0.1.17, «слухати як у плеєрі»).
     * Ставить екран «Історія», поки він на екрані.
     */
    var onFinished: ((String) -> Unit)? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    /** Почати (або продовжити) файл [newUri]. Інший файл — зупиняє попередній. */
    fun play(context: Context, newUri: String) {
        if (newUri == uri && player != null) {
            resume(context)
            return
        }
        release()
        uri = newUri
        error = null
        val app = context.applicationContext
        audio = app.getSystemService(AudioManager::class.java)
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(attrs)
            p.setDataSource(app, Uri.parse(newUri))
            p.setOnPreparedListener {
                prepared = true
                if (requestFocus()) it.start()
                changed()
            }
            p.setOnCompletionListener {
                abandonFocus()
                changed()
                val done = uri
                if (done != null) onFinished?.invoke(done)
            }
            p.setOnErrorListener { _, _, _ ->
                fail("Файл не відкривається — можливо, його видалили в «Моїх файлах».")
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            fail("Файл не відкривається — можливо, його видалили в «Моїх файлах».")
        }
        changed()
    }

    fun toggle(context: Context) {
        if (playing) pause() else resume(context)
    }

    private fun resume(context: Context) {
        val p = player ?: return
        if (!prepared) return
        if (audio == null) audio = context.applicationContext.getSystemService(AudioManager::class.java)
        if (p.currentPosition >= p.duration - 300) p.seekTo(0)
        if (requestFocus()) p.start()
        changed()
    }

    fun pause() {
        val p = player ?: return
        if (prepared && runCatching { p.isPlaying }.getOrDefault(false)) p.pause()
        changed()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        if (!prepared) return
        p.seekTo(ms.coerceIn(0L, durationMs).toInt())
        changed()
    }

    fun stop() {
        if (player == null && uri == null) return
        release()
        changed()
    }

    /** Вікно з плеєром з'явилось / зникло. Коли не лишилось жодного — зупиняємо (з запасом на поворот). */
    fun viewAttached() { attachedViews++ }

    fun viewDetached() {
        attachedViews = (attachedViews - 1).coerceAtLeast(0)
        handler.postDelayed({ if (attachedViews == 0) stop() }, 600)
    }

    private fun fail(message: String) {
        val keep = uri
        release()
        uri = keep
        error = message
        changed()
    }

    private fun requestFocus(): Boolean {
        val am = audio ?: return true
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                ) pause()
            }, handler)
            .build().also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audio ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
    }

    private fun release() {
        abandonFocus()
        player?.let { p -> runCatching { p.reset() }; runCatching { p.release() } }
        player = null
        prepared = false
        uri = null
        error = null
    }
}
