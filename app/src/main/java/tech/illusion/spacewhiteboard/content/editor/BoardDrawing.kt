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
        if (points.size == 1) {
            drawCircle(Color(colorArgb), widthDp / 2f, Offset(points[0].x, points[0].y))
        } else {
            drawPath(buildStrokePath(points), Color(colorArgb), style = inkStyle(widthDp))
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
        drawShapeGeometry(kind, a, b, colorArgb, widthDp)
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

/** 二次贝塞尔中点法建路径，坐标全在 board units，因此与视口无关、可缓存。 */
private fun buildStrokePath(points: List<Pt>): Path {
    val sp = StrokeSmoothing.smooth(points) ?: return Path()
    return Path().apply {
        moveTo(sp.start.x, sp.start.y)
        for (q in sp.quads) quadraticBezierTo(q.ctrl.x, q.ctrl.y, q.end.x, q.end.y)
        sp.end?.let { lineTo(it.x, it.y) }
    }
}
