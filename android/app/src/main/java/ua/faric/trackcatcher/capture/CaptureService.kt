package ua.faric.trackcatcher.capture

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import ua.faric.trackcatcher.R
import ua.faric.trackcatcher.TrackCatcherApp
import ua.faric.trackcatcher.core.Pcm
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.core.TrackSplitter
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.state.RecorderHub
import ua.faric.trackcatcher.ui.MainActivity

/**
 * Фонова частина: ловить звук телефона (AudioPlaybackCapture), слухає шторку,
 * ріже на пісні й віддає в чергу збереження. Працює і з вимкненим екраном.
 */
class CaptureService : Service(), NowPlayingTracker.Listener {
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var tracker: NowPlayingTracker? = null
    private var queue: FinisherQueue? = null
    private var settings: Settings.Values? = null
    private val events = ConcurrentLinkedQueue<TrackEvent>()

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var playerPaused = false
    @Volatile private var userStop = false

    private var sessionId = 0L
    private var lastNotified = ""
    private var recoveryQueue: FinisherQueue? = null
    private var unobserve: (() -> Unit)? = null

    private data class TrackEvent(val meta: TrackMeta, val positionMs: Long?, val force: Boolean, val details: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        unobserve = RecorderHub.observe { if (running || stopping) updateNotification() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> {
                userStop = true
                stopRecording()
            }
            ACTION_RECOVER -> recoverOrphans()
        }
        return START_NOT_STICKY
    }

    // ---------- старт ----------

