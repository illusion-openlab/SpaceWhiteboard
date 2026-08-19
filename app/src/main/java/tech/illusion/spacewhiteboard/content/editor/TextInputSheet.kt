package tech.illusion.spacewhiteboard.content.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.design.TextArea
import kotlinx.coroutines.delay
import tech.illusion.spacewhiteboard.board.AddItem
import tech.illusion.spacewhiteboard.board.Pt
import tech.illusion.spacewhiteboard.board.TextItem
import tech.illusion.spacewhiteboard.board.Viewport
import kotlin.math.min

/**
 * 文字工具默认字号，**画布单位**（与 `Stroke.widthDp` 同一类字面量，不是 `PicoTheme.typography`
 * 的某个排版角色——画布内容的字号要随 `viewport.scale` 一起缩放，主题排版角色是给 UI chrome
 * 文字用的、天生跟画布缩放无关，两者不是同一个维度，不能混用）。
 *
 * 从 `private` 改成 `internal`（1b-2 真机反馈修复项）：`CanvasSurface.kt` 的文字插入光标
 * （`drawTextCaret`）需要跟这里量出同一个 [oneLineBoard]，唯一办法是喂同一个字号常量给同一个
 * [measureTextLayout]——两处各自定义一份 48f 字面量，迟早会有一处改了另一处没跟上。
 */
internal const val TEXT_SIZE_BOARD = 48f

/**
 * 换行宽度的下限，画布单位（1b-2 复审修复项）。[wrapBoardFor] 优先取"画布宽度三分之一"与
 * "锚点到纸面右边界的剩余空间"两者中较小的一个，让越靠右的锚点换行越窄——但如果锚点已经
 * 非常靠近右边界，剩余空间可以趋近于 0，`Constraints(maxWidth = ...)` 允许的最小值理论上
 * 是 1px，实际测量出来的布局会变成一列几乎逐字换行的竖条，观感荒谬。这个下限保证换行宽度
 * 永远至少能装下大约两个 [TEXT_SIZE_BOARD] 字号的字符，不会退化成这种竖条；代价是紧贴右边界
 * 放置的文字，右侧一截仍会被 `clipToPaper` 裁掉——这是已知取舍，不是这一项要根治的问题
 * （根治需要选择工具把文字整体挪走，1b-4 才有）。
 */
internal const val MIN_WRAP_BOARD = 160f

/** 换行宽度：取「画布宽度三分之一」与「锚点到纸面右边界的剩余空间」中较小的一个，再夹一个
 *  下限（1b-2 复审修复项，见 [MIN_WRAP_BOARD]）。旧版是不看锚点位置的固定常量
 *  `CANVAS_W / 3f`——锚点靠近右边界时，这个固定宽度会比剩余空间宽得多，换行永远不会发生，
 *  文字一路排到纸面外，被 `clipToPaper` 从中间裁断、且不可恢复（橡皮擦忽略文字、选择工具
 *  还没做）。测量与存进 [TextItem.wBoard] 的必须是同一个值——这也是为什么这个函数只依赖
 *  `anchor.x`，不依赖任何组合期状态：同一个锚点在任何调用点算出来的结果必须完全一致。 */
private fun wrapBoardFor(anchorX: Float): Float =
    min(Viewport.CANVAS_W / 3f, Viewport.CANVAS_W - anchorX).coerceAtLeast(MIN_WRAP_BOARD)

