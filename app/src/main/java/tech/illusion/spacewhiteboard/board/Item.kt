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
 * [sizeBoard] 与 [wBoard] / [hBoard] / [eBoard] 都是**画布单位**，不是 sp/dp —— 白板上的文字是
 * 画布内容，应当随缩放一起变换。用 sp 会让它在屏幕上恒定大小，放大画布时相对内容越来越小。
 *
 * **[wBoard] 是换行约束，[eBoard] 是测量出的墨迹宽度，两者不是同一个数**（1b-2 复审修复项）：
 * [wBoard] 是喂给 `measureTextLayout` 的 `Constraints.maxWidth`——多长该换行；[eBoard] 是那次
 * 测量算出的 `TextLayoutResult.size.width`——这段文字实际画出来有多宽。旧版把 [wBoard]（一个
 * 换行约束常量，与文字本身长度无关）直接当作 [bounds] 的宽度用，于是一个四字短文本的导出裁切
 * 会带着约束常量那么宽的空白纸张，而不是文字本体的宽度。[bounds] 现在读 [eBoard]，短文本的
 * 包围盒因此正确地贴着墨迹本身，不再被换行约束撑大。
 *
 * [wBoard] / [hBoard] / [eBoard] 都是**创建时由 UI 层测量后存下来的**占位尺寸。`board/` 不能依赖
 * Compose，因此无法在这里重新测量。若渲染时的字体度量与创建时不同，存下的尺寸会有偏差；它们
 * 只用于 [bounds]（进而 `contentBounds` 与 PNG 导出裁切），这点偏差可以接受。
 */
data class TextItem(
    override val id: ItemId,
    val text: String,
    val colorArgb: Int,
    val sizeBoard: Float,
    val anchor: Pt,
    val wBoard: Float,
    val hBoard: Float,
    val eBoard: Float,
) : Item {
    override fun bounds(): BoardRect =
        BoardRect(anchor.x, anchor.y, anchor.x + eBoard, anchor.y + hBoard)
}
