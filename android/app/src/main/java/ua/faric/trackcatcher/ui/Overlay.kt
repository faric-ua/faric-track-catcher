package ua.faric.trackcatcher.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    /** Системні панелі (рядок стану згори, кнопки внизу): вікно не заходить під них (T41, faric 14.10). */
    private var barLeft = 0
    private var barTop = 0
    private var barRight = 0
    private var barBottom = 0

    val isShowing: Boolean get() = layer != null

    fun show(key: String, content: View, dismissOnOutside: Boolean = true) {
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
        placePanel()
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
        lp.topMargin = barTop + ui.dp(16)
        lp.bottomMargin = barBottom + ui.dp(16)
        p.layoutParams = lp
    }

    fun dismiss(notify: Boolean = true) {
        layer?.let { host.removeView(it) }
        layer = null
        panel = null
        key = null
        if (notify) onChange(null)
    }

    /** Типове вікно: заголовок, текст, кнопки (скасування — праворуч, правило YTM UX-018). */
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
        val row = FlowLayout(host.context, ui.dp(8), ui.dp(8))
        actions.forEachIndexed { i, (label, kind) ->
            row.addView(ui.button(label, kind) { onAction(i) })
        }
        box.addView(row, ui.matchWrap())
        return box
    }
}
