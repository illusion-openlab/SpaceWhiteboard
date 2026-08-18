# SpaceWhiteboard 设计契约 — 阶段 1a 编辑器

## 0. 元信息

- 应用名 / applicationId：SpaceWhiteboard / `tech.illusion.spacewhiteboard`
- 设计源类型：Claude 生成视觉稿（无 Figma、无截图输入）
- 设计源位置：`.spatialsdk/design-ref/design-mockup.html`（单文件、无外部资源，浏览器直接打开；1px = 1dp）
- 规格来源：`docs/superpowers/specs/2026-08-18-space-whiteboard-design.md`（本契约只解决其 §11 与 §12 留空的两件事，其余不改）
- 契约版本：v1
- 用户确认时间：**未确认**

### 本契约需要用户决定的事

| # | 决定项 | 状态 |
|---|---|---|
| 1 | 工具栏位置：顶部（变体 A）/ 左侧（变体 B） | **已确认：变体 B（工具栏在左侧）** — 用户于 2026-08-18 门禁 A 选定，理由见 §7 |
| 2 | `defaultColorScheme(...)` 的 16 个取值 | 已给出确切值（§8），随本契约一并确认 |

未确认之前不得执行 `pico-cli project create`，不得创建或编辑任何 Compose/UI 源文件。

---

## 1. 面板清单

| 面板 id | 容器类型 | 面板尺寸 | 层级/父面板 | 出现时机 |
|---|---|---|---|---|
| `editor` | WindowContainer（`DefaultWindowContainer`，Planar） | 默认 1280×800dp，`resizetype=1` 只设下限，可自由拖大 | 根 | 启动即显示（1a 无 Library 层，直接进编辑器） |
| `toolbar` | `editor` 内的布局区（不是独立容器） | A：1268×72dp / B：120×776dp | `editor` | 常显 |
| `canvas` | `editor` 内的布局区 | A：1256×692dp / B：1124×776dp | `editor` | 常显 |
| `shapePicker` | 浮层（`SpatialPopup` 或绝对定位 Box） | 内容自适应 | 锚在 `tool.shape` 上 | 仅当 `tool.shape` 为当前工具 |
| `clearDialog` | 模态覆盖层（`AlertDialog`） | 卡面 420dp 宽 | 覆盖 `editor` 全窗 | 点击 `act.clear` 后 |

**容器类型是架构决策**，不得为让代码编译通过而更改（规格 §2 已定死 `DefaultWindowContainer`）。

**根背景**：manifest `pico.spatial.windowcontainer.materialbackground="0"`，根节点写
`// design-style: opaque-root` + `Modifier.background(PicoTheme.colorScheme.fillSecondary)`。
这是 `spatial-ui-design-style` 规则 #4 允许的「显式不透明根」分支：先关掉系统玻璃，再自绘不透明底。
理由（规格 §12）：固定主题的视觉标识不能随房间环境光漂移。

**布局骨架**：根 `Box` → 内边距 12dp → A 为 `Column`（toolbar / 12dp gap / canvas），B 为 `Row`（toolbar / 12dp gap / canvas）。工具栏与画布之间没有分隔线，靠 12dp 间隙 + 纸面与根底的明度落差分开。

---

## 2. 元素表

位置为「相对父容器的锚点 + 偏移」。颜色一律写 `PicoTheme` 语义角色名，字体一律写 typography 角色名。
两个变体的**元素集合、颜色角色、字体角色、状态完全相同**，只有 `toolbar` 内部的排列方向与分组几何不同（见 §2.3）。

### 2.1 工具栏元素（A / B 共用）

