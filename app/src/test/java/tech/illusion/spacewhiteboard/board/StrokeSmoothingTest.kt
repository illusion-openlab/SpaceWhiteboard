package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSmoothingTest {

    @Test
    fun `no points yields null`() {
        assertNull(StrokeSmoothing.smooth(emptyList()))
    }

    @Test
    fun `a single point is a dot with no quads and no trailing line`() {
        val p = StrokeSmoothing.smooth(listOf(Pt(4f, 7f)))!!
        assertEquals(Pt(4f, 7f), p.start)
        assertTrue(p.quads.isEmpty())
        assertNull(p.end)
    }

    @Test
    fun `two points make a straight line with no quads`() {
        val p = StrokeSmoothing.smooth(listOf(Pt(0f, 0f), Pt(10f, 10f)))!!
        assertEquals(Pt(0f, 0f), p.start)
        assertTrue(p.quads.isEmpty())
        assertEquals(Pt(10f, 10f), p.end)
    }

    @Test
    fun `three points produce one quad whose control is the middle sample`() {
        val pts = listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(20f, 0f))
        val p = StrokeSmoothing.smooth(pts)!!
        assertEquals(Pt(0f, 0f), p.start)
        assertEquals(1, p.quads.size)
        assertEquals(Pt(10f, 0f), p.quads[0].ctrl)     // 控制点 = 原采样点
        assertEquals(Pt(15f, 0f), p.quads[0].end)      // 锚点 = 相邻两采样点的中点
        assertEquals(Pt(20f, 0f), p.end)               // 最后一段用直线收尾
    }

    @Test
    fun `n points produce n minus 2 quads`() {
        val pts = (0..9).map { Pt(it.toFloat(), 0f) }
        assertEquals(8, StrokeSmoothing.smooth(pts)!!.quads.size)
    }

    @Test
    fun `every quad anchor is the midpoint of its two adjacent samples`() {
        val pts = listOf(Pt(0f, 0f), Pt(10f, 4f), Pt(20f, 0f), Pt(30f, 8f))
        val p = StrokeSmoothing.smooth(pts)!!
        assertEquals(Pt(15f, 2f), p.quads[0].end)
        assertEquals(Pt(25f, 4f), p.quads[1].end)
    }

    @Test
    fun `full quads list is correct with asymmetric points`() {
        // Use 5 distinct asymmetric points to catch reversed order or ctrl/end swap
        val pts = listOf(Pt(0f, 0f), Pt(5f, 10f), Pt(15f, 5f), Pt(20f, 15f), Pt(30f, 2f))
        val p = StrokeSmoothing.smooth(pts)!!
        assertEquals(Pt(0f, 0f), p.start)
        // n=5, so n-2=3 quads
        assertEquals(3, p.quads.size)
        // Quad 0: ctrl = pts[1] = (5, 10), end = midpoint(pts[1], pts[2]) = (10, 7.5)
        assertEquals(StrokeSmoothing.QuadSeg(Pt(5f, 10f), Pt(10f, 7.5f)), p.quads[0])
        // Quad 1: ctrl = pts[2] = (15, 5), end = midpoint(pts[2], pts[3]) = (17.5, 10)
        assertEquals(StrokeSmoothing.QuadSeg(Pt(15f, 5f), Pt(17.5f, 10f)), p.quads[1])
        // Quad 2: ctrl = pts[3] = (20, 15), end = midpoint(pts[3], pts[4]) = (25, 8.5)
        assertEquals(StrokeSmoothing.QuadSeg(Pt(20f, 15f), Pt(25f, 8.5f)), p.quads[2])
        assertEquals(Pt(30f, 2f), p.end)
    }
}
