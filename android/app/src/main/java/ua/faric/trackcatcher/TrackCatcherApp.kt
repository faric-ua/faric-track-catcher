package ua.faric.trackcatcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TrackCatcherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ua.faric.trackcatcher.core.AppInfo.versionName = BuildConfig.VERSION_NAME
        ua.faric.trackcatcher.core.AppInfo.versionCode = BuildConfig.VERSION_CODE
        // Запис обірвався разом із застосунком чи була помилка — пояснити на екрані «Запис» (faric 20.10 17:10).
        ua.faric.trackcatcher.state.LastExit.install(this)
        // Застосунок щойно стартував — жодна фонова робота зараз не йде: «Перевіряю номери: 1 з 2», що лишилось
        // у шторці від закритого Android процесу, прибрати (faric 24.10 00:24: «як завжди, висить»).
        ua.faric.trackcatcher.capture.FlacBatchService.clearStale(this)
        // Переписування файлу обірвали — повернути оригінал з копії (у фоні: файл може бути великий).
        val app = this
        Thread({
            ua.faric.trackcatcher.capture.WriteJournal.recover(app)?.let { msg ->
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

    companion object {
        const val CHANNEL_WORK = "work"
        const val CHANNEL_RECORDING = "recording"
        const val CHANNEL_PLAYBACK = "playback"
    }
}