| id | 类型 | 父容器 | 锚点 | 偏移 | 尺寸 | 内边距 | 颜色角色 | 字体角色 | 圆角/形状 |
|---|---|---|---|---|---|---|---|---|---|
| `tool.pen` | ToggleIconButton | `toolbar` | 组首 | 0 | A 44×44 / B 48×48 | 图标 24dp 居中 | 底 `fillLight`；选中底 `fillPrimary`。图标 `labelSecondary`；选中 `labelPrimary` | — | 圆角 11dp(A)/12dp(B) |
| `tool.shape` | ToggleIconButton | `toolbar` | 接 `tool.pen` | 间距 6 | 同上 | 同上 | 同上 | — | 同上 |
| `tool.eraser` | ToggleIconButton | `toolbar` | 接 `tool.shape` | 间距 6 | 同上 | 同上 | 同上 | — | 同上 |
| `tool.move` | ToggleIconButton | `toolbar` | 接 `tool.eraser` | 间距 6 | 同上 | 同上 | 同上 | — | 同上 |
| `div.1` | 分隔线 | `toolbar` | 工具组之后 | 间距 8 | A 1×40 / B 88×1 | — | `dividerLine` | — | — |
| `palette` | 容器（"纸面托盘"） | `toolbar` | 接 `div.1` | 间距 8 | A 324×50 / B 100×188 | 3dp | 底 `fillTertiary` | — | 圆角 12dp |
| `color.0`…`color.7` | 色板项 | `palette` | A 单行 8 格 / B 2 列 4 行 | 格间距 2 | 格 A 38×44 / B 46×44，色点 26×26 | — | 色点＝墨水色（§9，文档数据非主题角色）；未选中环 `labelPrimaryLight` @20% 1.5dp；选中环 `fillPrimary` 3dp | — | 色点圆形 |
| `div.2` | 分隔线 | `toolbar` | 接 `palette` | 间距 8 | 同 `div.1` | — | `dividerLine` | — | — |
| `widthTray` | 容器（"纸面托盘"） | `toolbar` | 接 `div.2` | 间距 8 | A 98×50 / B 104×50 | 3dp | 底 `fillTertiary` | — | 圆角 12dp |
| `width.thin` / `width.medium` / `width.thick` | 选项 | `widthTray` | 单行 3 格 | 格间距 4 | 格 A 28×44 / B 30×44 | — | 预览横条＝当前墨水色，条高 2/4/8dp、宽 18dp；选中格底 `fillPrimary` @12% + `fillPrimary` 2dp 外环 | — | 格圆角 9dp，条圆角全圆 |
| `div.3` | 分隔线 | `toolbar` | 接 `widthTray` | 间距 8 | 同 `div.1` | — | `dividerLine` | — | — |
| `act.undo` | IconButton | `toolbar` | 接 `div.3` | 间距 8 | 44×44(A) / 46×44(B) | 图标 24dp | 底 `fillLight`，图标 `labelSecondary` | — | 圆角 10dp |
| `act.redo` | IconButton | `toolbar` | 接 `act.undo` | 间距 4 | 同上 | 同上 | 同上 | — | 同上 |
| `act.clear` | A：IconButton / B：Button（图标+文字） | `toolbar` | A 接 `act.redo`，间距 4；B 另起一行，间距 8 | — | A 44×44 / B 96×44 | A 图标 24dp；B 图标 20dp + 6dp + 文字 | 底 `fillLight`，图标与文字 `error` | B 文字 `bodySmall` | 圆角 10dp |
| `div.4` | 分隔线 | `toolbar` | 接 `act.clear` | 间距 8 | 同 `div.1` | — | `dividerLine` | — | — |
| `zoom.out` | IconButton | `toolbar` | 接 `div.4` | 间距 8 | A 32×44 / B 34×44 | 图标 20dp | 底 `fillLight`，图标 `labelSecondary` | — | 圆角 10dp |
| `zoom.value` | Text | `toolbar` | 接 `zoom.out` | 间距 2 | 最小宽 46×44 | — | `labelSecondary` | `bodySmall` + **等宽数字（tnum）** | — |
| `zoom.in` | IconButton | `toolbar` | 接 `zoom.value` | 间距 2 | 同 `zoom.out` | 图标 20dp | 同 `zoom.out` | — | 同上 |
| `zoom.fit` | A：IconButton / B：Button（图标+文字） | `toolbar` | A 接 `zoom.in`，间距 2；B 另起一行，间距 8 | — | A 44×44 / B 96×44 | A 图标 24dp；B 图标 20dp + 6dp + 文字 | 底 `fillLight`，图标与文字 `labelPrimary` | B 文字 `bodySmall` | 圆角 10dp |
| `div.5` | 分隔线 | `toolbar` | 接 `zoom.fit` | 间距 8 | 同 `div.1` | — | `dividerLine` | — | — |
| `act.export` | Button（图标+文字） | `toolbar` | 接 `div.5` | 间距 8 | 96×44 | 图标 20dp + 6dp + 文字 | 底 `fillPrimary`，图标与文字 `labelPrimary` | `bodySmall` | 圆角 10dp |
| `saveStatus` | Row（圆点 + Text），不可点击 | `toolbar` | A 贴最右 / B 贴最下（中间用 `Spacer(weight=1)` 撑开） | 外边距 10 | 高 40，宽 wrap | 左右 10 | 见 §3 状态表 | `bodySmall` | 圆点直径 8dp |

### 2.2 画布与浮层元素（A / B 共用）

