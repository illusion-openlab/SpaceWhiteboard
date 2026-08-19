package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.ToggleButton
import com.pico.spatial.ui.design.ToggleButtonDefaults
import com.pico.spatial.ui.design.ToggleIconButton
import com.pico.spatial.ui.design.ToggleIconButtonDefaults
import com.pico.spatial.ui.foundation.haptic.controllerHapticFeedback
import com.pico.spatial.ui.foundation.hover.spatialHoverEffect
import tech.illusion.spacewhiteboard.board.ShapeKind
import tech.illusion.spacewhiteboard.board.Viewport

/**
 * 自动保存状态指示（设计契约 §2.1 `saveStatus` / §3 状态表 / §4 文案表）。
 * Task 17 接线自动保存，真实状态从 [tech.illusion.spacewhiteboard.content.editor.AutoSave] 传进 [BoardEditor]。
 * 契约 §5 明确 1a 的 `saveStatus` 不可点击，所以这里没有回调。
 *
 * `Idle` 是 Task 17 新加的第四态：应用刚启动、还没有过一次保存尝试（debounce 从未触发，
 * 也没有发生过 onPause/onStop）。契约 §2.1 对 `saveStatus` 三态（Saved/Saving/Failed）
 * 都有视觉定义，唯独没提这种"还不知道"的初始态——用 `Saved` 顶替会撒谎（其实还没存过），
 * 用 `Failed` 顶替会误报。§2.1 原文写"Idle → 不显示"，即这一态不画东西，因此新增
 * 一个不落在契约状态表里的值是安全的：它只影响"画不画"，不影响其余三态的既有视觉。
 */
enum class SaveStatus { Idle, Saving, Saved, Failed }

// ── 设计契约 §2.3 变体 B（左侧栏）的几何。全部来自契约，不要就地"微调"。──────────────
/** `toolbar` 宽 120dp，上下内边距 6dp，子项间距 8dp。 */
val TOOLBAR_WIDTH = 120.dp
private val RAIL_PADDING = 6.dp
private val GROUP_GAP = 8.dp

/** `div.1`–`div.5`：88×1dp。 */
private val DIVIDER_WIDTH = 88.dp

/** `tool.*`：2×2 网格，48×48dp，圆角 12dp，格间距 6dp。 */
private val TOOL_SIZE = 48.dp
private val TOOL_GAP = 6.dp
private val TOOL_RADIUS = 12.dp

/** `palette`：100×188dp 纸面托盘，内边距 3dp；`color.*` 格 46×44dp、色点 26dp、格间距 2dp。 */
private val TRAY_PADDING = 3.dp
private val TRAY_RADIUS = 12.dp
private val SWATCH_CELL_W = 46.dp
private val SWATCH_CELL_H = 44.dp
private val SWATCH_GAP = 2.dp
private val SWATCH_DOT = 26.dp
private val SWATCH_RING_UNSELECTED = 1.5.dp
private val SWATCH_RING_SELECTED = 3.dp
/** 色板格本身没有可见底，圆角只用于裁剪 hover/按压效果的形状。 */
private val SWATCH_CELL_RADIUS = 9.dp

/** `widthTray`：104×50dp；`width.*` 格 30×44dp、格间距 4dp、圆角 9dp；预览条宽 18dp。 */
private val WIDTH_CELL_W = 30.dp
private val WIDTH_CELL_H = 44.dp
private val WIDTH_GAP = 4.dp
private val WIDTH_CELL_RADIUS = 9.dp
private val WIDTH_BAR_W = 18.dp

/** `act.undo` / `act.redo`：46×44dp，圆角 10dp，格间距 4dp。 */
private val ACTION_W = 46.dp
private val ACTION_H = 44.dp
private val ACTION_RADIUS = 10.dp
private val ACTION_GAP = 4.dp

/** `act.clear` / `zoom.fit` / `act.export`：96×44dp（B 变体带文字的整行按钮）。 */
private val WIDE_BUTTON_W = 96.dp

/** `zoom.out` / `zoom.in`：34×44dp；`zoom.value` 最小宽 46dp；三者间距 2dp。 */
private val ZOOM_STEP_W = 34.dp
private val ZOOM_VALUE_MIN_W = 46.dp
private val ZOOM_GAP = 2.dp

