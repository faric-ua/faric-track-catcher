package ua.faric.trackcatcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Смужка поступу (T93, faric 20.10 17:43: «коли довгі процеси — щоб якийсь прогрес-бар був»): заокруглена доріжка
 * й зелене заповнення. [fraction] < 0 — «не знаю, скільки» (лише доріжка).
 */
class ProgressLine(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.surfaceAlt }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.accent }
    private val rect = RectF()

    var fraction: Float = 0f
        set(v) {
            val c = if (v < 0f) -1f else v.coerceIn(0f, 1f)
            if (c != field) {
                field = c
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(rect, r, r, track)
        if (fraction > 0f) {
            rect.set(0f, 0f, maxOf(h, width * fraction), h)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}