/**
 * 把锚点再往纸面内侧收紧一次（1b-2 复审修复项 C）。锚点在这里进来之前已经被
 * `EditorState.beginTextPlacement` 夹进了 `[0, CANVAS_W] × [0, CANVAS_H]`——但那个夹取只保证
 * 锚点本身合法，不保证锚点之后还留得下任何东西。锚点正好夹在纸面右/下边界上时：横向，
 * [wrapBoardFor] 算出的"剩余空间"趋近于 0，会被 [MIN_WRAP_BOARD] 这个下限顶到 160，但锚点
 * 本身没挪、换行框整段落在 `[CANVAS_W, CANVAS_W + 160]`，一个字都不在纸面内；纵向同理，
 * 锚点贴在 `CANVAS_H` 上，连第一行的顶端都已经在纸面外。`clipToPaper` 会把这样的
 * `TextItem` 整段裁没——橡皮擦不认文字、1b-4 之前也没有选择工具，用户能做的只有撤销这一步
 * 或者清空整块白板，回收不了这一个看不见的对象。
 *
 * 这里再收紧一次上界：横向至少留出 [MIN_WRAP_BOARD]（换行宽度的下限，也就是不管怎么写、
 * 第一行至少能装下的宽度），纵向至少留出 [oneLineBoard]（真按 `TEXT_SIZE_BOARD` 测出来的
 * 一行高度，不是猜的比例）。不会把锚点推出 `[0, CANVAS_W] × [0, CANVAS_H]`——两个上界
 * （`CANVAS_W - MIN_WRAP_BOARD`、`CANVAS_H - oneLineBoard`）都远大于 0，`coerceAtMost`
 * 只收紧上界，原有的非负下界不受影响。[wrapBoardFor] 与最终提交进 [TextItem.anchor] 的必须
 * 是同一个收紧过的值——两处如果各读一份、其中一处漏了收紧，纸面边缘的失踪文字就会重新出现。
 */
private fun containAnchor(anchor: Pt, oneLineBoard: Float): Pt = Pt(
    anchor.x.coerceAtMost(Viewport.CANVAS_W - MIN_WRAP_BOARD),
    anchor.y.coerceAtMost(Viewport.CANVAS_H - oneLineBoard),
)

/** 面板卡面宽度，与 `clearDialog` 同宽（契约 §2.2 `clearDialog` 420dp），保持两个模态面板同一视觉语言。 */
private val SHEET_WIDTH = 420.dp
private val SHEET_RADIUS = 18.dp
private val SHEET_INPUT_MIN_H = 120.dp
private val SHEET_BUTTON_H = 44.dp
private val SHEET_BUTTON_HPAD = 24.dp
private val SHEET_BUTTON_GAP = 12.dp

/**
 * 卡面与锚点/光标（[drawTextCaret]）之间必须留出的可见间隙（1b-2 真机反馈修复项）——贴邻
 * 不等于零边距，题面明确要求"留一个看得见的缝隙，不是零边距贴合"。
 */
private val SHEET_ANCHOR_GAP = 16.dp

/**
 * `clearDialog.scrim` 同一个不透明度（契约 §8：`fillSecondary.copy(alpha = 0.72f)`）——
 * 这不是另起一个数，是复用本应用已经确立的"模态遮罩"视觉语言，两个模态面板压出的观感一致。
 */
private const val SHEET_SCRIM_ALPHA = 0.72f

/**
 * `focusRequester.requestFocus()` 失败重试之间的等待。不用 `withFrameNanos`：本项目已经
 * 证实它在 `DefaultWindowContainer` 里只触发一次、之后再也不跑（`AGENTS.md`），这里改用
 * `delay`。取值只需要明显小于用户能感知的延迟，不是一个需要在真机上标定的手感数字。
 */
private const val FOCUS_RETRY_DELAY_MS = 32L

/**
 * `requestFocus()` 的固定重试次数上限——不是"直到聚焦成功为止"的无界循环，见下面
 * `LaunchedEffect` 处的说明。命中即停：一旦某次调用不再抛异常就立刻返回，不会把剩余次数
 * 也打满。
 */
