package ua.faric.trackcatcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import ua.faric.trackcatcher.core.TimeText

/**
 * Картка плеєра в подробицях запису: «❚❚ Пауза / ▶ Грати», «Інший плеєр», смуга (торкнись або
 * потягни — перемотка), під нею «1:12» ліворуч і «−3:09» праворуч. Слухає [InAppPlayer]; зникла з екрана — плеєр зупиняється.
 */
@SuppressLint("ViewConstructor")
class PlayerView(
    context: Context,
    private val ui: Ui,
    private val uri: String,
    private val onExternal: () -> Unit,
) : LinearLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val toggle: TextView
    // Під смугою: ліворуч — скільки зіграно, праворуч — скільки ще лишилось (faric 14.10 04:41:
    // «4:5 / 8 / 5:39» у три рядки поміж кнопок читалось погано).
    private val elapsed: TextView = ui.numbers(ui.text("0:00", 14f, Palette.text))
    private val remaining: TextView = ui.numbers(ui.text("−0:00", 14f, Palette.muted))
    private val bar = ProgressTrack(context, ui) { ms -> InAppPlayer.seekTo(ms) }
    private val message: TextView = ui.text("", 13.5f, Palette.danger)
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    init {
        orientation = VERTICAL
        background = ui.rounded(Palette.surfaceAlt, Palette.border, radiusDp = 14)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        toggle = ui.button("❚❚ Пауза", Ui.Kind.PRIMARY) { InAppPlayer.toggle(context) }
        val top = FlowLayout(context, ui.dp(8), ui.dp(8))
        top.addView(toggle)
        top.addView(ui.button("Інший плеєр", Ui.Kind.QUIET) { InAppPlayer.pause(); onExternal() })
        addView(top, ui.matchWrap())
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(Ui.MIN_TOUCH_DP)))
        val times = ui.row(8).apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) }
        times.addView(elapsed, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        remaining.textAlignment = TEXT_ALIGNMENT_VIEW_END
        times.addView(remaining, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(times, ui.matchWrap())
        addView(message, ui.matchWrap())
        message.visibility = GONE
        contentDescription = "Плеєр запису"
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        InAppPlayer.viewAttached()
        InAppPlayer.addListener(listener)
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        InAppPlayer.removeListener(listener)
        InAppPlayer.viewDetached()
        super.onDetachedFromWindow()
    }

    private fun render() {
        val mine = InAppPlayer.uri == uri
        val err = if (mine) InAppPlayer.error else null
        message.visibility = if (err != null) VISIBLE else GONE
        message.text = err ?: ""
        val playing = mine && InAppPlayer.playing
        toggle.text = if (playing) "❚❚ Пауза" else "▶︎ Грати"
        val pos = if (mine) InAppPlayer.positionMs else 0L
        val dur = if (mine) InAppPlayer.durationMs else 0L
        val opening = mine && !InAppPlayer.prepared && err == null
        elapsed.text = if (opening) "відкриваю…" else TimeText.clock(pos)
        remaining.text = if (opening || dur <= 0) "" else "−" + TimeText.clock((dur - pos).coerceAtLeast(0L))
        elapsed.contentDescription = "Зіграно ${TimeText.clock(pos)} з ${TimeText.clock(dur)}"
        bar.set(pos, dur)
    }
}

/** Смуга відтворення: бурштин — зіграно, кружечок — де зараз. Торкнись або потягни — перемотка. */
@SuppressLint("ViewConstructor")
class ProgressTrack(context: Context, private val ui: Ui, private val onSeek: (Long) -> Unit) : View(context) {
    private var pos = 0L
    private var dur = 0L
    private var dragging = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        contentDescription = "Перемотка"
    }

    fun set(positionMs: Long, durationMs: Long) {
        if (dragging) return
        pos = positionMs
        dur = durationMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val pad = ui.dp(8).toFloat()
        val h = ui.dp(6).toFloat()
        val cy = height / 2f
        val w = width - pad * 2
        paint.color = Palette.meterOff
        rect.set(pad, cy - h / 2, pad + w, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        paint.color = Palette.accent
        rect.set(pad, cy - h / 2, pad + w * frac, cy + h / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        canvas.drawCircle(pad + w * frac, cy, ui.dp(8).toFloat(), paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dur <= 0) return false
        val pad = ui.dp(8).toFloat()
        val frac = ((e.x - pad) / (width - pad * 2)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                pos = (dur * frac).toLong(); invalidate()
            }
            MotionEvent.ACTION_MOVE -> { pos = (dur * frac).toLong(); invalidate() }
            MotionEvent.ACTION_UP -> {
                dragging = false
                onSeek((dur * frac).toLong())
            }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