/** `saveStatus`：高 40dp，左右内边距 10dp，圆点直径 8dp。 */
private val SAVE_ROW_H = 40.dp
private val SAVE_ROW_PADDING = 10.dp
private val SAVE_DOT = 8.dp

/** `shapePicker`：内边距 6dp，选项高 38dp、左右内边距 12dp、圆角 9dp、间距 4dp，卡面圆角 12dp。 */
private val PICKER_PADDING = 6.dp
private val PICKER_RADIUS = 12.dp
private val PICKER_ITEM_H = 38.dp
private val PICKER_ITEM_HPAD = 12.dp
private val PICKER_ITEM_RADIUS = 9.dp
private val PICKER_ITEM_GAP = 4.dp

/** `clearDialog`：卡面宽 420dp、圆角 18dp、内边距 26/26/20，按钮高 44dp、左右内边距 24dp。 */
private val DIALOG_W = 420.dp
private val DIALOG_RADIUS = 18.dp
private val DIALOG_BUTTON_H = 44.dp
private val DIALOG_BUTTON_HPAD = 24.dp
private val DIALOG_BUTTON_GAP = 12.dp

/** `clearDialog.scrim`：主题角色是不透明的，遮罩必须自己 copy(alpha)（契约 §8 实现要点）。 */
private const val SCRIM_ALPHA = 0.72f

/** 未选中色点外环 `labelPrimaryLight` @20%。 */
private const val SWATCH_RING_ALPHA = 0.2f

/** 选中粗细格底 `fillPrimary` @12%。 */
private const val WIDTH_SELECTED_FILL_ALPHA = 0.12f

/**
 * 等宽数字的 OpenType 特性标签。
 * SpatialUI 没有 tabular-figures 参数，唯一写法是把它塞进 `TextStyle.fontFeatureSettings`
 * （`Text` 的 `style` 收的是真正的 `androidx.compose.ui.text.TextStyle`，能一路传到 TextPaint）。
 * 用常量而不是到处写字面量：标签拼错不报错，只是静默失效。
 * **仍未验证**：PICO 系统字体是否真的带 `tnum` GSUB 表 —— 单张截图看不出来，需要真机看跨位数时宽度是否稳定。
 */
private const val TABULAR_FIGURES = "tnum"

/** 缩放边界比较用的容差，避免浮点相等判断把按钮卡在"永远可用"。 */
private const val SCALE_EPS = 1e-3f

/**
 * 编辑器左侧工具栏（设计契约 §7 变体 B —— 用户在门禁 A 已选定左侧，不是顶部）。
 *
 * 1a 的控件清单固定为：工具四选一 / 颜色八选一 / 粗细三选一 / 撤销重做清空 /
 * 缩放三件套 + 适应窗口 / 导出 PNG / 保存状态。文字、图片、选择工具属于 1b，不得提前加入（契约 §5）。
 *
 * 关于图标：契约把 `tool.*` / `act.*` / `zoom.*` 画成图标按钮，但 SpatialUI 6.0.0 只随包提供
 * `ic_sui_*` 这些库内部图标（搜索/关闭/箭头等），没有画笔、橡皮、撤销之类的业务图标，本项目也没有
 * 自备图标资源。按 Task 15 brief 的明确许可，这里一律用文字标签，不猜图标 API、不引外部图标包。
 */
