package tech.illusion.spacewhiteboard.board

/**
 * 二次贝塞尔中点法（signature-pad 的标准做法）：
 * 以相邻采样点的中点为路径锚点、原采样点为控制点。
 *
 * 为什么需要它：PICO 的注视+捏合拖拽事件下发频率未知且可能很稀疏
 * （StockQuote 实测 2D ACTION_MOVE 每次手势约只触发一次）。
 * 直接把采样点连成折线会画出可见的硬折角；本方法让稀疏采样也输出连续曲线。
 */
object StrokeSmoothing {

    data class QuadSeg(val ctrl: Pt, val end: Pt)

    /** 绘制约定：moveTo(start) → 逐个 quadraticBezierTo(ctrl, end) → end != null 时 lineTo(end)。 */
    data class SmoothPath(val start: Pt, val quads: List<QuadSeg>, val end: Pt?)

    fun smooth(points: List<Pt>): SmoothPath? {
        if (points.isEmpty()) return null
        if (points.size == 1) return SmoothPath(points[0], emptyList(), null)
        if (points.size == 2) return SmoothPath(points[0], emptyList(), points[1])

        val quads = ArrayList<QuadSeg>(points.size - 2)
        for (i in 1 until points.size - 1) {
            val mid = Pt(
                (points[i].x + points[i + 1].x) / 2f,
                (points[i].y + points[i + 1].y) / 2f,
            )
            quads += QuadSeg(ctrl = points[i], end = mid)
        }
        return SmoothPath(points[0], quads, points[points.size - 1])
    }
}
