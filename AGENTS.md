# SpaceWhiteboard — Project Agent Guidance

## 这是什么

**SpaceWhiteboard**：PICO OS 6 平面窗口白板应用。可以用笔在画布上手写、画三种形状（直线/矩形/圆），支持橡皮擦（局部擦除，矢量切分）、撤销/重做/清空、平移与缩放、自动保存与重启恢复、导出 PNG 到相册。

- 包名：`tech.illusion.spacewhiteboard`
- 容器：**`DefaultWindowContainer`（平面窗口），不是 `Stage`**。依据（规格 §2）：全部内容都是平面 2D Compose 绘制，没有任何真 3D 几何，沉浸式 `Stage` 不带来任何用得上的能力，且 `Stage` 会与其它应用抢占 full space（工作区已有先例：full space 占用会挡住别的应用启动）。**改任何东西都不要把容器换成 `Stage`/subwindow 来让代码编译过**——这是架构决策，不是实现细节。
- 默认窗口 1280×800dp，`resizetype=1`（`ContentMinSize`，只设下限，用户可拖大），`materialbackground=0`（关掉系统自适应玻璃，自绘固定配色）。

## 权威文档，按这个顺序读

1. [`.spatialsdk/design-contract.md`](.spatialsdk/design-contract.md) —— **一切视觉与文案的唯一基准**。改任何 UI（颜色角色取值、元素位置、文案、状态视觉）之前必须先读它，不要凭感觉改。变体 B（工具栏左侧）已经用户确认。
2. 规格：[`../docs/superpowers/specs/2026-08-18-space-whiteboard-design.md`](../docs/superpowers/specs/2026-08-18-space-whiteboard-design.md) —— 产品范围、数据模型、坐标模型、存储格式的原始设计依据。
3. 实施计划：[`../docs/superpowers/plans/2026-08-18-space-whiteboard-1a.md`](../docs/superpowers/plans/2026-08-18-space-whiteboard-1a.md) —— 20 个任务的逐步实现记录，含每一步做出的技术决定和理由。
4. SDD 决策台账（本项目从第一天到现在的完整决策历史，含被否定的假设、真机实测数据、审查中发现的真 bug）：[`.superpowers/sdd/2026-08-18-space-whiteboard-1a/progress.md`](.superpowers/sdd/2026-08-18-space-whiteboard-1a/progress.md)。**规格/计划文本本身有时已经过时或被现场证据推翻（例如 §6.2 关于事件下发频率的担忧、绝对坐标不可用的假设）——遇到冲突时以台账里记录的实测结论为准。**

## 阶段 1a 交付了什么

- 画笔（8 色调色板 + 3 档粗细）、形状工具（直线/矩形/圆）、橡皮擦（**已被 1b-1 取代**：当时是对象级、命中即整体删除；见下面「阶段 1b-1 交付了什么」）
- 撤销/重做（深度 50）、清空（二次确认，仍可撤销）
- 平移（手型工具）+ 缩放（按钮 −/+，范围 0.5×–4.0×，"适应窗口"）
- 自动保存（每次 Op 提交后 debounce 落盘）+ `onPause`/`onStop` 强制同步 flush + 启动恢复
- 导出当前内容为 PNG 到系统相册（`MediaStore`）

**阶段 1a 明确没有做**（属于阶段 1b / 2，不要现在补）：

- ~~没有文字工具~~ ——**阶段 1b-2 已交付**，见下面「阶段 1b-2 交付了什么：文字工具」
- **没有插入图片**（`ImageItem`、App 内选图网格、`AssetStore`）
- **没有选择工具**（选中/移动/缩放/删除已放置对象的选框与手柄、上下文条）——`Stroke`/`ShapeItem` 目前一旦画完就不能再单独移动或缩放，只能整体撤销或用橡皮擦删除
- **没有项目/白板组织层**（项目列表、白板列表、新建/重命名/删除、缩略图封面）——阶段 1a 固定写死 `projects/default/boards/main.wbd` 一份文档

`board/` 与 `store/` 的数据模型已经按最终形态设计（`items` 通用列表、`BoardCodec` 未知行前缀跳过并计数），所以 1b 加 `t`/`i` 行、阶段 2 加组织层都是纯增量，**不需要数据迁移**，这是选择"编辑器优先"分期而不返工的核心依据（规格 §14）。

## 阶段 1b-1 交付了什么：橡皮擦从整体删除改成局部擦除

橡皮擦不再是"扫到哪个对象就把整个对象删掉"，而是"只擦掉被扫过的那一部分"。

