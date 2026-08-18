package tech.illusion.spacewhiteboard.board

/** 一个对象连同它在文档中的原始索引 —— 撤销「删除」时靠 index 还原 z 序。 */
data class IndexedItem(val index: Int, val item: Item)

/** 不可变白板文档。每次修改返回新实例，便于撤销栈直接持有旧值。 */
data class BoardDoc(val items: List<Item> = emptyList()) {

    fun add(item: Item): BoardDoc = copy(items = items + item)

    fun removeIds(ids: Set<ItemId>): Pair<BoardDoc, List<IndexedItem>> {
        val removed = ArrayList<IndexedItem>()
        val kept = ArrayList<Item>(items.size)
        items.forEachIndexed { i, item ->
            if (item.id in ids) removed += IndexedItem(i, item) else kept += item
        }
        return copy(items = kept) to removed
    }

    /** 按索引升序逐个插回，从而精确还原被移除前的顺序。 */
    fun insertAll(entries: List<IndexedItem>): BoardDoc {
        val out = items.toMutableList()
        for (e in entries.sortedBy { it.index }) {
            out.add(e.index.coerceIn(0, out.size), e.item)
        }
        return copy(items = out)
    }

    fun replace(item: Item): BoardDoc =
        copy(items = items.map { if (it.id == item.id) item else it })

    fun contentBounds(): BoardRect? =
        items.map { it.bounds() }.reduceOrNull { a, b -> a.union(b) }
}