@Composable
fun ToolBar(
    state: EditorState,
    saveStatus: SaveStatus,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomFit: () -> Unit,
    onClearRequested: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = PicoTheme.colorScheme
    val typography = PicoTheme.typography

    Column(
        modifier = Modifier
            .width(TOOLBAR_WIDTH)
            .fillMaxHeight()
            .padding(vertical = RAIL_PADDING)
            .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GROUP_GAP),
    ) {
        // ── 工具四选一（互斥）：2×2 网格 ────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(TOOL_GAP)) {
            Row(horizontalArrangement = Arrangement.spacedBy(TOOL_GAP)) {
                ToolToggle(state, Tool.Pen, "画笔")
                ToolToggle(state, Tool.Shape, "形状")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TOOL_GAP)) {
                ToolToggle(state, Tool.Eraser, "橡皮")
                ToolToggle(state, Tool.Pan, "移动")
            }
        }

        RailDivider()   // div.1

        // ── 颜色八选一：2 列 × 4 行，摆在 fillTertiary 的"纸面托盘"上 ────────────────
        // 墨黑色点直接压在深色 chrome 上几乎看不见，托盘等于"色板就是一小片纸"（契约 §9）。
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(TRAY_RADIUS))
                .background(scheme.fillTertiary)
                .padding(TRAY_PADDING),
            verticalArrangement = Arrangement.spacedBy(SWATCH_GAP),
        ) {
            PALETTE.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP)) {
                    row.forEach { argb -> ColorSwatch(state, argb) }
                }
            }
        }

        RailDivider()   // div.2

        // ── 粗细三档：预览条用当前墨水色画，否则在浅色托盘上读不出来 ─────────────────
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(TRAY_RADIUS))
                .background(scheme.fillTertiary)
                .padding(TRAY_PADDING),
            horizontalArrangement = Arrangement.spacedBy(WIDTH_GAP),
        ) {
            WIDTHS.forEach { w -> WidthOption(state, w) }
        }

        RailDivider()   // div.3

        // ── 撤销 / 重做 / 清空 ─────────────────────────────────────────────────────
        // canUndo / canRedo 是 UndoStack 的 snapshot 镜像，每条变更路径都会刷新它们。
        // 禁用态不需要自己降对比：BasicButton 在 enabled=false 时读 LocalDisableAlpha(0.3f)
        // 自行施加 graphicsLayer alpha（已核对 design-6.0.0-sources.jar）。
        Row(horizontalArrangement = Arrangement.spacedBy(ACTION_GAP)) {
            ActionButton("撤销", enabled = state.canUndo, onClick = { state.undo() })
            ActionButton("重做", enabled = state.canRedo, onClick = { state.redo() })
        }
        Button(
            onClick = onClearRequested,
            enabled = state.doc.items.isNotEmpty(),
            size = ButtonDefaults.buttonSize(width = WIDE_BUTTON_W, height = ACTION_H),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.fillLight,
                contentColor = scheme.error,
            ),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(ACTION_RADIUS),
        ) {
            Text("清空", style = typography.bodySmall)
        }

        RailDivider()   // div.4

        // ── 缩放：− / 百分比 / +，外加"适应窗口" ────────────────────────────────────
        // 三个入口都由调用方（BoardEditor）提供实现；百分比直接读 viewport.scale，不是假数据。
        val scale = state.viewport.scale
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZOOM_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZoomStepButton("−", "缩小", enabled = scale > Viewport.MIN_SCALE + SCALE_EPS, onClick = onZoomOut)
            Text(
                text = "${(scale * 100).roundToInt()}%",
                modifier = Modifier.widthIn(min = ZOOM_VALUE_MIN_W),
                color = scheme.labelSecondary,
                textAlign = TextAlign.Center,
                // 等宽数字：100% → 75% 时位数变化，不加 tnum 整条左栏的宽度会跟着抖。
                style = typography.bodySmall.copy(fontFeatureSettings = TABULAR_FIGURES),
            )
            ZoomStepButton("+", "放大", enabled = scale < Viewport.MAX_SCALE - SCALE_EPS, onClick = onZoomIn)
        }
        Button(
            onClick = onZoomFit,
            size = ButtonDefaults.buttonSize(width = WIDE_BUTTON_W, height = ACTION_H),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.fillLight,
                contentColor = scheme.labelPrimary,
            ),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(ACTION_RADIUS),
        ) {
            Text("适应窗口", style = typography.bodySmall)
        }

        RailDivider()   // div.5

        // ── 导出 PNG：全应用唯一的 fillPrimary 实心按钮（除选中态之外）────────────────
        Button(
            onClick = onExport,
            size = ButtonDefaults.buttonSize(width = WIDE_BUTTON_W, height = ACTION_H),
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.fillPrimary,
                contentColor = scheme.labelPrimary,
            ),
            contentPadding = PaddingValues(0.dp),
            shape = RoundedCornerShape(ACTION_RADIUS),
        ) {
            Text("导出 PNG", style = typography.bodySmall)
        }

        // 保存状态贴最下端（契约 §2.1：B 变体中间用 weight 撑开）
        Spacer(Modifier.weight(1f))
        SaveStatusRow(saveStatus)
    }
}

