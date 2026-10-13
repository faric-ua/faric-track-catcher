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
        scrim.addView(panel, FrameLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = ui.dp(24)
            bottomMargin = ui.dp(24)
        })
        host.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layer = scrim
        onChange(key)
    }

    fun dismiss(notify: Boolean = true) {
        layer?.let { host.removeView(it) }
        layer = null
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
