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

/** 橡皮命中半径，board units。 */
private const val ERASER_RADIUS = 12f

@Composable
fun CanvasSurface(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val pathCache = remember { mutableMapOf<ItemId, Path>() }

    Canvas(
        modifier = modifier
            .background(Color(0xFFFFFFFF))   // 纸面色，Task 15 换成主题里的 fillTertiary
            // 手势回调里原先每个字段读取都是 state.<field>，在调用时求值 ——
            // 但这意味着工具/颜色/宽度在拖拽进行中被切换时，onDragStart 与 onDragEnd/onDrag
            // 可能读到不同的 state.tool，导致 start 按 Pen 建立 livePoints、end 却按 Shape 分派，
            // 委托进 Shape 分支发现 liveShapeStart 为空、什么都不提交，也从不清空 livePoints，
            // 留下一条永久重绘的幽灵墨迹。手势的语义应该是"这一笔归属于按下时选中的工具/样式"，
            // 所以在 onDragStart 里把 tool/shapeKind/colorArgb/widthDp 锁存进下面这些局部变量，
            // 其余回调只读锁存值，不再读 state.tool 等实时字段。
            // 这也是 pointerInput 必须留 Unit 作 key 的另一个理由：Key 变化会重建协程、
            // 把这些局部变量重新初始化为默认值，等于把"锁存"整个作废。
            .pointerInput(Unit) {
                // 累加游标，屏幕像素空间。
                // 只在 onDragStart 读一次绝对 offset，之后全靠累加 dragAmount ——
                // PICO 的注视+捏合交互里，拖拽中重读绝对坐标反映的是几乎不动的注视点，不是手的位移。
                var cursorPx = Offset.Zero
                var activeTool: Tool? = null   // null = 当前没有手势在进行
                var activeShape = ShapeKind.Line
                var activeColor = 0
                var activeWidth = 0f
                /** 本次擦除动作累积命中的对象；松手时整批作为一个 Op 提交。 */
                val erasedThisDrag = mutableListOf<IndexedItem>()

                detectSpatialDragGesture(
                    context = context,
                    onDragStart = { offset, _ ->
                        // offset 是 com.pico.spatial.ui.geometry.Offset3D（带 z），
                        // 这里只取 x/y 落回 2D 屏幕像素坐标。
                        cursorPx = Offset(offset.x, offset.y)
                        val p = state.viewport.toBoard(offset.x / density, offset.y / density)
                        activeTool = state.tool
                        activeShape = state.shapeKind
                        activeColor = state.colorArgb
                        activeWidth = state.widthDp
                        when (activeTool) {
                            Tool.Pen -> state.livePoints = listOf(p)
                            Tool.Shape -> {
                                state.liveShapeStart = p
                                state.liveShapeEnd = p
                            }
                            Tool.Eraser -> { erasedThisDrag.clear(); eraseAt(state, p, erasedThisDrag) }
                            Tool.Pan, null -> Unit
                        }
                    },
                    onDragEnd = { _ ->
                        when (activeTool) {
                            Tool.Pen -> {
                                val pts = state.livePoints
                                state.livePoints = emptyList()
                                if (pts.isNotEmpty()) {
                                    state.commit(AddItem(Stroke(state.newId(), activeColor, activeWidth, pts)))
                                }
                            }
                            Tool.Shape -> {
                                val a = state.liveShapeStart
                                val b = state.liveShapeEnd
                                state.liveShapeStart = null
                                state.liveShapeEnd = null
                                // 起点终点几乎重合视为误触，不产生一个看不见的零尺寸形状
                                if (a != null && b != null && a.distanceTo(b) > MIN_SHAPE_SIZE) {
                                    state.commit(AddItem(
                                        ShapeItem(state.newId(), activeShape, activeColor, activeWidth, a, b)
                                    ))
                                }
                            }
                            Tool.Eraser -> {
                                // 注意：擦除已在拖拽途中即时从 doc 移除（为了给出即时反馈），
                                // 所以这里只补一次入栈，不再 applyTo。命中为空时不入栈——
                                // 一个"什么都没发生"的撤销条目比没有条目更糟。
                                if (erasedThisDrag.isNotEmpty()) {
                                    state.pushEraseBatch(erasedThisDrag.toList())
                                    erasedThisDrag.clear()
                                }
                            }
                            Tool.Pan, null -> Unit
                        }
                        activeTool = null
                    },
                    onDragCancel = { _ ->
                        state.livePoints = emptyList()
                        state.liveShapeStart = null
                        state.liveShapeEnd = null
                        erasedThisDrag.clear()
                        activeTool = null
                    },
                ) { value ->
                    cursorPx = Offset(cursorPx.x + value.dragAmount.x, cursorPx.y + value.dragAmount.y)
                    val p = state.viewport.toBoard(cursorPx.x / density, cursorPx.y / density)
                    when (activeTool) {
                        Tool.Pen -> if (state.livePoints.isNotEmpty() &&
                            p.distanceTo(state.livePoints.last()) > MIN_STEP
                        ) {
                            state.livePoints = state.livePoints + p
                        }
                        Tool.Shape -> if (state.liveShapeStart != null) state.liveShapeEnd = p
                        Tool.Eraser -> eraseAt(state, p, erasedThisDrag)
                        Tool.Pan, null -> Unit
                    }
                }
            },
    ) {
        // 阈值式清理被擦掉对象留下的失效 Path 缓存条目：只在缓存明显超量时才重建一次存活 key 集合，
        // 避免在没有擦除发生的常态下每帧都做一次全量 Set 构建。
        if (pathCache.size > state.doc.items.size * 2) {
            val alive = state.doc.items.map { it.id }.toSet()
            pathCache.keys.retainAll(alive)
        }
        drawBoard(state.doc, state.viewport, density, pathCache)
        drawLiveStroke(state.livePoints, state.colorArgb, state.widthDp, state.viewport, density)
        val sa = state.liveShapeStart
        val sb = state.liveShapeEnd
        if (sa != null && sb != null) {
            drawLiveShape(state.shapeKind, sa, sb, state.colorArgb, state.widthDp, state.viewport, density)
        }
    }
}

/**
 * 命中的对象立即从文档移除（即时反馈），并记入本次擦除批次 [acc]。
 * [acc] 与 [EditorState.doc] 天然不会重复记录同一对象：一个对象一旦被移除，
 * 它就从 doc.items 里消失，同一次拖拽后续的采样点即使扫过同一位置也再命中不到它，
 * 所以这里不需要额外的去重逻辑。
 */
private fun eraseAt(state: EditorState, p: Pt, acc: MutableList<IndexedItem>) {
    val hits = HitTest.eraseHits(state.doc, p, ERASER_RADIUS)
    if (hits.isEmpty()) return
    acc += hits
    state.removeWithoutUndo(hits.map { it.item.id }.toSet())
}
