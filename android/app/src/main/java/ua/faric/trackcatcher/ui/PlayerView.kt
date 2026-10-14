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
 * Картка плеєра в подробицях запису: «❚❚ Пауза / ▶ Грати», смуга (торкнись або потягни — перемотка),
 * «1:12 / 4:21», «Інший плеєр». Слухає [InAppPlayer]; зникла з екрана — плеєр зупиняється.
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
    private val time: TextView = ui.numbers(ui.text("0:00 / 0:00", 14f, Palette.muted))
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
        val top = ui.row(12).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        top.addView(toggle)
        top.addView(time, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ui.button("Інший плеєр", Ui.Kind.QUIET) { InAppPlayer.pause(); onExternal() })
        addView(top, ui.matchWrap())
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, ui.dp(Ui.MIN_TOUCH_DP)))
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
        time.text = if (mine && !InAppPlayer.prepared && err == null) "відкриваю…" else "${TimeText.clock(pos)} / ${TimeText.clock(dur)}"
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
