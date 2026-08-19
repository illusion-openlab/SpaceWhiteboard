package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private fun stroke(id: Long) = Stroke(ItemId(id), 0xFF000000.toInt(), 2f, listOf(Pt(id.toFloat(), 0f)))

    @Test
    fun `undo removes an added item and redo puts it back`() {
        var doc = BoardDoc()
        val stack = UndoStack()
        val op = AddItem(stroke(1))
        doc = op.applyTo(doc); stack.push(op)
        assertEquals(1, doc.items.size)

        doc = stack.undo(doc)
        assertEquals(0, doc.items.size)

        doc = stack.redo(doc)
        assertEquals(listOf(ItemId(1)), doc.items.map { it.id })
    }

    @Test
    fun `undoing a batch erase restores every item at its original index`() {
        var doc = BoardDoc().add(stroke(1)).add(stroke(2)).add(stroke(3)).add(stroke(4))
        val (_, removed) = doc.removeIds(setOf(ItemId(2), ItemId(3)))
        val stack = UndoStack()
        val op = RemoveItems(removed)
        doc = op.applyTo(doc); stack.push(op)
        assertEquals(listOf(ItemId(1), ItemId(4)), doc.items.map { it.id })

        doc = stack.undo(doc)
        assertEquals(listOf(ItemId(1), ItemId(2), ItemId(3), ItemId(4)), doc.items.map { it.id })
    }

    @Test
    fun `transform undo restores the previous value`() {
        val before = stroke(1)
        val after = Stroke(ItemId(1), 0xFFFF0000.toInt(), 12f, listOf(Pt(50f, 50f)))
        var doc = BoardDoc().add(before)
        val stack = UndoStack()
        val op = TransformItem(before, after)
        doc = op.applyTo(doc); stack.push(op)
        assertEquals(12f, (doc.items[0] as Stroke).widthDp, 1e-4f)

        doc = stack.undo(doc)
        assertEquals(2f, (doc.items[0] as Stroke).widthDp, 1e-4f)
    }

    @Test
    fun `pushing a new op clears the redo stack`() {
        var doc = BoardDoc()
        val stack = UndoStack()
        val a = AddItem(stroke(1))
        doc = a.applyTo(doc); stack.push(a)
        doc = stack.undo(doc)
        assertTrue(stack.canRedo)

        val b = AddItem(stroke(2))
        doc = b.applyTo(doc); stack.push(b)
        assertFalse(stack.canRedo)
    }

    @Test
    fun `stack depth is capped and drops the oldest op`() {
        var doc = BoardDoc()
        val stack = UndoStack(maxDepth = 3)
        for (i in 1L..5L) {
            val op = AddItem(stroke(i))
            doc = op.applyTo(doc); stack.push(op)
        }
        assertEquals(5, doc.items.size)

        // 只能撤销最近 3 次
        repeat(3) { doc = stack.undo(doc) }
        assertFalse(stack.canUndo)
        assertEquals(listOf(ItemId(1), ItemId(2)), doc.items.map { it.id })
    }

    @Test
    fun `undo and redo on an empty stack are no-ops`() {
        val doc = BoardDoc().add(stroke(1))
        val stack = UndoStack()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertEquals(doc, stack.undo(doc))
        assertEquals(doc, stack.redo(doc))
    }

    @Test
    fun `full round trip over a mixed op sequence returns to start and back`() {
        val start = BoardDoc().add(stroke(1)).add(stroke(2)).add(stroke(3)).add(stroke(4))
        var doc = start
        val stack = UndoStack()

        val add = AddItem(stroke(5))
        doc = add.applyTo(doc); stack.push(add)

        val (_, removed) = doc.removeIds(setOf(ItemId(2), ItemId(4)))
        val remove = RemoveItems(removed)
        doc = remove.applyTo(doc); stack.push(remove)

        val before = stroke(1)
        val after = Stroke(ItemId(1), 0xFF00FF00.toInt(), 9f, listOf(Pt(9f, 9f)))
        val transform = TransformItem(before, after)
        doc = transform.applyTo(doc); stack.push(transform)

        val afterThirdOp = doc

        doc = stack.undo(doc)
        doc = stack.undo(doc)
        doc = stack.undo(doc)
        assertEquals(start, doc)

        doc = stack.redo(doc)
        doc = stack.redo(doc)
        doc = stack.redo(doc)
        assertEquals(afterThirdOp, doc)
    }

    @Test
    fun `undo then redo then undo returns to the undone state`() {
        var doc = BoardDoc()
        val stack = UndoStack()
        val a = AddItem(stroke(1))
        doc = a.applyTo(doc); stack.push(a)
        val b = AddItem(stroke(2))
        doc = b.applyTo(doc); stack.push(b)

        doc = stack.undo(doc)
        val afterFirstUndo = doc
        assertEquals(listOf(ItemId(1)), doc.items.map { it.id })

        doc = stack.redo(doc)
        assertEquals(listOf(ItemId(1), ItemId(2)), doc.items.map { it.id })

        doc = stack.undo(doc)
        assertEquals(afterFirstUndo, doc)
        assertEquals(listOf(ItemId(1)), doc.items.map { it.id })
    }
}
