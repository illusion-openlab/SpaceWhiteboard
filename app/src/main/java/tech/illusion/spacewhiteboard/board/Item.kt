package tech.illusion.spacewhiteboard.board

@JvmInline
value class ItemId(val value: Long)

/** 画布上的一个可绘制对象。列表顺序即 z 序（后面的画在上面）。 */
sealed interface Item {
    val id: ItemId
    fun bounds(): BoardRect
}

/** 自由笔迹。points 至少一个元素（单点 = 一个墨点）。 */
data class Stroke(
    override val id: ItemId,
    val colorArgb: Int,
    val widthDp: Float,
    val points: List<Pt>,
) : Item {
    init { require(points.isNotEmpty()) { "Stroke must have at least one point" } }

    override fun bounds(): BoardRect {
        var r = BoardRect(points[0].x, points[0].y, points[0].x, points[0].y)
        for (p in points) r = r.union(BoardRect(p.x, p.y, p.x, p.y))
        return r
    }
}

enum class ShapeKind { Line, Rect, Ellipse }

/**
 * 形状由两个点完全确定：Line 是 a→b 线段，Rect / Ellipse 取 a、b 为对角包围盒。
 * 这个表示让移动与缩放退化成对两个点做仿射变换，也让形状对"采样频率未知"的风险天然免疫。
 */
data class ShapeItem(
    override val id: ItemId,
    val kind: ShapeKind,
    val colorArgb: Int,
    val widthDp: Float,
    val a: Pt,
    val b: Pt,
) : Item {
    override fun bounds(): BoardRect = BoardRect.of(a, b)
}
