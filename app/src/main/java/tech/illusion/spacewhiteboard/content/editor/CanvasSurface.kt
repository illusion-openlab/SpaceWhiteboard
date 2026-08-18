package tech.illusion.spacewhiteboard.content.editor

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.pico.spatial.ui.design.PicoTheme
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
            // 纸面色走主题角色。本应用把 fillTertiary 定义成浅色暖白纸面（契约 §8），
            // 与它在其它项目里"深色面上的半透明浅底"的常见用法相反 —— 别当成写错了改回去。
            .background(PicoTheme.colorScheme.fillTertiary)
            // 手势回调里原先每个字段读取都是 state.<field>，在调用时求值 ——
            // 但这意味着工具/颜色/宽度在拖拽进行中被切换时，onDragStart 与 onDragEnd/onDrag
            // 可能读到不同的 state.tool，导致 start 按 Pen 建立 livePoints、end 却按 Shape 分派，
            // 委托进 Shape 分支发现 liveShapeStart 为空、什么都不提交，也从不清空 livePoints，
            // 留下一条永久重绘的幽灵墨迹。手势的语义应该是"这一笔归属于按下时选中的工具/样式"，
            // 所以 onDragStart 调 state.beginGesture() 把 tool/shapeKind/colorArgb/widthDp
            // 一次性锁存成一个 ActiveStyle，其余回调只读它，不再读 state.tool 等实时字段。
            // 锁存值放在 state 里（而不是只放手势协程的局部变量），是因为下面 draw lambda
            // 里的实时预览也要读同一份 —— 局部变量它读不到，读实时字段又会和提交值分叉。
            // 这也是 pointerInput 必须留 Unit 作 key 的另一个理由：Key 变化会重建协程、
            // 把 active 重新初始化为 null，等于把"锁存"整个作废。
            .pointerInput(Unit) {
                // 累加游标，屏幕像素空间。
                // 只在 onDragStart 读一次绝对 offset，之后全靠累加 dragAmount ——
                // PICO 的注视+捏合交互里，拖拽中重读绝对坐标反映的是几乎不动的注视点，不是手的位移。
                var cursorPx = Offset.Zero
                /** null = 当前没有手势在进行。锁存值同时存进 state，实时预览读的是同一份。 */
                var active: ActiveStyle? = null
                /** 本次擦除动作累积命中的对象（原始 Item，不带 index）；松手时整批作为一个 Op 提交。 */
                val erasedThisDrag = mutableListOf<Item>()
                /** 本次擦除拖拽开始前的文档快照——固定基准，用来在 finishEraseBatch 里换算每个
                 *  命中对象「删除前」的原始 index；不能用拖拽途中不断收缩的 live doc 当基准，
                 *  否则同一批里越晚命中的条目 index 会偏低，undo 插回时把图层顺序打乱。
                 *  BoardDoc 不可变，持有这个引用不产生额外拷贝开销。 */
                var eraseBaseline: BoardDoc? = null

                detectSpatialDragGesture(
                    context = context,
                    onDragStart = { offset, _ ->
                        // offset 是 com.pico.spatial.ui.geometry.Offset3D（带 z），
                        // 这里只取 x/y 落回 2D 屏幕像素坐标。
                        cursorPx = Offset(offset.x, offset.y)
                        val p = state.viewport.toBoard(offset.x / density, offset.y / density)
                        val style = state.beginGesture()
                        active = style
                        when (style.tool) {
                            Tool.Pen -> state.livePoints = listOf(p)
                            Tool.Shape -> {
                                state.liveShapeStart = p
                                state.liveShapeEnd = p
                            }
                            Tool.Eraser -> {
                                eraseBaseline = state.doc
                                erasedThisDrag.clear()
                                eraseAt(state, p, erasedThisDrag)
                            }
                            Tool.Pan -> Unit
                        }
                    },
                    onDragEnd = { _ ->
                        val style = active
                        when (style?.tool) {
                            Tool.Pen -> {
                                val pts = state.livePoints
                                state.livePoints = emptyList()
                                if (pts.isNotEmpty()) {
                                    state.commit(AddItem(
                                        Stroke(state.newId(), style.colorArgb, style.widthDp, pts)
                                    ))
                                }
                            }
                            Tool.Shape -> {
                                val a = state.liveShapeStart
                                val b = state.liveShapeEnd
                                state.liveShapeStart = null
                                state.liveShapeEnd = null
                                // 起点终点几乎重合视为误触，不产生一个看不见的零尺寸形状
                                if (a != null && b != null && a.distanceTo(b) > MIN_SHAPE_SIZE) {
                                    state.commit(AddItem(ShapeItem(
                                        state.newId(), style.shapeKind, style.colorArgb, style.widthDp, a, b
                                    )))
                                }
                            }
                            Tool.Eraser -> {
                                finishEraseBatch(state, eraseBaseline, erasedThisDrag)
                                eraseBaseline = null
                            }
                            Tool.Pan, null -> Unit
                        }
                        active = null
                        state.endGesture()
                    },
                    onDragCancel = { _ ->
                        state.livePoints = emptyList()
                        state.liveShapeStart = null
                        state.liveShapeEnd = null
                        // 笔/形状在提交前从不改动 doc，取消时丢弃 live 缓冲即可，没有数据要抢救。
                        // 橡皮不同：拖拽途中已经把命中对象从 doc 里即时移除了（见 eraseAt），
                        // 取消手势不能让这些移除变成"改过文档、却没有对应撤销记录"的既成事实——
                        // 用户看到消失的东西已经消失，那就必须能撤销，所以取消与正常松手走同一条路径。
                        finishEraseBatch(state, eraseBaseline, erasedThisDrag)
                        eraseBaseline = null
                        active = null
                        state.endGesture()
                    },
                ) { value ->
                    cursorPx = Offset(cursorPx.x + value.dragAmount.x, cursorPx.y + value.dragAmount.y)
                    val p = state.viewport.toBoard(cursorPx.x / density, cursorPx.y / density)
                    when (active?.tool) {
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
        // 预览一律读 live* 派生属性：手势进行中它们返回 onDragStart 锁存的那一份样式，
        // 与 onDragEnd 提交用的是同一个 ActiveStyle 实例。工具栏做出来之后这条很要紧 ——
        // 另一根手指完全可以在拖拽途中换颜色，那时读 state.colorArgb 会让预览和落笔不一致。
        drawLiveStroke(state.livePoints, state.liveColorArgb, state.liveWidthDp, state.viewport, density)
        val sa = state.liveShapeStart
        val sb = state.liveShapeEnd
        if (sa != null && sb != null) {
            drawLiveShape(
                state.liveShapeKind, sa, sb,
                state.liveColorArgb, state.liveWidthDp, state.viewport, density,
            )
        }
    }
}

/**
 * 命中的对象立即从文档移除（即时反馈），并记入本次擦除批次 [acc]。
 * [acc] 与 [EditorState.doc] 天然不会重复记录同一对象：一个对象一旦被移除，
 * 它就从 doc.items 里消失，同一次拖拽后续的采样点即使扫过同一位置也再命中不到它，
 * 所以这里不需要额外的去重逻辑。
 */
private fun eraseAt(state: EditorState, p: Pt, acc: MutableList<Item>) {
    val hits = HitTest.eraseHits(state.doc, p, ERASER_RADIUS)
    if (hits.isEmpty()) return
    acc += hits.map { it.item }
    state.removeWithoutUndo(hits.map { it.item.id }.toSet())
}

/**
 * 收尾一次擦除批次：命中过就整批入栈成一个 [RemoveItems] Op（文档已经改过了，这里只记账，
 * 不再 applyTo），命中为空就什么都不做——一个"什么都没发生"的撤销条目比没有条目更糟。
 * 无论走 onDragEnd 还是 onDragCancel，这一步都必须做，因为擦除已经在拖拽途中把命中对象
 * 从文档里即时移除了；两条路径共用同一个函数，避免各自维护一份而逐渐走样。
 *
 * 每个条目的 index 从 [baseline]——本次拖拽开始前的文档快照——里换算，而不是从命中时的
 * live doc 里取：拖拽途中 eraseAt 每命中一次，live doc 就整体收缩一次，用它当场记下的
 * index 只对"那一刻"成立，同一批里混用不同时刻的 index 会让 insertAll 插回的顺序错乱
 * （两个条目都可能落到同一个 shift 后的位置）。所有条目相对同一份 baseline 换算，
 * 就都在同一个坐标系里，insertAll 才能精确还原原始 z 序。
 */
private fun finishEraseBatch(state: EditorState, baseline: BoardDoc?, acc: MutableList<Item>) {
    if (acc.isEmpty()) return
    val base = baseline ?: return   // 不会发生：acc 非空意味着 onDragStart 已经设置过 baseline
    val entries = acc.map { item -> IndexedItem(base.items.indexOfFirst { it.id == item.id }, item) }
    state.pushEraseBatch(entries)
    acc.clear()
}