- **机制是矢量切分，不是位图遮罩**：拖拽路径上，落在橡皮圆圈半径内的点被直接丢弃，剩下的点按原顺序分段，每一段连续存活的点重新组成一个新的 `Stroke`（`board/StrokeSplit.kt` 的 `split(points, center, radius)`）。因为擦除后的结果仍然是矢量点集，不是像素遮罩，所以缩放画质、导出分辨率、撤销栈都不受影响——不会像位图擦除那样在放大或导出时出现锯齿、马赛克或分辨率上限。
- **形状也参与局部擦除**：一个被橡皮扫到的 `ShapeItem`（Line/Rect/Ellipse）先由 `board/ShapeOutline.kt` 的 `toPoints()` 采样成轮廓点集，再复用与笔迹相同的 `StrokeSplit.split` 逻辑切分，产生的每一段成为一条新 `Stroke`，继承原形状的颜色/线宽。
- **后果，用户已知并接受这个取舍**：被局部擦过的形状不再是"形状"——它变成了若干条 `Stroke`。`Stroke` 目前不支持被选中，所以一个被擦过一部分的矩形/圆/直线，以后再也无法用 1b-3 的选择工具整体选中、移动或改色，只能继续擦除或整体撤销。这不是 bug，是当前架构下矢量切分方案的固有代价，做决定时已经权衡过。
- **橡皮半径与橡皮光标都是屏幕空间常量**（`ERASER_RADIUS_PX`），不随 `viewport.scale` 缩放；命中测试与绘制光标用的是同一个表达式 `eraserRadiusBoard(density, viewport)`，所以光标显示的圈永远等于真实热区，不会出现"看着能擦到、实际擦不到"的错位。这与画笔的笔尖游标相反——笔尖游标随墨迹一起缩放。**这个不一致是故意的，不要为了"两个游标看起来统一"去改它**：橡皮的语义是"手部动作扫过的物理范围"，缩放画面时这个物理范围在屏幕上应该保持不变；笔尖游标的语义是"这一笔会落在纸面的哪个点"，本该跟着墨迹一起缩放，统一成同一种缩放行为会让其中一个游标开始说谎。
- 由此关闭两条来自 1a 的待办：**橡皮半径不再随缩放变化**（现在是屏幕空间常量）；**橡皮擦现在有可见光标**，且光标半径与真实热区精确一致。

## 阶段 1b-2 交付了什么：文字工具

点画布、系统软键盘打字、确认后文字成为画布内容——随文档一起保存、重启恢复、缩放、导出。

> **用户会立刻撞到的限制，写在最前面：文字目前不可删除单个对象。** 橡皮擦显式忽略 `TextItem`
> （见下），选择工具要到 **1b-4** 才会出现。本阶段放置的文字，唯一能拿掉它的办法是**撤销**
> （必须在放置后立刻做，一旦中间夹了别的操作就够不到）或**清空**（整块白板全部清除）。这不是
> bug，是分期交付的已知代价，交付时必须向用户说清楚，不要含糊过去。

- **`TextItem`**（`board/Item.kt`）加入 `Item` 密封接口家族，字段：`id`、`text`（原始字符串，
  未 trim、未转义）、`colorArgb`、`sizeBoard`（字号）、`anchor`（左上角 `Pt`）、`wBoard`（换行
  宽度）、`hBoard`（测量出的高度）。`bounds()` 直接是 `(anchor.x, anchor.y)` 到
  `(anchor.x + wBoard, anchor.y + hBoard)` 的矩形，参与 `contentBounds()`（进而 PNG 导出裁切）。
- **`sizeBoard`/`wBoard`/`hBoard` 全部是画布单位（board units），不是 `sp`。** 这是刻意背离规格
  原文里 `sizeSp` 措辞的决定：白板上的文字是**画布内容**，跟笔迹/形状一样要随 `viewport.scale`
  一起缩放——用户放大画布细看时，文字应该跟着墨迹一起变大。如果用 `sp`，文字大小由系统排版
  管道换算、天生跟屏幕密度绑定、不跟随画布缩放，缩放倍率越高文字相对墨迹越小，读起来会像是
  文字"缩水"了。`sizeBoard`/`wBoard`/`hBoard` 与 `Stroke.widthDp` 走的是同一套处理方式：都是
  画布单位字面量，只在 `drawBoard` 外层的 `withTransform`（`translate` + `scale`）里被放大一次，
  函数体内部从不再乘 density。
- **`wBoard`/`hBoard` 是创建时由 UI 层测量后存下来的固定值，`board/` 包本身无法重新测量**——
  `board/` 零 Compose import（硬不变量之一），量不了字。这意味着一旦存进 `TextItem`，这两个
  数字就固定了，不会因为设备、字体、或运行时环境不同而"自动纠正"；`measureTextLayout`（见下
  「`measureTextLayout` 是三个测量输入的唯一来源」一节）因此必须在创建时与渲染时被两个调用方
  以完全一致的输入调用。

### `t` 行格式与 base64

`board/BoardCodec.kt`：

```
t <argb8位小写hex> <sizeBoard> <x>,<y> <wBoard> <hBoard> <base64(utf8 text)|->
```

- **文字必须 base64。** `wbd1` 是按空格分词、按行分隔的纯文本格式；用户打的文字里随时可能出现
  空格、tab、换行——这些字符原样写进去会把一行"撕成"多个 token 或多行，解码时序号全部错位。
  base64 把任意字节编码成只含 `A-Za-z0-9+/=` 的字符串，天然不含空格/tab/换行，绕开了这个问题。
- **空文本哨兵 `-`。** base64 编码空字符串会产出空串，而 `decode` 按空格分词后过滤掉空 token，
  于是一个文本为空的 `t` 行会**少一个 token**、被当成畸形行整行跳过——用户在输入面板里什么都
  不打直接点确认就会触发（实际上这条路径已经在 `TextInputSheet.commitText` 里被挡掉了：空白
  文本直接 `cancelTextPlacement()`，根本不会走到编码；但 `BoardCodec` 自己也要对这种输入保持
  健壮，因为编解码器不能假设调用方永远守规矩）。解法是文本为空时第 7 个 token 显式写成单个
  字符 `-`，而不是 base64 编码结果。**这个哨兵不会与任何真实 base64 输出冲突**：base64 字母表
  是 `A-Za-z0-9+/=`，永远不产出裸 `-`，`-` 因此可以安全地被 `decode` 识别为"这是空文本"而不是
  "这是一段畸形 base64"。
