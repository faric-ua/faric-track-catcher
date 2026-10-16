package ua.faric.trackcatcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgPathTest {
    private fun end(s: SvgPath.Seg): Pair<Float, Float> = when (s) {
        is SvgPath.Seg.Move -> s.x to s.y
        is SvgPath.Seg.Line -> s.x to s.y
        is SvgPath.Seg.Cubic -> s.x to s.y
        SvgPath.Seg.Close -> Float.NaN to Float.NaN
    }

    @Test fun absoluteAndRelativeLines() {
        val segs = SvgPath.parse("M4 6h12M8 12l8-8V20H2z")
        assertEquals(SvgPath.Seg.Move(4f, 6f), segs[0])
        assertEquals(SvgPath.Seg.Line(16f, 6f), segs[1])
        assertEquals(SvgPath.Seg.Move(8f, 12f), segs[2])
        assertEquals(SvgPath.Seg.Line(16f, 4f), segs[3])
        assertEquals(SvgPath.Seg.Line(16f, 20f), segs[4])
        assertEquals(SvgPath.Seg.Line(2f, 20f), segs[5])
        assertEquals(SvgPath.Seg.Close, segs[6])
    }

    @Test fun implicitLinesAfterMove() {
        val segs = SvgPath.parse("M0 0 10 0 10 10")
        assertEquals(listOf(SvgPath.Seg.Move(0f, 0f), SvgPath.Seg.Line(10f, 0f), SvgPath.Seg.Line(10f, 10f)), segs)
    }

    @Test fun arcEndsExactlyAtTarget() {
        val segs = SvgPath.parse("M3 12a9 9 0 0 1 18 0")
        val (x, y) = end(segs.last())
        assertEquals(21f, x, 1e-4f)
        assertEquals(12f, y, 1e-4f)
        assertTrue(segs.drop(1).all { it is SvgPath.Seg.Cubic })
        assertEquals(2, segs.size - 1) // півкола = дві чверті
    }

    @Test fun circleStaysOnRadius() {
        val segs = SvgPath.parse(SvgPath.circle(12f, 12f, 8f))
        for (s in segs) if (s is SvgPath.Seg.Cubic) {
            val r = Math.hypot((s.x - 12).toDouble(), (s.y - 12).toDouble())
            assertEquals(8.0, r, 1e-3)
        }
    }

    @Test fun compactNumbers() {
        val segs = SvgPath.parse("M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7")
        val last = segs.last() as SvgPath.Seg.Cubic
        assertEquals(12f, last.x, 1e-3f)
        assertEquals(13.5f, last.y, 1e-3f) // дуга до (13; 11,8), далі c … −1 1,7
    }

    @Test fun quadraticBecomesCubicWithSameEnds() {
        val c = SvgPath.parse("M0 0Q10 10 20 0")[1] as SvgPath.Seg.Cubic
        assertEquals(20f, c.x, 0f)
        assertEquals(6.6667f, c.y1, 1e-3f)
    }
}