| id | 类型 | 父容器 | 锚点 | 偏移 | 尺寸 | 颜色角色 | 字体角色 | 圆角/形状 |
|---|---|---|---|---|---|---|---|---|
| `canvas.viewport` | Box（裁剪） | `editor` | 填满剩余 | — | A 1256×692 / B 1124×776 | 底 `fillSecondary`（与根同色，纸面之外的留白） | — | 圆角 10dp，`clipToBounds` |
| `canvas.paper` | Canvas（board 2400×1600 的矩形） | `canvas.viewport` | 居中 | 由 `Viewport` 决定 | 「适应窗口」时 A 1038×692 / B 1124×749 | `fillTertiary` | — | 圆角 6dp |
| `canvas.grid` | 点阵网格（`drawPoints`） | `canvas.paper` | board 每 100 单位一点 | — | 点半径 3.2 board 单位 | `labelPrimaryLight` @13% | — | 圆点 |
| `canvas.ink` | 已提交 items 的绘制层 | `canvas.paper` | board 坐标 | — | — | 每个 item 自带 `colorArgb`（§9），不走主题角色 | — | `StrokeCap.Round` / `StrokeJoin.Round` |
| `canvas.live` | 正在绘制的那一笔/那个形状（单独一层） | `canvas.paper` | 同上 | — | — | 同上 | — | 同上 |
| `canvas.emptyHint` | Column（两行文字），仅空文档时出现 | `canvas.paper` | 居中 | 0 | wrap | 主行 `labelPrimaryLight` @62%；副行 `labelPrimaryLight` @42% | 主行 `titleLarge`，副行 `bodyMedium` | — |
| `shapePicker` | 浮层容器 | 锚 `tool.shape` | A：按钮下方，左对齐按钮左边 / B：按钮右侧，上对齐工具组顶 | A 上移 4 / B 右移 6 | wrap，内边距 6 | 底 = `fillSecondary` 之上叠一层 `lightenHover`；1dp 外描边 `dividerLine` | — | 圆角 12dp |
| `shape.line` / `shape.rect` / `shape.circle` | 选项（图标 18dp + 文字） | `shapePicker` | A 横排 / B 竖排 | 间距 4 | 高 38，左右内边距 12 | 底 `fillLight`，内容 `labelSecondary`；选中底 `fillPrimary`，内容 `labelPrimary` | `bodySmall` | 圆角 9dp |
| `clearDialog.scrim` | 全窗遮罩 | `editor` | 填满 | 0 | fill | `fillSecondary.copy(alpha = 0.72f)` | — | — |
| `clearDialog` | 卡面 | 居中于 `clearDialog.scrim` | 居中 | 0 | 宽 420，高 wrap，内边距 26/26/20 | 底 = `fillSecondary` 之上叠一层 `lightenHover`；1dp 外描边 `dividerLine` | — | 圆角 18dp |
| `clearDialog.title` | Text | `clearDialog` | 左上 | 0 | wrap | `labelPrimary` | `titleMedium` | — |
| `clearDialog.body` | Text | `clearDialog` | 接标题 | 上 8 | wrap | `labelSecondary` | `bodyMedium` | — |
| `clearDialog.cancel` | Button | `clearDialog` | 右下，倒数第二 | 上 22 | 高 44，左右内边距 24 | 底 `fillLight`，文字 `labelPrimary` | `titleMedium`（Button 默认，仍显式传） | 全圆角 22dp |
| `clearDialog.confirm` | Button（破坏性） | `clearDialog` | 右下，最后 | 左 12 | 高 44，左右内边距 24 | 底 `error`，文字 `labelPrimary` | `titleMedium` | 全圆角 22dp |

### 2.3 两个变体唯一的差异

| | 变体 A（顶部） | 变体 B（左侧） |
|---|---|---|
| `toolbar` 主轴 | `Row`，高 72dp，左右内边距 6，子项间距 8 | `Column`，宽 120dp，上下内边距 6，子项间距 8 |
| 工具组排列 | 单行 4 个 44dp | 2×2 网格，48dp |
| 色板排列 | 单行 8 格 | 2 列 × 4 行 |
| 撤销/重做/清空 | 同一行三个图标按钮 | 撤销+重做同一行；清空单独一行，带文字 |
| 缩放组 | 同一行 `−` `100%` `+` `适应窗口` | `−` `100%` `+` 一行；`适应窗口` 单独一行，带文字 |
| `shapePicker` 方向 | 向下弹出，横排 | 向右弹出，竖排 |
| `saveStatus` 位置 | 最右端 | 最下端 |
| 排下来占用 | 约 1220dp / 可用 1268dp（余量 <50dp） | 约 737dp / 可用 776dp（余量约 39dp） |
| 「适应窗口」后纸面 | 1038×692dp（缩放 0.432） | 1124×749dp（缩放 0.468） |

