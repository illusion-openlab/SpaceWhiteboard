package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardDocTest {

    private fun stroke(id: Long) = Stroke(ItemId(id), 0xFF000000.toInt(), 2f, listOf(Pt(id.toFloat(), 0f)))

    @Test
    fun `add appends to the end preserving z-order`() {
        val doc = BoardDoc().add(stroke(1)).add(stroke(2))
        assertEquals(listOf(ItemId(1), ItemId(2)), doc.items.map { it.id })
    }

    @Test
    fun `removeIds returns removed entries with their original indices`() {
        val doc = BoardDoc().add(stroke(1)).add(stroke(2)).add(stroke(3))
        val (after, removed) = doc.removeIds(setOf(ItemId(1), ItemId(3)))
        assertEquals(listOf(ItemId(2)), after.items.map { it.id })
        assertEquals(listOf(0, 2), removed.map { it.index })
    }

    @Test
    fun `insertAll after removeIds restores the exact original z-order`() {
        // 这是撤销「擦除一批对象」能正确还原图层顺序的关键性质
        val original = BoardDoc().add(stroke(1)).add(stroke(2)).add(stroke(3)).add(stroke(4))
        val (after, removed) = original.removeIds(setOf(ItemId(2), ItemId(3)))
        val restored = after.insertAll(removed)
        assertEquals(original.items.map { it.id }, restored.items.map { it.id })
    }

    @Test
    fun `replace swaps the item with the same id and keeps its position`() {
        val doc = BoardDoc().add(stroke(1)).add(stroke(2)).add(stroke(3))
        val moved = Stroke(ItemId(2), 0xFFFF0000.toInt(), 9f, listOf(Pt(99f, 99f)))
        val out = doc.replace(moved)
        assertEquals(listOf(ItemId(1), ItemId(2), ItemId(3)), out.items.map { it.id })
        assertEquals(9f, (out.items[1] as Stroke).widthDp, 1e-4f)
    }

    @Test
    fun `contentBounds is null for an empty document`() {
        assertNull(BoardDoc().contentBounds())
    }

    @Test
    fun `contentBounds unions every item`() {
        val doc = BoardDoc()
            .add(Stroke(ItemId(1), 0, 1f, listOf(Pt(0f, 0f))))
            .add(ShapeItem(ItemId(2), ShapeKind.Rect, 0, 1f, Pt(30f, 20f), Pt(10f, -5f)))
        assertEquals(BoardRect(0f, -5f, 30f, 20f), doc.contentBounds())
    }

    @Test
    fun `insertAll after removeIds restores z-order for non-contiguous removals`() {
        // Test the load-bearing property with non-adjacent indices: remove [0, 2, 4] from [1,2,3,4,5]
        val original = BoardDoc()
            .add(stroke(1)).add(stroke(2)).add(stroke(3))
            .add(stroke(4)).add(stroke(5))
        val (after, removed) = original.removeIds(setOf(ItemId(1), ItemId(3), ItemId(5)))
        // Intermediate state should have only items at original indices 1,3 (ids 2,4)
        assertEquals(listOf(ItemId(2), ItemId(4)), after.items.map { it.id })
        // Restoration must put items back at their original indices
        val restored = after.insertAll(removed)
        assertEquals(original.items.map { it.id }, restored.items.map { it.id })
    }
}
