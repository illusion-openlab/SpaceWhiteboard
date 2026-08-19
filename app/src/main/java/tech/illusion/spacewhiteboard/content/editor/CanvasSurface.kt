package tech.illusion.spacewhiteboard.content.editor

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
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

/**
 * 采样距离过滤，**屏幕像素空间**（不是 board units）：与上一个被接受样本的屏幕像素位置
 * 比较，只在这个空间比才对——"手是不是基本没动"是屏幕空间的概念，跟当前缩放倍数无关。
 * 旧版在换算到 board units 之后才比较（等价于用同一个屏幕像素阈值除以 viewport.scale），
 * 于是同样的物理手部位移，缩放倍数越高换算出的 board 距离越小：50% 缩放时中位步长
 * 6.06px 换成约 6.06 board units，阈值 2.0 基本不拦；400% 缩放时同样的手部位移只值
 * ~0.76 board units，大半采样点被当成"没动"丢弃——恰好发生在用户放大想画细节、最需要
 * 采样密度的时候。这是原计划里的设计错误，不是实现疏漏，所以改法是整个过滤器搬到屏幕
 * 像素空间，不是"两个空间都卡一遍"。
 *
 * 取值依据 Task 19 在 PICO Swan 真机上的实测（2026-08-19，九笔画、1346 个 drag 事件）：
 * 90Hz 采样，事件间隔中位数 11.10ms（P90 11.49ms、P99 11.90ms，非常规律）；单事件步长
 * 中位数 6.06px（P90 10.87px、P99 30.54px）。阈值取中位步长的约三分之一（2.0px）：
 * 落在"中位步长的四分之一到一半"这个安全区间内，既能吞掉手部静止时驱动层仍在下发的
 * 抖动重复点，又远低于中位数真实移动，每秒 ~90 个真实样本几乎全部保留。
 */
private const val MIN_STEP_PX = 2.0f

/** 起点终点距离小于此值视为误触，不生成形状。board units。 */
private const val MIN_SHAPE_SIZE = 4f

/**
 * 橡皮命中半径，**屏幕像素空间**（不是 board units）——理由同 [MIN_STEP_PX] 顶部那段注释：
 * "手指划过多宽一圈算擦到"是屏幕空间的概念，跟当前缩放倍数无关。旧版直接拿 12 当 board
 * units 用，50% 缩放时屏幕热区只有 6dp、400% 时又变成 48dp，同一个手势在不同缩放下
 * 擦除范围感觉完全不同。换算成画布单位见 [eraserRadiusBoard]。
 *
 * 取值须明显大于 MIN_STEP_PX 一节里实测的中位采样步长（6.06px），否则手指划过的路径上会
 * 漏出没擦到的缝。**24px 已经在真机 PICO Swan 上完整试玩确认**：擦除确实生效，热区大小的
 * 手感合适，不是一个还需要现场核对的起始猜测值（详见 `AGENTS.md`「已验证的 / 未验证的」）。
 */
private const val ERASER_RADIUS_PX = 24f

/** 屏幕像素橡皮半径 → 画布单位半径：随缩放变化的是屏幕热区，不是画布几何距离。 */
private fun eraserRadiusBoard(density: Float, viewport: Viewport): Float =
    ERASER_RADIUS_PX / (density * viewport.scale)

// `canvas.paper` 圆角常量（契约 §2.2，6dp，不随 viewport.scale 缩放）已经内收进
// `BoardDrawing.kt` 的 `PAPER_CORNER_RADIUS_DP`——`drawPaper` 与 `clipToPaper` 现在读的是
// 同一个常量，这里不再重复定义一份，避免两处数值不小心分叉（Task 20 修复项）。

