package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSplitTest {

    private fun line(n: Int) = (0 until n).map { Pt(it * 10f, 0f) }

    @Test
    fun `no point inside the circle leaves the stroke whole`() {
        val pts = line(5)
        val out = StrokeSplit.split(pts, Pt(0f, 500f), 12f)
        assertEquals(1, out.size)
        assertEquals(pts, out[0])
    }

    @Test
    fun `a hit in the middle splits into two runs`() {
        // 点在 x = 0,10,20,30,40；橡皮罩住 x=20
        val out = StrokeSplit.split(line(5), Pt(20f, 0f), 5f)
        assertEquals(2, out.size)
        assertEquals(listOf(Pt(0f, 0f), Pt(10f, 0f)), out[0])
        assertEquals(listOf(Pt(30f, 0f), Pt(40f, 0f)), out[1])
    }

    @Test
    fun `a hit at the start leaves one run and drops nothing else`() {
        val out = StrokeSplit.split(line(5), Pt(0f, 0f), 5f)
        assertEquals(1, out.size)
        assertEquals(listOf(Pt(10f, 0f), Pt(20f, 0f), Pt(30f, 0f), Pt(40f, 0f)), out[0])
    }

    @Test
    fun `erasing everything yields no runs`() {
        assertTrue(StrokeSplit.split(line(5), Pt(20f, 0f), 1000f).isEmpty())
    }

    @Test
    fun `a surviving run of a single point is discarded as debris`() {
        // 罩住 x=10 与 x=30，中间只剩 x=20 这一个孤点；两端各剩一个孤点
        val out = StrokeSplit.split(listOf(Pt(0f,0f), Pt(10f,0f), Pt(20f,0f), Pt(30f,0f), Pt(40f,0f)),
                                    Pt(20f, 0f), 11f)
        assertTrue("孤立单点不应保留，实际: $out", out.isEmpty())
    }

    @Test
    fun `the boundary is inclusive so a point exactly at radius is erased`() {
        val out = StrokeSplit.split(listOf(Pt(0f,0f), Pt(10f,0f), Pt(20f,0f)), Pt(10f, 0f), 10f)
        // 三个点到圆心距离分别是 10、0、10，全部 <= 10，全被擦掉
        assertTrue(out.isEmpty())
    }

    @Test
    fun `an empty input yields no runs`() {
        assertTrue(StrokeSplit.split(emptyList(), Pt(0f, 0f), 10f).isEmpty())
    }
}
