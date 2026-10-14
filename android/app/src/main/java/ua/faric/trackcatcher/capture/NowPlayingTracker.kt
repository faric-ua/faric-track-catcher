package ua.faric.trackcatcher.capture

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import ua.faric.trackcatcher.core.LoopDetector
import ua.faric.trackcatcher.core.TrackMeta

/**
 * Слухач шторки: що грає зараз (назва, виконавець, альбом, обкладинка, позиція, пауза).
 * Стежить за всіма плеєрами; активний — той, що грає.
 * Усі виклики й події — у головному потоці.
 */
class NowPlayingTracker(
    context: Context,
    private val listener: Listener,
    /** Служба запису: щосекунди перевіряти повтор однієї пісні (плеєр може про нього мовчати). */
    private val watchLoops: Boolean = false,
) {
    interface Listener {
        /** Нова пісня (або та сама почалась спочатку — [restart]). */
        fun onTrack(meta: TrackMeta, positionMs: Long?, restart: Boolean)

        /** Плеєр дописав подробиці тієї самої пісні. */
        fun onDetails(meta: TrackMeta) {}

        fun onPlaying(playing: Boolean) {}
    }

    private val app = context.applicationContext
    private val msm = app.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(app, NowPlayingListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val watched = LinkedHashMap<MediaSession.Token, Watch>()
    private var active: MediaController? = null
    private var started = false
    private val loops = LoopDetector()
    private val loopPoll = object : Runnable {
        override fun run() {
            if (!started) return
            checkLoop()
            handler.postDelayed(this, 1_000)
        }
    }

    var meta: TrackMeta? = null
        private set
    var playing = false
        private set

    /** Чи відомий плеєр узагалі (якщо ні — пишемо все підряд). */
    val hasPlayer: Boolean get() = active != null

    val sourcePackage: String? get() = active?.packageName

    private inner class Watch(val controller: MediaController) : MediaController.Callback() {
        var lastState: PlaybackState? = null

        override fun onMetadataChanged(metadata: MediaMetadata?) = handleMeta(controller, metadata)

        override fun onPlaybackStateChanged(state: PlaybackState?) = handleState(controller, state)

        override fun onSessionDestroyed() {
            handler.post { refresh() }
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        sync(list ?: emptyList())
    }

    fun start(): Boolean {
        if (started) return true
        if (!hasAccess(app)) return false
        return try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            refresh()
            if (watchLoops) handler.postDelayed(loopPoll, 1_000)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        if (started) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        handler.removeCallbacksAndMessages(null)
        watched.values.forEach { w -> runCatching { w.controller.unregisterCallback(w) } }
        watched.clear()
        active = null
    }

    fun refresh() {
        if (!started) return
        try {
            sync(msm.getActiveSessions(component))
        } catch (e: SecurityException) {
            // доступ до сповіщень забрали
        }
    }

    fun positionMs(): Long? = active?.let { position(it.playbackState) }

    /** Поставити на паузу плеєр, що грає («Зупинитись після N пісень»). false — плеєра не видно. */
    fun pausePlayer(): Boolean {
        val c = active ?: return false
        return runCatching { c.transportControls.pause() }.isSuccess
    }

    private fun sync(list: List<MediaController>) {
        val own = app.packageName
        val fresh = list.filter { it.packageName != own }
        val tokens = fresh.map { it.sessionToken }.toSet()
        val iterator = watched.entries.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            if (e.key !in tokens) {
                runCatching { e.value.controller.unregisterCallback(e.value) }
                iterator.remove()
                if (active?.sessionToken == e.key) active = null
            }
        }
        for (c in fresh) {
            if (c.sessionToken !in watched) {
                val w = Watch(c)
                c.registerCallback(w, handler)
                watched[c.sessionToken] = w
            }
        }
        val playingOne = fresh.firstOrNull { isPlaying(it.playbackState) }
        val current = active?.let { a -> fresh.firstOrNull { it.sessionToken == a.sessionToken } }
        switchTo(playingOne ?: current ?: fresh.firstOrNull())
    }

    private fun switchTo(c: MediaController?) {
        if (c != null && active != null && c.sessionToken == active?.sessionToken) return
        active = c
        if (c == null) {
            setPlaying(false)
            return
        }
        handleMeta(c, c.metadata)
        handleState(c, c.playbackState)
    }

    private fun handleMeta(c: MediaController, m: MediaMetadata?) {
        if (c.sessionToken != active?.sessionToken) return
        val next = toMeta(c, m) ?: return
        val prev = meta
        if (prev != null && next.sameTrack(prev)) {
            val merged = prev.copy(
                album = next.album.ifBlank { prev.album },
                durationMs = if (next.durationMs > 0) next.durationMs else prev.durationMs,
                artworkPath = next.artworkPath ?: prev.artworkPath,
                sourcePackage = next.sourcePackage,
                mediaId = next.mediaId.ifBlank { prev.mediaId },
            )
            if (merged != prev) {
                meta = merged
                listener.onDetails(merged)
            }
            return
        }
        meta = next
        watched[c.sessionToken]?.lastState = null
        loops.reset()
        listener.onTrack(next, position(c.playbackState), restart = false)
    }

    private fun handleState(c: MediaController, s: PlaybackState?) {
        if (c.sessionToken != active?.sessionToken) {
            if (isPlaying(s)) switchTo(c)
            return
        }
        val pos = position(s)
        val w = watched[c.sessionToken]
        if (w != null && pos != null) {
            val m = meta
            // Порівнюємо з тим, де пісня МАЛА Б бути зараз (за попереднім станом), — багато плеєрів
            // не шлють позицію щосекунди, тож «останнє повідомлення» могло бути 0 з самого старту.
            val predictedPrev = position(w.lastState)
            if (isPlaying(s) && m != null && LoopDetector.wrapped(predictedPrev, pos)) {
                // Та сама пісня почалась спочатку? Чекаємо 1,5 с: якщо назва не змінилась — це повтор.
                handler.postDelayed({
                    val now = positionMs()
                    if (meta?.sameTrack(m) == true && now != null && now < 6_000) {
                        listener.onTrack(meta ?: m, now, restart = true)
                    }
                }, 1_500)
            }
            w.lastState = s
            loops.reset()
        }
        setPlaying(isPlaying(s))
    }

    /** Плеєр мовчить, а пісня мала б уже закінчитись — отже, грає знову (повтор однієї пісні). */
    private fun checkLoop() {
        val m = meta ?: return
        if (!playing) return
        val est = positionMs() ?: return
        val newPos = loops.check(est, m.durationMs) ?: return
        listener.onTrack(m, newPos, restart = true)
    }

    private fun setPlaying(p: Boolean) {
        if (p == playing) return
        playing = p
        listener.onPlaying(p)
    }

    private fun toMeta(c: MediaController, m: MediaMetadata?): TrackMeta? {
        if (m == null) return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val album = m.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: ""
        val duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
        val bitmap = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val art = bitmap?.let { Artwork.save(app, artist, title, album, it) }
        return TrackMeta(artist.trim(), title.trim(), album.trim(), duration, c.packageName, art, mediaId.trim())
    }

    companion object {
        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        fun isPlaying(s: PlaybackState?): Boolean =
            s != null && (s.state == PlaybackState.STATE_PLAYING || s.state == PlaybackState.STATE_BUFFERING)

        fun position(s: PlaybackState?): Long? {
            if (s == null || s.position < 0) return null
            var p = s.position
            if (s.state == PlaybackState.STATE_PLAYING && s.lastPositionUpdateTime > 0) {
                p += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
            }
            return p.coerceAtLeast(0L)
        }
    }
}
