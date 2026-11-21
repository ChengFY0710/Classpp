# 项目开发要求
在未特殊指明情况下，禁止任何模拟器/真机验证！

## 中文文案标点规范
- 界面中文文案禁止使用全角分号「；」：并列项用「、」，句间停顿用「，」。
- 界面中文文案禁止出现句号「。」（含多句文案的每个句尾）：需要断句时用「，」，或拆成多条文案。
- 例外：`SettingsCard` 纯描述卡（只有标题和描述、无控件与点击）下方的描述文字，可以使用
  「；」与「。」方便阅读。
- 适用范围：`strings.xml`、Kotlin / Compose 中用户可见的硬编码文案（按钮、提示、Toast、通知、
  小组件、描述等）、WebView / JS 桥接展示给用户的 Toast 与错误提示，以及会展示给用户的拼接文本
  （如多个上课地点）。不含代码注释、AI 提示词与工具描述、测试数据、应用内 Markdown 文档。
- 新增或修改文案后按上述规则自查。

## CardSection 间距规范
- 全项目禁止覆盖 `CardSection` 的默认间距：调用点一律不传 `spacing` / `titleSpacing`。
- 分组纵向节奏只由 `ui/theme` 的 `CardSectionSpacing` / `CardSectionTitleSpacing` 统一决定
  （当前卡↔卡与标题↔首卡均为 10dp）；不得为实现局部视觉而在调用点覆盖。

## 浮层层级动效规范
- `FadeOverlayDialog`（整屏压暗的页内居中对话框）永不触发 `OverlaySheet` 缩小后退：
  调用点传 `OverlaySheet(covered = ...)` 时，一律不得把 `FadeOverlayDialog` 的可见态 /
  挂载态并入 `covered`。
- 层级由 `FadeOverlayDialog` 自带的整屏遮罩表达，浮层保持原位即可；再叠一层缩小后退
  是对同一动作的重复表达。
- `covered` 只用于「上层是浮层或底部弹卡」的叠层场景（另一个 `OverlaySheet`、
  `BottomSelectSheet` 及其上搭的 `BottomTextFieldCard` 输入卡），此时两层同帧同曲线反向播放。

## Haze 磨砂规范
- 禁用 `HazeSourceSelection.All`。所有 `HazeInput.Sources` 一律使用 `HazeSourceSelection.Behind`
  （Haze 2 默认值）：只采样 z 序位于最近祖先 hazeSource 之后的源（无匹配祖先时回退为全部源），
  避免无视 z 序把前景 / 效果自身内容采进模糊。
- 采样输入统一通过 `ui/components/HazeBlurStyles.kt` 的 `hazeSources()` 构造，
  不要在调用点自行创建 `HazeInput.Sources`。
- 弹层（PopupMenuPopup）退场保持活采样毛玻璃，调用点不得按退场把 hazeState 置 null。
  崩溃防护不变量为「弹层在场（含退场）期间，被采样源不发生结构重写」：收起类重写动作
  经 `rememberPopupActionGate` 延后到退场播完；开关类（菜单不收起，如待办分组显隐）包
  `HazeRetreat.forRewrite` 源侧退让；浮层内非菜单触发的重写沿用既有退让接线。

  ## git提交规则
  - 没有特殊指明，不准擅自提交。
  - 若指明提交但未指明提交的分支，默认提交至main分支。
  - 提交命名按“新增/优化/修复：XXXXX”格式命名，言简意赅。

  ## 圆角使用规范
  - 除6dp及以下圆角，其余一律使用shapes库的圆滑圆角。