---

## 3. 状态清单

| 元素 id | 状态 | 视觉变化 |
|---|---|---|
| `tool.*` | 选中（四选一互斥） | 底 `fillLight` → `fillPrimary`，图标 `labelSecondary` → `labelPrimary` |
| `tool.*` / `act.*` / `zoom.*` | hover | 由 `Modifier.spatialHoverEffect` 提供，不自己写 `hoverable + scale` |
| `tool.*` / `act.*` / `zoom.*` | pressed | 由 `LocalIndication.current` 提供 |
| `shapePicker` | 出现 / 消失 | 仅 `tool.shape` 为当前工具时出现；它是浮层，展开与收起都**不改变** `canvas.viewport` 的尺寸 |
| `shape.*` | 选中（三选一互斥） | 底 `fillLight` → `fillPrimary`，内容 `labelSecondary` → `labelPrimary` |
| `color.*` | 选中（八选一互斥） | 色点外环 `labelPrimaryLight`@20% 1.5dp → `fillPrimary` 3dp |
| `width.*` | 选中（三选一互斥） | 格底透明 → `fillPrimary`@12% + `fillPrimary` 2dp 外环 |
| `act.undo` | 不可用（undo 栈空） | `enabled = false`；透明度由 `LocalDisableAlpha`（默认 0.3f）自动施加，**不要**另设禁用色 |
| `act.redo` | 不可用（redo 栈空） | 同上 |
| `act.clear` | 不可用（文档为空） | 同上 |
| `canvas.emptyHint` | 出现 / 消失 | 仅当文档 items 为空时出现 |
| `zoom.value` | 值变化 | 文本随 `Viewport.scale` 变化，范围 50%–400%；等宽数字，位数变化时不得抖动 |
| `zoom.out` / `zoom.in` | 到达边界 | 到 50% 时 `zoom.out` 禁用，到 400% 时 `zoom.in` 禁用 |
| `saveStatus` | 已保存 | 圆点 `passable`，文字 `labelSecondary` |
| `saveStatus` | 保存中 | 圆点 `alert`，文字 `labelSecondary` |
| `saveStatus` | 保存失败 | 圆点 `error`，文字 `error` |
| `clearDialog` | 出现 | 遮罩 + 卡面淡入；遮罩层**必须自己消费指针事件**（`pointerInput` + `awaitPointerEvent().changes.forEach { it.consume() }`），否则射线会穿透点亮下层按钮并触发触感 |

---

## 4. 文案清单

全部为简体中文。省略号统一用单字符 `…`（U+2026），不用三个点。

| 元素 id | 文案 |
|---|---|
| `tool.pen` | `画笔`（图标按钮，文字作为无障碍/悬浮说明） |
| `tool.shape` | `形状` |
| `tool.eraser` | `橡皮` |
| `tool.move` | `移动` |
| `shape.line` | `直线` |
| `shape.rect` | `矩形` |
| `shape.circle` | `圆` |
| `width.thin` | `细` |
| `width.medium` | `中` |
| `width.thick` | `粗` |
| `act.undo` | `撤销` |
| `act.redo` | `重做` |
| `act.clear` | `清空` |
| `zoom.out` | `缩小` |
| `zoom.in` | `放大` |
| `zoom.value` | `100%`（格式：整数 + `%`，无空格） |
| `zoom.fit` | `适应窗口` |
| `act.export` | `导出 PNG`（「出」与「PNG」之间一个半角空格） |
| `saveStatus`（已保存） | `已保存` |
| `saveStatus`（保存中） | `保存中…` |
| `saveStatus`（保存失败） | `保存失败` |
| `canvas.emptyHint` 主行 | `空白白板` |
| `canvas.emptyHint` 副行 | `选择画笔，在这里开始绘制` |
| `clearDialog.title` | `清空白板？` |
| `clearDialog.body` | `将删除画布上的全部内容，此操作可以撤销。` |
| `clearDialog.cancel` | `取消` |
| `clearDialog.confirm` | `清空` |
| 导出成功提示（Snackbar） | `已导出到相册` |
| 导出失败提示（Snackbar） | `导出失败，请重试` |
| 存档损坏时的启动提示（Snackbar） | `上次的存档无法读取，已保留原文件，从空白白板开始` |

---

## 5. 不做清单（YAGNI 边界，1a 内不得加回）

