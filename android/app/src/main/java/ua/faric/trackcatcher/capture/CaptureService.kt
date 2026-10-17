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
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import ua.faric.trackcatcher.R
import ua.faric.trackcatcher.TrackCatcherApp
import ua.faric.trackcatcher.core.GapDetector
import ua.faric.trackcatcher.core.CutShift
import ua.faric.trackcatcher.core.Pcm
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.StopRule
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
    private var writerThread: Thread? = null
    private var tracker: NowPlayingTracker? = null
    private var queue: FinisherQueue? = null
    private var settings: Settings.Values? = null
    /** Плеєр, лише чий звук пишеться (T43); null — пишеться все «медіа». */
    private var capturedPackage: String? = null
    private val events = ConcurrentLinkedQueue<TrackEvent>()

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var playerPaused = false
    @Volatile private var userStop = false

    // «Зупинитись після N пісень» (T40) і свій альбом (T39) — лише головний потік.
    private var limit = 0
    private var songsStarted = 0
    private var limitReached = false
    /** T60: хвилини (0 — без обмеження) і скільки звуку покладено у файл (без пауз плеєра). */
    private var limitMin = 0
    @Volatile private var keptFrames = 0L
    private var timeUpShown = false
    private var customAlbum = ""
    @Volatile private var discardLast = false

    // Діагностика (пише потік запису, читає сторож у головному потоці).
    @Volatile private var framesRead = 0L
    @Volatile private var lastReadAt = 0L
    @Volatile private var lastLoudAt = 0L
    @Volatile private var lastDb = Pcm.SILENT_DB
    private var startedAtMs = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            checkAudio()
            main.postDelayed(this, 1_000)
        }
    }

    private var sessionId = 0L
    private var lastNotified = ""
    private var recoveryQueue: FinisherQueue? = null
    private var unobserve: (() -> Unit)? = null

    private data class TrackEvent(
        val meta: TrackMeta,
        val positionMs: Long?,
        val force: Boolean,
        val details: Boolean,
        /** Коли сигнал прийшов (System.nanoTime — та сама шкала, що й AudioTimestamp). */
        val atNanos: Long = System.nanoTime(),
    )

    /**
     * Труба між потоками: потік запису лише читає звук і кладе сюди; потік диска ріже й пише.
     * Раніше обидва робили в одному потоці — коли диск «задумувався» (саме зберігалась
     * попередня пісня на 48 МБ), Android викидав незабраний звук, і у файлі був «чирк» (14.10).
     * Події шторки йдуть тією ж трубою — тож розріз стає точно між тими самими шматками звуку.
     */
    private sealed class Item {
        class Audio(val buf: ShortArray, val n: Int) : Item()
        class Event(val e: TrackEvent, val aheadFrames: Long) : Item()
        /** Кінець запису; [discardLast] — початок пісні після ліміту «N пісень» не зберігати. */
        class End(val discardLast: Boolean) : Item()
    }
    private val pipe = LinkedBlockingQueue<Item>()
    private val pool = ArrayBlockingQueue<ShortArray>(POOL_SIZE)
    @Volatile private var tracksOpened = 0
    @Volatile private var gaps = 0
    @Volatile private var lostMs = 0L
    @Volatile private var dropped = 0
    /** Скільки звуку Android уже зібрав, а потік запису ще не прочитав (мс); -1 — Android не каже. */
    @Volatile private var readerLagMs = -1L
    private val stamp = android.media.AudioTimestamp()
    private var capturedFrames = 0L
    /** Найменше відставання за сесію — «нуль» шкали (якщо Android рахує кадри не з нуля, це його прибирає). */
    private var minRawLag = Long.MAX_VALUE

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
            ACTION_RECOVER -> recoverOrphans(stopWhenNothing = true)
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

        // T43: лише звук плеєра, чия пісня в шторці (без «буль-буль» повідомлень і чужих відео).
        // Плеєра не видно або Android його не показує — як раніше, усе «медіа».
        val onlyPlayer = Settings(this).onlyPlayer
        val playerPkg = if (onlyPlayer) currentPlayerPackage() else null
        val playerUid = playerPkg?.let { pkg -> runCatching { packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull() }
        capturedPackage = if (playerUid != null) playerPkg else null
        val config = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .apply { if (playerUid != null) addMatchingUid(playerUid) }
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
                // Запас 4 с: стільки Android тримає звук, поки потік запису не забере його.
                .setBufferSizeInBytes(maxOf(minBuf * 4, Pcm.SAMPLE_RATE * Pcm.BYTES_PER_FRAME * 4))
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
        limit = values.stopAfter
        limitMin = values.stopAfterMin
        keptFrames = 0L
        timeUpShown = false
        songsStarted = 0
        limitReached = false
        discardLast = false
        customAlbum = values.customAlbum
        val db = HistoryDb.get(this)
        sessionId = db.startSession(System.currentTimeMillis(), values.label)
        queue = FinisherQueue(this) { main.post { onQueueIdle() } }
        // Тут НЕ можна зупиняти службу, якщо шматків немає: v0.1.0–0.1.3 тут закривали службу — вона
        // закривалась одразу після старту, звуку не було, а екран показував «Записую» (M30s, 14.10).
        recoverOrphans(stopWhenNothing = false)

        val segmentsDir = segmentsDir(this)
        val splitter = TrackSplitter(FileSegmentSink(segmentsDir, sessionId) { job -> queue?.submit(job, values) }, shiftMs = CutShift.effective(values.cutShiftMs))

        val t = NowPlayingTracker(this, this, watchLoops = true)
        tracker = t
        t.start()
        // start() уже повідомив поточну пісню через onTrack; якщо плеєра не видно — «невідома».
        if (t.meta == null) events.add(TrackEvent(withAlbum(TrackMeta(sourcePackage = t.sourcePackage ?: "")), null, force = false, details = false))
        playerPaused = t.hasPlayer && !t.playing

        try {
            rec.startRecording()
        } catch (e: Exception) {
            failStart("Запис не стартував: ${e.message}")
            return
        }
        running = true
        startedAtMs = System.currentTimeMillis()
        framesRead = 0L
        tracksOpened = 0
        gaps = 0
        lostMs = 0L
        dropped = 0
        readerLagMs = -1L
        capturedFrames = 0L
        minRawLag = Long.MAX_VALUE
        pipe.clear()
        lastReadAt = 0L
        lastLoudAt = startedAtMs
        main.postDelayed(watchdog, 1_000)
        RecorderHub.update {
            RecorderHub.State(
                phase = RecorderHub.Phase.RECORDING,
                sessionId = sessionId,
                startedAt = System.currentTimeMillis(),
                meta = t.meta?.let { withAlbum(it) },
                playing = t.playing,
                limit = limit,
                songs = songsStarted,
                limitMin = limitMin,
                notice = null,
            )
        }
        updateNotification()
        writerThread = Thread({ writerLoop(splitter) }, "track-catcher-writer").apply { start() }
        captureThread = Thread({ captureLoop(rec) }, "track-catcher-capture").apply {
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

    /** Потік запису: лише читає звук у Android і кладе в трубу. Нічого повільного тут бути не може. */
    private fun captureLoop(rec: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = Pcm.SAMPLE_RATE / 10 * Pcm.CHANNELS // 100 мс
        val gapDetector = GapDetector()
        var lastUi = 0L
        var error: String? = null
        try {
            while (running) {
                val buf = pool.poll() ?: ShortArray(chunk)
                val n = rec.read(buf, 0, chunk, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    pool.offer(buf)
                    error = "Запис звуку обірвався (код $n)"
                    break
                }
                if (n > 0) capturedFrames += n / Pcm.CHANNELS
                forwardEvents(rec)
                if (n == 0) {
                    pool.offer(buf)
                    continue
                }
                if (gapDetector.onRead(SystemClock.elapsedRealtime(), n / Pcm.CHANNELS)) {
                    gaps = gapDetector.gaps
                    lostMs = gapDetector.lostMs
                }
                val db = Pcm.rmsDb(buf, 0, n)
                val now = System.currentTimeMillis()
                framesRead += n / Pcm.CHANNELS
                lastReadAt = now
                lastDb = db
                if (db > DEAD_DB) lastLoudAt = now
                // Рівні — до того, як віддати шматок у трубу (далі ним володіє потік диска).
                val showUi = now - lastUi >= 100
                val l = if (showUi) Pcm.channelPeak(buf, n, 0) else 0f
                val r = if (showUi) Pcm.channelPeak(buf, n, 1) else 0f
                val paused = playerPaused
                // На паузі тиша у файл не пишеться. Якщо плеєр «на паузі», а звук є — пишемо (плеєр міг збрехати).
                if (!(paused && db < SILENT_DB)) {
                    if (pipe.size < MAX_PIPE) {
                        keptFrames += n / Pcm.CHANNELS
                        pipe.put(Item.Audio(buf, n))
                    } else {
                        // Диск стоїть уже хвилини — звук нема куди класти.
                        dropped++
                        pool.offer(buf)
                    }
                } else {
                    pool.offer(buf)
                }
                if (showUi) {
                    lastUi = now
                    RecorderHub.update { it.copy(levelL = l, levelR = r, tracks = tracksOpened) }
                }
            }
            forwardEvents(rec)
        } catch (e: Exception) {
            error = "Помилка запису: ${e.message}"
        } finally {
            pipe.put(Item.End(discardLast))
        }
        if (error != null) {
            val msg = error
            RecorderHub.update { it.copy(error = msg) }
            main.post { stopRecording() }
        }
    }

    /** Потік диска: ріже на пісні й пише шматки у файли. Може «задуматись» — звук чекає в трубі. */
    private fun writerLoop(splitter: TrackSplitter) {
        var error: String? = null
        var discard = false
        try {
            loop@ while (true) {
                when (val item = pipe.take()) {
                    is Item.Audio -> {
                        splitter.onAudio(item.buf, item.n)
                        pool.offer(item.buf)
                        tracksOpened = splitter.segmentsOpened
                    }
                    is Item.Event -> {
                        val e = item.e
                        if (e.details) splitter.onDetails(e.meta) else splitter.onTrackChange(e.meta, e.positionMs, e.force, item.aheadFrames)
                        tracksOpened = splitter.segmentsOpened
                    }
                    is Item.End -> {
                        discard = item.discardLast
                        break@loop
                    }
                }
            }
        } catch (e: Exception) {
            error = if (e.message?.contains("ENOSPC") == true) "Немає місця на телефоні — запис зупинено." else "Помилка запису: ${e.message}"
        } finally {
            try {
                splitter.stop(discardCurrent = discard)
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

    /**
     * Сторож у головному потоці (працює, навіть якщо читання звуку «застрягло»):
     * чи надходить звук від Android і чи він не порожній, коли плеєр грає.
     */
    private fun checkAudio() {
        val now = System.currentTimeMillis()
        val sinceStart = (now - startedAtMs) / 1000
        val noDataSec = if (lastReadAt == 0L) sinceStart else (now - lastReadAt) / 1000
        val playing = !playerPaused && tracker?.hasPlayer == true
        val quietSec = (now - lastLoudAt) / 1000
        val app = appName(RecorderHub.state.meta?.sourcePackage)
        val nowPkg = tracker?.sourcePackage
        val only = capturedPackage
        val warn = when {
            noDataSec >= 3 -> "Android не віддає звук уже $noDataSec с (жодного шматка). Зупини запис і почни знову."
            only != null && nowPkg != null && nowPkg != only ->
                "Пишеться лише звук «${appName(only)}», а зараз грає «${appName(nowPkg)}» — його звук не потрапить у файл. " +
                    "Зупини запис і почни знову (або «Налаштування» → «Звук для запису» → «Усе, що грає»)."
            playing && quietSec >= 5 -> "$app грає, а звук порожній уже $quietSec с — схоже, $app не дає себе записувати. " +
                (if (only != null) "Спробуй «Налаштування» → «Звук для запису» → «Усе, що грає» або інший плеєр." else "Спробуй інший плеєр.")
            else -> null
        }
        val level = if (lastDb <= Pcm.SILENT_DB + 1) "тиша" else "%d dB".format(Math.round(lastDb))
        val pos = tracker?.positionMs()
        val dur = RecorderHub.state.meta?.durationMs ?: 0L
        // Позиція в плеєрі: якщо вона більша за тривалість — плеєр не повідомив, що почав пісню знову.
        val player = if (pos == null) "" else " · плеєр ${TimeText.clock(pos)}" + (if (dur > 0) " з ${TimeText.clock(dur)}" else "")
        val lost = if (gaps > 0 || dropped > 0) " · пропусків ${gaps + dropped} (~${lostMs + dropped * 100L} мс)" else " · пропусків 0"
        val lag = readerLagMs.let { if (it >= 0) " · затримка $it мс" else "" }
        val source = only?.let { " · лише ${appName(it)}" } ?: ""
        val diag = "Звук від Android: ${Pcm.framesToMs(framesRead) / 1000} с отримано$source · рівень $level$player$lost$lag"
        val recMs = Pcm.framesToMs(keptFrames)
        if (limitMin > 0 && !timeUpShown && StopRule.timeUp(recMs, limitMin)) {
            timeUpShown = true
            RecorderHub.update { it.copy(notice = "Час вийшов ($limitMin хв) — дограю цю пісню й зупинюсь на наступній.") }
        }
        if (warn != RecorderHub.state.warning || diag != RecorderHub.state.diag || (limitMin > 0 && recMs / 1000 != RecorderHub.state.recordedMs / 1000)) {
            RecorderHub.update { it.copy(warning = warn, diag = diag, recordedMs = recMs) }
        }
    }

    /** Який плеєр зараз у шторці (перед стартом запису): грає — він, інакше перший видимий. */
    private fun currentPlayerPackage(): String? {
        val probe = NowPlayingTracker(this, object : NowPlayingTracker.Listener {
            override fun onTrack(meta: TrackMeta, positionMs: Long?, restart: Boolean) {}
        })
        return try {
            if (probe.start() && probe.hasPlayer) probe.sourcePackage?.takeIf { it != packageName } else null
        } finally {
            probe.stop()
        }
    }

    private fun appName(pkg: String?): String {
        if (pkg.isNullOrBlank()) return "Плеєр"
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "Плеєр"
        }
    }

    /**
     * Події шторки — у ту саму трубу, що й звук. Плюс поправка: Android каже (AudioTimestamp),
     * який кадр він зібрав у яку мить, тож знаємо, на скільки сигнал зі шторки «попереду» вже
     * прочитаного звуку. Без неї розріз ставав раніше, ніж закінчувалась пісня.
     */
    private fun forwardEvents(rec: AudioRecord) {
        if (events.isEmpty()) {
            updateLag(rec)
            return
        }
        val ok = updateLag(rec)
        while (true) {
            val e = events.poll() ?: break
            var ahead = 0L
            if (ok) {
                val frameAtEvent = stamp.framePosition + (e.atNanos - stamp.nanoTime) * Pcm.SAMPLE_RATE / 1_000_000_000L
                ahead = (frameAtEvent - capturedFrames - minRawLag).coerceIn(0L, MAX_AHEAD_FRAMES)
            }
            pipe.put(Item.Event(e, ahead))
        }
    }

    /** Наскільки потік запису відстає від Android. false — Android не дав мітки часу. */
    private fun updateLag(rec: AudioRecord): Boolean {
        val ok = try {
            rec.getTimestamp(stamp, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            readerLagMs = -1L
            return false
        }
        val nowFrame = stamp.framePosition + (System.nanoTime() - stamp.nanoTime) * Pcm.SAMPLE_RATE / 1_000_000_000L
        val raw = nowFrame - capturedFrames
        if (raw < minRawLag) minRawLag = raw
        readerLagMs = ((raw - minRawLag) * 1000L / Pcm.SAMPLE_RATE).coerceAtLeast(0L)
        return true
    }

    // ---------- шторка ----------

    override fun onTrack(meta: TrackMeta, positionMs: Long?, restart: Boolean) {
        if (limitReached) return // ліміт досягнуто — далі нічого не ріжемо, лише зупиняємось
        val m = withAlbum(meta)
        events.add(TrackEvent(m, positionMs, force = restart, details = false))
        if (!meta.isUnknown) songsStarted++
        RecorderHub.update { it.copy(meta = m, songs = songsStarted) }
        updateNotification()
        if (running && limit > 0 && songsStarted > limit) reachLimit()
        // T60: «N хвилин» — зупинка на першій зміні пісні біля кінця часу; ця пісня вже не пишеться.
        else if (running && !meta.isUnknown && StopRule.stopAtTrackChange(Pcm.framesToMs(keptFrames), limitMin)) reachLimit()
    }

    override fun onDetails(meta: TrackMeta) {
        if (limitReached) return
        val m = withAlbum(meta)
        events.add(TrackEvent(m, null, force = false, details = true))
        RecorderHub.update { it.copy(meta = m) }
    }

    /** Свій альбом (T39): усі пісні запису йдуть в один альбом — і в папку, і в теги. */
    private fun withAlbum(meta: TrackMeta): TrackMeta = if (customAlbum.isBlank()) meta else meta.copy(album = customAlbum)

    /**
     * Почалась (N+1)-а пісня: N-у вже записано. Плеєр — на паузу, через 2,5 с (щоб різак знайшов
     * тишу чи обрив між піснями) — зупинка; початок (N+1)-ї у файл не йде.
     */
    private fun reachLimit() {
        limitReached = true
        tracker?.pausePlayer()
        val done = if (limit > 0) TimeText.plural(limit, "пісню", "пісні", "пісень")
        else "${TimeText.clock(Pcm.framesToMs(keptFrames))} (ліміт $limitMin хв)"
        RecorderHub.update { it.copy(notice = "Записано $done — плеєр на паузі, запис зупинено.") }
        main.postDelayed({
            if (running) {
                discardLast = true
                userStop = true
                stopRecording()
            }
        }, 2_500)
    }

    override fun onPlaying(playing: Boolean) {
        playerPaused = !playing
        RecorderHub.update { it.copy(playing = playing) }
    }

    // ---------- стоп ----------

    private fun stopRecording() {
        if (!running) {
            if (!stopping && queue?.busy != true) {
                // Запису вже немає (служба закривалась) — повернути екран у «Готово», а не висіти в «Записую».
                if (RecorderHub.state.phase != RecorderHub.Phase.IDLE) {
                    RecorderHub.update { it.copy(phase = RecorderHub.Phase.IDLE, levelL = 0f, levelR = 0f, warning = null, diag = null) }
                }
                stopForegroundCompat()
                stopSelf()
            }
            return
        }
        running = false
        stopping = true
        main.removeCallbacks(watchdog)
        runCatching { record?.stop() }
        runCatching { captureThread?.join(3_000) }
        // Потік диска дописує те, що ще в трубі (зазвичай миттєво).
        runCatching { writerThread?.join(15_000) }
        cleanupCapture()
        HistoryDb.get(this).endSession(sessionId, System.currentTimeMillis())
        RecorderHub.update { it.copy(phase = RecorderHub.Phase.FINISHING, levelL = 0f, levelR = 0f, warning = null, diag = null) }
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
        writerThread = null
        projection?.let { p -> runCatching { p.stop() } }
        projection = null
        events.clear()
    }

    override fun onDestroy() {
        main.removeCallbacks(watchdog)
        if (running) {
            // Служба закривається посеред запису (система чи помилка) — сказати про це, а не висіти в «Записую».
            RecorderHub.update {
                it.copy(
                    phase = RecorderHub.Phase.IDLE, levelL = 0f, levelR = 0f, warning = null, diag = null,
                    error = "Запис зупинився: Android закрив фонову службу. Записане до цього збережеться при наступному запуску.",
                )
            }
            running = false
            runCatching { record?.stop() }
            runCatching { captureThread?.join(2_000) }
            runCatching { writerThread?.join(3_000) }
        }
        cleanupCapture()
        unobserve?.invoke()
        unobserve = null
        super.onDestroy()
    }

    // ---------- відновлення ----------

    /** Шматки, що лишились після закриття застосунку, — зберегти як «неповні». */
    private fun recoverOrphans(stopWhenNothing: Boolean) {
        val dir = segmentsDir(this)
        val orphans = dir.listFiles { f -> f.name.endsWith(".json") }
            ?.filter { !it.name.startsWith("s$sessionId-") || sessionId == 0L }
            ?.mapNotNull { SegmentJob.fromSidecar(it) }
            .orEmpty()
        if (orphans.isEmpty()) {
            if (stopWhenNothing && !running && !stopping && recoveryQueue == null) stopSelf()
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
        private const val POOL_SIZE = 300 // 30 с звуку без нових масивів
        private const val MAX_PIPE = 3_000 // 5 хв звуку в черзі на диск — далі вже нема сенсу чекати
        private const val MAX_AHEAD_FRAMES = Pcm.SAMPLE_RATE * 5L // поправка не більше 5 с

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
