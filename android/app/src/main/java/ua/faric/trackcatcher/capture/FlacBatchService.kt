package ua.faric.trackcatcher.capture

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import ua.faric.trackcatcher.R
import ua.faric.trackcatcher.TrackCatcherApp
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.data.HistoryDb
import ua.faric.trackcatcher.data.Settings
import ua.faric.trackcatcher.ui.MainActivity

/**
 * Фонова служба для [FlacBatch]: працює, навіть коли екран повернули чи застосунок згорнули; у шторці — поступ
 * і «Зупинити». Зупинка — після поточної пісні (FLAC не лишається недописаним).
 */
class FlacBatchService : Service() {
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            FlacBatch.stop()
            return START_NOT_STICKY
        }
        val ids = intent?.getLongArrayExtra(EXTRA_IDS)
        enter(notification("Готуюсь…", "", 0, ongoing = true))
        if (ids == null || worker != null) {
            if (worker == null) stopSelf()
            return START_NOT_STICKY
        }
        worker = Thread({ run(ids) }, "wav-to-flac").also { it.start() }
        return START_NOT_STICKY
    }

    private fun run(ids: LongArray) {
        val app = applicationContext
        val db = HistoryDb.get(app)
        val settings = Settings(app).snapshot()
        val problems = ArrayList<String>()
        var made = 0
        var cancelled = false
        for ((i, id) in ids.withIndex()) {
            if (FlacBatch.cancel) {
                cancelled = true
                break
            }
            val r = db.get(id) ?: continue
            if (!FlacBatch.isWav(r)) { made++; continue } // уже зроблено (повторний запуск)
            val label = r.title.ifBlank { r.displayName }
            var shown = -1
            fun say(p: Float) {
                val pct = (p * 100).toInt()
                if (pct == shown) return
                shown = pct
                FlacBatch.update { it.copy(index = i + 1, label = label, percent = pct) }
                notify("${i + 1} з ${ids.size}: $label", "$pct %", ((i + p) * 100 / ids.size).toInt())
            }
            say(0f)
            try {
                FlacBatch.makeOne(app, r, settings) { say(it) }
                made++
                FlacBatch.update { it.copy(made = made) }
            } catch (e: Exception) {
                problems += "$label — ${e.message ?: e.javaClass.simpleName}"
                FlacBatch.update { it.copy(problems = problems.toList()) }
            }
        }
        FlacBatch.update { it.copy(running = false, finished = true, made = made, problems = problems.toList(), cancelled = cancelled) }
        val title = if (cancelled) "Зупинено: зроблено $made з ${ids.size}" else "Готово: " + TimeText.plural(made, "FLAC", "FLAC", "FLAC") + " з обкладинками"
        val done = notification(title, if (problems.isEmpty()) "WAV лишились на місці" else "Не вийшло: ${problems.size} — подробиці в застосунку", -1, ongoing = false)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, done)
        worker = null
        stopSelf()
    }

    private fun enter(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTIF_ID, n)
    }

    private var lastShown = 0L

    private fun notify(title: String, text: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (now - lastShown < 700) return
        lastShown = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(title, text, percent, ongoing = true))
    }

    private fun notification(title: String, text: String, percent: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 31, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(MainActivity.EXTRA_TAB, "HISTORY"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, TrackCatcherApp.CHANNEL_WORK)
            .setSmallIcon(R.drawable.ic_stat_rec)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (ongoing) {
            if (percent >= 0) b.setProgress(100, percent, false)
            val stop = PendingIntent.getService(
                this, 32, Intent(this, FlacBatchService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_stop), "Зупинити", stop).build())
        }
        return b.build()
    }

    companion object {
        const val EXTRA_IDS = "ids"
        private const val ACTION_STOP = "ua.faric.trackcatcher.flac.STOP"
        private const val NOTIF_ID = 3
    }
}