- **`HEADER` 保持字面量 `wbd1`，不升版本号。** 这正是 1a/1b 分期承诺的向后兼容：`decode` 对任何
  无法识别的行前缀（1a 时代的 `t`、现在的 `i`）都跳过并计入 `skippedLines`，不抛异常。所以一份
  1b-2 存的、含 `t` 行的文档，拿一个只认 `wbd1` 头 + `s`/`h` 行的 1a 版本 App 打开，会正常跳过
  每一个 `t` 行、计数、继续解析剩下的笔迹/形状——不会解码失败、也不会丢整份文档，只是那些文字
  在旧版本里看不见。反过来，1b-2 的 `decode` 同样跳过它还不认识的 `i` 行（1b-3 的图片），已用
  `BoardCodecTextTest` 的 "an unknown prefix is still skipped rather than fatal" 锁定。

### `measureTextLayout` 是三个测量输入的唯一来源

`content/editor/BoardDrawing.kt` 的 `internal fun measureTextLayout(text, sizeBoard, wBoard,
measurer)` 是屏幕渲染路径（`drawTextItem`）与创建时测量路径（`TextInputSheet.commitText`，用来
算出存进 `TextItem.hBoard` 的高度）**共用的唯一入口**。三个测量输入——`TextStyle(fontSize =
sizeBoard.sp)`、`Constraints(maxWidth = wBoard.roundToInt().coerceAtLeast(1))`（含四舍五入）、
`density = TEXT_MEASURE_DENSITY`（`Density(1f, 1f)`）——全部封在这一个函数体内，两个调用方只
传 `text`/`sizeBoard`/`wBoard`/`measurer` 四个参数，不可能各自维护一份、迟早对不上。

- **`Density(density = 1f, fontScale = 1f)` 同时钉死两个量。** `density = 1f` 让 `sizeBoard.sp`
  换算出的像素数值上直接等于 `sizeBoard` 本身（缩放完全交给 `drawBoard` 外层的
  `scale(viewport.scale * density, ...)` 一次性完成，这里不能再乘一次设备密度，否则变成双重
  缩放）；`fontScale = 1f` 是故意**屏蔽系统无障碍字体缩放设置**——白板上的文字是文档内容，不该
  跟着"这台设备/这个用户把系统字体调大了多少"变化，否则同一份存档在不同用户的设备上会量出不
  同的换行结果和 `hBoard`，缩放和位置也会跟着漂移。
- **为什么不能把这三个输入拆开重新分别传。** 如果两个调用方各自手写
  `TextStyle`/`Constraints`/`density` 三个参数（Task 3 最初就是这么写的），"两处输入一致"就完全
  依赖"写代码的人对照注释抄一遍、以后改动时记得同步改两处"——这正是本项目反复踩到的那类 bug
  的形状（另一个真实案例见下文 `canvas.paper` 圆角）。抽成 `measureTextLayout` 这一个函数后，
  "输入相同"由函数签名本身保证。**如果以后又把这三个输入重新拆开写在两个地方，后果不是编译
  报错或立刻可见的 bug，而是存进文档的 `hBoard`（创建时测量）与实际渲染出来的高度悄悄不同
  步——症状会先在 PNG 导出上出现：导出的图片把文字底部裁掉一截**（因为 `contentBounds()` 用的
  是 `hBoard`，而画出来的字形实际更高或更矮），而屏幕上什么都看不出异常，非常隐蔽。
- 唯一仍未消除的风险点是 `wBoard.roundToInt()` 的四舍五入：`Constraints` 吃 `Int` px，`wBoard`
  是 `Float` board units，极端情况下（换行宽度刚好卡在某个词的临界宽度上）四舍五入误差可能改变
  "要不要在这个词处换行"的判定，从而多算或少算一行高度。这个风险现在只存在于
  `measureTextLayout` 内部**一处**，两个调用方永远读到同一次 `roundToInt()` 的结果，不会出现
  "渲染换的行"与"创建时存的行"互相打架。

### 已知但只能靠设备现场判断的观察

- **`TextInputSheet` 不跟随锚点，用 `Alignment.Center` 把 420dp 卡片固定在屏幕正中。** 一次靠近
  屏幕中心的点击，输入面板会正好盖住刚点的那个位置——打字期间完全看不到文字将落在哪。**Task 5
  原计划用真机 `PB311XKGL4160087B` 验证，但真机在验证途中从 adb 掉线（USB/会话断开，很可能是
  人取走或拔线），改用模拟器 `emulator-5554` 完成了这一条的截图取证**：见
  `.superpowers/sdd/2026-08-19-space-whiteboard-1b2-text/shots/task5-1-sheet-covers-tap.png`——
  锚点选在画布中心附近，截图确认面板确实盖住了刚"点击"的那个位置（已放置的文字只有右侧一段
  露在卡片外，左侧被卡片盖住）。**这条观察不是真人手势点出来的**：设备旁没有操作者，用一个
  临时 hook 直接调用 `state.beginTextPlacement`（与 `CanvasSurface` 里 `Tool.Text` 真实点击
  分支调用的是同一个函数）驱动出这个界面状态——布局与遮挡关系是真实渲染管线产出的，可信；但
  "用户真的用射线点一下会不会点在这个位置、点完的手感如何"仍然没有被验证过，只能等真机可用
  时补测。如果以后要修这条遮挡问题，思路是让面板偏移锚点而不是固定屏幕居中，这次没有做——只
  记录现场判断的结果，不做架构变更。
- **导出 PNG 与屏幕文字的字体度量是否一致：已通过模拟器验证一致。** 同一次 hook 驱动流程里，
  在画布上放置文字后直接调用真实的 `onExport()`（`Main.kt` 里导出按钮调的同一个 lambda），
  拉取 `Pictures/SpaceWhiteboard/whiteboard-*.png` 与截图对比：换行位置完全一致（都是在同一个
  词之后换行），两行文字都完整可见、底部没有被裁切——`export/BoardRenderer.kt` 用
  `createFontFamilyResolver(context)`、屏幕渲染用 `LocalFontFamilyResolver.current`，构造路径
  不同，但在本机系统默认字体（未声明任何自定义字体）下量出的字形度量与换行结果一致。
  见 `task5-2-placed-text-on-canvas.png`（屏幕）与 `task5-3-exported-png.png`（导出）。