- 文字工具、图片插入、选择工具、选框与缩放手柄、选中对象的上下文条（全部属于 1b）
- 项目列表 / 白板列表 / 新建 / 重命名 / 删除 / 缩略图封面（属于阶段 2）
- 形状填充色、双色（描边色 + 填充色）UI
- 图层、多人协作、云同步、无限画布、PDF 导出、语音输入、笔迹识别
- 显式「保存」按钮（规格 §9：只有状态指示）
- 工具栏的折叠 / 收起 / 拖动 / 自定义排序
- 工具栏分组标题文字、工具按钮下方的常显文字标签
- 深浅色主题切换（本应用是固定配色，不跟随系统/环境光）
- `saveStatus` 的点击重试（见 §11 已解决歧义 #4）

---

## 6. 截图验证计划（门禁 B）

总数 3 张，全部在模拟器上取。判定只依据截图。

| 序号 | 状态描述 | 到达方式 | 本图核对的元素 id |
|---|---|---|---|
| 1 | 启动默认态：空白画布，`tool.pen` 选中，`color.0` 选中，`width.medium` 选中，缩放 100%，`saveStatus`=已保存 | 启动后等待渲染 6s | `toolbar` 内全部元素（`tool.*` ×4、`palette` + `color.*` ×8、`widthTray` + `width.*` ×3、`act.undo/redo/clear`、`zoom.out/value/in/fit`、`act.export`、`saveStatus`）、`canvas.viewport`、`canvas.paper`、`canvas.grid`、`canvas.emptyHint`、`div.1`–`div.5` |
| 2 | 有内容 + `tool.shape` 选中 + `shapePicker` 展开（`shape.rect` 选中） | **走真实状态路径**：临时 `LaunchedEffect` 延时后调用与按钮相同的 `onToolSelected(Shape)` / `onShapeSelected(Rect)` 入口并注入若干 items，**不是**在首次组合前把状态写死；截完图移除钩子 | `shapePicker`、`shape.line/rect/circle`、`tool.shape` 选中态、`tool.pen` 回到未选中态、`canvas.ink`、`canvas.emptyHint` 已消失、`canvas.viewport` 尺寸**未**因浮层展开而变化 |
| 3 | `clearDialog` 打开 + `saveStatus`=保存失败 | 同上，临时钩子触发 `onClearRequested()` 与一次注入的保存失败状态 | `clearDialog.scrim`、`clearDialog`、`clearDialog.title/body/cancel/confirm`、`saveStatus`（`error` 圆点 + `error` 文字） |

### 每张图都要过的通用检查

1. **无 Material 观感**：没有出现 Material 的紫色/靛蓝默认强调色、没有 FAB、没有 Material 波纹。
2. **主题色真的生效**：`act.export` 与选中的 `tool.*` 是明确的蓝色块面，不是中性深灰。若它们是灰的，说明 `PicoTheme(colorScheme = WhiteboardColorScheme)` 没有真正传下去（工作区已两次踩到）。
3. **`fillTertiary` 反常用法未污染内置组件**：工具栏区域内除 `palette` 与 `widthTray` 两个纸面托盘外，**不得**出现其它浅色/白色块面。本应用把 `fillTertiary` 指定为浅色纸面，与它在其它项目里「深色面上的半透明浅底」的常见用法相反，任何靠默认取色的内置组件都可能突然变白。发现白块即为 Blocker，修法是给该组件显式传 `containerColor`。
4. **纸面之外是根底色**：`canvas.paper` 的四周应能看到 `fillSecondary` 的留白（变体 A 左右各约 109dp 明显可见；变体 B 上下各约 13dp，很窄，判不出来就标"不可判定"）。
5. **文字无截断**：`导出 PNG`、`适应窗口`、`保存中…` 三条最长，不得出现省略号截断或换行。
6. **等宽数字**：`zoom.value` 判不出来就标「不可判定」——静态单帧看不出抖动，需要连续两帧对比。

### 明确判不出来的（不要硬给结论）

- 任何精确 dp 数值（44dp 与 48dp 的差、8dp 与 12dp 的间距差）
- hover / pressed 效果（截图没有射线悬停）
- 触感与音效
- 笔迹跟手程度、平滑质量（规格 §15：只能真机人工验证）
- 点阵网格的确切透明度

---

## 7. 工具栏位置：A / B 对比与推荐

**推荐变体 B（工具栏在左侧）。三条依据，都可在视觉稿上复核：**

