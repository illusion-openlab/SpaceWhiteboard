package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import tech.illusion.spacewhiteboard.board.*
import kotlin.math.roundToInt

/**
 * `canvas.paper` 圆角，屏幕空间固定 dp（契约 §2.2），不随 `viewport.scale` 缩放——见 [drawPaper]。
 * 唯一定义处：[drawPaper] 用它画纸面，[clipToPaper] 用它换算出的 board-space 半径裁墨迹，
 * 两处永远读同一个常量，纸面圆多少墨迹就裁多少，不可能再出现「方裁圆纸」的缝隙（Task 20 修复项）。
 */
private const val PAPER_CORNER_RADIUS_DP = 6f

/**
 * 屏幕渲染与 PNG 导出共用的唯一绘制实现。
 * 单独成文件就是为了防止两条路径悄悄分叉 —— 导出图与屏幕所见必须一致。
 *
 * pathCache 以 ItemId 为键缓存 board units 空间的 Path。
 * 每帧重建数百个 Path 才是真正会掉帧的地方，drawPath 本身不是瓶颈。
 */

/**
 * `canvas.paper`：把 2400×1600 的逻辑画布画成一个有边界的圆角矩形，四周露出根/视口背景，
 * 让平移与缩放有一个看得见的参照物（契约 §2.2 `canvas.paper`；§6 通用检查第 4 条「纸面之外是根底色」）。
 * 只在屏幕渲染路径调用——它是 chrome，不是文档内容，PNG 导出不应该把它烤进图里，
 * 所以没有并进 [drawBoard]。
 *
 * 圆角固定 6dp（屏幕空间，不随 `viewport.scale` 缩放）：纸面的角是 UI 卡片语言，
 * 不是画布内容的一部分，缩放画布不应该让边角跟着变圆或变尖。取值见 [PAPER_CORNER_RADIUS_DP]。
 */
fun DrawScope.drawPaper(viewport: Viewport, density: Float, color: Color) {
    val leftPx = viewport.toScreenX(0f) * density
    val topPx = viewport.toScreenY(0f) * density
    val widthPx = Viewport.CANVAS_W * viewport.scale * density
    val heightPx = Viewport.CANVAS_H * viewport.scale * density
    drawRoundRect(
        color = color,
        topLeft = Offset(leftPx, topPx),
        size = Size(widthPx, heightPx),
        cornerRadius = CornerRadius(PAPER_CORNER_RADIUS_DP * density),
    )
}

/**
 * 把墨迹绘制裁到纸面矩形内（board 坐标 `(0,0)`–`(CANVAS_W,CANVAS_H)`）。
 *
 * 平移/缩放不会限制用户在哪里落笔——一旦视口挪开、画布之外的根背景露出来，
 * `toBoard()` 换算出的坐标就会落在 `[0,2400]×[0,1600]` 之外，笔画/形状会被画在
 * 纸面以外，浮在根背景上、看不出「纸面之外是根底色」（契约 §6 通用检查第 4 条）。
 *
 * 修法是**裁剪渲染，不裁剪坐标**：已提交数据、`livePoints`、`liveShapeStart/End`
 * 里存的仍然是用户实际画的原始 board 坐标，一根斜着穿出纸面边界的线不会被夹平在
 * 边界上变形——夹坐标会把「画出去的那一段」直接钳死在边线上，永久损坏这一笔的几何。
 * 裁剪只影响这一帧怎么画，不改一个像素的已存数据。
 *
 * 之所以传 board 坐标的矩形而不是屏幕像素矩形：调用点全部在 `withTransform` 内部
 * （已经把 board 坐标系变换到屏幕坐标系），裁剪在当前坐标系下生效，此处当前坐标系就是
 * board units，直接传 `(0, 0, CANVAS_W, CANVAS_H)` 即可，不需要再乘 density/scale
 * 换算——这也是导出路径（Task 18 `BoardRenderer`，用内容包围盒构造的另一个 `Viewport`
 * 调同一批函数）会自动裁在同一条边界上的原因：裁剪矩形写在 board 坐标里，不依赖调用方的
 * viewport/density 取值，屏幕渲染和导出用的是同一份变换 + 同一份裁剪，两条路径不可能
 * 裁出不同的结果。
 *
 * 圆角改法（Task 20 修复项）：[drawPaper] 画的纸面是圆角矩形，之前这里用 `clipRect` 裁一个
 * 直角矩形，四个角上的墨迹于是会露出纸面之外——裁剪比纸面更"方"。现在改成 `clipPath` 配一个
 * `RoundRect`，半径与 [drawPaper] 共用同一个 [PAPER_CORNER_RADIUS_DP]。但两者所在的坐标系
 * 不同：`drawPaper` 直接在屏幕像素空间画，圆角是 `PAPER_CORNER_RADIUS_DP * density` 像素；
 * 这里在 board units 空间裁（`withTransform` 已经叠了 `scale(viewport.scale * density, ...)`），
 * 同一个像素半径换算回 board units 就是 `PAPER_CORNER_RADIUS_DP / viewport.scale`——
 * density 在这一步会互相约掉，缩放倍率则不会，所以必须用 `viewport.scale` 显式换算，
 * 不能直接搬 `drawPaper` 那边的像素值过来用。
 */
