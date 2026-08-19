package tech.illusion.spacewhiteboard.board

/**
 * 撤销/重做栈。不持有文档本身 —— 调用方传入当前文档、拿到新文档，
 * 这样文档保持不可变，栈也不必关心谁在持有它。
 */
class UndoStack(private val maxDepth: Int = DEFAULT_DEPTH) {

    private val undos = ArrayDeque<Op>()
    private val redos = ArrayDeque<Op>()

    val canUndo: Boolean get() = undos.isNotEmpty()
    val canRedo: Boolean get() = redos.isNotEmpty()

    fun push(op: Op) {
        undos.addLast(op)
        if (undos.size > maxDepth) undos.removeFirst()
        redos.clear()
    }

    fun undo(doc: BoardDoc): BoardDoc {
        val op = undos.removeLastOrNull() ?: return doc
        redos.addLast(op)
        return op.revertFrom(doc)
    }

    fun redo(doc: BoardDoc): BoardDoc {
        val op = redos.removeLastOrNull() ?: return doc
        undos.addLast(op)
        return op.applyTo(doc)
    }

    fun clear() {
        undos.clear()
        redos.clear()
    }

    companion object { const val DEFAULT_DEPTH = 50 }
}
