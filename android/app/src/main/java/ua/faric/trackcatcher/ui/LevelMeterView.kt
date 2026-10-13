package ua.faric.trackcatcher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Індикатор рівня як на касетній деці: два ряди (L і R) по 24 сегменти, -48…0 dBFS.
 * Бурштин — нормальний рівень, червоні три верхні — близько до перевантаження.
 */
class LevelMeterView(context: Context, private val ui: Ui) : View(context) {
    private var left = 0f
    private var right = 0f
    private var holdL = 0f
    private var holdR = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.muted
        textSize = ui.dp(10).toFloat()
        typeface = Ui.MEDIUM
    }
    private val rect = RectF()

    init {
        contentDescription = "Рівень звуку"
    }

    fun setLevels(peakL: Float, peakR: Float) {
        left = toSegments(peakL)
        right = toSegments(peakR)
        holdL = if (left >= holdL) left else maxOf(left, holdL - 0.6f)
        holdR = if (right >= holdR) right else maxOf(right, holdR - 0.6f)
        invalidate()
    }

    private fun toSegments(peak: Float): Float {
        if (peak <= 0.00001f) return 0f
        val db = 20f * Math.log10(peak.toDouble()).toFloat()
        return ((db + 48f) / 48f * SEGMENTS).coerceIn(0f, SEGMENTS.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = ui.dp(34)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val labelW = ui.dp(14).toFloat()
        val rowH = (height - ui.dp(6)) / 2f
        drawRow(canvas, "L", left, holdL, 0f, rowH, labelW)
        drawRow(canvas, "R", right, holdR, rowH + ui.dp(6), rowH, labelW)
    }

    private fun drawRow(c: Canvas, name: String, level: Float, hold: Float, top: Float, h: Float, labelW: Float) {
        c.drawText(name, 0f, top + h * 0.78f, label)
        val gap = ui.dp(2).toFloat()
        val w = (width - labelW - gap * (SEGMENTS - 1)) / SEGMENTS
        val holdIndex = hold.toInt() - 1
        for (i in 0 until SEGMENTS) {
            val lit = i < level || i == holdIndex
            paint.color = when {
                !lit -> Palette.meterOff
                i >= SEGMENTS - 3 -> Palette.rec
                else -> Palette.accent
            }
            val x = labelW + i * (w + gap)
            rect.set(x, top, x + w, top + h)
            c.drawRoundRect(rect, ui.dp(2).toFloat(), ui.dp(2).toFloat(), paint)
        }
    }

    companion object {
        const val SEGMENTS = 24
    }
}
