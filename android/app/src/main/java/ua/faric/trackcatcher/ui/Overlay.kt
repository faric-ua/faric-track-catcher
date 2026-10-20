package ua.faric.trackcatcher.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.ViewTreeObserver
import android.widget.ScrollView

/**
 * Власні вікна поверх екрана (замість стандартних сірих діалогів Android).
 * Кожне вікно має ключ — після повороту екрана Activity відновлює його за ключем.
 */
class Overlay(private val host: FrameLayout, private val ui: Ui, private val onChange: (String?) -> Unit) {
    var key: String? = null
        private set
    private var layer: View? = null
    private var panel: View? = null
    private var scroll: ScrollView? = null
    /** Системні панелі (рядок стану згори, кнопки внизу): вікно не заходить під них (T41, faric 14.10). */
    private var barLeft = 0
    private var barTop = 0
    private var barRight = 0
    private var barBottom = 0
    /**
     * Верх вікна після першого показу (faric 18.10 04:33, відео: вікно «Публікую версію на Диск» стрибало, коли
     * текст поступу ставав довшим, — бо вікно щоразу ставало по центру заново). Далі верх стоїть на місці,
     * вікно росте лише вниз (не нижче системних кнопок — тоді прокрутка всередині). -1 — ще не показано.
     */
    private var pinnedTop = -1

    val isShowing: Boolean get() = layer != null

    /** Наскільки прокручено вміст вікна (зберегти при повороті). */
    val scrollY: Int get() = scroll?.scrollY ?: 0

    /** Прокрутка, яку треба повернути вікну [pendingKey], коли воно з'явиться (поворот, повернення в застосунок). */
    private var pendingKey: String? = null
    private var pendingY = 0

    fun restoreScrollLater(key: String, y: Int) {
        if (y <= 0) return
        pendingKey = key
        pendingY = y
    }

    /**
     * [keepScroll] — нове вікно замість такого самого (⏮/⏭ у подробицях запису, T51): прокрутити так,
     * щоб низ (плеєр і кнопки) лишився на тому самому місці екрана, а не стрибав угору.
     */
    /** [placeholder] — тимчасове вікно («Читаю файл…»): ключ той самий, але прокрутку повертаємо вже справжньому. */
    fun show(key: String, content: View, dismissOnOutside: Boolean = true, keepScroll: Boolean = false, placeholder: Boolean = false) {
        val fromBottom = if (keepScroll) scroll?.let { distanceFromBottom(it) } else null
        dismiss(notify = false)
        this.key = key
        val scrim = FrameLayout(host.context).apply {
            setBackgroundColor(Palette.scrim)
            isClickable = true
            setOnClickListener { if (dismissOnOutside) dismiss() }
        }
        val panel = ui.column().apply {
            background = ui.rounded(Palette.surface, Palette.border, radiusDp = 20)
            isClickable = true // дотик по вікну не закриває його
            elevation = ui.dp(12).toFloat()
        }
        val scroll = ScrollView(host.context).apply {
            isFillViewport = false
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(18))
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val widthDp = host.context.resources.configuration.screenWidthDp
        val panelW = minOf(ui.dp(560), (ui.dp(widthDp) * 0.92f).toInt())
        scrim.addView(panel, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        host.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = scrim
        this.panel = panel
        this.scroll = scroll
        pinnedTop = -1
        panel.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (v !== this.panel) return@addOnLayoutChangeListener
            if (pinnedTop < 0 && bottom > top) {
                pinnedTop = top // перший показ — по центру, як завжди; запам'ятали, де верх
            } else if (pinnedTop >= 0 && bottom - top != oldBottom - oldTop && top != pinnedTop) {
                v.post { pinTop() } // висота змінилась і вікно поїхало — повернути верх на місце
            }
        }
        placePanel()
        fromBottom?.let { restoreFromBottom(scroll, it) }
        if (!placeholder && pendingKey == key) {
            val y = pendingY
            pendingKey = null
            // Двічі: вміст може дорости після першого кадру (картинки, переноси).
            scroll.post { scroll.scrollTo(0, y) }
            scroll.postDelayed({ if (this.scroll === scroll) scroll.scrollTo(0, y) }, 250)
        }
        onChange(key)
    }

    /** Вікно на весь екран без рамки (нічний екран). */
    fun showFullScreen(key: String, content: View) {
        dismiss(notify = false)
        this.key = key
        content.isClickable = true
        host.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = content
        onChange(key)
    }

    /** Відступи системних панелей змінились (поворот, клавіатура) — переставити вікно. */
    fun setSystemBars(left: Int, top: Int, right: Int, bottom: Int) {
        barLeft = left
        barTop = top
        barRight = right
        barBottom = bottom
        placePanel()
    }

    private fun placePanel() {
        val p = panel ?: return
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        // Вікно — між системними панелями з запасом 16 dp; вище не влазить — прокручується всередині.
        lp.leftMargin = barLeft
        lp.rightMargin = barRight
        lp.topMargin = if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.TOP) maxOf(pinnedTop, barTop + ui.dp(16)) else barTop + ui.dp(16)
        lp.bottomMargin = barBottom + ui.dp(16)
        p.layoutParams = lp
    }

    private fun pinTop() {
        val p = panel ?: return
        val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
        if (pinnedTop < 0) return
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.topMargin = maxOf(pinnedTop, barTop + ui.dp(16))
        p.layoutParams = lp
    }

    fun dismiss(notify: Boolean = true) {
        pinnedTop = -1
        layer?.let { host.removeView(it) }
        layer = null
        panel = null
        scroll = null
        key = null
        if (notify) onChange(null)
    }

    /** Типове вікно: заголовок, текст, кнопки праворуч (скасування/«Закрити» — найправіша: YTM UX-018, faric T53). */
    fun dialog(
        title: String,
        message: CharSequence,
        actions: List<Pair<String, Ui.Kind>>,
        onAction: (Int) -> Unit,
        extra: View? = null,
    ): View {
        val box = ui.column(14)
        box.addView(ui.title(title, 19f))
        if (message.isNotEmpty()) box.addView(ui.text(message, 15f, Palette.muted))
        extra?.let { box.addView(it) }
        val row = FlowLayout(host.context, ui.dp(8), ui.dp(8), alignEnd = true) // кнопки праворуч, «Закрити» — найправіша (T53)
        actions.forEachIndexed { i, (label, kind) ->
            row.addView(ui.button(label, kind) { onAction(i) })
        }
        box.addView(row, ui.matchWrap())
        return box
    }
}

/** Скільки прокрутки лишилось до низу вмісту (T51: тримати низ на місці при заміні вмісту). */
fun distanceFromBottom(s: ScrollView): Int = (s.getChildAt(0)?.height ?: 0) - s.scrollY

/** Після побудови нового вмісту — прокрутити так, щоб до низу лишилось [fromBottom] (до першого кадру, без блимання). */
fun restoreFromBottom(s: ScrollView, fromBottom: Int) {
    s.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (s.viewTreeObserver.isAlive) s.viewTreeObserver.removeOnPreDrawListener(this)
            val h = s.getChildAt(0)?.height ?: return true
            s.scrollTo(0, (h - fromBottom).coerceAtLeast(0))
            return true
        }
    })
}
