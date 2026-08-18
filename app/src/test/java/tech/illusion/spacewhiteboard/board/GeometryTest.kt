package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {

    @Test
    fun `distanceTo is euclidean`() {
        assertEquals(5f, Pt(0f, 0f).distanceTo(Pt(3f, 4f)), 1e-4f)
    }

    @Test
    fun `BoardRect of normalizes a reversed drag`() {
        // 从右下往左上拖：a 在 b 的右下方
        val r = BoardRect.of(Pt(100f, 80f), Pt(20f, 10f))
        assertEquals(20f, r.left, 1e-4f)
        assertEquals(10f, r.top, 1e-4f)
        assertEquals(100f, r.right, 1e-4f)
        assertEquals(80f, r.bottom, 1e-4f)
        assertEquals(80f, r.width, 1e-4f)
        assertEquals(70f, r.height, 1e-4f)
    }

    @Test
    fun `ShapeItem bounds are normalized regardless of drag direction`() {
        val forward = ShapeItem(ItemId(1), ShapeKind.Rect, 0xFF112233.toInt(), 4f, Pt(10f, 10f), Pt(50f, 40f))
        val reversed = ShapeItem(ItemId(2), ShapeKind.Rect, 0xFF112233.toInt(), 4f, Pt(50f, 40f), Pt(10f, 10f))
        assertEquals(forward.bounds(), reversed.bounds())
    }

    @Test
    fun `Stroke bounds cover every sample point`() {
        val s = Stroke(ItemId(3), 0xFF000000.toInt(), 2f, listOf(Pt(5f, 9f), Pt(-3f, 20f), Pt(11f, 1f)))
        val b = s.bounds()
        assertEquals(-3f, b.left, 1e-4f)
        assertEquals(1f, b.top, 1e-4f)
        assertEquals(11f, b.right, 1e-4f)
        assertEquals(20f, b.bottom, 1e-4f)
    }

    @Test
    fun `single point stroke has zero-area bounds`() {
        val s = Stroke(ItemId(4), 0xFF000000.toInt(), 2f, listOf(Pt(7f, 7f)))
        assertEquals(0f, s.bounds().width, 1e-4f)
        assertEquals(0f, s.bounds().height, 1e-4f)
    }

    @Test
    fun `union grows to cover both rects`() {
        val u = BoardRect(0f, 0f, 10f, 10f).union(BoardRect(5f, -5f, 20f, 8f))
        assertEquals(BoardRect(0f, -5f, 20f, 10f), u)
    }

    @Test
    fun `contains and inflate`() {
        val r = BoardRect(0f, 0f, 10f, 10f)
        assertTrue(r.contains(Pt(5f, 5f)))
        assertFalse(r.contains(Pt(11f, 5f)))
        assertTrue(r.inflate(2f).contains(Pt(11f, 5f)))
    }
}
