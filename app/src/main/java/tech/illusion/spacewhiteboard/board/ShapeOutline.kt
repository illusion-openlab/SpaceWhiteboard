package tech.illusion.spacewhiteboard.board

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 把 [ShapeItem] 的轮廓采样成点列，供橡皮把形状转成 [Stroke] 后走同一套切分。
 *
 * 采样密度按周长自适应：太疏椭圆会显出多边形感，太密则一次擦除产生大量碎片点。
 */
object ShapeOutline {

    /** 目标采样间距（画布单位）。取值来自「相邻点间距不超过 12 单位看起来才是曲线」这条测试约束。 */
    private const val TARGET_SPACING = 8f
    const val MIN_SAMPLES = 16
    const val MAX_SAMPLES = 256

    fun toPoints(shape: ShapeItem): List<Pt> = when (shape.kind) {
        // 直线本来就由两个点确定，采样它没有意义
        ShapeKind.Line -> listOf(shape.a, shape.b)
        ShapeKind.Rect -> rectPoints(BoardRect.of(shape.a, shape.b))
        ShapeKind.Ellipse -> ellipsePoints(BoardRect.of(shape.a, shape.b))
    }

    /** 四边各自按间距细分，四个角一定落在采样点上，首尾重合以免擦除后露缝。 */
    private fun rectPoints(r: BoardRect): List<Pt> {
        val corners = listOf(
            Pt(r.left, r.top), Pt(r.right, r.top),
            Pt(r.right, r.bottom), Pt(r.left, r.bottom),
        )
        val out = ArrayList<Pt>()
        for (i in corners.indices) {
            val a = corners[i]
            val b = corners[(i + 1) % corners.size]
            val len = a.distanceTo(b)
            val n = max(1, (len / TARGET_SPACING).roundToInt())
            for (k in 0 until n) {
                val t = k.toFloat() / n
                out.add(Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        out.add(corners[0])   // 闭合
        return out
    }

    private fun ellipsePoints(r: BoardRect): List<Pt> {
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        val rx = r.width / 2f
        val ry = r.height / 2f
        val perimeter = (PI * (3f * (rx + ry) - kotlin.math.sqrt((3f * rx + ry) * (rx + 3f * ry)))).toFloat()
        val n = (perimeter / TARGET_SPACING).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val out = ArrayList<Pt>(n + 1)
        for (k in 0..n) {
            val a = 2.0 * PI * k / n
            out.add(Pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat()))
        }
        return out
    }
}
