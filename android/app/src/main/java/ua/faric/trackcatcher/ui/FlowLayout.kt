package ua.faric.trackcatcher.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import ua.faric.trackcatcher.core.flowEndOffset
import ua.faric.trackcatcher.core.flowRows

/**
 * Ряд кнопок, що переноситься на новий рядок, коли не влазить (вузький екран, великий шрифт).
 * [alignEnd] — кожен рядок притиснутий праворуч (кнопки вікон: «Закрити» ближче до правої руки, T53).
 */
class FlowLayout(
    context: Context,
    private val hGap: Int,
    private val vGap: Int,
    private val alignEnd: Boolean = false,
) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(
                MeasureSpec.makeMeasureSpec(maxW.coerceAtLeast(0), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            if (x > 0 && x + c.measuredWidth > maxW) {
                x = 0
                y += rowH + vGap
                rowH = 0
            }
            x += c.measuredWidth + hGap
            widest = maxOf(widest, x - hGap)
            rowH = maxOf(rowH, c.measuredHeight)
        }
        val w = if (mode == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec) else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + rowH + paddingTop + paddingBottom)
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