## 三个坐标空间与换算公式（全项目最容易踩错的地方）

一个 bug 在这里出现时代码照样编译、照样跑，只是墨迹悄悄画错位置或跟手感变差。

| 空间 | 单位 | 谁在用 | 换算基准 |
|---|---|---|---|
| **屏幕像素（px）** | `Offset3D.x/y`、`dragAmount.x/y` | 手势层原始输入，来自 `detectSpatialDragGesture` | `dp = px / density`；`density = LocalDensity.current.density` |
| **dp** | `Viewport.offsetX/Y`、`toScreenX/Y` 的返回值 | `Viewport` 本身全程工作在 dp 空间，不知道 density | 与 board units 的关系见下 |
| **board units（bu）** | `Pt.x/y`、`Stroke.points`、`ShapeItem.a/b`、画布逻辑尺寸常量 `2400×1600` | 一切存进 `Item`/`BoardDoc`/存档的坐标 | **1 bu = 1 dp，当 `viewport.scale == 1.0`**；一般地 `screen_dp = board * scale + offset` |

**手势侧（`CanvasSurface.kt`）**：累加发生在**屏幕 px 空间**（`cursorPx += value.dragAmount`），每个采样点单独转 board 坐标：

```kotlin
val p = state.viewport.toBoard(cursorPx.x / density, cursorPx.y / density)
```

`Viewport.toBoard(sx, sy) = Pt((sx - offsetX) / scale, (sy - offsetY) / scale)`，这里的 `sx, sy` 已经是**除过 density 之后的 dp**，不是原始 px。**分层顺序不能颠倒**：先在 px 空间累加，再统一转 dp 再转 board；反过来（先转坐标再累加）会让缩放倍率参与误差累积。

**绘制侧（`BoardDrawing.kt`）**：`DrawScope` 内的 CTM 是

```kotlin
translate(viewport.offsetX * density, viewport.offsetY * density)
scale(viewport.scale * density, viewport.scale * density, pivot = Offset.Zero)
```

代入即 `screen_px = board * scale * density + offset * density = (board * scale + offset) * density`，正是 `toScreenX/Y` 的结果再乘 density——与手势侧的 `toBoard` 互为精确逆运算（Task 12 已代数验证），没有漏乘或多乘 density 的地方。

**采样距离过滤 `MIN_STEP_PX` 必须在屏幕 px 空间比较，不能转到 board units 之后再比。** 早期实现在 board 空间比较，等价于同一个像素阈值被 `viewport.scale` 除了一遍：缩放倍率越高，同样的手部位移换算出的 board 距离越小，400% 缩放时正好是用户放大画细节、最需要采样密度的时候，采样反而被过滤掉大半。现在的实现（`content/editor/CanvasSurface.kt`）已经改成全程在 px 空间过滤，只有换算成 board 坐标追加进 `livePoints` 时才越过这条线。

## 真机实测数据（校准值,不是拍脑袋）

来源：Task 19，2026-08-19，真机 PICO Swan `PB311XKGL4160087B`，9 笔人工画的笔画，1346 个 drag 事件。

- **事件下发频率 90Hz**（事件间隔中位数 11.10ms，P90 11.49ms，P99 11.90ms，非常规律）
- **单事件步长中位数 6.06px**（P90 10.87px，P99 30.54px，最大 35.6px）
- 单笔画采样点数 44–387，中位 95
- 设备 **320dpi（density 2.0）**，分辨率 4160×2176

结论：采样频率充足（规格 §6.2 预先约定的判定表第一行），**二次贝塞尔平滑已经够用，不需要 Catmull-Rom 插值补点**。规格里担心的"3D 拖拽增量也像某项目 2D `ACTION_MOVE` 一样稀疏（每次手势约一个事件）"的风险**没有出现**——那条经验来自 gaze 驱动的另一个项目，对本应用的 ray+手部位移交互不成立。

`MIN_STEP_PX = 2.0f`（`content/editor/CanvasSurface.kt`）就是从这组数据反推的：取中位步长的约三分之一，落在"能吞掉手部静止时驱动层仍在下发的抖动重复点，又远低于中位数真实移动"的安全区间。**改这个值之前先看这组实测数据，不要凭感觉调。**

## 指针模型实验与被否定的假设

分支 `experiment/pointer-absolute`（commit `9e73cbc`，**未合并**，仅作证据保留）验证过一个假设：用户反馈"不跟手"是因为墨迹跟随手部累加位移（`dragAmount` 累加），而用户是用射线瞄准，如果换成 `detectSpatialPointerEvent` 的绝对表面坐标驱动墨迹，应该会更跟手。

**结果：假设被否定。** 在一条 716 个采样点的主笔画上，绝对坐标与累加坐标的中位差只有 3.1px（最大 16.5px，松手时 3.5px）——两者在实践上是同一个信号，这也解释了实验构建里叠加的两个游标环为什么用户只看到了一个（重叠在一起）。同时确认 `detectSpatialDragGesture` 与 `detectSpatialPointerEvent` **可以在同一个元素上共存**（两者都能触发）。

**这个实验还顺带推翻了规格/计划里一条更宽泛的规则**："拖拽中重读绝对坐标不可信"这条约束来自另一个项目**注视驱动**交互下的实测（注视点在捏合拖拽时几乎不动），本项目是**射线指向**交互，绝对坐标在拖拽中同样可信。stage-1a 仍然沿用累加模型（已经过审查、没有理由推翻），只是这条"绝对坐标不可用"的理由本身不再被当作跨项目通用定律。

