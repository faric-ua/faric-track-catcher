package ua.faric.trackcatcher.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import ua.faric.trackcatcher.capture.NowPlayingListener

/** Картка «Що дозволити»: кожен пункт — пояснення простими словами і одна кнопка. */
object PermissionsCard {
    private data class Item(val title: String, val why: String, val done: Boolean, val action: String, val run: () -> Unit, val hint: String? = null)

    fun build(host: MainActivity, alwaysShow: Boolean): LinearLayout? {
        val ui = host.ui
        val items = listOf(
            Item(
                "Запис звуку",
                "Щоб чути музику з телефона. Android назве це «Мікрофон», але пишеться лише звук самого телефона.",
                Perms.recordAudio(host), "Дозволити",
                { host.requestRuntimePermissions() },
            ),
            Item(
                "Назви пісень",
                "«Доступ до сповіщень» — щоб брати з шторки назву, виконавця й обкладинку.",
                Perms.nowPlaying(host), "Відкрити налаштування",
                { openListenerSettings(host) },
                hint = "Якщо перемикач сірий: Налаштування → Застосунки → Track Catcher → ⋮ → «Дозволити обмежені налаштування», потім знову сюди.",
            ),
            Item(
                "Повідомлення",
                "Щоб під час запису в шторці було видно, що пишеться, і була кнопка «Стоп».",
                Perms.notifications(host), "Дозволити",
                { host.requestRuntimePermissions() },
            ),
            Item(
                "Робота у фоні",
                "Щоб Samsung не приспав запис, коли екран вимкнено.",
                Perms.battery(host), "Дозволити",
                { openBattery(host) },
            ),
        )
        if (!alwaysShow && items.all { it.done }) return null
        val card = ui.card(12)
        card.addView(ui.label(if (items.all { it.done }) "Дозволи — усе готово" else "Що дозволити"))
        for (item in items) card.addView(itemView(host, item))
        return card
    }

    private fun itemView(host: MainActivity, item: Item): LinearLayout {
        val ui = host.ui
        val box = ui.column(6).apply {
            setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12))
            background = if (item.done) ui.rounded(Palette.selectedFill, Palette.success, radiusDp = 12, strokeDp = 2)
            else ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12)
        }
        val head = ui.row(10)
        head.addView(ui.text(item.title, 16f, medium = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.text(if (item.done) "Готово" else "Потрібно", 12f, if (item.done) Palette.success else Palette.warning, medium = true))
        box.addView(head)
        box.addView(ui.muted(item.why, 13.5f))
        if (!item.done) {
            box.addView(ui.button(item.action, Ui.Kind.PRIMARY) { item.run() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.START })
            item.hint?.let { hint ->
                box.addView(ui.text(hint, 12.5f, Palette.faint))
                box.addView(ui.button("Відкрити «Про застосунок»", Ui.Kind.QUIET) { openAppInfo(host) }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ))
            }
        }
        return box
    }

    private fun openListenerSettings(host: MainActivity) {
        val detail = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(host, NowPlayingListener::class.java).flattenToString(),
                )
        } else {
            null
        }
        if (detail != null && tryStart(host, detail)) return
        if (!tryStart(host, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) host.toast("Не вдалося відкрити налаштування")
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun openBattery(host: MainActivity) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + host.packageName))
        if (tryStart(host, direct)) return
        if (!tryStart(host, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) host.toast("Не вдалося відкрити налаштування батареї")
    }

    fun openAppInfo(host: MainActivity) {
        tryStart(host, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + host.packageName)))
    }

    private fun tryStart(host: MainActivity, intent: Intent): Boolean = try {
        host.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
