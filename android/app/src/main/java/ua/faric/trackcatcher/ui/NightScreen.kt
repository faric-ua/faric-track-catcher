package ua.faric.trackcatcher.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ua.faric.trackcatcher.core.TimeText
import ua.faric.trackcatcher.core.TrackMeta
import ua.faric.trackcatcher.state.RecorderHub

/**
 * «Нічний екран»: чорне тло, тьмяний бурштиновий текст, яскравість майже нуль,
 * екран не гасне сам. Android 15+ зупиняє запис при блокуванні — так блокування не настає.
 * Вихід — подвійний дотик або «Назад».
 */
class NightScreen(private val host: MainActivity, private val onExit: () -> Unit) {
    private val ui = host.ui
    private val handler = Handler(Looper.getMainLooper())
    private val clock = ui.numbers(ui.text("", 56f, Palette.nightText, medium = true)).apply { gravity = Gravity.CENTER }
    private val track = ui.text("", 18f, Palette.nightText).apply {
        gravity = Gravity.CENTER
        maxLines = 3
    }
    private val counts = ui.numbers(ui.text("", 14f, Palette.nightText)).apply { gravity = Gravity.CENTER }
    private val hint = ui.text("Двічі торкнись — вийти. Запис іде.", 13f, Palette.nightFaint).apply { gravity = Gravity.CENTER }
    private var lastTap = 0L
    private var opened = false

    val view: View = FrameLayout(host).apply {
        setBackgroundColor(Palette.night)
        val col = ui.column(14).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24))
            addView(clock, ui.matchWrap())
            addView(track, ui.matchWrap())
            addView(counts, ui.matchWrap())
            addView(hint, ui.matchWrap())
        }
        addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        contentDescription = "Нічний екран. Двічі торкнись, щоб вийти."
        setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                if (now - lastTap < 400) onExit() else v.performClick()
                lastTap = now
            }
            true
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    fun open() {
        val w = host.window
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w.attributes = w.attributes.apply { screenBrightness = 0.01f }
        WindowCompat.getInsetsController(w, w.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        opened = true
        RecorderHub.quietUi = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** Застосунок пішов у фон — таймер на паузу (вікно лишається). */
    fun pause() {
        handler.removeCallbacks(tick)
    }

    fun close() {
        handler.removeCallbacks(tick)
        RecorderHub.quietUi = false
        if (!opened) return
        opened = false
        val w = host.window
        w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun render() {
        val s = RecorderHub.state
        clock.text = "● " + TimeText.clock(System.currentTimeMillis() - s.startedAt)
        track.text = s.meta?.displayName() ?: TrackMeta.UNKNOWN_NAME
        counts.text = TimeText.plural(s.tracks.coerceAtLeast(1), "пісня", "пісні", "пісень") + " · збережено " + s.saved
    }

    companion object {
        const val KEY = "night"
    }
}