@Composable
fun CanvasSurface(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val scheme = PicoTheme.colorScheme
    val pathCache = remember { mutableMapOf<ItemId, Path>() }
    // 拖拽游标位置：Pen 或 Eraser 拖拽进行中，"系统认为笔尖/擦除中心在哪"（board units）；
    // 没有 Pen/Eraser 拖拽时为 null、不画。用 mutableStateOf 而不是手势协程里的局部变量，
    // 原因和 state.livePoints 一样——下面 Canvas 的 draw lambda 要在另一处读它来画游标，
    // 局部变量读不到。
    // 只做 state 1（拖拽中）：真机实验（分支 experiment/pointer-absolute，见 AGENTS.md
    // 「指针模型实验与被否定的假设」）已经证明 detectSpatialPointerEvent 与
    // detectSpatialDragGesture 可以在同一个元素上共存（两者都能触发），所以这不再是一个
    // "不知道行不行"的技术未知项。state 2（悬停中）没做纯粹是范围裁剪——本轮改动集中在
    // 拖拽路径，悬停游标是独立的一块工作量（接线悬停事件、决定悬停态的视觉区分），放到
    // 下一轮再做，不是拿"猜的"悬停位置糊弄用户。
    val dragCursorBoard = remember { mutableStateOf<Pt?>(null) }
    // 上面那个位置到底该画成笔尖环还是橡皮环——`active` 是手势协程里的局部变量，draw lambda
    // 读不到，所以跟 dragCursorBoard 一样单独存一份 state（Task 5：橡皮光标）。
    val dragCursorTool = remember { mutableStateOf<Tool?>(null) }

    Canvas(
        modifier = modifier
            // canvas.viewport 底色 = 根背景（fillSecondary），纸面之外露出这层留白，
            // 才能在截图里核对到「纸面之外是根底色」（契约 §6 通用检查第 4 条）。
            // 纸面本身（浅色暖白 fillTertiary，契约 §8）现在由 drawPaper 画成一个跟着
            // viewport 移动/缩放的有界矩形，不再是整个 Canvas 的背景——否则平移和缩放
            // 就没有任何可见边界，用户判断不出自己动了多少。
            .background(scheme.fillSecondary)
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
                /** 上一个被采样过滤器接受的样本的屏幕像素位置；Pen 专用，比较基准。 */
                var lastAcceptedPenPx = Offset.Zero
                /** null = 当前没有手势在进行。锁存值同时存进 state，实时预览读的是同一份。 */
                var active: ActiveStyle? = null
                /** 本次擦除拖拽开始前的文档快照——固定基准，用来在 finishEraseBatch 里换算
                 *  ReplaceItems.removed 的原始 index；不能用拖拽途中不断变化的 live doc 当基准，
                 *  否则同一批里越晚命中的条目 index 会偏低，undo 插回时把图层顺序打乱。
                 *  BoardDoc 不可变，持有这个引用不产生额外拷贝开销。 */
                var eraseBaseline: BoardDoc? = null
                /** eraseBaseline 里全部对象的 id 集合，随 eraseBaseline 一起设置——用来判断
                 *  一次命中触碰的是基准原始对象，还是本次拖拽中途已经切出来的碎片。 */
                var eraseBaselineIds: Set<ItemId> = emptySet()
                /** 本次擦除动作累积「碰过的基准对象」的 id；松手时据此从 eraseBaseline 换算
                 *  ReplaceItems.removed。碎片本身不进这个集合——它们的最终形态要等松手时
                 *  按 doc 终态收集（见 finishEraseBatch），不能在产生的当下就记下来，因为
                 *  同一个碎片完全可能被后续采样再切一次。 */
                val touchedBaselineIds = mutableSetOf<ItemId>()

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
                            Tool.Pen -> {
                                state.livePoints = listOf(p)
                                lastAcceptedPenPx = cursorPx
                                dragCursorBoard.value = p
                                dragCursorTool.value = Tool.Pen
                            }
                            Tool.Shape -> {
                                state.liveShapeStart = p
                                state.liveShapeEnd = p
                            }
                            Tool.Eraser -> {
                                eraseBaseline = state.doc
                                eraseBaselineIds = state.doc.items.mapTo(HashSet()) { it.id }
                                touchedBaselineIds.clear()
                                dragCursorBoard.value = p
                                dragCursorTool.value = Tool.Eraser
                                eraseAt(state, p, eraserRadiusBoard(density, state.viewport), eraseBaselineIds, touchedBaselineIds)
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
                                finishEraseBatch(state, eraseBaseline, touchedBaselineIds)
                                eraseBaseline = null
                                eraseBaselineIds = emptySet()
                            }
                            Tool.Pan, null -> Unit
                        }
                        dragCursorBoard.value = null
                        dragCursorTool.value = null
                        active = null
                        state.endGesture()
                    },
                    onDragCancel = { _ ->
                        state.livePoints = emptyList()
                        state.liveShapeStart = null
                        state.liveShapeEnd = null
                        dragCursorBoard.value = null
                        dragCursorTool.value = null
                        // 笔/形状在提交前从不改动 doc，取消时丢弃 live 缓冲即可，没有数据要抢救。
                        // 橡皮不同：拖拽途中已经把命中对象从 doc 里即时切分掉了（见 eraseAt），
                        // 取消手势不能让这些切分变成"改过文档、却没有对应撤销记录"的既成事实——
                        // 用户看到消失/变短的笔迹已经生效，那就必须能撤销，所以取消与正常松手走同一条路径。
                        //
                        // 但这条路径必须显式限定在"这次被取消的手势本身就是橡皮"（1b-1 复审发现）：
                        // onDragCancel 不像 onDragEnd 那样天然按 style.tool 分支，如果不加这个判断，
                        // 一旦某次手势的终止事件被上游丢掉（eraseBaseline/touchedBaselineIds 没能在
                        // 正常路径里清空），下一次即使是完全不相关的 Pen/Shape 手势被取消，也会无条件
                        // 把陈旧的 eraseBaseline/touchedBaselineIds 拿去记一笔 ReplaceItems——`added`
                        // 算的是"此刻 doc 里不属于那份陈旧基准的对象"，会把陈旧基准之后新画的笔迹一起
                        // 错记成"这次擦除产生的碎片"，撤销时连带删掉用户的新笔迹，redo 还会在
                        // ReplaceItems.applyTo 里把它们重新插入一遍变成重复。选择在调用点加 tool 判断，
                        // 而不是改成"每次 onDragStart 都清空橡皮 scratch 状态"，是因为现有的橡皮
                        // onDragStart 分支本来就会用当次拖拽的 state.doc 重新整套覆盖这三个变量
                        // （eraseBaseline/eraseBaselineIds/touchedBaselineIds.clear()），下一次真正的
                        // 橡皮手势开始时陈旧状态自然被覆盖、不会泄漏；真正的风险只在于"非橡皮手势的
                        // cancel 不该动用这些变量"，加在调用点最小、最直接地堵住这一条路径。
                        if (active?.tool == Tool.Eraser) {
                            finishEraseBatch(state, eraseBaseline, touchedBaselineIds)
                            eraseBaseline = null
                            eraseBaselineIds = emptySet()
                        }
                        active = null
                        state.endGesture()
                    },
                ) { value ->
                    cursorPx = Offset(cursorPx.x + value.dragAmount.x, cursorPx.y + value.dragAmount.y)
                    val p = state.viewport.toBoard(cursorPx.x / density, cursorPx.y / density)
                    when (active?.tool) {
                        Tool.Pen -> {
                            // 游标画的是"系统当下认为笔尖在哪"这个诚实的累计位置，每个事件都更新——
                            // 不受下面的采样过滤影响：过滤只决定要不要往 livePoints 里多记一个点
                            // （性能/平滑考虑），游标要如实反映驱动层刚刚汇报的真实位置。
                            dragCursorBoard.value = p
                            if (state.livePoints.isNotEmpty() &&
                                (cursorPx - lastAcceptedPenPx).getDistance() > MIN_STEP_PX
                            ) {
                                state.livePoints = state.livePoints + p
                                lastAcceptedPenPx = cursorPx
                            }
                        }
                        Tool.Shape -> if (state.liveShapeStart != null) state.liveShapeEnd = p
                        Tool.Eraser -> {
                            // 橡皮游标要跟手：跟笔尖游标一样，每个事件都如实更新"系统当下认为
                            // 擦除中心在哪"，不受任何采样过滤影响。
                            dragCursorBoard.value = p
                            eraseAt(state, p, eraserRadiusBoard(density, state.viewport), eraseBaselineIds, touchedBaselineIds)
                        }
                        // 平移是纯视口变化：不提交、不入撤销栈，视口本就不是文档的一部分。
                        // 用 value.dragAmount（本次增量，屏幕像素）而不是 cursorPx（累计绝对值），
                        // panBy 要的是这一帧的位移量，不是从手势起点算起的位移总量。
                        // Viewport 全程工作在 dp 空间，这里必须先除以 density 再传进去。
                        Tool.Pan -> state.viewport = state.viewport.panBy(
                            value.dragAmount.x / density,
                            value.dragAmount.y / density,
                        )
                        null -> Unit
                    }
                }
            },
    ) {
        // canvas.paper：先画纸面矩形，再叠已提交内容与实时预览——纸面是底，墨迹在纸上。
        drawPaper(state.viewport, density, scheme.fillTertiary)
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
        // 游标最后画，叠在已提交内容和实时预览之上，永远看得见。画成笔尖环还是橡皮环
        // 由 dragCursorTool 决定——两者共用同一个位置 state，但语义、颜色、半径来源都不同
        // （Task 5：橡皮环半径必须等于 eraserRadiusBoard，即 eraseAt 真正用来命中测试的
        // 那个半径，不能另起一个数）。Shape/Pan 拖拽不产生游标，穷举写出来只是为了让
        // `when` 保持对 Tool? 的完整覆盖，不是真的会画什么。
        dragCursorBoard.value?.let { pos ->
            when (dragCursorTool.value) {
                Tool.Pen -> drawPenCursor(pos, state.liveWidthDp, scheme.interaction, state.viewport, density)
                Tool.Eraser -> drawEraserCursor(
                    pos, eraserRadiusBoard(density, state.viewport), scheme.error, state.viewport, density,
                )
                Tool.Shape -> Unit
                Tool.Pan -> Unit
                null -> Unit
            }
        }
    }
}

