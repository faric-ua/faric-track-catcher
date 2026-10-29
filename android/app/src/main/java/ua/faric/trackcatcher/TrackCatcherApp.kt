package ua.faric.trackcatcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TrackCatcherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ua.faric.trackcatcher.core.AppInfo.versionName = BuildConfig.VERSION_NAME
        ua.faric.trackcatcher.core.AppInfo.versionCode = BuildConfig.VERSION_CODE
        // T108: журнал роботи — першим, щоб у нього потрапило все, що далі (і причина минулого закриття).
        ua.faric.trackcatcher.state.WorkLog.install(filesDir)
        ua.faric.trackcatcher.state.WorkLog.onAlarm = { ua.faric.trackcatcher.data.AutoBackup.request(this) }
        ua.faric.trackcatcher.state.WorkLog.i("старт", startLine())
        // Запис обірвався разом із застосунком чи була помилка — пояснити на екрані «Запис» (faric 20.10 17:10).
        ua.faric.trackcatcher.state.LastExit.install(this)
        // Застосунок щойно стартував — жодна фонова робота зараз не йде: «Перевіряю номери: 1 з 2», що лишилось
        // у шторці від закритого Android процесу, прибрати (faric 24.10 00:24: «як завжди, висить»).
        ua.faric.trackcatcher.capture.FlacBatchService.clearStale(this)
        // Переписування файлу обірвали — повернути оригінал з копії (у фоні: файл може бути великий).
        val app = this
        Thread({
            ua.faric.trackcatcher.capture.WriteJournal.recover(app)?.let { msg ->
                ua.faric.trackcatcher.state.WorkLog.w("старт", msg)
                ua.faric.trackcatcher.state.RecorderHub.update { it.copy(error = msg) }
            }
        }, "write-journal").start()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_RECORDING, "Запис", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Повідомлення під час запису: що пишеться і кнопка «Стоп»"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PLAYBACK, "Прослуховування", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Плеєр у шторці: що грає з історії, ⏮ ▶ ⏭"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORK, "Робота з файлами", NotificationManager.IMPORTANCE_LOW).apply {
                description = "WAV → FLAC з обкладинками: скільки зроблено, «Зупинити»"
                setShowBadge(false)
            },
        )
    }

    /** Перший рядок журналу після старту: версія, телефон, пам'ять (T108). */
    private fun startLine(): String {
        val am = getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { runCatching { am.getMemoryInfo(it) } }
        val mb = 1024L * 1024L
        return "${ua.faric.trackcatcher.core.AppInfo.encoder} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
            "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) · " +
            "пам'ять ${mem.availMem / mb} з ${mem.totalMem / mb} МБ вільно" + if (mem.lowMemory) " · МАЛО" else ""
    }

    companion object {
        const val CHANNEL_WORK = "work"
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_PLAYBACK = "playback"
    }
}