private inline fun DrawScope.clipToPaper(viewport: Viewport, block: DrawScope.() -> Unit) {
    val radiusBoardUnits = PAPER_CORNER_RADIUS_DP / viewport.scale
    val clipShape = Path().apply {
        addRoundRect(
            RoundRect(
                rect = Rect(0f, 0f, Viewport.CANVAS_W, Viewport.CANVAS_H),
                cornerRadius = CornerRadius(radiusBoardUnits, radiusBoardUnits),
            )
        )
    }
    clipPath(clipShape) {
        block()
    }
}

fun DrawScope.drawBoard(
    doc: BoardDoc,
    viewport: Viewport,
    density: Float,
    pathCache: MutableMap<ItemId, Path>,
    textMeasurer: TextMeasurer,
) {
    withTransform({
        translate(viewport.offsetX * density, viewport.offsetY * density)
        scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
    }) {
        clipToPaper(viewport) {
            for (item in doc.items) {
                when (item) {
                    is Stroke -> {
                        if (item.points.size == 1) {
                            drawCircle(Color(item.colorArgb), item.widthDp / 2f,
                                Offset(item.points[0].x, item.points[0].y))
                        } else {
                            val path = pathCache.getOrPut(item.id) { buildStrokePath(item.points) }
                            drawPath(path, Color(item.colorArgb), style = inkStyle(item.widthDp))
                        }
                    }
                    is ShapeItem -> drawShape(item)
                    is TextItem -> drawTextItem(item, textMeasurer)
                }
            }
        }
    }
}

/**
 * 文字度量用的固定密度：density=1f、fontScale=1f，与调用方的真实设备密度、系统字体缩放
 * 无关。[drawBoard] 在进入这个分支之前已经叠了 `scale(viewport.scale * density, ...)`——
 * 这个复合变换本身就会把这里画出来的每一个像素按 `density` 放大一次。如果
 * `TextMeasurer.measure` 又拿设备真实密度把 `sizeBoard.sp` 换算成 px（sp→px 本身就是
 * 再乘一次 density），就会变成"先乘一次密度、外层变换又乘一次"的双重缩放——正是这一步
 * brief 里点名的那类 bug（同一份文档在不同 dpi 设备上量出的字号相差一个跟设备有关的倍数）。
 * 用固定 [Density] 测量，`sizeBoard.sp` 换算出的 px 数值上就直接等于 [TextItem.sizeBoard]
 * 本身，缩放完全交给外层 `withTransform` 一次性完成——这与 [Stroke.widthDp] 直接被当成
 * board units 传给 [StrokeStyle]（同样只在外层变换里被放大一次，函数体内从不再乘
 * density）是同一种处理方式，文字与墨迹在这条变换链上走的是同一套规则。
 *
 * `fontScale` 固定 1f、不读系统无障碍字体缩放设置：`canvas.paper` 上的文字是画布内容，
 * 该跟着 `viewport.scale` 缩放，不该再叠加一层"这台设备/这个用户把系统字体调大了多少"——
 * 否则同一份文档在不同用户的设备上，[TextItem.hBoard]（创建时测量存下的高度）与这里实际
 * 画出来的高度会不一致，PNG 导出裁切的基准也会跟着漂移。
 *
 * 调用方传入的 [TextMeasurer] 无论是 `rememberTextMeasurer()`（读 `LocalDensity.current`
 * 当默认密度）还是手动构造的实例，这里都不使用它自带的默认密度——每次调用都用这个常量
 * 显式覆盖 `measure()` 的 `density` 参数，调用方的默认密度取值因此不影响这里的渲染结果。
 */