/** `div.1`–`div.5`：分组之间的细分隔线。 */
@Composable
private fun RailDivider() {
    HorizontalDivider(
        modifier = Modifier.width(DIVIDER_WIDTH),
        color = PicoTheme.colorScheme.dividerLine,
        thickness = 1.dp,
    )
}

/**
 * `tool.*`：四选一互斥。
 *
 * 用 [ToggleIconButton] 而不是自绘：选中/未选中两套配色、hover、按压、触感、无障碍
 * 都由设计系统给全。互斥语义靠"取消勾选时不做任何事"实现 —— 再点一次当前工具不会把它关掉。
 */
@Composable
private fun ToolToggle(state: EditorState, tool: Tool, label: String) {
    val scheme = PicoTheme.colorScheme
    ToggleIconButton(
        checked = state.tool == tool,
        onCheckedChange = { checked -> if (checked) state.tool = tool },
        colors = ToggleIconButtonDefaults.toggleIconButtonColors(
            checkedContainerColor = scheme.fillPrimary,
            checkedContentColor = scheme.labelPrimary,
            uncheckedContainerColor = scheme.fillLight,
            uncheckedContentColor = scheme.labelSecondary,
        ),
        size = ToggleIconButtonDefaults.toggleIconButtonSize(TOOL_SIZE),
        shape = RoundedCornerShape(TOOL_RADIUS),
    ) {
        Text(label, style = PicoTheme.typography.bodySmall)
    }
}

/**
 * `color.*`：浅色纸面托盘上的选项，选中语言是**外环**而不是填充 ——
 * 填充会盖掉墨水色本身，而那正是要看的东西（契约 §9）。
 *
 * 没有对应的内置组件（`Option` 是卡片式选项，`ToggleIconButton` 的内容会被容器着色接管），所以按 custom-component 规则自绘：
 * 尺寸 → 形状 → spatialHoverEffect → clickable(indication) → controllerHapticFeedback。
 */
@Composable
private fun ColorSwatch(state: EditorState, argb: Int) {
    val scheme = PicoTheme.colorScheme
    val selected = state.colorArgb == argb
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = SWATCH_CELL_W, height = SWATCH_CELL_H)
            .clip(RoundedCornerShape(SWATCH_CELL_RADIUS))
            .spatialHoverEffect()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ) { state.colorArgb = argb }
            .controllerHapticFeedback(interactionSource = interactionSource),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SWATCH_DOT)
                .clip(CircleShape)
                .background(Color(argb))
                .border(
                    width = if (selected) SWATCH_RING_SELECTED else SWATCH_RING_UNSELECTED,
                    // 主题角色不透明，未选中的淡环必须自己 copy(alpha)
                    color = if (selected) scheme.fillPrimary
                    else scheme.labelPrimaryLight.copy(alpha = SWATCH_RING_ALPHA),
                    shape = CircleShape,
                )
        )
    }
}

/** `width.*`：预览条用当前墨水色画；选中格底 `fillPrimary`@12% + 2dp 外环。 */
@Composable
private fun WidthOption(state: EditorState, widthDp: Float) {
    val scheme = PicoTheme.colorScheme
    val selected = state.widthDp == widthDp
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(width = WIDTH_CELL_W, height = WIDTH_CELL_H)
            .clip(RoundedCornerShape(WIDTH_CELL_RADIUS))
            .spatialHoverEffect()
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
            ) { state.widthDp = widthDp }
            .controllerHapticFeedback(interactionSource = interactionSource)
            .then(
                if (selected) Modifier
                    .background(scheme.fillPrimary.copy(alpha = WIDTH_SELECTED_FILL_ALPHA))
                    .border(2.dp, scheme.fillPrimary, RoundedCornerShape(WIDTH_CELL_RADIUS))
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = WIDTH_BAR_W, height = widthDp.dp)
                .clip(CircleShape)
                .background(Color(state.colorArgb))
        )
    }
}

