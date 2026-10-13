package ua.faric.trackcatcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TrackCatcherApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_RECORDING, "Запис", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Повідомлення під час запису: що пишеться і кнопка «Стоп»"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RECORDING = "recording"
    }
}
