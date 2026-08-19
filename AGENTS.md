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

- **没有文字工具**（`TextItem` 及其输入面板）——规格 §7.3 的系统软键盘 vs 自绘键盘查证还没做
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
```

- 首行必须是字面量 `wbd1`（版本号），否则整份解码抛 `CodecException`。
- **`ItemId` 不进文件**：解码时按出现顺序重新分配 `ItemId(1)`、`ItemId(2)`……
- **前向兼容约定，1b 依赖它免迁移**：任何无法识别的行前缀（1b 将追加的 `t`/`i`）在解码时被**跳过并计入 `skippedLines`**，不抛异常、不中断解码。任何格式错误的一行（列数不对、颜色不是合法 hex、点数不够）同样降级为跳过计数，绝不让一行坏数据毁掉整份文档。
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
- `board/` 与 `store/` 的全部逻辑正确性——89/89 JUnit 单测（12 个测试类：1a 的基础上，1b-1 新增 `StrokeSplitTest`、`ShapeOutlineTest`、`OpsReplaceTest`），覆盖几何归一化、`BoardDoc` 增删改与 z 序还原、`Viewport` 坐标互转与缩放边界、`BoardCodec` 编解码往返与损坏容忍、`UndoStack` 各类 Op（含 `ReplaceItems`）、`HitTest`、`StrokeSmoothing`、`BoardStore` 原子写盘与损坏恢复、矢量切分（`StrokeSplit`）、形状轮廓采样（`ShapeOutline`）。2026-08-19 Task 6 在 commit `4822f3d` 上重跑（`--rerun-tasks`，非缓存命中）确认：89/89 全绿，0 失败 0 错误。
- 模拟器截图（门禁 B）：主题配色真的生效（非 Material 通用灰）、纸面有边界且四周露出根背景、无 Material 观感、文字不截断、清空二次确认覆盖层正确出现且消费指针事件。这只验证了**静态渲染**，不验证任何手感。
- 真机确认（用户照片 + Task 19/实验分支日志）：自动保存指示（"已保存"）、笔画渲染平滑、闭合曲线正确、固定主题、左侧工具栏、纸面边界——这些是**观感/正确性**层面的确认。
- 真机采样实测（Task 19）：90Hz / 6.06px 中位步长 / 320dpi，见上文。
- Task 6（2026-08-19，真机 `PB311XKGL4160087B`）：`app-debug.apk` 装机成功（`adb install -r -d`），`dumpsys activity activities` 确认 `topResumedActivity`/`ResumedActivity` 均为 `tech.illusion.spacewhiteboard/.platform.LaunchActivity`，`adb logcat -b crash` 中无本包任何记录。这只证明**装得上、起前台、没崩**，不证明局部擦除好不好用。

**明确未验证**：
- **局部擦除的"手感"完全没有经过任何验证，也没有办法用单测覆盖。** 切分算法本身（`StrokeSplit`）、轮廓采样（`ShapeOutline`）、`ReplaceItems` 的索引正确性都有单测锁定，Task 6 也重跑确认 89/89 绿；但"橡皮热区大小是否合适""擦除的时候是否跟手""碎片断面看起来是否难看"这三件事，单测和模拟器截图都够不到，只能由用户在真机上试出来。**不要把"切分逻辑对"当成"擦除手感好"去汇报——这是两件独立的事，Task 6 只验证了前者。**
- **手感本身没有解决。** 用户报告"不跟手"，已证明与坐标来源（累加 vs 绝对）无关，真正原因（延迟/固定偏移/平滑）没有查清；上文给出的区分实验（先静止后移动）还没有跑过。**不要向用户或下一个会话宣称"跟手问题已修复"**——已经做的只是加了一个诚实反映系统认知位置的游标,缓解了"看不到落墨点"这一半的抱怨,没有触碰"跟手"本身。
- 窗口拖动改变尺寸时内容是否真的保住（`configChanges` 声明了,但没有真机验证过实际拖拽）。
- 工具栏各按钮在真实射线/手势下的可点性——模拟器 `adb input tap/swipe` 摸得到输入管线但点不准 SpatialUI 组件的判定,不能作为证据。
- `zoom.value` 的等宽数字（`tnum`）在设备字体上是否真的生效（GSUB 特性支持未确认）。

## 遗留问题清单（从台账整理,供 1b/2 或专项 triage）

- **撤销按钮的启用/禁用对比度太弱**，用户在真机上几乎看不出差异。根因是渲染层面而非逻辑层面（`canUndo` 状态本身正确翻转，已用 DIAG 日志证实）：`ActionButton` 用 `labelSecondary`（已经偏暗）叠加 `LocalDisableAlpha` 的 0.3 倍透明度，而"清空"按钮额外把文字换成红色所以看得出来,撤销/重做没有这层额外区分。候选修法：启用态改用更亮的 `labelPrimary`,让 0.3 倍透明度有东西可以压暗。位置：`content/editor/ToolBar.kt`。
- **椭圆的橡皮擦命中测试用包围盒近似**，不是精确的椭圆边界。在包围盒四角附近有约 `0.41 × min(rx, ry)` 的误判区——在一个 200×200 的椭圆上，用户可能在视觉上完全空白的区域扫过就把它擦掉。这条没有被任何测试锁定，可以自由改进。位置：`board/HitTest.kt`。
- **导出 PNG 的位图尺寸没有上限。** 最坏情况（内容铺满整张 2400×1600 逻辑画布 + 2× 导出倍率）是 4800×3200 `ARGB_8888`，约 58.6 MiB，故意不加裁剪/分块处理（一次性、用户触发、跑在 IO 线程）。如果真机上观察到 OOM 或明显卡顿再引入降采样或分块渲染，不要提前基于猜测处理。位置：`export/BoardRenderer.kt`。
- **没有实现悬停游标。** 笔尖游标目前只覆盖"拖拽进行中"这一种状态。上文「指针模型实验与被否定的假设」已经证明 `detectSpatialPointerEvent`（悬停/未按下）与 `detectSpatialDragGesture` 可以在同一元素上共存（两者都能触发）——这不再是共存性未知项，纯粹是本轮范围裁剪：接线悬停事件、设计悬停态的视觉区分是独立工作量，留到下一轮再做。位置：`content/editor/CanvasSurface.kt` 顶部注释里有完整的决策记录。
- **`ShapeOutline.toPoints()` 的 `MAX_SAMPLES=256` 会让很大的椭圆擦出来变成可见多边形。** 铺满画布的椭圆（rx=1200, ry=800）周长约 6346，按目标间距需要 793 个采样点，被 clamp 到 256 后平均间距 24.8、最大缝隙 29.5 board units——超出测试套件里"看起来像曲线"的 12-unit 经验阈值约 2.5 倍。擦一次这种大椭圆，断面会看到明显的棱角。这与 `Rect`（完全不 clamp）的行为不一致。建议把 `MAX_SAMPLES` 提到约 1024 与 `Rect` 的实际行为对齐；未做是因为测试套件里最大的椭圆用例正好卡在 clamp 边界，没有真实触发过这个问题。位置：`board/ShapeOutline.kt`。
- **橡皮擦每个采样点都整份拷贝文档。** `CanvasSurface.kt` 里橡皮拖拽的每个采样点都要做一次 `toMutableList` + 新建 `BoardDoc`；按 Task 19 实测的 90Hz 事件频率，就是每秒 90 次整份文档拷贝，叠加在每帧要重绘所有已提交条目之上。没有做过性能画像，不知道实际开销有多大。这跟 1a 遗留的"不跟手"问题目前**没有**已证实的关联，但如果真机上感觉擦除比画画更卡、更滞后，这是第一个该去查的地方。
- **橡皮光标用 `scheme.error`（红色）这个颜色角色，语义上有冲突，Task 5 评审已提出但故意留到真机现场判断。** design-contract 里 `scheme.error` 是留给破坏性/失败信号用的（清空确认按钮、保存失败提示），而橡皮光标是要跟着手部持续显示一整个擦除过程的环。评审的判断：持续跟手的红环读起来像"出错了"而不是"这是橡皮擦"，还跟清空按钮撞色。如果用户在真机上觉得别扭，推荐的备选方案是用**形状**区分（比如虚线环）而不是继续占用一个语义色。位置：`content/editor/BoardDrawing.kt` 的 `drawEraserCursor`。

## 环境变量（每条 Bash 调用都要自己 export，`~/.zshrc` 不会被自动 source）

```bash
export PICO_HOME='/Users/zohar/Library/PICO/sdk'
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
```

## 常用命令

```bash
cd /Users/zohar/WorkSpace/Project/PicoProjects/SpaceWhiteboard
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
./gradlew :app:testDebugUnitTest    # 89 个纯逻辑测试，board/ + store/
./gradlew :app:assembleDebug
```

真机（供用户试玩，装完不要 stop）：`PB311XKGL4160087B`。模拟器（供 agent 验证，用完立即 stop 并释放锁）与真机可能同时在线，每条 `pico-cli`/`adb` 命令都要显式 `-d <serial>`，设备锁 owner 字符串要跨 Bash 调用保持一致。