**手感问题仍未解决。** 用户在真机上的原话反馈是"不太跟手，并且画的途中我不知道我的画笔落脚点在哪（没有反馈）"。第二点已经用笔尖游标（`drawPenCursor`，`content/editor/BoardDrawing.kt`）缓解——拖拽中显示"系统认为落墨点在哪"。但第一点（"不跟手"本身）**已经证明与坐标来源无关**，真正原因待查，候选项：渲染延迟、系统上报位置与用户主观瞄准点之间的固定偏移、平滑/过滤本身带来的手感。区分实验很简单但还没跑：**先保持静止再点一下，再移动画一笔**——如果静止时游标就偏，是固定偏移；如果静止时准、移动时才滞后，是延迟。这需要用户在真机上专门做一次这个动作并观察。

## 存储格式 `wbd1`

`store/BoardStore.kt` + `board/BoardCodec.kt`。行式文本，不用 JSON（理由：笔迹是海量浮点数，JSON 体积/解析开销不划算；`org.json` 在 JVM 单测里是 stub；纯字符串处理零依赖、可流式、易人工排查）。

```
wbd1
s <argb8位小写hex> <widthDp> <x>,<y> <x>,<y> ...
h <Line|Rect|Ellipse> <argb8位小写hex> <widthDp> <x>,<y> <x>,<y>
t <argb8位小写hex> <sizeBoard> <x>,<y> <wBoard> <hBoard> <base64(utf8 text)|->
```

- 首行必须是字面量 `wbd1`（版本号），否则整份解码抛 `CodecException`。**加 `t` 行没有把版本号
  升成 `wbd2`**——这就是 1a/1b 分期承诺的向后兼容本身，见下一条。
- **`ItemId` 不进文件**：解码时按出现顺序重新分配 `ItemId(1)`、`ItemId(2)`……
- **前向兼容约定，1b 依赖它免迁移**：任何无法识别的行前缀（1b-2 的 `t`、1b-3 将追加的 `i`）在解码时被**跳过并计入 `skippedLines`**，不抛异常、不中断解码。任何格式错误的一行（列数不对、颜色不是合法 hex、点数不够）同样降级为跳过计数，绝不让一行坏数据毁掉整份文档。`t` 行的文本字段必须 base64（原始文字可能含空格/tab/换行，会撕碎这个按空格分词的行式格式），空文本用哨兵 `-`（base64 字母表 `A-Za-z0-9+/=` 永远不产出裸 `-`，不会与真实编码冲突）——完整理由见上面「阶段 1b-2 交付了什么：文字工具」。
- `unhex` 用 `Long.parseLong(s, 16).toInt()`，不是 `s.toInt(16)`——后者对任何最高位为 1 的 8 位 ARGB 颜色（不透明色几乎全是）会直接抛异常。

## 硬不变量（改代码前必须知道,否则容易在不知不觉中破坏它们）

- **`ItemId` 绝不能被重新分配给不同的对象。** `AddItem.revertFrom` 按 id 删除，撤销栈的正确性建立在"同一个 id 永远只指向曾经存在的那一个对象"上。`EditorState.newId()`（`content/editor/EditorState.kt`）是唯一发号者，且启动恢复时从已加载文档的 `items.size` 重新起号（`initialNextId`），不会与文档里已有的 id 冲突。
- **`board/` 包（`Pt`/`BoardDoc`/`Viewport`/`BoardCodec`/`Ops`/`UndoStack`/`HitTest`/`StrokeSmoothing`/`Item`）零 `androidx.compose.*` import。** 这是它能跑纯 JVM 单测（70/70 全部在这个包 + `store/`）的前提；任何往里面加 Compose 类型的改动都会让测试套件失去意义。
- **`when (activeTool)`/`when (item)` 等分支必须保持穷尽、不加 `else`。** 例如 `CanvasSurface.kt` 里对 `Tool` 的 `when`、`HitTest.eraseHits` 里对 `Item` 的 `when`。这是故意的：本项目 Kotlin 2.1.20 下裸 `when` **语句**（不是表达式）对枚举/密封类型不穷尽会直接报编译错误（`'when' expression must be exhaustive`，已在本仓库实测确认），所以 1b 加 `TextItem`/`ImageItem` 或第五个 `Tool` 时,编译器会强制你在每一处显式决定怎么处理,不会静默漏掉分支。不要为了"看起来更完备"加 `else -> Unit`。
- **`DefaultWindowContainer` 内 `withFrameNanos` 是死的**：只触发一次就再不恢复（工作区其它项目已实测确认，本项目未使用它但沿用这条约束）。任何需要周期性触发的逻辑用 `delay` 驱动的协程循环，不要指望帧回调。
- **橡皮擦按"拖拽开始前的文档快照"为基准换算删除索引，不是按拖拽途中不断收缩的 live doc。** 见 `CanvasSurface.kt` 的 `eraseBaseline` 与 `finishEraseBatch`：同一次拖拽里每命中一个对象，doc 就收缩一次；如果各条目各自按命中那一刻的 doc 换算 index，混用不同时刻的 index 会让 `insertAll` 撤销还原时的图层顺序错乱。这个 bug 曾真实出现过并被审查发现（详见台账 Task 14）。
- **`ReplaceItems`（`board/Ops.kt`，1b-1 加入 Op 家族，一对多替换：一个对象被局部擦除后拆成若干碎片）的索引契约**：`removed`/`added` 两个列表里的 index 都是**操作执行之后、最终文档里的位置**，不是原始文档里的位置——`added` 的 index 必须按累计位移计算，不能直接照抄原始 index，否则 `insertAll` 顺序插入时会把本该排在后面的条目挤错位置。
  worked case（来自台账 Task 3）：`[A,B,C,D]`，A 切成 2 个碎片，C 切成 2 个碎片，B、D 保留。
  - 正确（最终位置）：a0:0 a1:1 c0:3 c1:4 → 结果 `[a0,a1,B,c0,c1,D]`
  - 错误（照抄原始 index）：c0:2 c1:3 → 结果 `[a0,a1,c0,c1,B,D]`（B 被挤到了 C 的碎片后面，图层顺序错了）
  这条契约做错不会报错也不会崩溃，只会**静默**把图层顺序打乱——切分、命中测试、撤销栈单独看都是对的，只有跨层拼起来的最终顺序会错，很难通过局部测试发现。
  **一条声明但没有代码强制的不变量**：`added` 里的碎片 id 不能与幸存条目的 id 冲突。`ReplaceItems.revertFrom` 是按 id 在全文档范围内删除来实现撤销的；如果某个碎片 id 恰好撞上了一个幸存条目的 id，撤销时会连带删掉那个真实幸存的条目，悄悄破坏撤销栈。目前安全，唯一原因是碎片 id 全部来自 `EditorState.newId()`（单调递增计数器）铸造，不会和文档里任何既有 id 相同——**如果以后改变碎片 id 的生成方式，必须重新核实这条不变量仍然成立**。
