package tech.illusion.spacewhiteboard.board

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ShapeOutlineTest {

    /** 大多数用例不关心橡皮半径本身，只关心"间距不超过 TARGET_SPACING"这条历史行为——
     *  传入等于旧固定间距的半径（8f）让 `min(TARGET_SPACING, radiusBoard)` 退化成旧值，
     *  这样这些用例的数值期望不用因为签名变化而重新推导。 */
    private val LEGACY_SPACING_RADIUS = 8f

    private fun shape(kind: ShapeKind, a: Pt, b: Pt) =
        ShapeItem(ItemId(1), kind, 0xFF000000.toInt(), 2f, a, b)

    @Test
    fun `a mid-line erase actually removes sample points`() {
        // 1b-1 复审发现的 bug：旧实现只把直线采样成两个端点，橡皮圈扫过线的中段时，
        // HitTest 判定命中（到线段的连续距离 <= radius），但 StrokeSplit.split 找不到
        // 任何落在圈内的采样点，什么都删不掉——命中转 Stroke 却视觉不变。现在直线必须
        // 跟 Rect/Ellipse 一样按 `min(TARGET_SPACING, radiusBoard)` 细分。
        val a = Pt(0f, 0f)
        val b = Pt(100f, 0f)
        val radius = 4f
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Line, a, b), radius)

        assertTrue("直线必须采样出中间点，不能只有两个端点", pts.size > 2)

        val mid = Pt(50f, 0f)
        val survivors = pts.filterNot { it.distanceTo(mid) <= radius }
        assertTrue(
            "橡皮圈（半径 $radius）扫过线段中点时必须真的删掉至少一个采样点，" +
                "否则命中测试通过却什么都没擦掉",
            survivors.size < pts.size,
        )
    }

    @Test
    fun `line sampling spacing tightens with a smaller eraser radius`() {
        // 采样间距是 min(TARGET_SPACING, radiusBoard)：半径比 TARGET_SPACING 还小时，
        // 采样必须跟着变密，否则高倍缩放下（radiusBoard 可以小到个位数）橡皮圈仍然可能
        // 落在两个采样点之间的缝隙里，命中却擦不动任何东西。
        val a = Pt(0f, 0f)
        val b = Pt(120f, 0f)
        val loose = ShapeOutline.toPoints(shape(ShapeKind.Line, a, b), 8f)
        val tight = ShapeOutline.toPoints(shape(ShapeKind.Line, a, b), 2f)
        assertTrue("橡皮半径变小时直线采样应该变密", tight.size > loose.size)
    }

    @Test
    fun `a rect includes all four corners and closes back to the start`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Rect, Pt(0f, 0f), Pt(100f, 60f)), LEGACY_SPACING_RADIUS)
        val corners = listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(100f, 60f), Pt(0f, 60f))
        for (c in corners) {
            assertTrue("缺角 $c", pts.any { abs(it.x - c.x) < 0.01f && abs(it.y - c.y) < 0.01f })
        }
        assertEquals("首尾必须重合，否则擦除后会露出一道缝", pts.first(), pts.last())
    }

    @Test
    fun `a reversed-drag rect samples the same outline as a forward one`() {
        val fwd = ShapeOutline.toPoints(shape(ShapeKind.Rect, Pt(0f, 0f), Pt(100f, 60f)), LEGACY_SPACING_RADIUS)
        val rev = ShapeOutline.toPoints(shape(ShapeKind.Rect, Pt(100f, 60f), Pt(0f, 0f)), LEGACY_SPACING_RADIUS)
        assertEquals(fwd, rev)
    }

    @Test
    fun `every ellipse sample lies on the ellipse`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(200f, 100f)), LEGACY_SPACING_RADIUS)
        val cx = 100f; val cy = 50f; val rx = 100f; val ry = 50f
        for (p in pts) {
            val v = ((p.x - cx) / rx) * ((p.x - cx) / rx) + ((p.y - cy) / ry) * ((p.y - cy) / ry)
            assertTrue("点 $p 不在椭圆上 (v=$v)", abs(v - 1f) < 0.02f)
        }
        assertEquals("椭圆首尾必须重合", pts.first(), pts.last())
    }

    @Test
    fun `sampling density scales with perimeter and stays within bounds`() {
        val small = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(20f, 20f)), LEGACY_SPACING_RADIUS)
        val big = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(2000f, 1200f)), LEGACY_SPACING_RADIUS)
        assertTrue("大形状的采样点应多于小形状", big.size > small.size)
        assertTrue("小形状不应少于下限", small.size >= ShapeOutline.MIN_SAMPLES)
        assertTrue("大形状不应超过上限", big.size <= ShapeOutline.MAX_SAMPLES + 1)
    }

    @Test
    fun `consecutive ellipse samples are close enough to look smooth`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(600f, 400f)), LEGACY_SPACING_RADIUS)
        val maxGap = pts.zipWithNext().maxOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        assertTrue("相邻采样间距 $maxGap 过大，椭圆会显出多边形感", maxGap <= 12f)
    }

    @Test
    fun `a degenerate zero-size shape does not crash and yields at least two points`() {
        val pts = ShapeOutline.toPoints(shape(ShapeKind.Ellipse, Pt(5f, 5f), Pt(5f, 5f)), LEGACY_SPACING_RADIUS)
        assertTrue(pts.size >= 2)
    }

    @Test
    fun `a canvas-filling ellipse never gaps wider than the eraser radius at max zoom`() {
        // 1b-1 残留 bug：旧的 `MAX_SAMPLES = 256` 不是保护性上限，是会把大椭圆的采样间距
        // 钉死在几何（周长）决定的值上、彻底脱离 radiusBoard 的一个 clamp。铺满画布的椭圆
        // rx=1200/ry=800（Viewport.CANVAS_W/H 的一半）周长约 6346，旧 clamp 下平均间距
        // 约 24.8、最大间距约 29.5（AGENTS.md 旧记录的数字）——这里独立推导橡皮半径，
        // 不沿用那条记录，只是印证同一个量级。
        //
        // 橡皮半径的推导：CanvasSurface.eraserRadiusBoard(density, viewport) =
        // ERASER_RADIUS_PX(24) / (density * viewport.scale)。在 Viewport.MAX_SCALE 倍缩放、
        // 真机 320dpi（density=2.0，AGENTS.md「已验证的设备规格」记录的 PICO Swan 规格）下，
        // 这是 radiusBoard 能收紧到的下限：
        val density = 2.0f
        val radiusAtMaxZoom = 24f / (density * Viewport.MAX_SCALE)
        assertEquals("推导用的缩放上限跟 Viewport 实际的 MAX_SCALE 对不上了", 3f, radiusAtMaxZoom, 0.001f)

        val shape = shape(ShapeKind.Ellipse, Pt(0f, 0f), Pt(Viewport.CANVAS_W, Viewport.CANVAS_H))
        val pts = ShapeOutline.toPoints(shape, radiusAtMaxZoom)

        // 先确认这条测试真的踩进了旧 clamp 会绑定的区域——点数得远超旧的 256，
        // 否则这张测试根本没有覆盖到大椭圆场景，什么都没证明。
        assertTrue(
            "采样点数 ${pts.size} 没有远超旧 MAX_SAMPLES=256，这条测试没有真正覆盖大椭圆场景",
            pts.size > 256,
        )

        val maxGap = pts.zipWithNext().maxOf { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
        assertTrue(
            "相邻采样间距 $maxGap 超过了橡皮半径 $radiusAtMaxZoom：橡皮圈整圈扫过去可能没扫中" +
                "任何一个采样点，HitTest 命中却 StrokeSplit 什么都删不掉——命中转 Stroke 却视觉不变",
            maxGap <= radiusAtMaxZoom,
        )
    }
}