1. **画布可视面积大 17%。** 默认窗口 1280×800（宽高比 1.60），白板逻辑尺寸 2400×1600（宽高比 1.50）。
   顶部栏把视口压成 1256×692（1.82），偏离 1.50 很远，「适应窗口」后纸面只有 1038×692，左右各浪费约 109dp。
   左侧栏把视口压成 1124×776（1.45），几乎正好贴合 1.50，纸面 1124×749，只浪费上下各 13dp。
   缩放倍率 0.468 对 0.432，可视面积 842k dp² 对 718k dp²，**多出 17.3%**。对一个「除了工具栏其余全是画布」的应用，这是最直接的收益。

2. **顶部栏在 1a 就已经没有余量了。** 按本稿尺寸把 1a 的这一套控件排成一行，占约 1220dp，可用 1268dp，余量不到 50dp。
   阶段 1b 要加「文字 / 图片 / 选择」三个工具（约 150dp），顶部栏必然溢出，届时只能改成两行（再吃掉 8% 的窗口高度）或塞进折叠菜单（多一层交互）。
   左侧栏 1a 占约 737dp / 可用 776dp；1b 加完之后最多是开始纵向滚动，布局结构不变。**这条决定了「现在选 A，两周后要返工」。**

3. **形状子选择不会引起重排。** 左侧栏的子选择向右弹出到画布上方，顶部栏的向下弹出同样是浮层——两者都不重排。
   但顶部栏若因为 1b 溢出而改成「子选择内联展开」，就会挤动同一行里其它控件的位置；左侧栏没有这个演化压力。

**A 的优点，如实列出**：横排工具栏是桌面白板的主流约定，第一眼认知成本更低；视线水平扫描，不需要纵向移动；对习惯 Miro / FigJam / 系统画板的用户更"不用想"。

**如果用户选 A**：本契约除 §2.3 的排列方式、§6 通用检查第 4 条的留白描述外，其余（配色、元素集合、状态、文案）全部照用，不需要重新走门禁 A。

---

## 8. 颜色 token 表 —— `defaultColorScheme(...)` 的确切取值

固定配色，**不使用** `PicoTheme { }` 的系统自适应默认方案（规格 §12；工作区已在 SpaceLianliankan 与 Overwinter 两次复现"默认方案跑出来是没有主题色的通用浅色 UI"）。

> 说明：契约模板要求元素表里只写角色名、不写色值——§2 遵守了这一点。
> 本节是**角色本身的定义**，也就是 `defaultColorScheme(...)` 的入参，必须是确切值，否则 Task 3 无从实现。
> 两者不矛盾：§8 定义角色，§2 引用角色。

| 参数 | 取值 | 不透明度 | 本应用内的唯一含义 |
|---|---|---|---|
| `fillPrimary` | `0xFF2F6BEA` | 不透明 | 全应用唯一的高饱和块面：选中的 `tool.*`、选中的 `shape.*`、`act.export` 底色；选中 `color.*` 的外环、选中 `width.*` 的外环 |
| `fillSecondary` | `0xFF101826` | 不透明 | 应用根背景（自绘不透明底）、工具栏所在的整块底、`canvas.viewport` 中纸面之外的留白；`clearDialog.scrim` 也由它 `.copy(alpha=0.72f)` 得到 |
| `fillTertiary` | `0xFFF6F3EC` | 不透明 | `canvas.paper` 纸面，以及 `palette` / `widthTray` 两个纸面托盘。全应用的浅色大块面只有这些 |
| `fillLight` | `0x26FFFFFF` | 15% 白 | 未选中的 `tool.*`、全部 `act.*` / `zoom.*` 图标按钮、`shape.*` 未选中态、`clearDialog.cancel` 的底色。压在 `fillSecondary` 上呈现约 `#2A3140` |
| `labelPrimary` | `0xFFEDF1F7` | 不透明 | 深色 chrome 上的一级前景：选中工具的图标、`act.export` / `zoom.fit` 的图标与文字、`clearDialog.title`、两个对话框按钮的文字 |
| `labelPrimaryLight` | `0xFF16202E` | 不透明 | **浅色面上的深色前景**：`canvas.emptyHint`（@62% / @42%）、`canvas.grid`（@13%）、未选中色点的外环（@20%）。角色语义与 Overwinter 的同名用法一致 |
| `labelSecondary` | `0xB8EDF1F7` | 72% 白 | 未选中工具与图标按钮的图标、`zoom.value`、`saveStatus` 文案（非失败态）、`clearDialog.body` |
| `labelTertiary` | `0x7AEDF1F7` | 48% 白 | 1a 未使用。显式给值只是为了不落回 SDK 默认值 |
| `labelQuaternary` | `0x4DEDF1F7` | 30% 白 | 1a 未使用。同上 |
| `lightenHover` | `0x14FFFFFF` | 8% 白 | hover 提亮层（由 `spatialHoverEffect` 消费）；`shapePicker` 与 `clearDialog` 的卡面 = `fillSecondary` 之上叠这一层 |
| `lightenPressed` | `0x24FFFFFF` | 14% 白 | 按下提亮层 |
| `error` | `0xFFE5484D` | 不透明 | `act.clear` 的图标与文字、`clearDialog.confirm` 底色、`saveStatus` 失败态的圆点与文字 |
| `alert` | `0xFFE9A23B` | 不透明 | `saveStatus` 保存中的圆点 |
| `passable` | `0xFF3DBE8B` | 不透明 | `saveStatus` 已保存的圆点 |
| `interaction` | `0xFF6699FF` | 不透明 | 1a 未使用。显式给值只是为了不落回 SDK 默认值 |
| `dividerLine` | `0x24FFFFFF` | 14% 白 | `div.1`–`div.5` 分组分隔线；`shapePicker` 与 `clearDialog` 的 1dp 外描边 |

