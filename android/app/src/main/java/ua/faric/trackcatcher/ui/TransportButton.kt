package ua.faric.trackcatcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/**
 * Кругла кнопка плеєра зі значком, намальованим лініями (не шрифтом і не смайлом — Samsung малює «▶»
 * кольоровим смайлом, T33). Форма значків — як у PulseDeck з faric-music-visualizer
 * (`PulseDeckIconButton`: ⏮ ▶ ❚❚ ⏭), кольори — Track Catcher: головна («Грати») — бурштин, решта — спокійні.
 * faric 15.10 15:36: «подивися Music Visualizer — там плеєр: плей, пауза, попередня, наступна».
 */
@SuppressLint("ViewConstructor")
class TransportButton(
    context: Context,
    private val ui: Ui,
    icon: Icon,
    private val primary: Boolean,
    onClick: () -> Unit,
) : View(context) {
    enum class Icon { PREVIOUS, PLAY, PAUSE, NEXT }

    var icon: Icon = icon
        set(value) {
            if (field == value) return
            field = value
            contentDescription = describe(value)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        contentDescription = describe(icon)
        setOnClickListener { onClick() }
        // Відгук на дотик — той самий «бриж», що й на інших кнопках, у межах кола.
        foreground = ui.pressable(ui.rounded(Palette.transparent, radiusDp = 200), 200)
    }

    private fun describe(i: Icon) = when (i) {
        Icon.PREVIOUS -> "Попередня пісня"
        Icon.PLAY -> "Грати"
        Icon.PAUSE -> "Пауза"
        Icon.NEXT -> "Наступна пісня"
    }

    override fun onDraw(canvas: Canvas) {
        val side = min(width, height).toFloat()
        if (side <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        val r = side / 2f - ui.dp(1)

        paint.style = Paint.Style.FILL
        paint.color = if (primary) Palette.accentFill else Palette.surfaceAlt
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ui.dp(if (primary) 2 else 1).toFloat()
        paint.color = if (primary) Palette.accent else Palette.border
        canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (primary) Palette.accent else Palette.text
        val s = side * (if (primary) 0.86f else 1f)
        when (icon) {
            Icon.PLAY -> {
                // Трикутник трохи праворуч від центру — на око він тоді по центру.
                path.reset()
                path.moveTo(cx - s * 0.11f, cy - s * 0.20f)
                path.lineTo(cx + s * 0.19f, cy)
                path.lineTo(cx - s * 0.11f, cy + s * 0.20f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Icon.PAUSE -> {
                val w = s * 0.08f
                val gap = s * 0.05f
                rect.set(cx - gap - w, cy - s * 0.19f, cx - gap, cy + s * 0.19f)
                canvas.drawRoundRect(rect, w * 0.35f, w * 0.35f, paint)
                rect.set(cx + gap, cy - s * 0.19f, cx + gap + w, cy + s * 0.19f)
                canvas.drawRoundRect(rect, w * 0.35f, w * 0.35f, paint)
            }
            Icon.PREVIOUS -> skip(canvas, cx, cy, s, forward = false)
            Icon.NEXT -> skip(canvas, cx, cy, s, forward = true)
        }
    }

    /** ⏮ / ⏭: риска й трикутник (як у PulseDeck). */
    private fun skip(canvas: Canvas, cx: Float, cy: Float, s: Float, forward: Boolean) {
        val dir = if (forward) 1f else -1f
        val bar = s * 0.06f
        val barX = cx + dir * s * 0.17f
        rect.set(minOf(barX, barX - dir * bar), cy - s * 0.16f, maxOf(barX, barX - dir * bar), cy + s * 0.16f)
        canvas.drawRoundRect(rect, bar / 2f, bar / 2f, paint)
        path.reset()
        path.moveTo(cx - dir * s * 0.15f, cy - s * 0.18f)
        path.lineTo(cx + dir * s * 0.09f, cy)
        path.lineTo(cx - dir * s * 0.15f, cy + s * 0.18f)
        path.close()
        canvas.drawPath(path, paint)
    }
}
