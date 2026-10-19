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

/**
 * Ширини елементів рядка, щоб рядок займав усю ширину (faric 19.10 20:09: «дві плитки — рівномірно на рядок;
 * одна, бо друга не влазить, — на весь рядок»). Усі влазять у рівну частку — усі однакові; інакше вільне місце
 * ділиться порівну.
 */
fun flowFill(rowWidths: List<Int>, maxW: Int, gap: Int): List<Int> {
    val n = rowWidths.size
    if (n == 0) return emptyList()
    val avail = maxW - gap * (n - 1)
    if (avail <= 0) return rowWidths
    val equal = avail / n
    if (rowWidths.all { it <= equal }) return List(n) { i -> if (i == n - 1) avail - equal * (n - 1) else equal }
    val extra = (avail - rowWidths.sum()).coerceAtLeast(0)
    return rowWidths.mapIndexed { i, w -> w + extra / n + if (i == n - 1) extra % n else 0 }
}
