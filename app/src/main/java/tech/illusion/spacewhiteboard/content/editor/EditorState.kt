package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.illusion.spacewhiteboard.board.*

enum class Tool { Pen, Shape, Eraser, Pan, Text }

/**
 * 一次手势按下时锁存的工具与样式。
 *
 * 语义：**这一笔归属于按下那一刻选中的工具与样式**。手势进行中改工具栏不影响它。
 * 不锁存会出两类 bug：onDragStart 按 Pen 建立 livePoints、onDragEnd 却按 Shape 分派，
 * 留下一条永不清除的幽灵墨迹；以及实时预览用新颜色画、提交却用旧颜色，所见非所得。
 *
 * 之所以是 snapshot state 而不是手势协程里的局部变量：Canvas 的 draw lambda 也要读它
 * 来画实时预览。局部变量画不到，读 `state.colorArgb` 又会和提交值分叉 —— 只有把锁存值
 * 放进 state，预览与提交才是同一个表达式，不可能再对不上。
 */
data class ActiveStyle(
    val tool: Tool,
    val shapeKind: ShapeKind,
    val colorArgb: Int,
    val widthDp: Float,
)

/**
 * 调色板与笔宽档位。取值与 .spatialsdk/design-contract.md §9 一致：
 * 8 色画笔调色板（文档数据，不是主题角色），默认选中序号 0（墨黑）；
 * 三档粗细 2/4/8dp，默认选中序号 1（中）。
 */
val PALETTE: List<Int> = listOf(
    0xFF1B1B1F.toInt(), 0xFF6B7280.toInt(), 0xFFE03131.toInt(), 0xFFF08C00.toInt(),
    0xFF2F9E44.toInt(), 0xFF1971C2.toInt(), 0xFF7048E8.toInt(), 0xFFE64980.toInt(),
)
val WIDTHS: List<Float> = listOf(2f, 4f, 8f)   // board units（= dp @ scale 1.0）

/**
 * 编辑器的全部可变状态。
 *
 * 每一个会影响 UI 的字段都是 snapshot state（mutableStateOf）——
 * 普通 var 在 Canvas 的 draw lambda 里读只订阅"绘制阶段"，不触发重组，
 * 表现为"画面第一帧之后再也不更新"，而编译、单测、代码审查都发现不了。
 */
class EditorState(initialDoc: BoardDoc, initialNextId: Long) {

    var doc by mutableStateOf(initialDoc); private set
    var viewport by mutableStateOf(Viewport())
    var tool by mutableStateOf(Tool.Pen)
    var shapeKind by mutableStateOf(ShapeKind.Line)
    var colorArgb by mutableStateOf(PALETTE[0])
    var widthDp by mutableStateOf(WIDTHS[1])

    /**
     * 手势进行中锁存的工具与样式；null = 当前没有手势在进行。
     * 由 [beginGesture] 在 onDragStart 设置、[endGesture] 在松手/取消时清除。
     */
    var activeStyle by mutableStateOf<ActiveStyle?>(null); private set

    /** 实时预览要用的墨水色：手势进行中用锁存值，否则跟随当前选择。粗细、形状同理。 */
    val liveColorArgb: Int get() = activeStyle?.colorArgb ?: colorArgb
    val liveWidthDp: Float get() = activeStyle?.widthDp ?: widthDp
    val liveShapeKind: ShapeKind get() = activeStyle?.shapeKind ?: shapeKind

    // 绘制中的临时状态
    var livePoints by mutableStateOf<List<Pt>>(emptyList())
    var liveShapeStart by mutableStateOf<Pt?>(null)
    var liveShapeEnd by mutableStateOf<Pt?>(null)

    /**
     * 文字工具已点选、但尚未确认输入的锚点（画布坐标）。非 null 即表示输入面板应当显示。
     * 必须是 snapshot state：`BoardEditor` 靠读它触发 `TextInputSheet` 的重组，普通 var 只在
     * 组合阶段读不到之后的写入，面板会永远不出现——与本类其余字段同一条理由（见类注释）。
     */
    var pendingTextAnchor by mutableStateOf<Pt?>(null)

    /** 输入面板里的当前文本。面板关闭（提交或取消）时清空。 */
    var pendingText by mutableStateOf("")

    /** 文字工具点击定位后调用：记下锚点、清空输入框，驱动 `TextInputSheet` 出现。 */
    fun beginTextPlacement(anchor: Pt) {
        pendingTextAnchor = anchor
        pendingText = ""
    }

    /** 取消文字输入：不留锚点、不留文本、不压入任何 Op —— 取消必须什么痕迹都不留。 */
    fun cancelTextPlacement() {
        pendingTextAnchor = null
        pendingText = ""
    }

    // 撤销栈本身不是 snapshot state，所以把它的两个可用性标志镜像成 state
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** 每次文档变更 +1。自动保存靠 LaunchedEffect(revision) 监听。 */
    var revision by mutableStateOf(0); private set

    private val undoStack = UndoStack()
    private var nextId = initialNextId

    fun newId(): ItemId = ItemId(nextId++)

    /**
     * 手势按下：把当前工具与样式锁存下来并返回。
     * 返回值供手势协程直接持有，省掉后续每次都去读 [activeStyle] 再判空。
     */
    fun beginGesture(): ActiveStyle =
        ActiveStyle(tool, shapeKind, colorArgb, widthDp).also { activeStyle = it }

    /** 手势结束（松手或取消）：解除锁存，预览重新跟随当前选择。 */
    fun endGesture() { activeStyle = null }

    fun commit(op: Op) {
        doc = op.applyTo(doc)
        undoStack.push(op)
        afterMutation()
    }

    fun undo() { doc = undoStack.undo(doc); afterMutation() }
    fun redo() { doc = undoStack.redo(doc); afterMutation() }

    /**
     * 擦除途中的即时切分：改文档但不入撤销栈。
     * 配套的 [pushEraseBatch] 在松手时一次性把整批记账为一个 [ReplaceItems] —— 这样一次擦除
     * 只对应一次撤销。取 [BoardDoc] 整体而不是增删 id 集合，因为切分既要移除命中对象、
     * 也要在同一个位置插入碎片，调用方（CanvasSurface.eraseAt）已经把两步都做完了。
     */
    fun setDocWithoutUndo(newDoc: BoardDoc) {
        doc = newDoc
        revision++
    }

    /**
     * 把一次擦除动作记账为单个 [ReplaceItems]（文档在拖拽途中已经改过了，这里只记账，
     * 不再 applyTo）。[removed] 为空时什么都不做——一个"什么都没发生"的撤销条目比没有
     * 条目更糟，调用方（CanvasSurface.finishEraseBatch）也会做同样的判空，这里再挡一层。
     */
    fun pushEraseBatch(removed: List<IndexedItem>, added: List<IndexedItem>) {
        if (removed.isEmpty()) return
        undoStack.push(ReplaceItems(removed, added))
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    fun clearAll() {
        if (doc.items.isEmpty()) return
        commit(RemoveItems(doc.items.mapIndexed { i, item -> IndexedItem(i, item) }))
    }

    private fun afterMutation() {
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
        revision++
    }
}
