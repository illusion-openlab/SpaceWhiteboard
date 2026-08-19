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

/**
 * 画布上的一段文字。
 *
 * [sizeBoard] 与 [wBoard] / [hBoard] 都是**画布单位**，不是 sp/dp —— 白板上的文字是画布内容，
 * 应当随缩放一起变换。用 sp 会让它在屏幕上恒定大小，放大画布时相对内容越来越小。
 *
 * [wBoard] / [hBoard] 是**创建时由 UI 层测量后存下来的**占位尺寸。`board/` 不能依赖 Compose，
 * 因此无法在这里重新测量。若渲染时的字体度量与创建时不同，存下的尺寸会有偏差；它只用于
 * [bounds]（进而 `contentBounds` 与 PNG 导出裁切），这点偏差可以接受。
 */
data class TextItem(
    override val id: ItemId,
    val text: String,
    val colorArgb: Int,
    val sizeBoard: Float,
    val anchor: Pt,
    val wBoard: Float,
    val hBoard: Float,
) : Item {
    override fun bounds(): BoardRect =
        BoardRect(anchor.x, anchor.y, anchor.x + wBoard, anchor.y + hBoard)
}
