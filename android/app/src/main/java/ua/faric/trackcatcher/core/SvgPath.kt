package ua.faric.trackcatcher.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Розбір рядка шляху SVG (`d="M4 7h16…"`) у прості відрізки: перехід, лінія, крива Безьє, замкнути.
 * Потрібен, щоб значки застосунку (T58) були ті самі, що в інструкції (`docs/guide/guide.html`):
 * той самий запис шляху, без бібліотек і без картинок. Без Android — перевіряється тестами.
 *
 * Підтримано: M L H V C S Q T A Z (великі — абсолютні, малі — відносні). Дуги й квадратичні криві
 * переводяться в кубічні Безьє.
 */
object SvgPath {
    sealed class Seg {
        data class Move(val x: Float, val y: Float) : Seg()
        data class Line(val x: Float, val y: Float) : Seg()
        data class Cubic(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : Seg()
        object Close : Seg()
    }

    private val TOKEN = Regex("[MmLlHhVvCcSsQqTtAaZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    fun parse(d: String): List<Seg> {
        val tokens = TOKEN.findAll(d).map { it.value }.toList()
        val out = ArrayList<Seg>()
        var i = 0
        var cmd = ' '
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastWasCubic = false
        var lastQx = 0f
        var lastQy = 0f
        var lastWasQuad = false

        fun num(): Float = tokens[i++].toFloat()
        fun hasNum() = i < tokens.size && !tokens[i][0].isLetter()

        while (i < tokens.size) {
            if (tokens[i][0].isLetter()) cmd = tokens[i++][0]
            val rel = cmd.isLowerCase()
            val ox = if (rel) x else 0f
            val oy = if (rel) y else 0f
            var cubic = false
            var quad = false
            when (cmd.uppercaseChar()) {
                'M' -> {
                    x = ox + num(); y = oy + num()
                    startX = x; startY = y
                    out += Seg.Move(x, y)
                    cmd = if (rel) 'l' else 'L' // наступні пари після M — це лінії
                }
                'L' -> { x = ox + num(); y = oy + num(); out += Seg.Line(x, y) }
                'H' -> { x = ox + num(); out += Seg.Line(x, y) }
                'V' -> { y = oy + num(); out += Seg.Line(x, y) }
                'C' -> {
                    val x1 = ox + num(); val y1 = oy + num(); val x2 = ox + num(); val y2 = oy + num()
                    x = ox + num(); y = oy + num()
                    out += Seg.Cubic(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cubic = true
                }
                'S' -> {
                    val x1 = if (lastWasCubic) 2 * x - lastCtrlX else x
                    val y1 = if (lastWasCubic) 2 * y - lastCtrlY else y
                    val x2 = ox + num(); val y2 = oy + num()
                    x = ox + num(); y = oy + num()
                    out += Seg.Cubic(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cubic = true
                }
                'Q' -> {
                    val qx = ox + num(); val qy = oy + num()
                    val nx = ox + num(); val ny = oy + num()
                    out += quadToCubic(x, y, qx, qy, nx, ny)
                    x = nx; y = ny; lastQx = qx; lastQy = qy; quad = true
                }
                'T' -> {
                    val qx = if (lastWasQuad) 2 * x - lastQx else x
                    val qy = if (lastWasQuad) 2 * y - lastQy else y
                    val nx = ox + num(); val ny = oy + num()
                    out += quadToCubic(x, y, qx, qy, nx, ny)
                    x = nx; y = ny; lastQx = qx; lastQy = qy; quad = true
                }
                'A' -> {
                    val rx = num(); val ry = num(); val rot = num()
                    val large = num() != 0f; val sweep = num() != 0f
                    val nx = ox + num(); val ny = oy + num()
                    out += arcToCubics(x, y, rx, ry, rot, large, sweep, nx, ny)
                    x = nx; y = ny
                }
                'Z' -> {
                    out += Seg.Close
                    x = startX; y = startY
                }
                else -> i++ // невідома команда — пропустити число, не падати
            }
            lastWasCubic = cubic
            lastWasQuad = quad
            if (cmd.uppercaseChar() == 'Z' && hasNum()) cmd = 'L'
        }
        return out
    }

    private fun quadToCubic(x0: Float, y0: Float, qx: Float, qy: Float, x: Float, y: Float) = Seg.Cubic(
        x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
        x + 2f / 3f * (qx - x), y + 2f / 3f * (qy - y),
        x, y,
    )

    /** Дуга SVG (кінцеві точки) → кубічні Безьє, по шматку на ≤ 90°. Алгоритм — SVG 1.1, додаток F.6. */
    fun arcToCubics(
        x1: Float, y1: Float, rxIn: Float, ryIn: Float, rotDeg: Float,
        large: Boolean, sweep: Boolean, x2: Float, y2: Float,
    ): List<Seg> {
        if (x1 == x2 && y1 == y2) return emptyList()
        var rx = abs(rxIn.toDouble())
        var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) return listOf(Seg.Line(x2, y2))
        val phi = Math.toRadians(rotDeg.toDouble())
        val cp = cos(phi)
        val sp = sin(phi)
        val dx = (x1 - x2) / 2.0
        val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy
        val y1p = -sp * dx + cp * dy
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) {
            val s = sqrt(lambda)
            rx *= s; ry *= s
        }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
        val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        val theta1 = angle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var delta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && delta > 0) delta -= 2 * PI
        if (sweep && delta < 0) delta += 2 * PI
        val parts = maxOf(1, ceil(abs(delta) / (PI / 2)).toInt())
        val step = delta / parts
        val k = 4.0 / 3.0 * tan(step / 4)
        val out = ArrayList<Seg>(parts)
        var t = theta1
        fun pt(a: Double): Pair<Double, Double> {
            val px = rx * cos(a)
            val py = ry * sin(a)
            return (cp * px - sp * py + cx) to (sp * px + cp * py + cy)
        }
        fun deriv(a: Double): Pair<Double, Double> {
            val px = -rx * sin(a)
            val py = ry * cos(a)
            return (cp * px - sp * py) to (sp * px + cp * py)
        }
        repeat(parts) { n ->
            val t2 = t + step
            val (sx, sy) = pt(t)
            val (ex, ey) = if (n == parts - 1) x2.toDouble() to y2.toDouble() else pt(t2)
            val (d1x, d1y) = deriv(t)
            val (d2x, d2y) = deriv(t2)
            out += Seg.Cubic(
                (sx + k * d1x).toFloat(), (sy + k * d1y).toFloat(),
                (ex - k * d2x).toFloat(), (ey - k * d2y).toFloat(),
                ex.toFloat(), ey.toFloat(),
            )
            t = t2
        }
        return out
    }

    /** Коло як шлях (для значків): дві дуги. */
    fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r} ${cy}A$r $r 0 1 0 ${cx + r} ${cy}A$r $r 0 1 0 ${cx - r} ${cy}Z"
}
