package ua.faric.trackcatcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TrackCatcherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ua.faric.trackcatcher.core.AppInfo.versionName = BuildConfig.VERSION_NAME
        ua.faric.trackcatcher.core.AppInfo.versionCode = BuildConfig.VERSION_CODE
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