/** `act.undo` / `act.redo`：底 `fillLight`，内容 `labelSecondary`，禁用态由 SDK 自动降透明度。 */
@Composable
private fun ActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = scheme.fillLight,
            contentColor = scheme.labelSecondary,
        ),
        size = IconButtonDefaults.iconButtonSize(width = ACTION_W, height = ACTION_H),
        shape = RoundedCornerShape(ACTION_RADIUS),
        enabled = enabled,
    ) {
        Text(label, style = PicoTheme.typography.bodySmall)
    }
}

/**
 * `zoom.out` / `zoom.in`。
 *
 * 显示的是 `−` / `+` 字形而不是契约文案表里的「缩小」「放大」——契约把这两个元素定义为
 * **图标**按钮，文案列注明是无障碍/悬浮说明。既然本项目没有图标资源，`−`/`+` 字形比两个
 * 汉字更接近原设计意图，同时把契约文案挂到 semantics 上，无障碍读出的仍是「缩小」「放大」。
 */
@Composable
private fun ZoomStepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = scheme.fillLight,
            contentColor = scheme.labelSecondary,
        ),
        size = IconButtonDefaults.iconButtonSize(width = ZOOM_STEP_W, height = ACTION_H),
        shape = RoundedCornerShape(ACTION_RADIUS),
        enabled = enabled,
    ) {
        Text(glyph, style = PicoTheme.typography.bodySmall)
    }
}

/**
 * `saveStatus`：圆点 + 文字，不可点击（契约 §5、§11 歧义 #4：1a 不做点击重试）。
 * `Idle` 按契约 §2.1「不显示」处理——直接不画，Spacer(weight=1f) 照常把其余内容顶住。
 */
@Composable
private fun SaveStatusRow(status: SaveStatus) {
    if (status == SaveStatus.Idle) return
    val scheme = PicoTheme.colorScheme
    val dotColor = when (status) {
        SaveStatus.Idle -> return
        SaveStatus.Saved -> scheme.passable
        SaveStatus.Saving -> scheme.alert
        SaveStatus.Failed -> scheme.error
    }
    val textColor = if (status == SaveStatus.Failed) scheme.error else scheme.labelSecondary
    val label = when (status) {
        SaveStatus.Idle -> return
        SaveStatus.Saved -> "已保存"
        SaveStatus.Saving -> "保存中…"
        SaveStatus.Failed -> "保存失败"
    }
    Row(
        modifier = Modifier
            .height(SAVE_ROW_H)
            .padding(horizontal = SAVE_ROW_PADDING),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SAVE_DOT).clip(CircleShape).background(dotColor))
        Text(label, color = textColor, style = PicoTheme.typography.bodySmall)
    }
}

/**
 * `shapePicker`：仅当形状工具被选中时出现。
 *
 * 它是**浮层**，由 [BoardEditor] 放在编辑器根 Box 里绝对定位，不参与左栏的 Column 排布 ——
 * 契约 §3 要求展开与收起都不改变 `canvas.viewport` 的尺寸。
 */
@Composable
internal fun ShapePicker(state: EditorState, modifier: Modifier = Modifier) {
    val scheme = PicoTheme.colorScheme
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(PICKER_RADIUS))
            // 卡面 = fillSecondary 之上叠一层 lightenHover（8% 白），两层背景依次绘制即可
            .background(scheme.fillSecondary)
            .background(scheme.lightenHover)
            .border(1.dp, scheme.dividerLine, RoundedCornerShape(PICKER_RADIUS))
            .padding(PICKER_PADDING)
            .then(modifier),
        verticalArrangement = Arrangement.spacedBy(PICKER_ITEM_GAP),
    ) {
        ShapeOption(state, ShapeKind.Line, "直线")
        ShapeOption(state, ShapeKind.Rect, "矩形")
        ShapeOption(state, ShapeKind.Ellipse, "圆")
    }
}

