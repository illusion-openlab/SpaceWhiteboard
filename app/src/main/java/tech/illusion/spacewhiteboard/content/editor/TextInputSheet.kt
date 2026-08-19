package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextArea
import tech.illusion.spacewhiteboard.board.AddItem
import tech.illusion.spacewhiteboard.board.TextItem
import tech.illusion.spacewhiteboard.board.Viewport
import kotlin.math.min

/**
 * 文字工具默认字号，**画布单位**（与 `Stroke.widthDp` 同一类字面量，不是 `PicoTheme.typography`
 * 的某个排版角色——画布内容的字号要随 `viewport.scale` 一起缩放，主题排版角色是给 UI chrome
 * 文字用的、天生跟画布缩放无关，两者不是同一个维度，不能混用）。
 */
private const val TEXT_SIZE_BOARD = 48f

/**
 * 换行宽度的下限，画布单位（1b-2 复审修复项）。[wrapBoardFor] 优先取"画布宽度三分之一"与
 * "锚点到纸面右边界的剩余空间"两者中较小的一个，让越靠右的锚点换行越窄——但如果锚点已经
 * 非常靠近右边界，剩余空间可以趋近于 0，`Constraints(maxWidth = ...)` 允许的最小值理论上
 * 是 1px，实际测量出来的布局会变成一列几乎逐字换行的竖条，观感荒谬。这个下限保证换行宽度
 * 永远至少能装下大约两个 [TEXT_SIZE_BOARD] 字号的字符，不会退化成这种竖条；代价是紧贴右边界
 * 放置的文字，右侧一截仍会被 `clipToPaper` 裁掉——这是已知取舍，不是这一项要根治的问题
 * （根治需要选择工具把文字整体挪走，1b-4 才有）。
 */
private const val MIN_WRAP_BOARD = 160f

/** 换行宽度：取「画布宽度三分之一」与「锚点到纸面右边界的剩余空间」中较小的一个，再夹一个
 *  下限（1b-2 复审修复项，见 [MIN_WRAP_BOARD]）。旧版是不看锚点位置的固定常量
 *  `CANVAS_W / 3f`——锚点靠近右边界时，这个固定宽度会比剩余空间宽得多，换行永远不会发生，
 *  文字一路排到纸面外，被 `clipToPaper` 从中间裁断、且不可恢复（橡皮擦忽略文字、选择工具
 *  还没做）。测量与存进 [TextItem.wBoard] 的必须是同一个值——这也是为什么这个函数只依赖
 *  `anchor.x`，不依赖任何组合期状态：同一个锚点在任何调用点算出来的结果必须完全一致。 */
private fun wrapBoardFor(anchorX: Float): Float =
    min(Viewport.CANVAS_W / 3f, Viewport.CANVAS_W - anchorX).coerceAtLeast(MIN_WRAP_BOARD)

/** 面板卡面宽度，与 `clearDialog` 同宽（契约 §2.2 `clearDialog` 420dp），保持两个模态面板同一视觉语言。 */
private val SHEET_WIDTH = 420.dp
private val SHEET_RADIUS = 18.dp
private val SHEET_INPUT_MIN_H = 120.dp
private val SHEET_BUTTON_H = 44.dp
private val SHEET_BUTTON_HPAD = 24.dp
private val SHEET_BUTTON_GAP = 12.dp

/**
 * `clearDialog.scrim` 同一个不透明度（契约 §8：`fillSecondary.copy(alpha = 0.72f)`）——
 * 这不是另起一个数，是复用本应用已经确立的"模态遮罩"视觉语言，两个模态面板压出的观感一致。
 */
private const val SHEET_SCRIM_ALPHA = 0.72f