/**
 * 命中的对象立即切分（即时反馈）：Stroke 直接切它的点列，ShapeItem 先经 [ShapeOutline.toPoints]
 * 转成点列再走同一个 [StrokeSplit.split]，产出的碎片都是 Stroke，颜色/线宽沿用原对象。
 * 碎片当场写回 [EditorState.doc]（不入撤销栈），用户能看到笔迹被划断的实时反馈。
 *
 * 命中对象若是 [baselineIds] 里的基准对象，记入 [touchedBaselineIds]——这是松手时换算
 * `ReplaceItems.removed` 的依据。命中对象若不在 baselineIds 里，说明它是本次拖拽中途已经
 * 切出来的碎片，不需要（也不能）再记一次「基准对象」：它的最终形态由 doc 的终态决定，
 * 松手时按 doc 直接收集（见 finishEraseBatch），不在这里提前记账。
 *
 * **命中不等于真的擦掉了什么**（1b-1 复审发现）：[HitTest] 判定的是到连续几何的距离，
 * 椭圆包围盒近似在四角附近还有已知的误判区（见 `AGENTS.md` 遗留问题清单），命中完全可能
 * 发生在橡皮圈根本没扫中任何采样点的地方。[fragmentsOf] 返回 `null` 表示这次命中是这样的
 * 空操作——此时既不替换这个对象、不铸造新 `ItemId`，也不把它计入 [touchedBaselineIds]：
 * 一次没有实际擦除任何东西的命中，不应该在文档里留下痕迹，也不该消耗一次撤销记录。
 *
 * 一次采样可能同时命中多个对象：按 index 降序依次替换，保证还没处理的命中的 index
 * 不会被前面的替换改变长度而错位。
 */
