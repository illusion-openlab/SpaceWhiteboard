package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ShapeOutlineTest {

    private fun shape(kind: ShapeKind, a: Pt, b: Pt) =
        ShapeItem(ItemId(1), kind, 0xFF000000.toInt(), 2f, a, b)

    @Test
    fun `a line samples to its two endpoints only`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Line, Pt(0f, 0f), Pt(100f, 50f)))
        assertEquals(listOf(Pt(0f, 0f), Pt(100f, 50f)), pts)
    }

    @Test
    fun `a rect includes all four corners and closes back to the start`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Rect, Pt(0f, 0f), Pt(100f, 60f)))
        val corners = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 60f), Pt(0f, 60f))
        for (c in corners) {
            assertTrue("缺角 $c", pts.any { abs(it.x - c.x) < 0.01f && abs(it.y - c.y) < 0.01f })
        }
        assertEquals("首尾必须重合，否则擦除后会露出一道缝", pts.first(), pts.last())
    }

    @Test
    fun `a reversed-drag rect samples the same outline as a forward one`() {
        val fwd = ShapeOutline.toPoints(shape(ShapeKind.Rect, Pt(0f, 0f), Pt(100f, 60f)))
        val rev = ShapeOutline.toPoints(shape(ShapeKind.Rect, Pt(100f, 60f), Pt(0f, 0f)))
        assertEquals(fwd, rev)
    }

    @Test
    fun `every ellipse sample lies on the ellipse`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(200f, 100f)))
        val cx = 100f; val cy = 50f; val rx = 100f; val ry = 50f
        for (p in pts) {
            val v = ((p.x - cx) / rx) * ((p.x - cx) / rx) + ((p.y - cy) / ry) * ((p.y - cy) / ry)
            assertTrue("点 $p 不在椭圆上 (v=$v)", abs(v - 1f) < 0.02f)
        }
        assertEquals("椭圆首尾必须重合", pts.first(), pts.last())
    }

    @Test
    fun `sampling density scales with perimeter and stays within bounds`() {
        val small = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(20f, 20f)))
        val big = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(2000f, 1200f)))
        assertTrue("大形状的采样点应多于小形状", big.size > small.size)
        assertTrue("小形状不应少于下限", small.size >= ShapeOutline.MIN_SAMPLES)
        assertTrue("大形状不应超过上限", big.size <= ShapeOutline.MAX_SAMPLES + 1)
    }

    @Test
    fun `consecutive ellipse samples are close enough to look smooth`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(600f, 400f)))
        val maxGap = pts.zipWithNext().maxOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        assertTrue("相邻采样间距 $maxGap 过大，椭圆会显出多边形感", maxGap <= 12f)
    }

    @Test
    fun `a degenerate zero-size shape does not crash and yields at least two points`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(5f, 5f), Pt(5f, 5f)))
        assertTrue(pts.size >= 2)
    }
}
