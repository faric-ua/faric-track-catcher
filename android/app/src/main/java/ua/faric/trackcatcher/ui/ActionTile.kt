package ua.faric.trackcatcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import kotlin.math.min

/**
 * Частина СПІЛЬНОЇ ПЛИТКИ ПЛЕЄРА (версія — [PlayerView.TILE_VERSION]; копіюється в Audio Converter).
 *
 * Маленька дія всередині плитки плеєра: круглий значок і підпис під ним — «Поділитись»,
 * «Плеєр», «Видалити» (faric 15.10 17:31: «маленькими ярличками, такими кнопочками
 * інтересними — у нашу плитку»). Уся колонка — зона дотику (≥ 48 dp), значок малюється лініями
 * в тому ж стилі, що й ⏮ ▶ ⏭ ([TransportButton]).
 *
 * T52 (скрін 15.10 21:21): підпис — **один рядок**, не влазить — «…» (було «Поділитис / ь», а
 * «Інший / плеєр» у 2 рядки зсував кружок «Видалити» нижче — не в одну лінію). Кружки однакові:
 * та сама заливка й рамка; «Видалити» виділене лише червоним значком і підписом (раніше червона
 * рамка й заливка робили кружок на вигляд більшим).
 */
@SuppressLint("ViewConstructor")
class ActionTile(
    context: Context,
    ui: Ui,
    icon: Icon,
    label: String,
    danger: Boolean,
    onClick: () -> Unit,
) : LinearLayout(context) {
    enum class Icon { SHARE, OPEN, CONVERT, DELETE }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true
        isFocusable = true
        minimumHeight = ui.dp(Ui.MIN_TOUCH_DP)
        setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(4))
        background = ui.pressable(ui.rounded(Palette.transparent, radiusDp = 14), 14)
        contentDescription = label
        setOnClickListener { onClick() }
        addView(Glyph(context, ui, icon, danger), LayoutParams(ui.dp(GLYPH_DP), ui.dp(GLYPH_DP)))
        addView(
            ui.text(label, 12.5f, if (danger) Palette.danger else Palette.muted, medium = true).apply {
                gravity = Gravity.CENTER
                textAlignment = TEXT_ALIGNMENT_CENTER
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(5) },
        )
    }

    @SuppressLint("ViewConstructor")
    private class Glyph(context: Context, private val ui: Ui, private val icon: Icon, private val danger: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val side = min(width, height).toFloat()
            if (side <= 0f) return
            val cx = width / 2f
            val cy = height / 2f
            val r = side / 2f - ui.dp(1)
            // Усі кружки однакові (T52) — відрізняється лише колір значка.
            paint.style = Paint.Style.FILL
            paint.color = Palette.surface
            canvas.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ui.dp(1).toFloat()
            paint.color = Palette.border
            canvas.drawCircle(cx, cy, r - paint.strokeWidth / 2f, paint)

            val ink = if (danger) Palette.danger else Palette.text
            paint.color = ink
            paint.strokeWidth = side * 0.055f
            when (icon) {
                Icon.SHARE -> {
                    // Три вузли, з'єднані лініями.
                    val a = floatArrayOf(cx + side * 0.15f, cy - side * 0.16f)
                    val b = floatArrayOf(cx - side * 0.15f, cy)
                    val c = floatArrayOf(cx + side * 0.15f, cy + side * 0.16f)
                    canvas.drawLine(a[0], a[1], b[0], b[1], paint)
                    canvas.drawLine(b[0], b[1], c[0], c[1], paint)
                    paint.style = Paint.Style.FILL
                    for (p in listOf(a, b, c)) canvas.drawCircle(p[0], p[1], side * 0.07f, paint)
                }
                Icon.OPEN -> {
                    // Віконце з відкритим кутом і стрілка назовні.
                    val l = cx - side * 0.19f
                    val t = cy - side * 0.13f
                    val rr = cx + side * 0.13f
                    val bb = cy + side * 0.19f
                    path.reset()
                    path.moveTo(cx - side * 0.02f, t)
                    path.lineTo(l, t)
                    path.lineTo(l, bb)
                    path.lineTo(rr, bb)
                    path.lineTo(rr, cy + side * 0.02f)
                    canvas.drawPath(path, paint)
                    val tipX = cx + side * 0.2f
                    val tipY = cy - side * 0.2f
                    canvas.drawLine(cx - side * 0.04f, cy + side * 0.04f, tipX, tipY, paint)
                    canvas.drawLine(tipX - side * 0.13f, tipY, tipX, tipY, paint)
                    canvas.drawLine(tipX, tipY, tipX, tipY + side * 0.13f, paint)
                }
                Icon.CONVERT -> {
                    // Дві стрілки по колу — той самий значок «Повтор / інший формат», що й в інструкції (LineIcon.REPEAT).
                    val half = (side * 0.24f).toInt()
                    LineIcon.REPEAT.drawable(ink, half * 2).apply {
                        setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
                        draw(canvas)
                    }
                }
                Icon.DELETE -> {
                    // Кошик: кришка з ручкою і корпус з двома рисками.
                    canvas.drawLine(cx - side * 0.2f, cy - side * 0.13f, cx + side * 0.2f, cy - side * 0.13f, paint)
                    rect.set(cx - side * 0.07f, cy - side * 0.21f, cx + side * 0.07f, cy - side * 0.13f)
                    canvas.drawRoundRect(rect, side * 0.03f, side * 0.03f, paint)
                    path.reset()
                    path.moveTo(cx - side * 0.15f, cy - side * 0.08f)
                    path.lineTo(cx - side * 0.12f, cy + side * 0.21f)
                    path.lineTo(cx + side * 0.12f, cy + side * 0.21f)
                    path.lineTo(cx + side * 0.15f, cy - side * 0.08f)
                    canvas.drawPath(path, paint)
                    canvas.drawLine(cx - side * 0.045f, cy - side * 0.02f, cx - side * 0.04f, cy + side * 0.14f, paint)
                    canvas.drawLine(cx + side * 0.045f, cy - side * 0.02f, cx + side * 0.04f, cy + side * 0.14f, paint)
                }
            }
        }
    }

    companion object {
        const val GLYPH_DP = 40
    }
}
