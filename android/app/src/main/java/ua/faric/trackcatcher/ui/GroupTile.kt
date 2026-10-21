package ua.faric.trackcatcher.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * СПІЛЬНА ПЛИТКА ГРУПИ (версія — [VERSION]; Audio Converter може взяти собі, як [PlayerView] і [FileInfoPanel]).
 *
 * Закрита / відкрита плитка альбому, виконавця чи сесії (T89, faric 20.10 01:58 і 21.10 20:13–20:14: «вибрав
 * альбоми — хопа, закриті; знайшов потрібний — торкнувся, розкрився»). Ліворуч — обкладинка (необов'язково),
 * далі назва (більшим шрифтом, в один рядок; не влазить — «…», а постоїш 3 с — назва сама проїде справа наліво,
 * щоб видно було кінець), під нею — підрядок (скільки пісень і часу) і позначки (напр. «тріски: 2»).
 * Праворуч — стрілка лініями: «›» закрита, «⌄» відкрита. Уся плитка — зона дотику.
 *
 * Самодостатня: лише [Ui] і [Palette] (як решта спільних файлів).
 */
@SuppressLint("ViewConstructor")
class GroupTile(
    context: Context,
    private val ui: Ui,
    title: String,
    subtitle: CharSequence,
    marks: List<Pair<String, Int>>,
    art: View?,
    open: Boolean,
    /** Плитка всередині іншої (сесія в альбомі) — менша й з відступом. */
    nested: Boolean,
    onToggle: () -> Unit,
) : LinearLayout(context) {

    /** Назва — щоб екран міг запускати / зупиняти прокрутку назви ([startMarquee], [stopMarquee]). */
    val titleView: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = ui.dp(if (nested) 12 else 14)
        setPadding(pad, ui.dp(if (nested) 10 else 12), ui.dp(10), ui.dp(if (nested) 10 else 12))
        background = ui.pressable(
            ui.rounded(
                if (open) Palette.surfaceAlt else Palette.surface,
                if (open) Palette.accent else Palette.border,
                radiusDp = 16,
            ),
            16,
        )
        isClickable = true
        isFocusable = true
        minimumHeight = ui.dp(56)
        setOnClickListener { onToggle() }

        art?.let {
            val size = ui.dp(if (nested) 40 else 52)
            addView(it, LayoutParams(size, size).apply { rightMargin = ui.dp(12) })
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        titleView = ui.text(title, if (nested) 15.5f else 18f, Palette.text, medium = true).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            marqueeRepeatLimit = 2
            setHorizontallyScrolling(true)
        }
        texts.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (subtitle.isNotEmpty()) texts.addView(ui.numbers(ui.muted(subtitle, if (nested) 12.5f else 13.5f)).apply {
            setPadding(0, ui.dp(2), 0, 0)
        })
        if (marks.isNotEmpty()) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            marks.forEachIndexed { i, (text, color) ->
                line.addView(ui.text(if (i == 0) text else " · $text", 12.5f, color))
            }
            texts.addView(line)
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(Chevron(context, open), LayoutParams(ui.dp(32), ui.dp(32)).apply { leftMargin = ui.dp(8) })
        contentDescription = (if (open) "Згорнути: " else "Розгорнути: ") + title
    }

    /** Назва не влазить — проїхати нею (як біжучий рядок). Повертає, чи було що прокручувати. */
    fun startMarquee(): Boolean {
        val l = titleView.layout ?: return false
        if (l.lineCount == 0 || l.getEllipsisCount(0) == 0) return false
        titleView.ellipsize = TextUtils.TruncateAt.MARQUEE
        titleView.isSelected = true
        return true
    }

    fun stopMarquee() {
        if (titleView.ellipsize != TextUtils.TruncateAt.END) {
            titleView.isSelected = false
            titleView.ellipsize = TextUtils.TruncateAt.END
        }
    }

    /** Стрілка лініями, у стилі значків застосунку: відкрита — донизу, закрита — праворуч. */
    private class Chevron(context: Context, private val open: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Palette.muted
        }
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.strokeWidth = w * 0.075f
            val s = w * 0.2f
            path.reset()
            if (open) {
                path.moveTo(w / 2 - s, h / 2 - s / 2); path.lineTo(w / 2, h / 2 + s / 2); path.lineTo(w / 2 + s, h / 2 - s / 2)
            } else {
                path.moveTo(w / 2 - s / 2, h / 2 - s); path.lineTo(w / 2 + s / 2, h / 2); path.lineTo(w / 2 - s / 2, h / 2 + s)
            }
            canvas.drawPath(path, paint)
        }
    }

    companion object {
        /** Змінив вигляд — +1 і рядок у «Журнал стилю» (`docs/UI_STYLE_CONTRACT.md`). */
        const val VERSION = 1

        /** Скільки стояти, перш ніж назва поїде (faric 21.10 20:14: «через 3 секунди»). */
        const val MARQUEE_DELAY_MS = 3_000L

        /**
         * Біжучі назви для прокручуваного списку: після кожної прокрутки — усі назад до «…», і через 3 с тиші —
         * поїхати тим, що видно на екрані й не влазять.
         */
        class Marquee(private val scroller: View) {
            private val tiles = ArrayList<GroupTile>()
            private val handler = Handler(Looper.getMainLooper())
            private val go = Runnable {
                for (t in tiles) {
                    val r = android.graphics.Rect()
                    if (t.isAttachedToWindow && t.getGlobalVisibleRect(r) && r.height() >= t.height / 2) t.startMarquee()
                }
            }

            fun clear() {
                handler.removeCallbacks(go)
                tiles.clear()
            }

            fun add(tile: GroupTile) {
                tiles += tile
            }

            /** Щось змінилось (прокрутили, перебудували) — назви на місце й чекати 3 с знову. */
            fun restart() {
                handler.removeCallbacks(go)
                tiles.forEach { it.stopMarquee() }
                handler.postDelayed(go, MARQUEE_DELAY_MS)
            }

            fun stop() {
                handler.removeCallbacks(go)
                tiles.forEach { it.stopMarquee() }
            }
        }
    }
}