/**
 * 文字工具的输入面板：点画布定位之后弹出，`TextArea` 收系统输入法打进来的文字，
 * 确认后测量、生成 [TextItem] 并提交；取消则什么都不发生。
 *
 * 显示条件与调用方（[BoardEditor]）的 `if (state.pendingTextAnchor != null)` 一致；这里再判
 * 一次空是防御性的——`state.pendingTextAnchor` 一旦变 null（提交/取消之后）组合就应该整体
 * 消失，不应该走到下面用一个已经不存在的锚点提交。
 *
 * 系统输入法由系统自己创建、摆放（`AGENTS.md`/plan 已在模拟器实证：聚焦后
 * `mCurId=com.bytedance.pico.inputmethod/.PicoIME`、`mInputShown=true`，字符真的进了
 * `value`），这里不做任何定位相关的事——但**弹出这件事本身需要先有焦点**（1b-2 复审修复项，
 * 见下面 `LaunchedEffect` 的说明），面板出现不等于系统已经把输入焦点给了 `TextArea`。
 *
 * **卡面避开锚点，而不是靠边（1b-4 用户决策）**：锚点在画布上半区就把卡面放到下半区，反之
 * 放到上半区——不是把卡面移到某个固定角落。理由是书写文字最常见的用途是给画布上已有的东西
 * 加标注，构图时需要同时看见锚点位置和它周围的内容，靠边会让锚点附近整块被卡面挡住、什么都
 * 看不见；避开锚点所在的那一半，锚点周围至少留出半块画布可见。
 */
