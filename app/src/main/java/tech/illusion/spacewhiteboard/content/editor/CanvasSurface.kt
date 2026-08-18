package tech.illusion.spacewhiteboard.content.editor

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import tech.illusion.spacewhiteboard.board.*

private const val TAG = "SpaceWhiteboard"

/** 采样距离过滤，board units。手停住时驱动层仍持续下发事件，不过滤会塞进几千个重复点。
 *  初值 2.0 是保守起步值，Task 19 用真机实测数据校准。 */
private const val MIN_STEP = 2.0f

/** 起点终点距离小于此值视为误触，不生成形状。board units。 */
private const val MIN_SHAPE_SIZE = 4f

@Composable
fun CanvasSurface(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val pathCache = remember { mutableMapOf<ItemId, Path>() }

    Canvas(
        modifier = modifier
            .background(Color(0xFFFFFFFF))   // 纸面色，Task 15 换成主题里的 fillTertiary
            // 手势回调里的每个字段读取都是 state.<field>，在调用时求值，不按值捕获 ——
            // 加 state.tool / shapeKind / colorArgb / widthDp 进 key 只会在这些值变化时
            // 取消重启协程（且不会触发 onDragCancel），可能扔下半成品 livePoints/liveShapeStart
            // 却没有纯粹坏处；因此固定用 Unit，手势协程只创建一次。
            .pointerInput(Unit) {
                // 累加游标，屏幕像素空间。
                // 只在 onDragStart 读一次绝对 offset，之后全靠累加 dragAmount ——
                // PICO 的注视+捏合交互里，拖拽中重读绝对坐标反映的是几乎不动的注视点，不是手的位移。
                var cursorPx = Offset.Zero

                detectSpatialDragGesture(
                    context = context,
                    onDragStart = { offset, _ ->
                        // offset 是 com.pico.spatial.ui.geometry.Offset3D（带 z），
                        // 这里只取 x/y 落回 2D 屏幕像素坐标。
                        cursorPx = Offset(offset.x, offset.y)
                        val p = state.viewport.toBoard(offset.x / density, offset.y / density)
                        // 绑定成 val 强制 when 处于表达式上下文：少写一个 Tool 分支会让这里编译不过，
                        // 而不是悄悄什么都不做——新增枚举值不会被静默继承成 Pen/Shape 行为。
                        val dispatched: Unit = when (state.tool) {
                            Tool.Pen -> state.livePoints = listOf(p)
                            Tool.Shape -> {
                                state.liveShapeStart = p
                                state.liveShapeEnd = p
                            }
                            Tool.Eraser, Tool.Pan -> Unit
                        }
                    },
                    onDragEnd = { _ ->
                        val dispatched: Unit = when (state.tool) {
                            Tool.Pen -> {
                                val pts = state.livePoints
                                state.livePoints = emptyList()
                                if (pts.isNotEmpty()) {
                                    state.commit(AddItem(Stroke(state.newId(), state.colorArgb, state.widthDp, pts)))
                                } else Unit
                            }
                            Tool.Shape -> {
                                val a = state.liveShapeStart
                                val b = state.liveShapeEnd
                                state.liveShapeStart = null
                                state.liveShapeEnd = null
                                // 起点终点几乎重合视为误触，不产生一个看不见的零尺寸形状
                                if (a != null && b != null && a.distanceTo(b) > MIN_SHAPE_SIZE) {
                                    state.commit(AddItem(
                                        ShapeItem(state.newId(), state.shapeKind, state.colorArgb, state.widthDp, a, b)
                                    ))
                                } else Unit
                            }
                            Tool.Eraser, Tool.Pan -> Unit
                        }
                    },
                    onDragCancel = { _ ->
                        state.livePoints = emptyList()
                        state.liveShapeStart = null
                        state.liveShapeEnd = null
                    },
                ) { value ->
                    cursorPx = Offset(cursorPx.x + value.dragAmount.x, cursorPx.y + value.dragAmount.y)
                    val p = state.viewport.toBoard(cursorPx.x / density, cursorPx.y / density)
                    val dispatched: Unit = when (state.tool) {
                        Tool.Pen -> if (state.livePoints.isNotEmpty() &&
                            p.distanceTo(state.livePoints.last()) > MIN_STEP
                        ) {
                            state.livePoints = state.livePoints + p
                        } else Unit
                        Tool.Shape -> if (state.liveShapeStart != null) state.liveShapeEnd = p else Unit
                        Tool.Eraser, Tool.Pan -> Unit
                    }
                }
            },
    ) {
        drawBoard(state.doc, state.viewport, density, pathCache)
        drawLiveStroke(state.livePoints, state.colorArgb, state.widthDp, state.viewport, density)
        val sa = state.liveShapeStart
        val sb = state.liveShapeEnd
        if (sa != null && sb != null) {
            drawLiveShape(state.shapeKind, sa, sb, state.colorArgb, state.widthDp, state.viewport, density)
        }
    }
}
