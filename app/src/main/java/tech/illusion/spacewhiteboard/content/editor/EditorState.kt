package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import tech.illusion.spacewhiteboard.board.*

enum class Tool { Pen, Shape, Eraser, Pan }

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

    // 绘制中的临时状态
    var livePoints by mutableStateOf<List<Pt>>(emptyList())
    var liveShapeStart by mutableStateOf<Pt?>(null)
    var liveShapeEnd by mutableStateOf<Pt?>(null)

    // 撤销栈本身不是 snapshot state，所以把它的两个可用性标志镜像成 state
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** 每次文档变更 +1。自动保存靠 LaunchedEffect(revision) 监听。 */
    var revision by mutableStateOf(0); private set

    private val undoStack = UndoStack()
    private var nextId = initialNextId

    fun newId(): ItemId = ItemId(nextId++)

    fun commit(op: Op) {
        doc = op.applyTo(doc)
        undoStack.push(op)
        afterMutation()
    }

    fun undo() { doc = undoStack.undo(doc); afterMutation() }
    fun redo() { doc = undoStack.redo(doc); afterMutation() }

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