- **`BoardStore.save()` 必须继续对外抛异常，不能吞。** `load()` 反过来必须把所有异常（不止 `IOException`）都吞掉降级为 `LoadResult.Corrupt`——因为它在应用启动路径上，任何未捕获异常都意味着应用起不来；而 `save()` 吞异常会让"写盘失败"变成"用户以为保存了、其实没有"的静默数据丢失，比崩溃更糟。改动 `store/BoardStore.kt` 时保持这个不对称。

## 已验证的 / 未验证的（不要含糊其辞地都说"已验证"）

**已验证**：
- `board/` 与 `store/` 的全部逻辑正确性——90/90 JUnit 单测（12 个测试类：1a 的基础上，1b-1 新增 `StrokeSplitTest`、`ShapeOutlineTest`、`OpsReplaceTest`），覆盖几何归一化、`BoardDoc` 增删改与 z 序还原、`Viewport` 坐标互转与缩放边界、`BoardCodec` 编解码往返与损坏容忍、`UndoStack` 各类 Op（含 `ReplaceItems`）、`HitTest`、`StrokeSmoothing`、`BoardStore` 原子写盘与损坏恢复、矢量切分（`StrokeSplit`）、形状轮廓采样（`ShapeOutline`）。2026-08-19 Task 6 在 commit `4822f3d` 上重跑（`--rerun-tasks`，非缓存命中）确认：89/89 全绿，0 失败 0 错误——这是 1b-1 复审修复轮**之前**的基线数字。复审轮改了 `ShapeOutlineTest`（`board/ShapeOutline.kt` 的采样签名加了 `radiusBoard` 参数、`Line` 不再只采样两端点）：删掉了一条断言"直线只采样两端点"的测试（这条断言现在描述的是被修掉的 bug，不是期望行为），换成两条断言"直线采样密度跟着橡皮半径变化、擦中点真的会删点"的测试，净增 1 条。`--rerun-tasks` 重跑后确认：**90/90 全绿，0 失败 0 错误**。
- 模拟器截图（门禁 B）：主题配色真的生效（非 Material 通用灰）、纸面有边界且四周露出根背景、无 Material 观感、文字不截断、清空二次确认覆盖层正确出现且消费指针事件。这只验证了**静态渲染**，不验证任何手感。
- 真机确认（用户照片 + Task 19/实验分支日志）：自动保存指示（"已保存"）、笔画渲染平滑、闭合曲线正确、固定主题、左侧工具栏、纸面边界——这些是**观感/正确性**层面的确认。
- 真机采样实测（Task 19）：90Hz / 6.06px 中位步长 / 320dpi，见上文。
- Task 6（2026-08-19，真机 `PB311XKGL4160087B`）：`app-debug.apk` 装机成功（`adb install -r -d`），`dumpsys activity activities` 确认 `topResumedActivity`/`ResumedActivity` 均为 `tech.illusion.spacewhiteboard/.platform.LaunchActivity`，`adb logcat -b crash` 中无本包任何记录。这只证明**装得上、起前台、没崩**，不证明局部擦除好不好用。
- **局部擦除的"手感"已经过真机验证（此前长期标记为"完全未验证"，现已更正）。** 用户在真机 PICO Swan 上完整试玩了一整轮擦除操作，确认：擦除确实生效（不是命中却没反应），橡皮热区大小（`ERASER_RADIUS_PX = 24f`）的手感合适，不需要再调。切分算法本身（`StrokeSplit`）、轮廓采样（`ShapeOutline`）、`ReplaceItems` 的索引正确性此前已由单测锁定；这一条补上的是单测和模拟器截图都够不到的"手感"这一半。**这条结论不覆盖 1b-1 复审新加的过渡采样修法**（见下文「橡皮擦局部切分只保证咬到采样点，不保证咬到几何」）——那个修法本身有单测锁定，但它改变了 `ShapeOutline` 的采样密度，还没有专门重新跑一轮真机手感验证；已知它不会让手感变差（采样只会变密，不会变疏），只是没有独立证据。