private val TEXT_MEASURE_DENSITY = Density(density = 1f, fontScale = 1f)

/**
 * 屏幕渲染（[drawTextItem]）与创建时测量（`TextInputSheet.commitText`，Task 4）共用的唯一
 * 测量入口——这是保证两者"用一模一样的输入喂给一模一样的纯函数"的**唯一**方式。
 *
 * Task 3 最初的写法是在两处分别手写 `TextStyle(fontSize = sizeBoard.sp)` /
 * `Constraints(maxWidth = wBoard.roundToInt().coerceAtLeast(1))` / `density = TEXT_MEASURE_DENSITY`
 * 这三个参数，靠"写的时候对照注释抄一遍"来保证一致——这正是本项目反复踩到的那类 bug 的
 * 形状：两处各自维护同一份不变式，迟早会有一处改了另一处没跟上（参见 `canvas.paper` 圆角
 * 那次教训）。抽成这一个函数后，"输入相同"由函数签名保证，不再依赖两处开发者的自律。
 *
 * 不传 `color`：颜色不影响布局测量，也不该影响——[drawTextItem] 在拿到这个纯几何的
 * `TextLayoutResult` 之后，用 `drawText(layout, color = ...)` 的显式 `color` 参数覆盖着色，
 * 创建时的测量（`TextInputSheet`）根本不关心颜色，只要 `layout.size.height` 这个几何量。
 *
 * 唯一仍未消除的风险点是 `wBoard.roundToInt()` 这一步的四舍五入：`Constraints` 吃 `Int` px，
 * `wBoard` 是 `Float` board units，四舍五入误差在极端情况下（换行宽度刚好卡在某个词的
 * 临界宽度上）可能让"要不要在这个词处换行"的判定发生变化、从而改变行数与高度——但这个风险
 * 现在也只存在于一处（这个函数内部），两个调用方永远读到同一次 `roundToInt()` 的结果，
 * 不会出现"渲染量出来的换行"与"创建时存进 `hBoard` 的换行"互相打架的情况。
 */
internal fun measureTextLayout(
    text: String,
    sizeBoard: Float,
    wBoard: Float,
    measurer: TextMeasurer,
): TextLayoutResult = measurer.measure(
    text = text,
    style = TextStyle(fontSize = sizeBoard.sp),
    constraints = Constraints(maxWidth = wBoard.roundToInt().coerceAtLeast(1)),
    density = TEXT_MEASURE_DENSITY,
)

/**
 * 文字绘制分支（Task 3；Task 4 起测量逻辑收进 [measureTextLayout]，这里只是调用方之一）。
 * 颜色在测量之后才通过 `drawText` 的 `color` 参数施加，不参与布局——见 [measureTextLayout] 的说明。
 */
private fun DrawScope.drawTextItem(item: TextItem, textMeasurer: TextMeasurer) {
    val layout = measureTextLayout(item.text, item.sizeBoard, item.wBoard, textMeasurer)
    drawText(layout, color = Color(item.colorArgb), topLeft = Offset(item.anchor.x, item.anchor.y))
}

/**
 * 正在绘制、尚未提交的那一笔。
 *
 * 这不是独立的图层：`CanvasSurface` 只有一个 `Canvas`，每接受一个新采样点触发重绘时，
 * 这里画的实时预览与 [drawBoard] 画的已提交内容都会在同一帧里重新执行一遍——"单独一层，
 * 只有它随采样重绘"曾是这里的说法，但代码里并没有分层机制，纠正为如实描述。是否值得
 * 拆成独立图层缩小重绘范围是渲染结构问题，留给专门的重构轮次评估，这次不做结构改动。
 */