### 配色意图（一句话）

深墨蓝的 chrome + 暖白纸面 + 单一方位蓝强调色。整个界面只有两种大块面（深底与纸面）和一种强调色，"当前选了什么"永远只由那一种蓝表达。

### 对比度自检（sRGB，供复核）

- `labelPrimary` on `fillSecondary`：约 15:1
- `fillPrimary` on `fillSecondary`：约 3.7:1（块面对底，不是文字，足够醒目）
- `labelPrimary` on `fillPrimary`：约 4.8:1（按钮文字 ≥18sp，达标）
- `labelPrimary` on `error`：约 3.9:1（`clearDialog.confirm` 文字 ≥18sp，达标）
- `fillTertiary` on `fillSecondary`：约 16:1（纸面与留白的分界不会看不清）

### 实现要点（写给 Task 3）

```kotlin
// content/WhiteboardTheme.kt
val WhiteboardColorScheme: ColorScheme = defaultColorScheme(
    fillPrimary = Color(0xFF2F6BEA),
    fillSecondary = Color(0xFF101826),
    fillTertiary = Color(0xFFF6F3EC),
    fillLight = Color(0x26FFFFFF),
    labelPrimary = Color(0xFFEDF1F7),
    labelPrimaryLight = Color(0xFF16202E),
    labelSecondary = Color(0xB8EDF1F7),
    labelTertiary = Color(0x7AEDF1F7),
    labelQuaternary = Color(0x4DEDF1F7),
    lightenHover = Color(0x14FFFFFF),
    lightenPressed = Color(0x24FFFFFF),
    error = Color(0xFFE5484D),
    alert = Color(0xFFE9A23B),
    passable = Color(0xFF3DBE8B),
    interaction = Color(0xFF6699FF),
    dividerLine = Color(0x24FFFFFF),
)
// Main.kt: DefaultWindowContainer { PicoTheme(colorScheme = WhiteboardColorScheme) { AppRoot() } }
```

- `PicoTheme` 的颜色角色**都是不透明的**，需要半透明必须显式 `.copy(alpha = …)`（`clearDialog.scrim`、`canvas.grid`、`canvas.emptyHint`、未选中色点外环）。
- SpatialUI `Button` 不传 `style` 时默认 `titleMedium`；本契约凡指定了字体角色的地方都要**显式传** `style`。
- SpatialUI `Button` 的 `ButtonColors` **没有** disabled 颜色槽，禁用态由 `LocalDisableAlpha`（0.3f）实现——不要再自己写降对比逻辑。

---

## 9. 画笔 8 色调色板（文档数据，不是主题角色）

这 8 个值以 `colorArgb` 存进 `Stroke` / `ShapeItem`（规格 §4.2），与主题无关；换主题不改已画内容。全部按「在 `#F6F3EC` 纸面上清晰可读」挑选。

| 序号 | 名称 | 值 | 备注 |
|---|---|---|---|
| 0 | 墨黑 | `0xFF1B1B1F` | **默认选中** |
| 1 | 石墨 | `0xFF6B7280` | |
| 2 | 朱红 | `0xFFE03131` | |
| 3 | 橙 | `0xFFF08C00` | |
| 4 | 绿 | `0xFF2F9E44` | |
| 5 | 蓝 | `0xFF1971C2` | |
| 6 | 紫 | `0xFF7048E8` | |
| 7 | 品红 | `0xFFE64980` | |

