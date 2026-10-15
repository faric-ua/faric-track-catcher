package ua.faric.trackcatcher.core

/**
 * Розбити ширини елементів на рядки, як ряд кнопок з переносом (`ui/FlowLayout`): номери елементів кожного рядка.
 * Елемент, ширший за рядок, стає сам на свій рядок. Без Android — для тестів (T53: притискання рядків праворуч).
 */
fun flowRows(widths: List<Int>, maxW: Int, gap: Int): List<List<Int>> {
    val rows = ArrayList<MutableList<Int>>()
    var x = 0
    widths.forEachIndexed { i, w ->
        if (rows.isEmpty() || (x > 0 && x + w > maxW)) {
            rows += mutableListOf<Int>()
            x = 0
        }
        rows.last() += i
        x += w + gap
    }
    return rows
}

/** Відступ зліва для рядка, притиснутого праворуч: [maxW] − ширина рядка (не менше 0). */
fun flowEndOffset(rowWidths: List<Int>, maxW: Int, gap: Int): Int =
    (maxW - (rowWidths.sum() + gap * (rowWidths.size - 1).coerceAtLeast(0))).coerceAtLeast(0)