fun DrawScope.drawLiveStroke(
    points: List<Pt>,
    colorArgb: Int,
    widthDp: Float,
    viewport: Viewport,
    density: Float,
) {
    if (points.isEmpty()) return
    withTransform({
        translate(viewport.offsetX * density, viewport.offsetY * density)
        scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
    }) {
        clipToPaper(viewport) {
            if (points.size == 1) {
                drawCircle(Color(colorArgb), widthDp / 2f, Offset(points[0].x, points[0].y))
            } else {
                drawPath(buildStrokePath(points), Color(colorArgb), style = inkStyle(widthDp))
            }
        }
    }
}

/** 正在拖拽、尚未提交的形状预览。 */
fun DrawScope.drawLiveShape(
    kind: ShapeKind, a: Pt, b: Pt, colorArgb: Int, widthDp: Float,
    viewport: Viewport, density: Float,
) {
    withTransform({
        translate(viewport.offsetX * density, viewport.offsetY * density)
        scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
    }) {
        clipToPaper(viewport) {
            drawShapeGeometry(kind, a, b, colorArgb, widthDp)
        }
    }
}

private fun DrawScope.drawShape(sh: ShapeItem) =
    drawShapeGeometry(sh.kind, sh.a, sh.b, sh.colorArgb, sh.widthDp)

private fun DrawScope.drawShapeGeometry(
    kind: ShapeKind, a: Pt, b: Pt, colorArgb: Int, widthDp: Float,
) {
    val color = Color(colorArgb)
    when (kind) {
        ShapeKind.Line -> drawLine(
            color, Offset(a.x, a.y), Offset(b.x, b.y),
            strokeWidth = widthDp, cap = StrokeCap.Round,
        )
        ShapeKind.Rect -> {
            val r = BoardRect.of(a, b)
            drawRect(color, Offset(r.left, r.top), Size(r.width, r.height), style = inkStyle(widthDp))
        }
        ShapeKind.Ellipse -> {
            val r = BoardRect.of(a, b)
            drawOval(color, Offset(r.left, r.top), Size(r.width, r.height), style = inkStyle(widthDp))
        }
    }
}

