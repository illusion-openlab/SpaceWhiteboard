package tech.illusion.spacewhiteboard.board

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 把 [ShapeItem] 的轮廓采样成点列，供橡皮把形状转成 [Stroke] 后走同一套切分。
 *
 * 采样密度按周长自适应：太疏椭圆会显出多边形感，太密则一次擦除产生大量碎片点。
 *
 * **采样间距必须跟随橡皮半径，不能是固定值**（1b-1 复审发现的临界 bug）：
 * [HitTest.strokeHit]/[HitTest.shapeHit] 判定的是到连续几何（线段）的距离，但
 * [StrokeSplit.split] 删除的是离散采样点——橡皮圆圈扫过几何但没扫中任何一个采样点时，
 * 命中测试仍然会 fire，切分却什么都删不掉，整个对象照样被"命中转 Stroke"一次（无谓的
 * 撤销记录 + 视觉上什么都没变）。旧实现固定间距 8 画布单位，而橡皮半径是屏幕空间常量
 * 换算过来的（`eraserRadiusBoard`），在高倍缩放下可以小到 3 画布单位——比采样间距还密，
 * 缝隙必然大于橡皮直径，扫过去必然漏擦。这里把间距钉在 `min(TARGET_SPACING, radiusBoard)`：
 * 采样永远至少跟橡皮一样密，橡皮画多大的圈，圈内必然至少有一个采样点。
 *
 * 这是**过渡修法**，不是根治：根治方案是让 [StrokeSplit] 直接对线段切分、在几何交点处断开，
 * 那样无论采样多稀疏都保证咬得动。之所以不在这轮做，见 `AGENTS.md` 里记录的已知残留问题——
 * 手绘笔迹本身按 90Hz 采样，天然致密，不会触发这个缝隙，所以现在的过渡修法已经堵住了
 * 唯一可复现的路径（形状轮廓采样 + 高倍缩放）。
 */
object ShapeOutline {

    /** 目标采样间距上限（画布单位）。取值来自「相邻点间距不超过 12 单位看起来才是曲线」这条测试约束。
     *  实际采样间距是 `min(TARGET_SPACING, radiusBoard)`——橡皮半径比这个值还小时，间距跟着收紧。 */
    private const val TARGET_SPACING = 8f
    const val MIN_SAMPLES = 16
    const val MAX_SAMPLES = 256

    /**
     * @param radiusBoard 本次擦除操作的橡皮半径（画布单位）。采样间距永远不超过它，
     *   保证切分时橡皮圈内至少能扫到一个采样点——见类文档。
     */
    fun toPoints(shape: ShapeItem, radiusBoard: Float): List<Pt> {
        // 半径异常（<=0，理论上不会从 eraserRadiusBoard 产生）时退化为一个极小间距下限，
        // 只是避免除零/死循环，不代表这是一个被支持的输入。
        val spacing = min(TARGET_SPACING, radiusBoard).coerceAtLeast(0.01f)
        return when (shape.kind) {
            // 直线跟 Rect 的一条边一样，按间距细分，不再只取两个端点——只取端点时，
            // 端点之间的橡皮擦除既不删除任何采样点（视觉不变却整体转 Stroke），又可能因为
            // 擦到端点附近让 MIN_RUN 判定整条线消失，两者都不是"局部擦除"应有的行为。
            ShapeKind.Line -> linePoints(shape.a, shape.b, spacing)
            ShapeKind.Rect -> rectPoints(BoardRect.of(shape.a, shape.b), spacing)
            ShapeKind.Ellipse -> ellipsePoints(BoardRect.of(shape.a, shape.b), spacing)
        }
    }

    /** 直线按间距细分成一串点，含两端点。 */
    private fun linePoints(a: Pt, b: Pt, spacing: Float): List<Pt> {
        val len = a.distanceTo(b)
        val n = max(1, (len / spacing).roundToInt())
        val out = ArrayList<Pt>(n + 1)
        for (k in 0..n) {
            val t = k.toFloat() / n
            out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
        }
        return out
    }

    /** 四边各自按间距细分，四个角一定落在采样点上，首尾重合以免擦除后露缝。 */
    private fun rectPoints(r: BoardRect, spacing: Float): List<Pt> {
        val corners = listOf(
            Pt(r.left, r.top), Pt(r.right, r.top),
            Pt(r.right, r.bottom), Pt(r.left, r.bottom),
        )
        val out = ArrayList<Pt>()
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val len = a.distanceTo(b)
            val n = max(1, (len / spacing).roundToInt())
            for (k in 0 until n) {
                val t = k.toFloat() / n
                out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        out.add(corners[0])   // 闭合
        return out
    }

    private fun ellipsePoints(r: BoardRect, spacing: Float): List<Pt> {
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val rx = r.width / 2f
        val ry = r.height / 2f
        val perimeter = (PI * (3f * (rx + ry) - kotlin.math.sqrt((3f * rx + ry) * (rx + 3f * ry)))).toFloat()
        val n = (perimeter / spacing).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val out = ArrayList<Pt>(n + 1)
        for (k in 0..n) {
            val a = 2.0 * PI * k / n
            out.add(Pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat()))
        }
        return out
    }
}
