package ua.faric.trackcatcher.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import ua.faric.trackcatcher.R
import ua.faric.trackcatcher.TrackCatcherApp
import ua.faric.trackcatcher.ui.InAppPlayer
import ua.faric.trackcatcher.ui.MainActivity

/**
 * Прослуховування в шторці й на заблокованому екрані (faric 15.10 17:31: «щоб у шторці зверху теж
 * відображалось, що там грається — як робили в Visualizer»).
 *
 * У Visualizer це `PlaybackMediaSessionService` на бібліотеці Media3 (ExoPlayer). Тут — те саме
 * вбудованими засобами Android (MediaSession + MediaStyle), без нової бібліотеки: плеєр лишається
 * [InAppPlayer], служба лише показує картку з назвою, обкладинкою й ⏮ ▶ ⏭ і передає натискання.
 * Наш власний MediaSession Track Catcher не записує й не бере за «пісню, що грає» (NowPlayingTracker
 * пропускає свій пакет; звук — ALLOW_CAPTURE_BY_NONE).
 */
class PlaybackService : Service() {
    private var session: MediaSession? = null
    private var artFor: Long = -1
    private var art: Bitmap? = null
    private var foreground = false
    private val listener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        session = MediaSession(this, "Track Catcher — прослуховування").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = InAppPlayer.resume(this@PlaybackService)
                override fun onPause() = InAppPlayer.pause()
                override fun onSkipToNext() { InAppPlayer.skip(this@PlaybackService, next = true) }
                override fun onSkipToPrevious() { InAppPlayer.skip(this@PlaybackService, next = false) }
                override fun onSeekTo(pos: Long) = InAppPlayer.seekTo(pos)
                override fun onStop() = InAppPlayer.stop()
            })
            setSessionActivity(openApp())
            isActive = true
        }
        InAppPlayer.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService вимагає startForeground за кілька секунд — навіть поки файл ще відкривається.
        if (!foreground) {
            val t = InAppPlayer.current
            val s = session
            if (t != null && s != null) {
                try {
                    startForeground(NOTIF_ID, notification(t, true, s), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    foreground = true
                } catch (e: Exception) {
                    // не дали — нижче просто покажемо картку
                }
            }
        }
        when (intent?.action) {
            ACTION_TOGGLE -> InAppPlayer.toggle(this)
            ACTION_NEXT -> InAppPlayer.skip(this, next = true)
            ACTION_PREVIOUS -> InAppPlayer.skip(this, next = false)
            ACTION_STOP -> InAppPlayer.stop()
        }
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        InAppPlayer.removeListener(listener)
        session?.isActive = false
        session?.release()
        session = null
        running = false
        super.onDestroy()
    }

    private fun update() {
        val s = session ?: return
        val t = InAppPlayer.current
        if (t == null || InAppPlayer.uri == null) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        val playing = InAppPlayer.playing
        // Файл ще відкривається — вважаємо «грає», щоб картка не блимала.
        val active = playing || (!InAppPlayer.prepared && InAppPlayer.error == null)
        if (artFor != t.id) {
            artFor = t.id
            art = t.artworkPath?.let { decode(it) }
        }
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, InAppPlayer.durationMs)
        art?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        s.setMetadata(meta.build())
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        if (InAppPlayer.hasNeighbor(next = true)) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (InAppPlayer.hasNeighbor(next = false)) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, InAppPlayer.positionMs, if (playing) 1f else 0f)
                .build(),
        )

        val n = notification(t, active, s)
        val nm = getSystemService(NotificationManager::class.java)
        if (active) {
            // Грає — служба «на передньому плані», щоб Android не зупинив звук, коли застосунок сховано.
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                foreground = true
            } catch (e: Exception) {
                nm.notify(NOTIF_ID, n)
            }
        } else {
            // Пауза — картку можна змахнути (тоді плеєр зупиняється).
            if (foreground) stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
            nm.notify(NOTIF_ID, n)
        }
    }

    private fun notification(t: InAppPlayer.Track, playing: Boolean, s: MediaSession): Notification {
        val b = Notification.Builder(this, TrackCatcherApp.CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle(t.title.ifBlank { "Невідома пісня" })
            .setContentText(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "))
            .setContentIntent(openApp())
            .setDeleteIntent(action(ACTION_STOP, 13))
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
        art?.let { b.setLargeIcon(it) }
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_ctl_previous), "Попередня", action(ACTION_PREVIOUS, 10)).build())
        b.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, if (playing) R.drawable.ic_ctl_pause else R.drawable.ic_ctl_play),
                if (playing) "Пауза" else "Грати",
                action(ACTION_TOGGLE, 11),
            ).build(),
        )
        b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_ctl_next), "Наступна", action(ACTION_NEXT, 12)).build())
        b.setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
        return b.build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this, 14, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Обкладинка для шторки — зменшена (до ~512 px), щоб не тримати в пам'яті велику картинку. */
    private fun decode(path: String): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) {
        null
    }

    companion object {
        const val NOTIF_ID = 2
        private const val ACTION_TOGGLE = "ua.faric.trackcatcher.play.TOGGLE"
        private const val ACTION_NEXT = "ua.faric.trackcatcher.play.NEXT"
        private const val ACTION_PREVIOUS = "ua.faric.trackcatcher.play.PREVIOUS"
        private const val ACTION_STOP = "ua.faric.trackcatcher.play.STOP"

        @Volatile
        var running = false
            private set

        /** Викликається, коли користувач сам почав слухати (застосунок на екрані). */
        fun ensureStarted(context: Context) {
            if (running) return
            try {
                context.startForegroundService(Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                // Android не дав запустити службу (рідко) — грає однаково, лише без шторки.
            }
        }

        fun stop(context: Context) {
            if (!running) return
            context.stopService(Intent(context, PlaybackService::class.java))
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        }
    }
}