private fun eraseAt(
    state: EditorState,
    p: Pt,
    radiusBoard: Float,
    baselineIds: Set<ItemId>,
    touchedBaselineIds: MutableSet<ItemId>,
) {
    val hits = HitTest.eraseHits(state.doc, p, radiusBoard)
    if (hits.isEmpty()) return

    val items = state.doc.items.toMutableList()
    var changed = false
    for (hit in hits.sortedByDescending { it.index }) {
        val fragments = fragmentsOf(state, hit.item, p, radiusBoard) ?: continue
        items.removeAt(hit.index)
        items.addAll(hit.index, fragments)
        if (hit.item.id in baselineIds) touchedBaselineIds += hit.item.id
        changed = true
    }
    if (changed) state.setDocWithoutUndo(BoardDoc(items))
}

/**
 * 把一个命中对象在 [center]/[radiusBoard] 处切开，产出存活段构成的 Stroke 碎片列表；
 * 若 [StrokeSplit.split] 只切出一段、且这一段的长度等于输入点数（即没有任何点被删除），
 * 说明这是一次没有实际擦除效果的空命中，返回 `null` 让调用方原样跳过这个对象。
 */
private fun fragmentsOf(state: EditorState, item: Item, center: Pt, radiusBoard: Float): List<Stroke>? {
    val points: List<Pt>
    val colorArgb: Int
    val widthDp: Float
    when (item) {
        is Stroke -> { points = item.points; colorArgb = item.colorArgb; widthDp = item.widthDp }
        is ShapeItem -> { points = ShapeOutline.toPoints(item, radiusBoard); colorArgb = item.colorArgb; widthDp = item.widthDp }
    }
    val runs = StrokeSplit.split(points, center, radiusBoard)
    if (runs.size == 1 && runs[0].size == points.size) return null
    return runs.map { pts -> Stroke(state.newId(), colorArgb, widthDp, pts) }
}

