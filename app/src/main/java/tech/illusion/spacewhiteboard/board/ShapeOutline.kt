package tech.illusion.spacewhiteboard.board

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 把 [ShapeItem] 的轮廓采样成点列，供橡皮把形状转成 [Stroke] 后走同一套切分。
 *
 * 采样密度自适应形状大小：太疏椭圆会显出多边形感，太密则一次擦除产生大量碎片点。
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

    /**
     * 椭圆采样点数的上限。曾经是 256——那不是保护性上限，是会把大椭圆的采样间距钉死在
     * 几何决定的值上、彻底脱离 `radiusBoard` 的一个 bug（见类文档）。
     *
     * 现在的值来自最坏场景的正向推导：铺满画布的椭圆 rx=1200、ry=800（[Viewport.CANVAS_W]/
     * [Viewport.CANVAS_H] 的一半），在 [Viewport.MAX_SCALE]=4、真机 density=2.0（`AGENTS.md`
     * 记录的 PICO Swan 320dpi 规格）下，`eraserRadiusBoard` = 24 / (2.0×4.0) = 3 画布单位，
     * 也就是 `spacing` 能收紧到的下限。[ellipsePoints] 里 `n` 现在按 `max(rx, ry)`（等角度采样
     * 里弧长变化率 `ds/dθ` 的最大值，见该函数内注释）反推，取
     * `n = ceil(2π·max(rx,ry) / spacing) = ceil(2π·1200 / 3) = 2514`。
     * 只要 `MAX_SAMPLES` 不小于这个数，clamp 就不可能在 spacing 已经追上 radius 之前生效——
     * 也就是"clamp 不再可能饿死橡皮"这条不变量。4096 在此基础上留了约 1.6× 余量。
     *
     * 下游没有点数假设需要与之对齐：[StrokeSplit.split] 是纯 O(n) 顺序扫描；
     * `BoardDoc.contentBounds()`/`ShapeItem.bounds()` 用的是 `a`/`b` 两点算包围盒，
     * 从不读采样点列；`BoardCodec` 把 `Stroke.points` 原样按空格拼接成一行文本，没有
     * 数量上限；PNG 导出（`BoardRenderer`/`drawBoard`）把点丢给 `Path`，同样没有容量假设。
     * 铺满画布的 Rect 在同样缩放下本就会产出约 2666 个未被 clamp 的采样点
     * （周长 8000 / spacing 3），4096 这个量级不是新引入的负担。
     */
    const val MAX_SAMPLES = 4096

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

    /**
     * 等角度采样（`k/n` 平分整圆）不等于等弧长采样：弧长变化率
     * `ds/dθ = sqrt(rx²sin²θ + ry²cos²θ)`，在长轴方向（θ=π/2 或 3π/2）取到最大值
     * `max(rx, ry)`，在短轴方向取到最小值 `min(rx, ry)`。早先按周长反推 `n`
     * （`n = perimeter / spacing`，等价于用平均弧长变化率 `perimeter / 2π`）只保证
     * *平均*间距等于 `spacing`——canvas-filling 椭圆 rx=1200/ry=800 这个长宽比下，
     * 长轴附近实际弧长比平均值还大约 1.19 倍（`1200 / (perimeter/2π)`），刚好可能把
     * 最坏那一段的间距顶到超过橡皮半径，又踩回"命中却擦不掉"的坑——只是从"整体被
     * clamp 摊薄"降级成了"长宽比悬殊时长轴附近偶尔漏一点"，仍然是同一类 bug。
     *
     * 换成按 `max(rx, ry)` 反推：只要 `n >= 2π·max(rx,ry) / spacing`，每一步角度
     * `dθ = 2π/n` 对应的弧长上界 `dθ · max(ds/dθ) = dθ · max(rx,ry) <= spacing`，
     * 而弦长（真正拿去跟橡皮半径比的东西）不会超过弧长——这对任意长宽比都是严格保证，
     * 不再是"平均够、局部未必够"。
     */
    private fun ellipsePoints(r: BoardRect, spacing: Float): List<Pt> {
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val rx = r.width / 2f
        val ry = r.height / 2f
        val n = ceil(2.0 * PI * max(rx, ry) / spacing).toInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val out = ArrayList<Pt>(n + 1)
        for (k in 0..n) {
            val a = 2.0 * PI * k / n
            out.add(Pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat()))
        }
        return out
    }
}