private fun inkStyle(widthDp: Float) =
    StrokeStyle(width = widthDp, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 笔尖游标的环形描边宽度，board units，跟当前笔宽无关——环本身要细，撑起环的直径才随笔宽变。 */
private const val PEN_CURSOR_RING_STROKE = 1.5f

/**
 * 橡皮游标的环形描边宽度，**屏幕空间常量** dp（1b-1 复审发现的 bug）——不能借用
 * [PEN_CURSOR_RING_STROKE]：那个常量的单位是 board units，在 `withTransform` 已经叠了
 * `scale(viewport.scale * density, ...)` 的坐标系下直接使用，画出来的描边宽度会随
 * `viewport.scale` 变化（0.5× 时只有 0.75dp、4× 时变成 6dp），而 `AGENTS.md` 明确声称橡皮
 * 游标"整体是屏幕空间常量"——半径已经是（见 [drawEraserCursor] 直接吃 `radiusBoard`
 * 这个已经按屏幕像素换算过的值），描边宽度却漏了这一步，导致声明与实现不一致。
 * 用法见 [drawEraserCursor]：换算成 board units 时除以 `viewport.scale`（这里已经在
 * board-space 坐标系内，不需要再乘/除 density），得到的描边在屏幕上永远是这个 dp 值，
 * 不随缩放变化，笔尖游标与橡皮游标各自的描边缩放行为因此互不干扰。
 */
private const val ERASER_CURSOR_RING_STROKE_DP = 1.5f

/**
 * 笔尖游标（Task 19 修复轮）：只在屏幕渲染路径调用，从不并入 [drawBoard]——
 * 游标不是文档内容，PNG 导出（`export.BoardRenderer`）只调 [drawBoard]，天然看不到它，
 * 不需要额外开关去把它排除在导出之外。
 *
 * 画成空心细环、直径等于当前笔宽，而不是实心点：实心点在浅色纸面上很容易被误认成刚落下
 * 的一个墨点（尤其笔宽档位选到最细的 2dp 时），环形不会与真正的墨迹形状混淆，同时环的
 * 直径如实预告"落这一笔会有多粗"。颜色取 [WhiteboardColorScheme] 的 `interaction` 角色
 * （契约里专门给交互反馈用的强调色，不是画笔调色板里的任何一色），在暖白纸面和深色根背景
 * 上都压得住对比度，不额外引入新的颜色字面量。跟墨迹一样先 `withTransform` 再
 * [clipToPaper]——游标本身也不该画到纸面之外。
 */
fun DrawScope.drawPenCursor(
    pos: Pt,
    widthDp: Float,
    color: Color,
    viewport: Viewport,
    density: Float,
) {
    withTransform({
        translate(viewport.offsetX * density, viewport.offsetY * density)
        scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
    }) {
        clipToPaper(viewport) {
            drawCircle(
                color = color,
                radius = widthDp / 2f,
                center = Offset(pos.x, pos.y),
                style = StrokeStyle(width = PEN_CURSOR_RING_STROKE, cap = StrokeCap.Round),
            )
        }
    }
}

/**
 * 橡皮游标（Task 5，局部擦除跟随轮）：与 [drawPenCursor] 同样只在屏幕渲染路径调用、从不并入
 * [drawBoard]——PNG 导出（`export.BoardRenderer`）只调 [drawBoard]，天然看不到它。
 *
 * 半径直接吃调用方传入的 [radiusBoard]，不在这里重新计算——调用点（`CanvasSurface`）传的是
 * `eraserRadiusBoard(density, viewport)`，跟 `eraseAt` 真正拿去做命中测试的半径是同一份换算
 * 结果，两处永远读同一个来源，环画多大就真的擦多大。热区本身是屏幕空间常量（Task 4a
 * `ERASER_RADIUS_PX`），所以这个环在屏幕上尺寸恒定、不随 `viewport.scale` 缩放——这是预期
 * 行为，不是需要"修"的缩放缺陷：笔尖游标画的是"这一笔会有多粗"（画布几何量，理应随缩放
 * 变化），橡皮游标画的是"手指划过多宽一圈算擦到"（屏幕手势量，不该随缩放变化）。
 * **描边宽度也必须是屏幕空间常量**（1b-1 复审修复的 bug）：半径是，但环的描边宽度之前
 * 借用了 [PEN_CURSOR_RING_STROKE]（board units 常量），在这个已经叠了 `viewport.scale`
 * 的坐标系里直接用，等于描边宽度随缩放变化——同一个"屏幕空间常量"的游标，一半（半径）真
 * 一半（描边）假。现在用专属的 [ERASER_CURSOR_RING_STROKE_DP] 并除以 `viewport.scale`
 * 抵消坐标系自带的缩放，两半才真的统一成同一种缩放行为。
 *

 * 颜色取 [WhiteboardColorScheme] 的 `error` 角色（警示/危险语义，天然贴合"即将删除"），
 * 与笔尖游标固定用的 `interaction`（蓝）色相不同，一眼能分清当前是画还是擦，不新增颜色
 * 字面量。裁剪与笔尖游标一致：先 `withTransform` 再 [clipToPaper]，游标本身也不该画到
 * 纸面之外。
 */
fun DrawScope.drawEraserCursor(
    pos: Pt,
    radiusBoard: Float,
    color: Color,
    viewport: Viewport,
    density: Float,
) {
    withTransform({
        translate(viewport.offsetX * density, viewport.offsetY * density)
        scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
    }) {
        clipToPaper(viewport) {
            drawCircle(
                color = color,
                radius = radiusBoard,
                center = Offset(pos.x, pos.y),
                style = StrokeStyle(
                    width = ERASER_CURSOR_RING_STROKE_DP / viewport.scale,
                    cap = StrokeCap.Round,
                ),
            )
        }
    }
}

/** 二次贝塞尔中点法建路径，坐标全在 board units，因此与视口无关、可缓存。 */
private fun buildStrokePath(points: List<Pt>): Path {
    val sp = StrokeSmoothing.smooth(points) ?: return Path()
    return Path().apply {
        moveTo(sp.start.x, sp.start.y)
        for (q in sp.quads) quadraticBezierTo(q.ctrl.x, q.ctrl.y, q.end.x, q.end.y)
        sp.end?.let { lineTo(it.x, it.y) }
    }
}