- 1b-2（文字工具）单测：102/102 全绿（14 个测试类，`--rerun-tasks` 重跑非缓存命中），新增
  `TextItemTest`（`bounds()`、`contentBounds()` 参与、橡皮不影响文字、文字不影响同位置笔迹的
  可擦除性）与 `BoardCodecTextTest`（`t` 行编解码往返，含空格/tab/换行、空文本哨兵、与
  `s`/`h` 行混排、畸形行降级为跳过、未知前缀容忍）。`./gradlew :app:assembleDebug` 通过。
  源码 grep 无 `androidx.compose.material`/`MaterialTheme`；`releaseRuntimeClasspath` 依赖树
  660 行，grep `-i material` 命中 0 次。
- Task 5（2026-08-19，模拟器 `emulator-5554`，**不是真机**——见下方说明）：装机、前台确认
  （`dumpsys activity activities` 轮询 `topResumedActivity` 直到命中本包，不是固定 `sleep`）、
  `logcat -b crash` 检查——这三条只证明**装得上、起前台、没崩**。文字工具"手感"这一半（下面
  明确未验证清单）不在这三条覆盖范围内。真机 `PB311XKGL4160087B` 原本是这次验证的目标设备，
  但在取到设备锁、刚开始装机时就从 `adb devices` 掉线（很可能是人取走或拔了线），之后一直没
  再出现；改到模拟器上完成了本任务能完成的那部分核实，真机上的装机/前台/crash 检查**这次没
  有跑到**，留给下一次真机可用时补。

**明确未验证**：
- **系统输入法弹出的时机与流畅度、中文输入是否正常、点击定位是否准、放置后文字的大小/位置是否
  符合用户预期——这些都需要用户在真机上亲自打字才能判断。** Task 5 验证时用的是模拟器而不是
  真机，且设备旁没有人类操作者，用一个临时 hook 直接调用
  `state.beginTextPlacement`/`state.commit(AddItem(TextItem(...)))` 驱动了**真实的状态与渲染
  路径**，但没有经过真实的点击手势、没有让系统 IME 真的弹出接收过按键——所以"IME 弹出是否
  顺畅""中文输入是否正常""点击定位是否准"这三条，Task 5 的证据完全覆盖不到，仍然是纯粹
  未验证。这份文档所在阶段的可行性调研已经在模拟器上确认过系统软键盘能弹出、能收到字符（见
  `TextInputSheet` 的类注释：`mCurId=com.bytedance.pico.inputmethod/.PicoIME`、
  `mInputShown=true`），但那不等于"在真机上顺手"，中文输入、点击定位准不准这些仍然要靠真机。
- **手感本身没有解决。** 用户报告"不跟手"，已证明与坐标来源（累加 vs 绝对）无关，真正原因（延迟/固定偏移/平滑）没有查清；上文给出的区分实验（先静止后移动）还没有跑过。**不要向用户或下一个会话宣称"跟手问题已修复"**——已经做的只是加了一个诚实反映系统认知位置的游标,缓解了"看不到落墨点"这一半的抱怨,没有触碰"跟手"本身。
- 窗口拖动改变尺寸时内容是否真的保住（`configChanges` 声明了,但没有真机验证过实际拖拽）。
- 工具栏各按钮在真实射线/手势下的可点性——模拟器 `adb input tap/swipe` 摸得到输入管线但点不准 SpatialUI 组件的判定,不能作为证据。
- `zoom.value` 的等宽数字（`tnum`）在设备字体上是否真的生效（GSUB 特性支持未确认）。

## 遗留问题清单（从台账整理,供 1b/2 或专项 triage）

