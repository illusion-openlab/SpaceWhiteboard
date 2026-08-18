package tech.illusion.spacewhiteboard.board

/**
 * 一次可撤销的文档变更。
 * 画一笔、擦一批、放对象、移动、缩放、删除、清空 —— 全部归约成这三种 Op，共用一个撤销栈。
 */
sealed interface Op {
    fun applyTo(doc: BoardDoc): BoardDoc
    fun revertFrom(doc: BoardDoc): BoardDoc
}

data class AddItem(val item: Item) : Op {
    override fun applyTo(doc: BoardDoc): BoardDoc = doc.add(item)
    override fun revertFrom(doc: BoardDoc): BoardDoc = doc.removeIds(setOf(item.id)).first
}

/** 一次擦除可能命中多个对象；整批作为一个 Op，否则擦一下要撤销十几次。 */
data class RemoveItems(val entries: List<IndexedItem>) : Op {
    override fun applyTo(doc: BoardDoc): BoardDoc =
        doc.removeIds(entries.map { it.item.id }.toSet()).first
    override fun revertFrom(doc: BoardDoc): BoardDoc = doc.insertAll(entries)
}

/** 移动/缩放。拖拽结束时才入栈一次，不是每次采样入栈。 */
data class TransformItem(val before: Item, val after: Item) : Op {
    override fun applyTo(doc: BoardDoc): BoardDoc = doc.replace(after)
    override fun revertFrom(doc: BoardDoc): BoardDoc = doc.replace(before)
}