/** `shape.*`：三选一互斥，选中语言与 `tool.*` 一致（深色 chrome 上用填充）。 */
@Composable
private fun ShapeOption(state: EditorState, kind: ShapeKind, label: String) {
    val scheme = PicoTheme.colorScheme
    ToggleButton(
        checked = state.shapeKind == kind,
        onCheckedChange = { checked -> if (checked) state.shapeKind = kind },
        colors = ToggleButtonDefaults.toggleButtonColors(
            checkedContainerColor = scheme.fillPrimary,
            checkedContentColor = scheme.labelPrimary,
            uncheckedContainerColor = scheme.fillLight,
            uncheckedContentColor = scheme.labelSecondary,
        ),
        size = ButtonDefaults.buttonSize(height = PICKER_ITEM_H),
        // 自定义 ButtonSize 落进 shapeForButtonSize 的 else 分支 = RectangleShape，
        // contentPadding 也会落回默认值，两者都必须显式传，否则出来是个直角大按钮。
        contentPadding = PaddingValues(horizontal = PICKER_ITEM_HPAD),
        shape = RoundedCornerShape(PICKER_ITEM_RADIUS),
    ) {
        Text(label, style = PicoTheme.typography.bodySmall)
    }
}

/**
 * `clearDialog`：清空的二次确认。
 *
 * 清空本身可撤销（一次 `RemoveItems`），仍然要确认 —— 误触清空整块白板的心理代价
 * 远高于多点一次，所以正文明说"此操作可以撤销"来压低确认成本（契约 §11 歧义 #6）。
 *
 * 两条必做：遮罩自己 `copy(alpha)`（主题角色不透明，直接用会挡死画布），
 * 以及遮罩必须消费指针事件（见 [consumeAllPointerEvents]）。
 */
@Composable
internal fun ClearConfirmOverlay(onCancel: () -> Unit, onConfirm: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    val typography = PicoTheme.typography
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.fillSecondary.copy(alpha = SCRIM_ALPHA))
            .consumeAllPointerEvents(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(DIALOG_W)
                .clip(RoundedCornerShape(DIALOG_RADIUS))
                .background(scheme.fillSecondary)
                .background(scheme.lightenHover)
                .border(1.dp, scheme.dividerLine, RoundedCornerShape(DIALOG_RADIUS))
                .padding(start = 26.dp, top = 26.dp, end = 26.dp, bottom = 20.dp)
        ) {
            Text("清空白板？", color = scheme.labelPrimary, style = typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "将删除画布上的全部内容，此操作可以撤销。",
                color = scheme.labelSecondary,
                style = typography.bodyMedium,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(DIALOG_BUTTON_GAP),
            ) {
                Button(
                    onClick = onCancel,
                    size = ButtonDefaults.buttonSize(height = DIALOG_BUTTON_H),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.fillLight,
                        contentColor = scheme.labelPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = DIALOG_BUTTON_HPAD),
                    shape = CircleShape,
                ) {
                    // 契约指定了字体角色的地方一律显式传 style，不吃 Button 的默认值
                    Text("取消", style = typography.titleMedium)
                }
                Button(
                    onClick = onConfirm,
                    size = ButtonDefaults.buttonSize(height = DIALOG_BUTTON_H),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.labelPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = DIALOG_BUTTON_HPAD),
                    shape = CircleShape,
                ) {
                    Text("清空", style = typography.titleMedium)
                }
            }
        }
    }
}

/**
 * 让一个覆盖层真正挡住下层交互。
 *
 * `Modifier.background` 不装 pointer-input 节点，Compose 的命中测试是按位置做的，
 * 所以一个只有 `fillMaxSize() + background()` 的遮罩**挡不住**下层：射线落在遮罩上
 * （而不是落在中间那块卡片上）的区域会穿透到兄弟节点。SpatialUI 的按钮无条件挂
 * `spatialHoverEffect` + `clickable` + `controllerHapticFeedback`，于是用户在覆盖层上
 * 扫射线时，下面那排工具栏按钮会 hover 高亮、给触感，甚至被点中 —— 包括这个对话框
 * 正在守护的「清空」本身。
 *
 * 用默认的 `PointerEventPass.Main`（自底向上）：卡片自己的按钮先于祖先的消费循环
 * 拿到事件，所以对话框上的「取消」「清空」照常可点。
 *
 * 这不是死代码，编译、单测和静态截图都发现不了它缺失 —— 删之前先想清楚。
 */
private fun Modifier.consumeAllPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent().changes.forEach { it.consume() }
        }
    }
}
