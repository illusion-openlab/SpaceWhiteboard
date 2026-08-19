package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitTestTest {

    @Test
    fun `distanceToSegment returns perpendicular distance when the foot is inside`() {
        assertEquals(5f, HitTest.distanceToSegment(Pt(5f, 5f), Pt(0f, 0f), Pt(10f, 0f)), 1e-4f)
    }

    @Test
    fun `distanceToSegment clamps to the endpoints`() {
        // 垂足落在线段外侧，应退化为到端点的距离
        assertEquals(10f, HitTest.distanceToSegment(Pt(20f, 0f), Pt(0f, 0f), Pt(10f, 0f)), 1e-4f)
        assertEquals(10f, HitTest.distanceToSegment(Pt(-10f, 0f), Pt(0f, 0f), Pt(10f, 0f)), 1e-4f)
    }

    @Test
    fun `distanceToSegment handles a degenerate zero-length segment`() {
        assertEquals(5f, HitTest.distanceToSegment(Pt(3f, 4f), Pt(0f, 0f), Pt(0f, 0f)), 1e-4f)
    }

    @Test
    fun `strokeHit is true near the polyline and false far from it`() {
        val s = Stroke(ItemId(1), 0, 2f, listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 100f)))
        assertTrue(HitTest.strokeHit(s, Pt(50f, 3f), radius = 5f))
        assertTrue(HitTest.strokeHit(s, Pt(103f, 50f), radius = 5f))
        assertFalse(HitTest.strokeHit(s, Pt(50f, 40f), radius = 5f))
    }

    @Test
    fun `strokeHit works for a single point dot`() {
        val dot = Stroke(ItemId(1), 0, 2f, listOf(Pt(10f, 10f)))
        assertTrue(HitTest.strokeHit(dot, Pt(12f, 10f), radius = 5f))
        assertFalse(HitTest.strokeHit(dot, Pt(30f, 10f), radius = 5f))
    }

    @Test
    fun `shapeHit on a Line uses segment distance`() {
        val line = ShapeItem(ItemId(1), ShapeKind.Line, 0, 2f, Pt(0f, 0f), Pt(100f, 100f))
        assertTrue(HitTest.shapeHit(line, Pt(50f, 52f), radius = 5f))
        assertFalse(HitTest.shapeHit(line, Pt(50f, 90f), radius = 5f))
    }

    @Test
    fun `shapeHit on a Rect hits the border but not the hollow interior`() {
        val rect = ShapeItem(ItemId(1), ShapeKind.Rect, 0, 2f, Pt(0f, 0f), Pt(100f, 100f))
        assertTrue(HitTest.shapeHit(rect, Pt(50f, 2f), radius = 5f))     // 靠近上边框
        assertTrue(HitTest.shapeHit(rect, Pt(98f, 50f), radius = 5f))    // 靠近右边框
        assertFalse(HitTest.shapeHit(rect, Pt(50f, 50f), radius = 5f))   // 正中间，只描边所以不命中
        assertFalse(HitTest.shapeHit(rect, Pt(150f, 50f), radius = 5f))  // 完全在外
    }

    @Test
    fun `eraseHits returns every ink object under the cursor with its index`() {
        val doc = BoardDoc()
            .add(Stroke(ItemId(1), 0, 2f, listOf(Pt(0f, 0f), Pt(100f, 0f))))
            .add(Stroke(ItemId(2), 0, 2f, listOf(Pt(0f, 500f), Pt(100f, 500f))))
            .add(ShapeItem(ItemId(3), ShapeKind.Line, 0, 2f, Pt(0f, 2f), Pt(100f, 2f)))

        val hits = HitTest.eraseHits(doc, Pt(50f, 0f), radius = 5f)
        assertEquals(listOf(0, 2), hits.map { it.index })
        assertEquals(listOf(ItemId(1), ItemId(3)), hits.map { it.item.id })
    }

    @Test
    fun `shapeHit on a Rect thinner than 2 times radius treats the whole shape as a hit`() {
        // 高度 4，radius 12：内缩框 (2*radius=24 大于高度) 会退化为空集，
        // 此时整体应被视为命中，中点也算命中。
        val thinRect = ShapeItem(ItemId(1), ShapeKind.Rect, 0, 2f, Pt(0f, 0f), Pt(100f, 4f))
        assertTrue(HitTest.shapeHit(thinRect, Pt(50f, 2f), radius = 12f))
    }

    @Test
    fun `shapeHit on a zero-length Line behaves like a point without crashing`() {
        val point = ShapeItem(ItemId(1), ShapeKind.Line, 0, 2f, Pt(20f, 20f), Pt(20f, 20f))
        assertTrue(HitTest.shapeHit(point, Pt(22f, 20f), radius = 5f))
        assertFalse(HitTest.shapeHit(point, Pt(50f, 50f), radius = 5f))
    }
}