**为什么色板要放在纸面托盘上**：墨黑色点直接放在深色工具栏上几乎看不见。把 8 个色点放进一个 `fillTertiary` 的小托盘，等于「色板就是一小片纸」，所见即所得。同理 `widthTray` 上的粗细预览横条用当前墨水色画，也才读得出来。

### 三档粗细

| 档位 | `widthDp` | 用途 |
|---|---|---|
| 细 | 2dp | 批注、细节勾画 |
| 中 | 4dp | 常规书写（**默认选中**） |
| 粗 | 8dp | 标题、强调 |

### 选中语言的两套写法（刻意区分，不是不一致）

- 深色 chrome 上的选项（`tool.*`、`shape.*`）：**填充** `fillPrimary`。
- 浅色纸面托盘上的选项（`color.*`、`width.*`）：**外环** `fillPrimary`（填充会盖掉墨水色本身，那正是要看的东西）。

---

## 10. 与规格的对应关系（便于复核没有越界）

| 规格条目 | 本契约的落点 |
|---|---|
| §2 容器 `DefaultWindowContainer` / 1280×800 / `resizetype=1` | §1 面板清单 |
| §5 画布 2400×1600、缩放 0.5×–4.0×、适应窗口 | §2.2 `canvas.paper`、§3 `zoom.*` |
| §6.5 形状只描边不填充 | §5 不做清单 |
| §8 清空可撤销但仍要二次确认 | §4 `clearDialog.body` 文案、§2.2 `clearDialog` |
| §9 无显式保存按钮，只有状态指示 | §2.1 `saveStatus`、§5 不做清单 |
| §11 工具栏元素清单 | §2.1（1a 版本：无文字/图片/选择） |
| §11 工具互斥 | §3 状态清单 |
| §12 浅色纸面 + 深色工具栏、`materialbackground=0`、禁 Material | §1 根背景、§8 |
| §14 分期 1a 范围 | §5 不做清单 |
| §15 截图验证必须有一档走真实路径 | §6 第 2、3 张的到达方式 |

---

## 11. 本环节解决的规格歧义（都可翻转，若用户不同意请在确认时指出）

1. **1a 只有四个工具，没有「选择」。** 规格 §11 列的是五选一（含「选择」），但 §14 把选择工具划给 1b。本契约按 1a 实现四选一：画笔 / 形状 / 橡皮 / 移动。规格 §11 的「手型（平移）」在 UI 上叫「移动」——两个字更短，竖排工具栏里更合适，语义不变。
2. **纸面加了极淡的点阵网格（`canvas.grid`）。** 规格没提。加它的理由：画布是有边界的 2400×1600 且可平移缩放，没有任何参照物时用户感知不到自己平移了多少。这是本环节新增的视觉决定，**移除它的成本几乎为零**（删一次 `drawPoints`），不同意就说。
3. **色板与粗细放在「纸面托盘」上，而不是直接放在深色工具栏上。** 见 §9 的理由。这引出一个副作用：`fillTertiary` 在本应用里是浅色，与它在同工作区其它项目里的常见用法相反，因此 §6 通用检查第 3 条专门盯这一点。
4. **`saveStatus` 在 1a 不可点击。** 规格 §9 没说失败后怎么重试。本契约选择：只做显示，下一次 Op 提交时自动重试写盘。理由是任务约束明确要求「工具栏只有这些控件，不许多」。若用户希望「点一下重试」，加上的成本很低。
5. **导出结果用 Snackbar 提示。** 规格 §10 只说写入 MediaStore，没说反馈方式。用 `SnackbarHost`（SpatialUI 内置），文案见 §4。这不是工具栏控件，不违反控件清单。
6. **`clearDialog` 的正文明说「此操作可以撤销」。** 依据规格 §8「清空是一次 `RemoveItems`，因此可撤销；仍加二次确认」。把可撤销写进文案能显著降低确认对话框的心理成本。
7. **配色方向（深墨蓝 + 暖白纸 + 方位蓝）是本环节自行选定的。** 规格 §12 只规定了「浅色纸面 + 深色工具栏」这一大方向。纸面选暖白 `#F6F3EC` 而不是纯白 `#FFFFFF`：纯白在头显里长时间看偏刺眼，且与 `labelPrimary`（近白）在同一块面上容易混。

---

## 12. 门禁状态

- [x] 采集设计源（无外部视觉资产，生成 HTML 视觉稿）
- [x] 生成设计契约
- [x] **用户确认（2026-08-18 门禁 A 通过）** —— ① 工具栏位置：**变体 B（左侧）**；② 配色与其余内容照本契约执行
- [ ] 实现（Task 3 起）
- [ ] 门禁 B 限时 UI 验证
