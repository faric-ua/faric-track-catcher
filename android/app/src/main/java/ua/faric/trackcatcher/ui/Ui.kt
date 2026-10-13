package ua.faric.trackcatcher.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ua.faric.trackcatcher.core.RecStatus

/**
 * Будівельні блоки інтерфейсу. Кожен новий елемент — звідси (правило Visualizer:
 * «успадковуй стиль найближчого сусіда»). Ніяких стандартних сірих діалогів і кнопок.
 */
class Ui(val ctx: Context) {
    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    /** Дві колонки — за реальною шириною (правило Renault), не просто за поворотом. */
    val twoPane: Boolean get() = ctx.resources.configuration.screenWidthDp >= TWO_PANE_DP

    // ---------- фон ----------

    fun rounded(fill: Int, stroke: Int = Palette.transparent, radiusDp: Int = 14, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != Palette.transparent) setStroke(dp(strokeDp), stroke)
        }

    fun pressable(base: Drawable, radiusDp: Int = 14): Drawable =
        RippleDrawable(ColorStateList.valueOf(Palette.ripple), base, rounded(Palette.text, radiusDp = radiusDp))

    // ---------- текст ----------

    fun text(s: CharSequence, sizeSp: Float = 16f, color: Int = Palette.text, medium: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            typeface = if (medium) MEDIUM else REGULAR
            setLineSpacing(0f, 1.15f)
        }

    fun title(s: CharSequence, sizeSp: Float = 20f): TextView = text(s, sizeSp, Palette.text, medium = true)

    fun muted(s: CharSequence, sizeSp: Float = 13f): TextView = text(s, sizeSp, Palette.muted)

    /** Підпис розділу: дрібні великі літери з розрядкою. */
    fun label(s: String): TextView = text(s.uppercase(), 11.5f, Palette.muted, medium = true).apply {
        letterSpacing = 0.08f
    }

    fun numbers(tv: TextView): TextView = tv.apply { fontFeatureSettings = "tnum" }

    // ---------- контейнери ----------

    fun column(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    fun row(gapDp: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gapDp > 0) {
            dividerDrawable = gap(gapDp)
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        }
    }

    private fun gap(gapDp: Int) = GradientDrawable().apply {
        setColor(Palette.transparent)
        setSize(dp(gapDp), dp(gapDp))
    }

    fun card(gapDp: Int = 10, fill: Int = Palette.surface, stroke: Int = Palette.border): LinearLayout = column(gapDp).apply {
        background = rounded(fill, stroke, radiusDp = 16)
        setPadding(dp(16), dp(14), dp(16), dp(16))
    }

    fun scroll(child: View, padDp: Int = 16): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp + 8))
        addView(child, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun hscroll(child: View): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(child)
    }

    fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    fun weight(w: Float, vertical: Boolean = false): LinearLayout.LayoutParams =
        if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w)
        else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)

    // ---------- кнопки ----------

    enum class Kind { PRIMARY, REC, NORMAL, DANGER, QUIET }

    /**
     * Кнопка: мінімум 48 dp, текст переноситься (до 3 рядків) — довгі українські підписи не обрізаються.
     */
    fun button(label: String, kind: Kind = Kind.NORMAL, large: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            typeface = MEDIUM
            textSize = if (large) 17f else 15f
            minHeight = dp(if (large) 56 else MIN_TOUCH_DP)
            minimumHeight = dp(if (large) 56 else MIN_TOUCH_DP)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            styleButton(this, kind)
        }

    fun styleButton(b: TextView, kind: Kind) {
        val (fill, stroke, color) = when (kind) {
            Kind.PRIMARY -> Triple(Palette.accentFill, Palette.accent, Palette.accent)
            Kind.REC -> Triple(Palette.rec, Palette.rec, Palette.bg)
            Kind.DANGER -> Triple(Palette.dangerFill, Palette.danger, Palette.danger)
            Kind.NORMAL -> Triple(Palette.surfaceAlt, Palette.border, Palette.text)
            Kind.QUIET -> Triple(Palette.transparent, Palette.border, Palette.muted)
        }
        b.setTextColor(color)
        b.background = pressable(rounded(fill, stroke, radiusDp = 12, strokeDp = if (kind == Kind.REC) 0 else 1), 12)
    }

    fun setEnabled(b: View, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.45f
    }

    /** Вибір з кількох: вибране — зелена рамка 2 dp, білий жирний текст. Без галочок. */
    fun choice(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
        text = label
        gravity = Gravity.CENTER
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        maxLines = 2
        textSize = 15f
        minHeight = dp(MIN_TOUCH_DP)
        minimumHeight = dp(MIN_TOUCH_DP)
        minWidth = dp(64)
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
        styleChoice(this, selected)
    }

    fun styleChoice(v: TextView, selected: Boolean) {
        v.isSelected = selected
        v.typeface = if (selected) MEDIUM else REGULAR
        v.setTextColor(if (selected) Palette.text else Palette.muted)
        v.background = pressable(
            if (selected) rounded(Palette.selectedFill, Palette.success, radiusDp = 12, strokeDp = 2)
            else rounded(Palette.surfaceAlt, Palette.border, radiusDp = 12),
            12,
        )
        val base = v.text.toString().removeSuffix(SELECTED_SUFFIX)
        v.contentDescription = if (selected) base + SELECTED_SUFFIX else base
    }

    /** Статус запису — кольорова «пігулка» зі словом (не лише колір). */
    fun pill(status: RecStatus): TextView = text(status.label, 12f, statusColor(status), medium = true).apply {
        setPadding(dp(10), dp(3), dp(10), dp(4))
        background = rounded(statusFill(status), radiusDp = 20)
        maxLines = 1
    }

    fun statusColor(s: RecStatus) = when (s) {
        RecStatus.COMPLETE -> Palette.success
        RecStatus.PARTIAL -> Palette.partial
        RecStatus.FAILED -> Palette.danger
        RecStatus.SKIPPED -> Palette.muted
    }

    fun statusFill(s: RecStatus) = when (s) {
        RecStatus.COMPLETE -> Palette.selectedFill
        RecStatus.PARTIAL -> Palette.partialFill
        RecStatus.FAILED -> Palette.dangerFill
        RecStatus.SKIPPED -> Palette.surfaceAlt
    }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(Palette.border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1) / 2))
    }

    /** Рядок «назва — значення» для подробиць. */
    fun fact(name: String, value: String): LinearLayout = row(12).apply {
        gravity = Gravity.TOP
        addView(muted(name, 13f), LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(numbers(text(value, 14f)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    companion object {
        const val MIN_TOUCH_DP = 48
        const val TWO_PANE_DP = 600
        const val SELECTED_SUFFIX = " · вибрано"
        val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
