package ua.faric.trackcatcher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import ua.faric.trackcatcher.core.flowEndOffset
import ua.faric.trackcatcher.core.flowFill
import ua.faric.trackcatcher.core.flowRows

/**
 * Ряд кнопок, що переноситься на новий рядок, коли не влазить (вузький екран, великий шрифт).
 * [alignEnd] — кожен рядок притиснутий праворуч (кнопки вікон: «Закрити» ближче до правої руки, T53).
 * [fill] — кожен рядок займає всю ширину: плитки рядка однакові, одна в рядку — на весь рядок (faric 19.10 20:09).
 * Без [fill] — як були (вибір формату, мітки в «Про файл»).
 */
class FlowLayout(
    context: Context,
    private val hGap: Int,
    private val vGap: Int,
    private val alignEnd: Boolean = false,
    private val fill: Boolean = true,
) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val visible = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        for (c in visible) {
            c.measure(
                MeasureSpec.makeMeasureSpec(maxW.coerceAtLeast(0), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
        }
        val stretch = fill && maxW != Int.MAX_VALUE
        val rows = flowRows(visible.map { it.measuredWidth }, maxW, hGap)
        var y = 0
        var widest = 0
        rows.forEachIndexed { ri, row ->
            if (stretch) {
                val widths = flowFill(row.map { visible[it].measuredWidth }, maxW, hGap)
                row.forEachIndexed { k, i ->
                    visible[i].measure(MeasureSpec.makeMeasureSpec(widths[k], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                }
                // Однакова висота плиток у рядку (текст у два рядки — сусідня теж вища).
                val h = row.maxOf { visible[it].measuredHeight }
                row.forEach { i ->
                    val c = visible[i]
                    if (c.measuredHeight != h) c.measure(MeasureSpec.makeMeasureSpec(c.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
                }
            }
            val rowW = row.sumOf { visible[it].measuredWidth } + hGap * (row.size - 1)
            widest = maxOf(widest, rowW)
            y += row.maxOf { visible[it].measuredHeight } + if (ri < rows.lastIndex) vGap else 0
        }
        val w = if (mode == MeasureSpec.EXACTLY || stretch) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val visible = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != View.GONE }
        val rows = flowRows(visible.map { it.measuredWidth }, maxW, hGap)
        var y = 0
        for (row in rows) {
            var x = if (alignEnd) flowEndOffset(row.map { visible[it].measuredWidth }, maxW, hGap) else 0
            var rowH = 0
            for (i in row) {
                val c = visible[i]
                c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
                x += c.measuredWidth + hGap
                rowH = maxOf(rowH, c.measuredHeight)
            }
            y += rowH + vGap
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(p: LayoutParams): LayoutParams = LayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p != null
}
