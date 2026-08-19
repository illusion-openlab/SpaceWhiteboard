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

/**
 * 一批对象换成另一批 —— 局部擦除专用：一条笔迹被擦断后变成 N 条碎片。
 *
 * [added] 用 IndexedItem 而不是裸 Item，是因为碎片必须回到原对象所在的图层位置；
 * 追加到末尾会让被擦过的笔迹莫名跑到所有内容的最上面。
 *
 * **Index contract:** Both [removed] and [added] indices are positions in the document **as it will be
 * after the operation completes** — i.e. [added] indices are final-document positions computed with
 * cumulative accounting, not raw indices in the original or intermediate document.
 *
 * If a caller mistakenly passes original-document indices for [added], fragments from an earlier
 * group may be inserted in the wrong layer order. Example: starting from [A, B, C, D], if A becomes
 * two fragments [a0, a1] and C becomes [c0, c1], the correct indices are a0:0, a1:1, c0:3, c1:4
 * (final positions). Passing c0:2, c1:3 (original C position + offset) instead would produce
 * [a0, a1, c0, c1, B, D] — B is pushed after all of C's fragments. Callers must compute final indices
 * by accounting for all earlier removals and insertions.
 *
 * **Id uniqueness assumption:** [added] item ids must not collide with ids of surviving items
 * (items not in [removed]). If a collision occurs, [revertFrom] would incorrectly delete the
 * surviving item when removing [added] ids. This is unreachable in practice because fragment ids
 * come from a monotonic counter that never reissues, but it is a stated invariant.
 */
data class ReplaceItems(
    val removed: List<IndexedItem>,
    val added: List<IndexedItem>,
) : Op {
    override fun applyTo(doc: BoardDoc): BoardDoc =
        doc.removeIds(removed.map { it.item.id }.toSet()).first.insertAll(added)

    override fun revertFrom(doc: BoardDoc): BoardDoc =
        doc.removeIds(added.map { it.item.id }.toSet()).first.insertAll(removed)
}