private const val FOCUS_RETRY_ATTEMPTS = 3

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
 * **卡面贴邻锚点，不再对半选边（1b-2 真机反馈修复项，取代 886c4b7 的上/下半区二分）**：
 * 上一版"锚点在上半区就把卡面丢到下半区、反之丢到上半区"在真机上验出的问题是卡面离锚点太远——
 * 半区二分保证的只是"卡面不挡住锚点"，没有保证卡面离锚点近。用户在真机上试完后选定的方案是
 * 贴邻放置：卡面出现在锚点旁边，水平方向偏到剩余空间更宽的那一侧、垂直方向贴着锚点，但绝不
 * 覆盖锚点本身（含 [drawTextCaret] 画的那条光标线）。见下面 `BoxWithConstraints` 里的
 * 水平/垂直偏移量计算。
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

    // 面板刚出现时抓一次焦点，系统 IME 才会跟着自动弹出（1b-2 复审修复项）——面板出现前用户
    // 必须先手动点一下 TextArea 才能开始打字，现在打字可以立刻开始。
    //
    // 用标准的 `FocusRequester` + `Modifier.focusRequester(...)` 挂在 `TextArea(modifier = ...)`
    // 上（1b-2 复审修复项 B，纠正上一版的结论）。上一版认为这条路径是死的：
    // `com.pico.spatial.ui.design` 6.0.0 的 `TextArea`（源码见 design-6.0.0-sources.jar 的
    // `TextField.kt` 第 164 行）确实把外部传入的 `modifier` 用在最外层的
    // `BoxWithConstraints(modifier = modifier)` 上，不是直接挂在内部真正持有 `focusTarget()`
    // 的 `BasicTextField` 节点上——这一半观察是对的。但由此推出"`FocusRequester` 因此找不到
    // 目标、`requestFocus()` 是静默空操作"是错的：挂在一个不可聚焦的祖先节点上的
    // `Modifier.focusRequester` 不是空操作，这是标准 Compose 焦点语义、不是这个设计系统的特例——
    // `requestFocus()` 会沿着这个节点的焦点子树往下找，委托给子树里第一个可获得焦点的目标，
    // 而 `BasicTextField` 恰好就在这棵子树里。用 `LocalFocusManager.moveFocus(Next)` 反而更脆弱：
    // 它依赖组合顺序（`ToolBar` 在 `BoardEditor.kt` 里比这个面板先组合，"下一个可聚焦节点"完全
    // 可能落在工具栏的某个按钮上，不是这里的 `TextArea`）和 effect 的调度时序（面板必须先被
    // 放置好，`moveFocus` 才有一个已经存在的焦点树可以走）,两个前提都不是这段代码自己能保证的。
    //
    // 抓焦点的 `LaunchedEffect` 必须活在下面 `BoxWithConstraints` 的 content 里，不能待在这个
    // 层级（1b-2 复审修复项 E，修正上一版的放置错误）：`BoxWithConstraints` 是一个
    // `SubcomposeLayout`——它的 content（包括挂 `Modifier.focusRequester` 的 `TextArea`）是在
    // **测量**阶段才被子组合出来的，不是跟这个外层 Composable 同一次组合。`LaunchedEffect` 放
    // 在这里，意味着它属于外层组合、跟 `TextArea` 所在的子组合完全是两回事——`focusRequester`
    // 什么时候真正挂上某个焦点节点，相对于这个 effect 什么时候跑，是一场帧时序竞态，赌哪一边
    // 先到没有意义。而且 `FocusRequester.requestFocus()` 输了这场竞态时不是静默空操作，是真的
    // 抛 `IllegalStateException`（"FocusRequester is not initialized..."——已在本应用编译产物的
    // dex 里直接验证过这行字符串存在）：异常会杀死这个协程，原本指望的重试永远执行不到，异常
    // 还会从 `LaunchedEffect` 里向外传——也就是说，弹出输入面板这个动作本身能崩溃应用。修法有
    // 两条，缺一不可：把这个 effect 挪进 `BoxWithConstraints` 的 content（这样它跟
    // `TextArea` 属于同一次子组合，`focusRequester` 挂没挂不再是跨子组合的竞态），并且让每一次
    // `requestFocus()` 调用本身都吞掉这个异常（见下面 effect 里的 `runCatching`）——这样即使
    // 子组合内部仍然存在一次调度延迟（`BasicTextField` 的 `focusTarget()` 节点还没挂完），
    // 也只是让这一次调用失败重试，不会再让协程被异常杀死。重试次数是写死的
    // [FOCUS_RETRY_ATTEMPTS] 次，命中即停，不存在"一直不获得焦点就一直重试"的循环路径。
    val focusRequester = remember { FocusRequester() }

    // 换行宽度依赖锚点位置（见 wrapBoardFor 的说明），只在锚点第一次出现时算一次；锚点在这个
    // 面板存活期间不会变（提交/取消都会让 state.pendingTextAnchor 变 null、面板整体消失）。
    //
    // 先测出「一行有多高」（1b-2 复审修复项 C 的输入之一）：拿一个必然不会换行的短样本
    // （宽度给到 MIN_WRAP_BOARD，远宽于一个字符）在 TEXT_SIZE_BOARD 字号下量一次，量出的是
    // 真实的一行高度，不是"字号 × 某个猜的行高倍数"——字体的行高倍数不是这个项目验证过的数,
    // 这里量比猜更站得住。measurer/fontFamilyResolver 在这个面板存活期间不变，用 remember 避免
    // 每次 `pendingText` 变化（每敲一个字都会重组这个 Composable）都重新量一次。
    val oneLineBoard = remember(measurer, fontFamilyResolver) {
        measureTextLayout("字", TEXT_SIZE_BOARD, MIN_WRAP_BOARD, measurer, fontFamilyResolver)
            .size.height.toFloat()
    }
    // 锚点再收紧一次（见 containAnchor 的说明）：横向至少留出 MIN_WRAP_BOARD、纵向至少留出
    // oneLineBoard，保证不会提交一个从落地那一刻起就整段在纸面外、谁也看不见删不掉的 TextItem。
    // 下面 wrapBoard/sheetAlignment/commitText 全部读这个收紧过的锚点，不再读原始 anchor——
    // 两处若各读一份,纸面边缘的失踪文字就会重新出现。
    val safeAnchor = containAnchor(anchor, oneLineBoard)
    val wrapBoard = wrapBoardFor(safeAnchor.x)

    // 卡面实测高度（px，1b-2 真机反馈修复项）：贴邻放置时垂直方向要知道卡面到底多高才能判断
    // 会不会被窗口底边裁掉，但卡面高度不是固定值——`TextArea` 没有 `heightIn` 的上限，用户
    // 打字多了会长高。用 `Modifier.onGloballyPositioned` 在 Column 布局完成后把真实高度写回来，
    // 比在这里猜一个"卡面大概多高"的常量更站得住，见下面用到它的说明。用 remember 而不是每次
    // 重组重新建：面板存活期间要持续累积同一份最新测量结果，不能被 `pendingText` 触发的重组重置。
    val sheetHeightPx = remember { mutableStateOf(0) }

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
                    anchor = safeAnchor,
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

    // `BoxWithConstraints` 而不是 `Box`：卡面贴邻锚点摆放需要知道锚点此刻在**屏幕**上的位置，
    // 不能只看它在画布逻辑坐标（`[0,CANVAS_H]`）里的位置——视口一旦平移或缩放过，同一个锚点在
    // 文档坐标系里的数值和它此刻实际显示在屏幕哪个位置就会不一致。修法是换算成屏幕坐标：
    // `state.viewport.toScreenX/Y(...)` 用的是与手势读取（`CanvasSurface` 的 `toBoard`）、绘制
    // （`BoardDrawing.kt` 的 `withTransform`）同一份 `offset`/`scale`，结果是 dp（`Viewport`
    // 本身是 dp 空间），跟这个 `BoxWithConstraints` 的 `maxWidth`/`maxHeight` 同一个单位。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 主题角色不透明，遮罩底色必须自己 copy(alpha)。
            .background(scheme.fillSecondary.copy(alpha = SHEET_SCRIM_ALPHA))
            // 必须消费指针事件，否则点击会穿透遮罩点亮下层工具栏（与 ClearConfirmOverlay 同一条教训）。
            .consumeAllPointerEvents(),
    ) {
        // 这里才是 TextArea 真正所在的子组合（见上面 focusRequester 声明处的说明）——effect
        // 必须留在这个 lambda 里，不能提到 BoxWithConstraints 外层。每次 `requestFocus()` 都用
        // `runCatching` 包一层：调用发生在 `focusRequester` 还没挂上任何焦点节点的那一刻会抛
        // `IllegalStateException`，`runCatching` 让这次调用失败但协程活下来，才轮得到下一次
        // 重试；一旦某次调用不抛异常（`isSuccess`），说明已经挂上了目标，立刻返回、不再消耗
        // 剩下的重试次数。
        LaunchedEffect(Unit) {
            repeat(FOCUS_RETRY_ATTEMPTS) { attempt ->
                val requested = runCatching { focusRequester.requestFocus() }.isSuccess
                if (requested) return@LaunchedEffect
                if (attempt < FOCUS_RETRY_ATTEMPTS - 1) {
                    delay(FOCUS_RETRY_DELAY_MS)
                }
            }
        }

        // 锚点相对这个 `BoxWithConstraints`（铺满整个编辑器窗口，见上面的说明）左上角的屏幕
        // 坐标，dp。`viewport.toScreenX/Y` 给出的坐标是相对 `canvas.viewport` 自身左上角的——
        // 二分对齐（886c4b7）只关心锚点落在上半屏还是下半屏，`canvas.viewport` 与编辑器窗口
        // 原点之间那点 `EDITOR_PADDING`/`TOOLBAR_WIDTH`/`RAIL_CANVAS_GAP` 偏移，量级远小于
        // 半屏，可以忽略；贴邻放置要把卡面钉在锚点旁边几十 dp 的地方，同一个偏移量已经跟
        // [SHEET_ANCHOR_GAP]、[SHEET_WIDTH] 同一个量级，必须显式加回来——否则算出来的贴邻
        // 位置会系统性地偏左偏上，卡面看起来"贴的不是锚点"。`EDITOR_PADDING`/`RAIL_CANVAS_GAP`
        // 现在从 `BoardEditor.kt` 的 `private` 改成 `internal`，跟 `TOOLBAR_WIDTH`（`ToolBar.kt`，
        // 本来就是 `internal`/公开）一样单一定义、两处共读。
        val anchorScreenX = EDITOR_PADDING.value + TOOLBAR_WIDTH.value + RAIL_CANVAS_GAP.value +
            state.viewport.toScreenX(safeAnchor.x)
        val anchorScreenY = EDITOR_PADDING.value + state.viewport.toScreenY(safeAnchor.y)

        // 水平：偏到剩余空间更宽的那一侧——锚点越靠右，右侧剩余空间越少，越应该翻到左侧
        // （题面里"贴近右边界就翻到锚点左侧"这条要求，本质就是"选剩余空间更大的一侧"，不需要
        // 单独判断"是否已经贴近边界"）。留出 [SHEET_ANCHOR_GAP] 的可见间隙，保证卡面不会跟
        // 锚点/光标线（[drawTextCaret]）零边距贴在一起。最后再夹一次到 `[0, maxWidth - SHEET_WIDTH]`
        // 兜底：只有窗口本身比 `SHEET_WIDTH + SHEET_ANCHOR_GAP` 还窄这种退化场景才会真的触发，
        // 代价是那种极端场景下贴邻间隙可能被压缩，不是这一项要解决的问题。
        val roomRight = maxWidth.value - anchorScreenX
        val roomLeft = anchorScreenX
        val preferredLeft = if (roomRight >= roomLeft) {
            anchorScreenX + SHEET_ANCHOR_GAP.value
        } else {
            anchorScreenX - SHEET_ANCHOR_GAP.value - SHEET_WIDTH.value
        }
        val sheetLeft = preferredLeft.coerceIn(0f, (maxWidth.value - SHEET_WIDTH.value).coerceAtLeast(0f))

        // 垂直：贴着锚点的屏幕 y 坐标放（卡面顶部与锚点齐平），除非会被底边裁掉——那种情况下
        // 往上收，让卡面整体留在窗口内（题面"贴近底边就上移"）。卡面高度不是固定值（`TextArea`
        // 没有 `heightIn` 的上限，用户打字多了会长高），这里用 [sheetHeightPx] 记录上一帧
        // `onGloballyPositioned` 实测的真实高度来夹这个上界——比硬编码一个"卡面大概多高"的猜测
        // 常量更站得住（跟 [oneLineBoard] 用测量代替猜测是同一条理由）。首帧 `sheetHeightPx`
        // 还是 0，夹出来的上界是 `maxHeight`，第一帧可能贴着底边越界一点，下一帧测到真实高度后
        // 自动收紧——不是无界的抖动，一次测量就收敛，可接受的短暂近似。
        val sheetHeightDp = with(LocalDensity.current) { sheetHeightPx.value.toDp() }
        val sheetTop = anchorScreenY.coerceIn(
            0f,
            (maxHeight.value - sheetHeightDp.value).coerceAtLeast(0f),
        )

        Column(
            modifier = Modifier
                .offset(x = sheetLeft.dp, y = sheetTop.dp)
                .onGloballyPositioned { sheetHeightPx.value = it.size.height }
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
                modifier = Modifier.fillMaxWidth().heightIn(min = SHEET_INPUT_MIN_H)
                    .focusRequester(focusRequester),
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