/**
 * 收尾一次擦除批次：命中过就把整批记账成一个 [ReplaceItems] Op（文档在拖拽途中已经改过了，
 * 这里只记账，不再 applyTo），命中为空就什么都不做——一个"什么都没发生"的撤销条目比没有
 * 条目更糟。无论走 onDragEnd 还是 onDragCancel，这一步都必须做，因为擦除已经在拖拽途中
 * 把命中对象从文档里即时切分掉了；两条路径共用同一个函数，避免各自维护一份而逐渐走样。
 *
 * - `removed`：[touchedBaselineIds] 里每个 id 从 [baseline]——本次拖拽开始前的文档快照——
 *   换算出的原始 index + 对象本身。
 * - `added`：**此刻**（松手/取消这一时刻）[EditorState.doc] 里所有不属于 baseline 的对象，
 *   连同它们此刻的 index。不能在碎片产生的当下就记下来——早产生的碎片完全可能被后续采样
 *   再切一次，只有松手时读到的才是它的终态。因为 doc 在整个拖拽期间只会发生「移除基准对象
 *   或更早的碎片、插入新碎片」这一种变化，此刻 doc 里"id 不在 baseline 里"的对象精确等于
 *   全部存活碎片，"id 在 baseline 里"的对象精确等于未被碰过的幸存原对象——不需要另外做
 *   累计位移的算术，doc 的当前顺序本身就是最终顺序。
 */
private fun finishEraseBatch(state: EditorState, baseline: BoardDoc?, touchedBaselineIds: MutableSet<ItemId>) {
    if (touchedBaselineIds.isEmpty()) return
    val base = baseline ?: return   // 不会发生：touchedBaselineIds 非空意味着 onDragStart 已经设置过 baseline
    val baselineIds = base.items.mapTo(HashSet()) { it.id }
    val removed = touchedBaselineIds
        .map { id -> val i = base.items.indexOfFirst { it.id == id }; IndexedItem(i, base.items[i]) }
        .sortedBy { it.index }
    val added = state.doc.items.withIndex()
        .filter { (_, item) -> item.id !in baselineIds }
        .map { (i, item) -> IndexedItem(i, item) }
    state.pushEraseBatch(removed, added)
    touchedBaselineIds.clear()
}
