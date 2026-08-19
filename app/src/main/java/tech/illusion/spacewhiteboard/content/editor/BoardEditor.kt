package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import tech.illusion.spacewhiteboard.board.Viewport

/** 编辑器根内边距（契约 §1 布局骨架：根 Box → 内边距 12dp）。 */
private val EDITOR_PADDING = 12.dp

/** 工具栏与画布之间的间隙。契约 §1：两者之间没有分隔线，靠 12dp 间隙 + 明度落差分开。 */
private val RAIL_CANVAS_GAP = 12.dp

/** `canvas.viewport` 圆角。 */
private val CANVAS_RADIUS = 10.dp

/** `shapePicker` 在 B 变体里向右弹出、上对齐工具组顶部，右移 6dp（契约 §2.2）。 */
private val PICKER_OFFSET_X = EDITOR_PADDING + TOOLBAR_WIDTH + 6.dp
private val PICKER_OFFSET_Y = EDITOR_PADDING + 6.dp

/** 每次点击 −/+ 的缩放倍率。 */
private const val ZOOM_STEP = 1.25f

/**
 * 编辑器根：左侧工具栏 + 画布，外加两个浮层（形状子选择、清空确认）。
 *
 * 契约 §7 变体 B —— 工具栏在**左侧**，不是顶部。用户在门禁 A 选定，理由是画布可视面积
 * 多 17%，且顶部栏在 1a 就已经没有横向余量了。
 *
 * 两个浮层是根 Box 的兄弟节点而不是 Row 的子节点：契约 §3 要求形状子选择的展开与收起
 * **不改变** `canvas.viewport` 的尺寸，清空确认要盖满整个编辑器窗口。
 */
@Composable
fun BoardEditor(
    state: EditorState,
    saveStatus: SaveStatus,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    // rememberSaveable 而不是 remember：进程被回收重建时，正开着的确认框应该还在，
    // 否则用户回来看到白板完好、以为自己已经取消过了。
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    /** `canvas.viewport` 的实测尺寸（px）。缩放要以视口中心为锚点，必须知道它有多大。 */
    var canvasSizePx by remember { mutableStateOf(IntSize.Zero) }

    // 首次布局自动 fit：canvasSizePx 从 0 变成一个真实尺寸时执行一次 Viewport.fit。
    // 判据用「viewport 仍是默认值 Viewport()」而不是一个 hasFitted 布尔量——
    // 这样窗口第一次量出真实尺寸才会 fit；用户手动平移/缩放过一次之后，viewport 就再也不等于
    // 默认值了（fit 出来的 offset/scale 几乎不可能正好落回 (0,0,1.0)），后续 canvasSizePx
    // 因窗口尺寸变化而再次触发这个 effect 时也不会误把「用户选的视口」当成「还没 fit 过」而复位。
    LaunchedEffect(canvasSizePx) {
        if (canvasSizePx != IntSize.Zero && state.viewport == Viewport()) {
            state.viewport = Viewport.fit(
                viewW = canvasSizePx.width / density,
                viewH = canvasSizePx.height / density,
            )
        }
    }

    // Viewport 的 offset 与 CanvasSurface 里的换算一致，都是 dp（输入侧先除过 density）。
    fun zoomBy(factor: Float) {
        if (canvasSizePx == IntSize.Zero) return
        state.viewport = state.viewport.zoomAround(
            factor = factor,
            anchorSx = canvasSizePx.width / density / 2f,
            anchorSy = canvasSizePx.height / density / 2f,
        )
    }

    Box(
        // design-style: opaque-root —— manifest 已把 materialbackground 设为 "0" 关掉系统玻璃，
        // 这里自绘不透明底。理由（契约 §1/§12）：固定主题的视觉标识不能随房间环境光漂移。
        modifier = Modifier
            .fillMaxSize()
            .background(PicoTheme.colorScheme.fillSecondary)
            .then(modifier)
    ) {
        Row(Modifier.fillMaxSize().padding(EDITOR_PADDING)) {
            ToolBar(
                state = state,
                saveStatus = saveStatus,
                onZoomOut = { zoomBy(1f / ZOOM_STEP) },
                onZoomIn = { zoomBy(ZOOM_STEP) },
                onZoomFit = {
                    if (canvasSizePx != IntSize.Zero) {
                        state.viewport = Viewport.fit(
                            viewW = canvasSizePx.width / density,
                            viewH = canvasSizePx.height / density,
                        )
                    }
                },
                onClearRequested = { confirmingClear = true },
                onExport = onExport,
            )
            Spacer(Modifier.width(RAIL_CANVAS_GAP))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(CANVAS_RADIUS))
                    .onSizeChanged { canvasSizePx = it }
            ) {
                CanvasSurface(state, Modifier.fillMaxSize())
            }
        }

        // `shapePicker`：仅当形状工具被选中时出现，向右弹到画布上方。
        if (state.tool == Tool.Shape) {
            // 偏移挂在外层包装 Box 上，而不是传进 ShapePicker 的 modifier：
            // offset 若接在 background/padding 之后只会挪动内容，卡面本身留在原地。
            Box(Modifier.offset(x = PICKER_OFFSET_X, y = PICKER_OFFSET_Y)) {
                ShapePicker(state)
            }
        }

        // `clearDialog`：清空可撤销，但误触清空整块白板的心理代价远高于多点一次。
        if (confirmingClear) {
            ClearConfirmOverlay(
                onCancel = { confirmingClear = false },
                onConfirm = {
                    state.clearAll()
                    confirmingClear = false
                },
            )
        }
    }
}
