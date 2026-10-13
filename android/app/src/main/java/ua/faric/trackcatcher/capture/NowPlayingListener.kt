package ua.faric.trackcatcher.capture

import android.service.notification.NotificationListenerService

/**
 * «Доступ до сповіщень» для Track Catcher. Самі сповіщення не читаємо —
 * цей доступ лише дозволяє бачити, що грає плеєр (MediaSession): назву, виконавця, обкладинку.
 */
class NowPlayingListener : NotificationListenerService()
