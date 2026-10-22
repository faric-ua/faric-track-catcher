package ua.faric.trackcatcher.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import ua.faric.trackcatcher.state.RecorderHub
import ua.faric.trackcatcher.ui.MainActivity

/**
 * Плитка «Запис» у шторці (T62, ідея Claude; faric 17.10: «все, що ти сказав, підтримую»).
 *
 * Запис не йде — дотик відкриває Track Catcher і одразу питає вікно Android «Почати запис?»
 * (без нього Android не дає записувати звук — це вимога системи, не наша). Запис іде — дотик зупиняє,
 * як «Зупинити» в застосунку: записане зберігається.
 */
class RecordTile : TileService() {
    private var unsubscribe: (() -> Unit)? = null

    override fun onStartListening() {
        shownKey = null
        unsubscribe?.invoke()
        unsubscribe = RecorderHub.observe { render(it) }
    }

    override fun onStopListening() {
        unsubscribe?.invoke()
        unsubscribe = null
    }

    override fun onClick() {
        when (RecorderHub.state.phase) {
            RecorderHub.Phase.RECORDING -> runCatching { startService(CaptureService.stopIntent(this)) }
            RecorderHub.Phase.FINISHING -> {} // ще дозберігає — нічого не чіпаємо
            RecorderHub.Phase.IDLE -> openAndStart()
        }
    }

    private fun openAndStart() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_TAB, "RECORD")
            .putExtra(MainActivity.EXTRA_START, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 30, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }

    /** Що плитка вже показує — оновлювати шторку лише коли щось змінилось (рівні звуку — 10 разів на секунду). */
    private var shownKey: String? = null

    private fun render(s: RecorderHub.State) {
        val tile = qsTile ?: return
        val key = "${s.phase}|${s.songs}"
        if (key == shownKey) return
        shownKey = key
        tile.label = "Запис"
        tile.state = when (s.phase) {
            RecorderHub.Phase.RECORDING -> Tile.STATE_ACTIVE
            RecorderHub.Phase.FINISHING -> Tile.STATE_UNAVAILABLE
            RecorderHub.Phase.IDLE -> Tile.STATE_INACTIVE
        }
        tile.subtitle = when (s.phase) {
            RecorderHub.Phase.RECORDING -> if (s.songs > 0) "іде · пісень: ${s.songs}" else "іде · торкни — стоп"
            RecorderHub.Phase.FINISHING -> "зберігає…"
            RecorderHub.Phase.IDLE -> "Track Catcher"
        }
        tile.updateTile()
    }
}
