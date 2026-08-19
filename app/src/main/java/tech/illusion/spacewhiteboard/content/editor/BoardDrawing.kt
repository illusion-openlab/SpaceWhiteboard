package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import tech.illusion.spacewhiteboard.board.*

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
 * 不是画布内容的一部分，缩放画布不应该让边角跟着变圆或变尖。
 */
fun DrawScope.drawPaper(viewport: Viewport, density: Float, color: Color, cornerRadiusDp: Float) {
    val leftPx = viewport.toScreenX(0f) * density
    val topPx = viewport.toScreenY(0f) * density
    val widthPx = Viewport.CANVAS_W * viewport.scale * density
    val heightPx = Viewport.CANVAS_H * viewport.scale * density
    drawRoundRect(
        color = color,
        topLeft = Offset(leftPx, topPx),
        size = Size(widthPx, heightPx),
        cornerRadius = CornerRadius(cornerRadiusDp * density),
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
 * （已经把 board 坐标系变换到屏幕坐标系），`clipRect` 在当前坐标系下裁剪，此处
 * 当前坐标系就是 board units，直接传 `(0, 0, CANVAS_W, CANVAS_H)` 即可，不需要
 * 再乘 density/scale 换算——这也是导出路径（Task 18 `BoardRenderer`，用内容包围盒
 * 构造的另一个 `Viewport` 调同一批函数）会自动裁在同一条边界上的原因：裁剪矩形
 * 写在 board 坐标里，不依赖调用方的 viewport/density 取值，屏幕渲染和导出用的是
 * 同一份变换 + 同一份裁剪，两条路径不可能裁出不同的结果。
 */
private inline fun DrawScope.clipToPaper(block: DrawScope.() -> Unit) {
    clipRect(left = 0f, top = 0f, right = Viewport.CANVAS_W, bottom = Viewport.CANVAS_H) {
        block()
    }
}

fun DrawScope.drawBoard(
    doc: BoardDoc,
    viewport: Viewport,
    density: Float,
    pathCache: MutableMap<ItemId, Path>,
) {
    withTransform({
        translate(viewport.offsetX * density, viewport.offsetY * density)
        scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
    }) {
        clipToPaper {
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
                }
            }
        }
    }
}

/** 正在绘制、尚未提交的那一笔。单独一层，只有它随采样重绘。 */
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
        clipToPaper {
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
        clipToPaper {
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
        clipToPaper {
            drawCircle(
                color = color,
                radius = widthDp / 2f,
                center = Offset(pos.x, pos.y),
                style = StrokeStyle(width = PEN_CURSOR_RING_STROKE, cap = StrokeCap.Round),
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