    @SuppressLint("MissingPermission")
    private fun startRecording(intent: Intent) {
        if (running || stopping) return
        userStop = false
        enterForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, notification("Готуюсь до запису…", ""))
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            if (data == null) null else mpm.getMediaProjection(code, data)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            failStart("Android не дав дозволу на запис звуку. Натисни «Почати запис» ще раз.")
            return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                main.post {
                    if (running && !userStop) {
                        // Android 15+ сам зупиняє запис, коли екран блокується.
                        RecorderHub.update {
                            it.copy(error = "Запис зупинив Android — найчастіше через блокування екрана. " +
                                "Записане збережено. Наступного разу під час запису вмикай «Нічний екран».")
                        }
                    }
                    stopRecording()
                }
            }
        }, main)
        projection = proj

        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Pcm.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf * 4, Pcm.SAMPLE_RATE * Pcm.BYTES_PER_FRAME))
                .setAudioPlaybackCaptureConfig(config)
                .build()
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            failStart("Не вдалося увімкнути запис звуку. Перевір дозвіл «Мікрофон» для Track Catcher.")
            return
        }
        record = rec

        val values = Settings(this).snapshot()
        settings = values
        val db = HistoryDb.get(this)
        sessionId = db.startSession(System.currentTimeMillis(), values.label)
        queue = FinisherQueue(this) { main.post { onQueueIdle() } }
        recoverOrphans()

        val segmentsDir = segmentsDir(this)
        val splitter = TrackSplitter(FileSegmentSink(segmentsDir, sessionId) { job -> queue?.submit(job, values) })

        val t = NowPlayingTracker(this, this)
        tracker = t
        t.start()
        // start() уже повідомив поточну пісню через onTrack; якщо плеєра не видно — «невідома».
        if (t.meta == null) events.add(TrackEvent(TrackMeta(sourcePackage = t.sourcePackage ?: ""), null, force = false, details = false))
        playerPaused = t.hasPlayer && !t.playing

        try {
            rec.startRecording()
        } catch (e: Exception) {
            failStart("Запис не стартував: ${e.message}")
            return
        }
        running = true
        RecorderHub.update {
            RecorderHub.State(
                phase = RecorderHub.Phase.RECORDING,
                sessionId = sessionId,
                startedAt = System.currentTimeMillis(),
                meta = t.meta,
                playing = t.playing,
            )
        }
        updateNotification()
        captureThread = Thread({ captureLoop(rec, splitter) }, "track-catcher-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun failStart(message: String) {
        RecorderHub.update { it.copy(phase = RecorderHub.Phase.IDLE, error = message) }
        cleanupCapture()
        stopForegroundCompat()
        stopSelf()
    }

    // ---------- запис ----------

    private fun captureLoop(rec: AudioRecord, splitter: TrackSplitter) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ShortArray(Pcm.SAMPLE_RATE / 10 * Pcm.CHANNELS) // 100 мс
        var silentMs = 0L
        var lastUi = 0L
        var error: String? = null
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    error = "Запис звуку обірвався (код $n)"
                    break
                }
                drainEvents(splitter)
                if (n == 0) continue
                val db = Pcm.rmsDb(buf, 0, n)
                val paused = playerPaused
                // На паузі тиша у файл не пишеться. Якщо плеєр «на паузі», а звук є — пишемо (плеєр міг збрехати).
                if (!(paused && db < SILENT_DB)) splitter.onAudio(buf, n)
                val frames = n / Pcm.CHANNELS
                silentMs = if (!paused && db < DEAD_DB) silentMs + Pcm.framesToMs(frames.toLong()) else 0L
                val now = System.currentTimeMillis()
                if (now - lastUi >= 100) {
                    lastUi = now
                    val l = Pcm.channelPeak(buf, n, 0)
                    val r = Pcm.channelPeak(buf, n, 1)
                    val trackerPlaying = !paused && tracker?.hasPlayer == true
                    val warn = if (trackerPlaying && silentMs > 5_000) {
                        "Плеєр грає, а звуку немає ${silentMs / 1000} с. Можливо, цей застосунок не дає себе записувати."
                    } else {
                        null
                    }
                    RecorderHub.update {
                        it.copy(levelL = l, levelR = r, tracks = splitter.segmentsOpened, warning = warn)
                    }
                }
            }
            drainEvents(splitter)
        } catch (e: Exception) {
            error = if (e.message?.contains("ENOSPC") == true) "Немає місця на телефоні — запис зупинено." else "Помилка запису: ${e.message}"
        } finally {
            try {
                splitter.stop()
            } catch (e: Exception) {
                if (error == null) error = "Помилка під час зупинки: ${e.message}"
            }
        }
        if (error != null) {
            val msg = error
            RecorderHub.update { it.copy(error = msg) }
            main.post { stopRecording() }
        }
    }

    private fun drainEvents(splitter: TrackSplitter) {
        while (true) {
            val e = events.poll() ?: break
            if (e.details) splitter.onDetails(e.meta) else splitter.onTrackChange(e.meta, e.positionMs, e.force)
        }
    }

    // ---------- шторка ----------

    override fun onTrack(meta: TrackMeta, positionMs: Long?, restart: Boolean) {
        events.add(TrackEvent(meta, positionMs, force = restart, details = false))
        RecorderHub.update { it.copy(meta = meta) }
        updateNotification()
    }

    override fun onDetails(meta: TrackMeta) {
        events.add(TrackEvent(meta, null, force = false, details = true))
        RecorderHub.update { it.copy(meta = meta) }
    }

    override fun onPlaying(playing: Boolean) {
        playerPaused = !playing
        RecorderHub.update { it.copy(playing = playing) }
    }

    // ---------- стоп ----------

    private fun stopRecording() {
        if (!running) {
            if (!stopping && queue?.busy != true) {
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        running = false
        stopping = true
        runCatching { record?.stop() }
        runCatching { captureThread?.join(3_000) }
        cleanupCapture()
        HistoryDb.get(this).endSession(sessionId, System.currentTimeMillis())
        RecorderHub.update { it.copy(phase = RecorderHub.Phase.FINISHING, levelL = 0f, levelR = 0f, warning = null) }
        // Після зупинки звук уже не пишеться — лишається лише збереження.
        enterForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, notification("Зберігаю останні пісні…", ""))
        if (queue?.busy != true) onQueueIdle()
    }

    private fun onQueueIdle() {
        if (running) {
            updateNotification()
            return
        }
        if (!stopping) return
        stopping = false
        queue?.shutdown()
        queue = null
        RecorderHub.update { it.copy(phase = RecorderHub.Phase.IDLE, queued = 0) }
        stopForegroundCompat()
        stopSelf()
    }

    private fun cleanupCapture() {
        tracker?.stop()
        tracker = null
        runCatching { record?.release() }
        record = null
        captureThread = null
        projection?.let { p -> runCatching { p.stop() } }
        projection = null
        events.clear()
    }

    override fun onDestroy() {
        if (running) {
            running = false
            runCatching { record?.stop() }
            runCatching { captureThread?.join(2_000) }
        }
        cleanupCapture()
        unobserve?.invoke()
        unobserve = null
        super.onDestroy()
    }

    // ---------- відновлення ----------

    /** Шматки, що лишились після закриття застосунку, — зберегти як «неповні». */
    private fun recoverOrphans() {
        val dir = segmentsDir(this)
        val orphans = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.filter { !it.name.startsWith("s$sessionId-") || sessionId == 0L }
            ?.mapNotNull { SegmentJob.fromSidecar(it) }
            .orEmpty()
        if (orphans.isEmpty()) {
            if (!running && !stopping && recoveryQueue == null) stopSelf()
            return
        }
        val values = settings ?: Settings(this).snapshot()
        val active = queue
        if (active != null) {
            orphans.forEach { active.submit(it, values) }
            return
        }
        if (recoveryQueue != null) return // відновлення вже йде
        val q = FinisherQueue(this) {
            main.post {
                recoveryQueue?.shutdown()
                recoveryQueue = null
                if (!running && !stopping) stopSelf()
            }
        }
        recoveryQueue = q
        orphans.forEach { q.submit(it, values) }
    }

    // ---------- повідомлення ----------

    private fun updateNotification() {
        val s = RecorderHub.state
        val title = when {
            running -> "⏺ Записую: " + (s.meta?.displayName() ?: TrackMeta.UNKNOWN_NAME)
            else -> "Зберігаю останні пісні…"
        }
        val text = buildString {
            append(TimeText.plural(s.tracks.coerceAtLeast(1), "пісня", "пісні", "пісень"))
            append(" · збережено ").append(s.saved)
            if (s.queued > 0) append(" · у черзі ").append(s.queued)
        }
        val key = "$title|$text"
        if (key == lastNotified) return
        lastNotified = key
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification(title, text))
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, TrackCatcherApp.CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
        if (running) {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_stop), "Стоп", stop).build())
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun enterForeground(type: Int, n: Notification) {
        try {
            startForeground(NOTIF_ID, n, type)
        } catch (e: Exception) {
            // Наприклад, тип dataSync недоступний — збереження все одно піде у фоні.
            runCatching { getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, n) }
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "ua.faric.trackcatcher.action.START"
        const val ACTION_STOP = "ua.faric.trackcatcher.action.STOP"
        const val ACTION_RECOVER = "ua.faric.trackcatcher.action.RECOVER"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 42
        private const val SILENT_DB = -60.0
        private const val DEAD_DB = -70.0

        fun segmentsDir(context: Context) = File(context.filesDir, "segments")

        fun hasOrphans(context: Context): Boolean =
            segmentsDir(context).listFiles { f -> f.name.endsWith(".json") }?.isNotEmpty() == true

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun stopIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)

        fun recoverIntent(context: Context): Intent = Intent(context, CaptureService::class.java).setAction(ACTION_RECOVER)
    }
}