@Composable
fun TextInputSheet(state: EditorState, modifier: Modifier = Modifier) {
    val anchor = state.pendingTextAnchor ?: return
    val scheme = PicoTheme.colorScheme
    val typography = PicoTheme.typography
    // 组合期创建一次；实际测量的输入（字号/换行宽度/密度/布局方向/字体解析器）全部走
    // BoardDrawing.kt 的 measureTextLayout，与屏幕渲染共用同一个函数——两处不可能再各自
    // 维护一份不变式。
    val measurer = rememberTextMeasurer()
    // 显式读出来传给 measureTextLayout（1b-2 复审修复项）：与 CanvasSurface.kt 同一份修复的
    // 另一半，两个调用方都不再依赖 measurer 自带的默认 resolver。
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val focusManager = LocalFocusManager.current

    // 面板刚出现时抓一次焦点，系统 IME 才会跟着自动弹出（1b-2 复审修复项）——面板出现前用户
    // 必须先手动点一下 TextArea 才能开始打字，现在打字可以立刻开始。
    //
    // 没有用标准的 `FocusRequester` + `Modifier.focusRequester(...)`：那套机制要求 requester
    // 和真正的 `focusTarget()` 落在同一条 modifier 链上，但 `com.pico.spatial.ui.design`
    // 6.0.0 的 `TextArea`（源码见 design-6.0.0-sources.jar 的 `TextField.kt`）把外部传入的
    // `modifier` 参数用在最外层的 `BoxWithConstraints` 上，内部真正调用
    // `com.pico.spatial.ui.design.text.BasicTextField` 时用的是它自己另起的一个固定
    // `Modifier`——外部 `modifier` 根本不会流到那个真正持有 `focusTarget()` 的节点，挂在
    // `TextArea(modifier = ...)` 上的 `FocusRequester` 因此找不到目标，`requestFocus()` 会是
    // 静默空操作。改用 `LocalFocusManager.moveFocus`：不需要拿到目标节点的引用，只要面板刚
    // 出现、整个应用当前没有任何键盘焦点（PICO 的按钮/色板走的是控制器射线选中，不是标准 Compose
    // 键盘焦点系统，`TextArea` 内部的 `BasicTextField` 目前是这条 focus 链路上唯一的参与者），
    // `moveFocus(Next)` 就会落在焦点顺序里的第一个可聚焦节点上——本面板里 `TextArea` 排在
    // 取消/确认按钮之前，因此这一次调用落在它上面。
    LaunchedEffect(Unit) {
        focusManager.moveFocus(FocusDirection.Next)
    }

    // 换行宽度依赖锚点位置（见 wrapBoardFor 的说明），只在锚点第一次出现时算一次；锚点在这个
    // 面板存活期间不会变（提交/取消都会让 state.pendingTextAnchor 变 null、面板整体消失）。
    val wrapBoard = wrapBoardFor(anchor.x)
    // 卡面避开锚点所在的半区（见类注释「卡面避开锚点」）：CANVAS_H 是画布逻辑高度的中线，
    // 不是屏幕像素中线——这与 anchor 本身的坐标系一致，也是用户决策原文里"画布上/下半区"
    // 的字面含义，不需要经过 viewport 再换算成屏幕坐标。
    val sheetAlignment = if (anchor.y < Viewport.CANVAS_H / 2f) Alignment.BottomCenter else Alignment.TopCenter

    fun commitText(raw: String) {
        val text = raw.trim()
        // 空白文本不创建对象：一个看不见但存在的 TextItem 会占据撤销栈、出现在存档里，
        // 直接当取消处理——不留锚点、不留文本、不压 Op。
        if (text.isEmpty()) {
            state.cancelTextPlacement()
            return
        }
        val layout = measureTextLayout(text, TEXT_SIZE_BOARD, wrapBoard, measurer, fontFamilyResolver)
        state.commit(
            AddItem(
                TextItem(
                    id = state.newId(),
                    text = text,
                    colorArgb = state.colorArgb,
                    sizeBoard = TEXT_SIZE_BOARD,
                    anchor = anchor,
                    // 换行约束——多宽该折行，喂进 measureTextLayout 的那个数，跟渲染时用的必须
                    // 是同一个值（否则换行结果会不一致），但它不是这段文字实际有多宽。
                    wBoard = wrapBoard,
                    // measureTextLayout 用 TEXT_MEASURE_DENSITY（density=1f, fontScale=1f）测量，
                    // size.height/size.width 数值上就是画布单位，不需要（也不能）再除以设备密度。
                    hBoard = layout.size.height.toFloat(),
                    // 测量出的墨迹宽度——bounds()（进而导出裁切）真正读的是这个数，不是 wBoard
                    // （1b-2 复审修复项：见 Item.kt 的 TextItem 类注释）。一段没有触发换行的短
                    // 文本，这个数会远小于 wrapBoard；已用 BoardCodecTextTest 的
                    // "a short text's round-tripped extent is its measured ink width, not the
                    // wrap width" 锁定。
                    eBoard = layout.size.width.toFloat(),
                ),
            ),
        )
        state.cancelTextPlacement()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 主题角色不透明，遮罩底色必须自己 copy(alpha)。
            .background(scheme.fillSecondary.copy(alpha = SHEET_SCRIM_ALPHA))
            // 必须消费指针事件，否则点击会穿透遮罩点亮下层工具栏（与 ClearConfirmOverlay 同一条教训）。
            .consumeAllPointerEvents(),
        contentAlignment = sheetAlignment,
    ) {
        Column(
            modifier = Modifier
                .width(SHEET_WIDTH)
                .clip(RoundedCornerShape(SHEET_RADIUS))
                .background(scheme.fillSecondary)
                .background(scheme.lightenHover)
                .border(1.dp, scheme.dividerLine, RoundedCornerShape(SHEET_RADIUS))
                .padding(start = 26.dp, top = 26.dp, end = 26.dp, bottom = 20.dp),
        ) {
            Text("输入文字", color = scheme.labelPrimary, style = typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TextArea(
                value = state.pendingText,
                onValueChange = { state.pendingText = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = SHEET_INPUT_MIN_H),
                placeholder = { Text("输入文字…", style = typography.bodyLarge) },
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(SHEET_BUTTON_GAP),
            ) {
                // 取消/确认视觉上不分主次（都是 fillLight 底 + labelPrimary 内容）——
                // fillPrimary 在这个设计系统里是"全应用唯一"的强调色（ToolBar.kt 的
                // act.export 独占），这里再引入第二个 fillPrimary 实心按钮会破坏那条约定。
                Button(
                    onClick = { state.cancelTextPlacement() },
                    size = ButtonDefaults.buttonSize(height = SHEET_BUTTON_H),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.fillLight,
                        contentColor = scheme.labelPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = SHEET_BUTTON_HPAD),
                    shape = CircleShape,
                ) {
                    Text("取消", style = typography.titleMedium)
                }
                Button(
                    onClick = { commitText(state.pendingText) },
                    size = ButtonDefaults.buttonSize(height = SHEET_BUTTON_H),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.fillLight,
                        contentColor = scheme.labelPrimary,
                    ),
                    contentPadding = PaddingValues(horizontal = SHEET_BUTTON_HPAD),
                    shape = CircleShape,
                ) {
                    Text("确认", style = typography.titleMedium)
                }
            }
        }
    }
}
