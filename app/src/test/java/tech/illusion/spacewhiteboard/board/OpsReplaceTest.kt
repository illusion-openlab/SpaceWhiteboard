package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Test

class OpsReplaceTest {

    private fun s(id: Long, x: Float) =
        Stroke(ItemId(id), 0xFF000000.toInt(), 2f, listOf(Pt(x, 0f), Pt(x + 1f, 0f)))

    @Test
    fun `applyTo swaps the original for its fragments at the same layer position`() {
        val doc = BoardDoc().add(s(1, 0f)).add(s(2, 10f)).add(s(3, 20f))
        val op = ReplaceItems(
            removed = listOf(IndexedItem(1, doc.items[1])),
            added = listOf(IndexedItem(1, s(10, 10f)), IndexedItem(2, s(11, 14f))),
        )
        val out = op.applyTo(doc)
        // 碎片必须落在原对象的位置，不能跑到末尾
        assertEquals(listOf(ItemId(1), ItemId(10), ItemId(11), ItemId(3)), out.items.map { it.id })
    }

    @Test
    fun `revertFrom restores the original document exactly`() {
        val doc = BoardDoc().add(s(1, 0f)).add(s(2, 10f)).add(s(3, 20f))
        val op = ReplaceItems(
            removed = listOf(IndexedItem(1, doc.items[1])),
            added = listOf(IndexedItem(1, s(10, 10f)), IndexedItem(2, s(11, 14f))),
        )
        assertEquals(doc.items.map { it.id }, op.revertFrom(op.applyTo(doc)).items.map { it.id })
    }

    @Test
    fun `an erase that fully consumes an object adds nothing back`() {
        val doc = BoardDoc().add(s(1, 0f)).add(s(2, 10f))
        val op = ReplaceItems(removed = listOf(IndexedItem(0, doc.items[0])), added = emptyList())
        val out = op.applyTo(doc)
        assertEquals(listOf(ItemId(2)), out.items.map { it.id })
        assertEquals(doc.items.map { it.id }, op.revertFrom(out).items.map { it.id })
    }

    @Test
    fun `replacing several originals at once keeps every layer position`() {
        val doc = BoardDoc().add(s(1, 0f)).add(s(2, 10f)).add(s(3, 20f)).add(s(4, 30f))
        val op = ReplaceItems(
            removed = listOf(IndexedItem(0, doc.items[0]), IndexedItem(2, doc.items[2])),
            added = listOf(IndexedItem(0, s(10, 0f)), IndexedItem(2, s(12, 20f))),
        )
        val out = op.applyTo(doc)
        assertEquals(listOf(ItemId(10), ItemId(2), ItemId(12), ItemId(4)), out.items.map { it.id })
        assertEquals(doc.items.map { it.id }, op.revertFrom(out).items.map { it.id })
    }

    @Test
    fun `multiple replacements with compounding fragments use final-document indices not original indices`() {
        // [A, B, C, D] → A becomes [a0, a1], C becomes [c0, c1] → [a0, a1, B, c0, c1, D]
        val doc = BoardDoc().add(s(1, 0f)).add(s(2, 10f)).add(s(3, 20f)).add(s(4, 30f))
        val op = ReplaceItems(
            removed = listOf(IndexedItem(0, doc.items[0]), IndexedItem(2, doc.items[2])),
            // a0, a1 go at indices 0, 1 (final positions)
            // c0, c1 go at indices 3, 4 (final positions, NOT 2, 3)
            // This tests that added indices are final-document positions with cumulative accounting
            added = listOf(
                IndexedItem(0, s(10, 0f)), IndexedItem(1, s(11, 1f)),  // a0, a1
                IndexedItem(3, s(12, 20f)), IndexedItem(4, s(13, 21f)) // c0, c1
            ),
        )
        val out = op.applyTo(doc)
        // Must be [a0, a1, B, c0, c1, D], not [a0, a1, c0, B, c1, D]
        assertEquals(listOf(ItemId(10), ItemId(11), ItemId(2), ItemId(12), ItemId(13), ItemId(4)), out.items.map { it.id })
        assertEquals(doc.items.map { it.id }, op.revertFrom(out).items.map { it.id })
    }
}
