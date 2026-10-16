package ua.faric.trackcatcher.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import ua.faric.trackcatcher.core.SvgPath

/**
 * Значки лініями — ті самі, що в інструкції (`docs/guide/guide.html`, faric 16.10 15:20: «там іконочки
 * такі красиві — підстав у програмі, де потрібно», T58). Рамка 24×24, лінія 1,8, круглі кінці; частини
 * з [Part.filled] — заливка. Малюються кодом: без картинок і бібліотек, будь-який розмір і колір.
 */
enum class LineIcon(vararg val parts: Part) {
    KEY(Part(SvgPath.circle(8f, 15f, 4f)), Part("M11 12l8-8M16 7l3 3M14 9l2 2")),
    REC(Part(SvgPath.circle(12f, 12f, 8f)), Part(SvgPath.circle(12f, 12f, 3.5f), filled = true)),
    MOON(Part("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")),
    LIST(
        Part("M8 6h12M8 12h12M8 18h12"),
        Part(SvgPath.circle(4f, 6f, 1f)), Part(SvgPath.circle(4f, 12f, 1f)), Part(SvgPath.circle(4f, 18f, 1f)),
    ),
    DISC(Part(SvgPath.circle(12f, 12f, 9f)), Part(SvgPath.circle(12f, 12f, 2.5f)), Part("M12 3v3M18.4 5.6l-2.1 2.1")),
    SHIELD(Part("M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z"), Part("M12 8v5M12 16.5v.5")),
    CUT(Part(SvgPath.circle(6f, 7f, 3f)), Part(SvgPath.circle(6f, 17f, 3f)), Part("M8.5 8.5L20 19M8.5 15.5L20 5")),
    PLAY(Part(SvgPath.circle(12f, 12f, 9f)), Part("M10 8.5v7l6-3.5z", filled = true)),
    FOLDER(Part("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")),
    BARS(Part("M5 20V10M10 20V4M15 20v-7M20 20v-4")),
    EDGES(Part("M2 18L7 6h10l5 12"), Part("M2 21h20")),
    CLOUD(Part("M7 18a4.5 4.5 0 0 1-.6-9 6 6 0 0 1 11.5 1.5A3.8 3.8 0 0 1 17.5 18z"), Part("M12 11v6M9.5 14.5L12 17l2.5-2.5")),
    HELP(Part(SvgPath.circle(12f, 12f, 9f)), Part("M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7M12 17v.3")),
    SLIDERS(
        Part("M4 7h9M17 7h3M4 17h3M11 17h9"),
        Part(SvgPath.circle(15f, 7f, 2f)), Part(SvgPath.circle(9f, 17f, 2f)),
    ),
    FILE(Part("M6 3h8l4 4v14H6z"), Part("M14 3v4h4M9 13h6M9 17h6")),
    REPEAT(Part("M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3"), Part("M18 3v4h-4M6 21v-4h4")),
    INFO(Part(SvgPath.circle(12f, 12f, 9f)), Part("M12 11v6M12 7.5v.3")),
    BOOK(Part("M4 5a2 2 0 0 1 2-2h13v15H6a2 2 0 0 0-2 2z"), Part("M4 20a2 2 0 0 0 2 2h13v-4M9 7h6")),
    ;

    class Part(val d: String, val filled: Boolean = false) {
        val path: Path by lazy { toPath(SvgPath.parse(d)) }
    }

    /** Значок для [android.widget.TextView] чи [android.widget.ImageView]. */
    fun drawable(color: Int, sizePx: Int): LineIconDrawable = LineIconDrawable(this, color).apply { setBounds(0, 0, sizePx, sizePx) }

    companion object {
        /** Символ, на місці якого в тексті стоїть значок ([iconText]). */
        const val OBJ = '￼'

        fun toPath(segs: List<SvgPath.Seg>): Path = Path().apply {
            for (s in segs) when (s) {
                is SvgPath.Seg.Move -> moveTo(s.x, s.y)
                is SvgPath.Seg.Line -> lineTo(s.x, s.y)
                is SvgPath.Seg.Cubic -> cubicTo(s.x1, s.y1, s.x2, s.y2, s.x, s.y)
                SvgPath.Seg.Close -> close()
            }
        }
    }
}

/** Малює [LineIcon] у своїх межах: масштаб 24 → ширина меж. Колір — [color]. */
class LineIconDrawable(private val icon: LineIcon, color: Int) : Drawable() {
    var color: Int = color
        set(value) {
            field = value
            stroke.color = value
            fill.color = value
            invalidateSelf()
        }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 24f
        val save = canvas.save()
        canvas.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        canvas.scale(s, s)
        for (p in icon.parts) canvas.drawPath(p.path, if (p.filled) fill else stroke)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
    }

    @Deprecated("Drawable.getOpacity")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * Текст зі значком на початку: «[значок] Нічний екран». [above] — значок над текстом (вкладки).
 * Значок — [ImageSpan] по центру рядка, тож у кнопці посередині він стоїть поруч з текстом.
 */
fun Ui.iconText(icon: LineIcon, label: CharSequence, color: Int, sizeDp: Int = 18, above: Boolean = false): CharSequence =
    SpannableStringBuilder().apply {
        append(LineIcon.OBJ)
        setSpan(ImageSpan(icon.drawable(color, dp(sizeDp)), DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(if (above) "\n" else " ")
        append(label)
    }

/** Підпис без значка — для озвучення й порівнянь. */
fun plainLabel(text: CharSequence): String = text.toString().replace(LineIcon.OBJ.toString(), "").trim()