- **撤销按钮的启用/禁用对比度太弱**，用户在真机上几乎看不出差异。根因是渲染层面而非逻辑层面（`canUndo` 状态本身正确翻转，已用 DIAG 日志证实）：`ActionButton` 用 `labelSecondary`（已经偏暗）叠加 `LocalDisableAlpha` 的 0.3 倍透明度，而"清空"按钮额外把文字换成红色所以看得出来,撤销/重做没有这层额外区分。候选修法：启用态改用更亮的 `labelPrimary`,让 0.3 倍透明度有东西可以压暗。位置：`content/editor/ToolBar.kt`。
- **椭圆的橡皮擦命中测试用包围盒近似**，不是精确的椭圆边界。在包围盒四角附近有约 `0.41 × min(rx, ry)` 的误判区——在一个 200×200 的椭圆上，用户可能在视觉上完全空白的区域扫过就把它擦掉。这条没有被任何测试锁定，可以自由改进。位置：`board/HitTest.kt`。
- **导出 PNG 的位图尺寸没有上限。** 最坏情况（内容铺满整张 2400×1600 逻辑画布 + 2× 导出倍率）是 4800×3200 `ARGB_8888`，约 58.6 MiB，故意不加裁剪/分块处理（一次性、用户触发、跑在 IO 线程）。如果真机上观察到 OOM 或明显卡顿再引入降采样或分块渲染，不要提前基于猜测处理。位置：`export/BoardRenderer.kt`。
- **没有实现悬停游标。** 笔尖游标目前只覆盖"拖拽进行中"这一种状态。上文「指针模型实验与被否定的假设」已经证明 `detectSpatialPointerEvent`（悬停/未按下）与 `detectSpatialDragGesture` 可以在同一元素上共存（两者都能触发）——这不再是共存性未知项，纯粹是本轮范围裁剪：接线悬停事件、设计悬停态的视觉区分是独立工作量，留到下一轮再做。位置：`content/editor/CanvasSurface.kt` 顶部注释里有完整的决策记录。
- ~~`ShapeOutline.toPoints()` 的 `MAX_SAMPLES=256` 会让很大的椭圆擦出来变成可见多边形~~ ——**已修复，且旧诊断本身是错的**：真正的后果不是"看起来像多边形"（渲染层的 Bézier 平滑把 24.8~29.5 board units 的采样间距抹成约 0.06~0.09 board units 的视觉偏差，在 2400 单位画布上完全看不出来），而是**大椭圆擦不掉**——这条 clamp 一绑定，采样间距就完全脱离 `radiusBoard`、只由几何（周长）决定，跟本文件同一节里"局部切分只保证咬到采样点"记录的那类 bug 是同一根因：橡皮圈扫过几何却没扫中任何采样点，`HitTest` 命中 fire，`StrokeSplit.split` 却什么都删不掉，整个椭圆照样被"命中转 Stroke"一次（无谓撤销记录 + 视觉不变）。铺满画布的椭圆（rx=1200, ry=800）在 `Viewport.MAX_SCALE`（4×）、真机 320dpi（density=2.0）下 `radiusBoard` 收紧到 3 board units，旧 clamp 撑出的平均间距 24.8、最大缝隙 29.5 远超这个数。修法：把 `MAX_SAMPLES` 从 256 提到 4096（正向推导：这个最坏场景要求 `n = ceil(2π·max(rx,ry)/spacing) ≈ 2514`，4096 留了约 1.6× 余量），同时把 `n` 的反推公式从"按周长的平均弧长变化率"换成"按 `max(rx, ry)` 的最大弧长变化率"——等角度采样在长宽比悬殊时不是等弧长采样，只按周长反推只保证*平均*间距达标，长轴附近实际间距会比平均值大（本例约 1.19 倍），足以让最坏一段单独超过橡皮半径。新增测试 `ShapeOutlineTest.a canvas-filling ellipse never gaps wider than the eraser radius at max zoom` 锁定这条不变量。位置：`board/ShapeOutline.kt`。
- **橡皮擦每个采样点都整份拷贝文档。** `CanvasSurface.kt` 里橡皮拖拽的每个采样点都要做一次 `toMutableList` + 新建 `BoardDoc`；按 Task 19 实测的 90Hz 事件频率，就是每秒 90 次整份文档拷贝，叠加在每帧要重绘所有已提交条目之上。没有做过性能画像，不知道实际开销有多大。这跟 1a 遗留的"不跟手"问题目前**没有**已证实的关联，但如果真机上感觉擦除比画画更卡、更滞后，这是第一个该去查的地方。
- ~~橡皮光标用 `scheme.error`（红色）这个颜色角色，语义上有冲突~~ ——**已裁定，不再是待办**：Task 5 评审曾预测持续跟手的红环会读起来像"出错了"而不是"这是橡皮擦"、还跟清空按钮撞色，故意留到真机现场判断。用户在真机上完整试玩后，这个顾虑没有被验证成真，实际使用中没有反馈别扭。裁定结果：**保留 `scheme.error`**，不引入形状区分（虚线环）之类的备选方案。这条评审异议是预测性的，真实使用证据优先于预测——不要因为看到"曾经有过语义冲突的评审意见"就重新提起这个问题或改动颜色角色。位置：`content/editor/BoardDrawing.kt` 的 `drawEraserCursor`。
- **`ShapeOutline` 的橡皮擦局部切分只保证咬到采样点，不保证咬到几何——1b-1 复审发现并打了过渡补丁，根治方案还没做。** `HitTest.strokeHit`/`shapeHit` 判定的是到连续几何（线段）的距离，`StrokeSplit.split` 删的却是离散采样点；橡皮圈扫过几何但没扫中任何采样点时，命中测试仍会 fire，切分却什么都删不掉。已知会触发这条路径的两个原因：`ShapeKind.Line` 原来只采样两个端点；轮廓采样间距是固定的画布单位常量，而橡皮半径是随缩放变化的屏幕空间常量换算过来的，高倍缩放下半径可以比采样间距还小。**已经做的过渡修法**（`board/ShapeOutline.kt`）：采样间距改成 `min(TARGET_SPACING, radiusBoard)`，永远不比当次橡皮半径更疏；`Line` 改成跟 `Rect`/`Ellipse` 一样按间距细分，不再只取端点。这堵住了目前唯一可复现的路径（形状轮廓采样 + 高倍缩放/端点附近擦除）。**根治方案是把 `StrokeSplit` 改成直接对线段切分、在几何交点处断开**，这样无论采样多稀疏都保证咬得动——这次故意不做，只做过渡修法。**残留风险**：这个过渡修法只覆盖"采样点足够密"这一个条件，理论上仍然不是万能的——如果笔迹本身采样得足够稀疏，橡皮圈依然可能扫过两个相邻采样点之间的几何却一个点都没扫中。**当前不可达**：手绘笔迹（`Stroke`）按 Task 19 实测的 90Hz 采样频率生成，单笔画采样点数中位 95、最稀也有 44 个，天然致密，不会触发这条缝——这条残留风险目前只是理论上存在，没有已知的真实复现路径。位置：`board/HitTest.kt`、`board/StrokeSplit.kt`、`board/ShapeOutline.kt`。

## 环境变量（每条 Bash 调用都要自己 export，`~/.zshrc` 不会被自动 source）

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

## 常用命令

```bash
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceWhiteboard
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew :app:testDebugUnitTest    # 90 个纯逻辑测试，board/ + store/
./gradlew :app:assembleDebug
```

真机（供用户试玩，装完不要 stop）：`PB311XKGL4160087B`。模拟器（供 agent 验证，用完立即 stop 并释放锁）与真机可能同时在线，每条 `pico-cli`/`adb` 命令都要显式 `-d <serial>`，设备锁 owner 字符串要跨 Bash 调用保持一致。
