package ua.faric.trackcatcher.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import ua.faric.trackcatcher.core.Playlist
import ua.faric.trackcatcher.playback.PlaybackService

/**
 * Плеєр усередині застосунку (faric 14.10: «щоб не відкривав зовнішній плеєр»).
 * Один на весь застосунок — переживає поворот екрана. Усі виклики — з головного потоку.
 * Свій звук Track Catcher сам не записує: ALLOW_CAPTURE_BY_NONE.
 *
 * v0.1.18 (faric 15.10 17:31: «щоб у шторці зверху теж показувало, що грається»): черга записів
 * історії, ⏮/⏭ і «дограла — наступна» живуть тут, а не в екрані, — тож працюють і зі шторки,
 * і коли застосунок сховано. Шторку й заблокований екран показує [PlaybackService].
 */
object InAppPlayer {
    /** Що грає: запис історії й файл, який саме слухаємо (MP3 або WAV). */
    data class Track(
        val id: Long,
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkPath: String?,
    )

    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var audio: AudioManager? = null
    private var app: Context? = null
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

    /** Поточний запис і черга (порядок історії — новіші згори). */
    var current: Track? = null
        private set
    private var queue: List<Track> = emptyList()

    /** Перейшли на інший запис не з екрана (шторка, «дограла») — екран показує його подробиці. */
    var onTrackChanged: ((Track) -> Unit)? = null

    val playing: Boolean get() = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
    val positionMs: Long get() = if (prepared) runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L) else 0L
    val durationMs: Long get() = if (prepared) runCatching { player?.duration?.toLong() ?: 0L }.getOrDefault(0L).coerceAtLeast(0L) else 0L

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
        .build()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it() }

    /** Грати [track]; [newestFirst] — записи з файлами в порядку історії (для ⏮/⏭). */
    fun play(context: Context, track: Track, newestFirst: List<Track>) {
        queue = newestFirst
        current = track
        open(context, track.uri)
    }

    fun hasNeighbor(next: Boolean): Boolean {
        val cur = current ?: return false
        return Playlist.neighbor(queue.map { it.id }, { true }, cur.id, next) != null
    }

    /** ⏮ / ⏭ у черзі. Повертає false на краю. */
    fun skip(context: Context, next: Boolean): Boolean {
        val cur = current ?: return false
        val id = Playlist.neighbor(queue.map { it.id }, { true }, cur.id, next) ?: return false
        val t = queue.firstOrNull { it.id == id } ?: return false
        current = t
        open(context, t.uri)
        onTrackChanged?.invoke(t)
        return true
    }

    /** Відкрити файл і почати. Той самий файл — продовжити. */
    private fun open(context: Context, newUri: String) {
        val appCtx = context.applicationContext
        app = appCtx
        if (newUri == uri && player != null) {
            resume(appCtx)
            return
        }
        release()
        uri = newUri
        error = null
        audio = appCtx.getSystemService(AudioManager::class.java)
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(attrs)
            p.setDataSource(appCtx, Uri.parse(newUri))
            p.setOnPreparedListener {
                prepared = true
                if (requestFocus()) it.start()
                changed()
            }
            p.setOnCompletionListener {
                // Дограла — наступна (як в альбомі); остання — стоп на місці.
                if (!skip(appCtx, next = true)) {
                    abandonFocus()
                    changed()
                }
            }
            p.setOnErrorListener { _, _, _ ->
                fail("Файл не відкривається — можливо, його видалили в «Моїх файлах».")
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            fail("Файл не відкривається — можливо, його видалили в «Моїх файлах».")
        }
        PlaybackService.ensureStarted(appCtx)
        changed()
    }

    fun toggle(context: Context) {
        if (playing) pause() else resume(context)
    }

    fun resume(context: Context) {
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

    /** Зупинити зовсім: файл закрито, шторка зникає. */
    fun stop() {
        if (player == null && uri == null && current == null) return
        release()
        current = null
        queue = emptyList()
        changed()
        app?.let { PlaybackService.stop(it) }
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
