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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * 文字工具默认字号，**画布单位**（与 `Stroke.widthDp` 同一类字面量，不是 `PicoTheme.typography`
 * 的某个排版角色——画布内容的字号要随 `viewport.scale` 一起缩放，主题排版角色是给 UI chrome
 * 文字用的、天生跟画布缩放无关，两者不是同一个维度，不能混用）。
 */
private const val TEXT_SIZE_BOARD = 48f

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
 * `value`），这里不做任何定位相关的事。
 */
@Composable
fun TextInputSheet(state: EditorState, modifier: Modifier = Modifier) {
    val anchor = state.pendingTextAnchor ?: return
    val scheme = PicoTheme.colorScheme
    val typography = PicoTheme.typography
    // 组合期创建一次；实际测量的输入（字号/换行宽度/密度）全部走 BoardDrawing.kt 的
    // measureTextLayout，与屏幕渲染共用同一个函数——两处不可能再各自维护一份不变式。
    val measurer = rememberTextMeasurer()

    fun commitText(raw: String) {
        val text = raw.trim()
        // 空白文本不创建对象：一个看不见但存在的 TextItem 会占据撤销栈、出现在存档里，
        // 直接当取消处理——不留锚点、不留文本、不压 Op。
        if (text.isEmpty()) {
            state.cancelTextPlacement()
            return
        }
        // 换行宽度取画布宽度的三分之一；测量与存进 TextItem.wBoard 的必须是同一个值，
        // 否则渲染换行与 bounds()（进而 PNG 导出裁切）会不一致。
        val wrapBoard = Viewport.CANVAS_W / 3f
        val layout = measureTextLayout(text, TEXT_SIZE_BOARD, wrapBoard, measurer)
        state.commit(
            AddItem(
                TextItem(
                    id = state.newId(),
                    text = text,
                    colorArgb = state.colorArgb,
                    sizeBoard = TEXT_SIZE_BOARD,
                    anchor = anchor,
                    wBoard = wrapBoard,
                    // measureTextLayout 用 TEXT_MEASURE_DENSITY（density=1f, fontScale=1f）测量，
                    // size.height 数值上就是画布单位，不需要（也不能）再除以设备密度。
                    hBoard = layout.size.height.toFloat(),
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
        contentAlignment = Alignment.Center,
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
