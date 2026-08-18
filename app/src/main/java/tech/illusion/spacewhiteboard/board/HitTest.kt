package tech.illusion.spacewhiteboard.board

object HitTest {

    /** 点 p 到线段 ab 的最短距离；垂足落在段外时退化为到端点的距离。 */
    fun distanceToSegment(p: Pt, a: Pt, b: Pt): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0f) return p.distanceTo(a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f)
        return p.distanceTo(Pt(a.x + t * dx, a.y + t * dy))
    }

    fun strokeHit(s: Stroke, p: Pt, radius: Float): Boolean {
        if (s.points.size == 1) return p.distanceTo(s.points[0]) <= radius
        for (i in 0 until s.points.size - 1) {
            if (distanceToSegment(p, s.points[i], s.points[i + 1]) <= radius) return true
        }
        return false
    }

    /**
     * 形状只描边，所以 Rect / Ellipse 判定的是"靠近边框"：
     * 落在外扩 radius 的框内，且不落在内缩 radius 的框内。
     * 对于比 2*radius 还窄的细形状，内缩框会退化成空集，此时整体视为命中 —— 这正是期望行为。
     */
    fun shapeHit(sh: ShapeItem, p: Pt, radius: Float): Boolean = when (sh.kind) {
        ShapeKind.Line -> distanceToSegment(p, sh.a, sh.b) <= radius
        ShapeKind.Rect, ShapeKind.Ellipse -> {
            val r = sh.bounds()
            r.inflate(radius).contains(p) && !r.inflate(-radius).contains(p)
        }
    }

    /**
     * 橡皮命中：只作用于"墨水类"（Stroke / ShapeItem）。
     * 文字与图片不被橡皮擦影响，只能通过选择工具删除（阶段 1b）。
     *
     * 这里的 when 是穷尽的；阶段 1b 加入 TextItem / ImageItem 时编译器会强制在此显式处理，
     * 这是有意的 —— 避免新类型被默认分支静默吞掉。
     */
    fun eraseHits(doc: BoardDoc, p: Pt, radius: Float): List<IndexedItem> =
        doc.items.mapIndexedNotNull { i, item ->
            val hit = when (item) {
                is Stroke -> strokeHit(item, p, radius)
                is ShapeItem -> shapeHit(item, p, radius)
            }
            if (hit) IndexedItem(i, item) else null
        }
}
