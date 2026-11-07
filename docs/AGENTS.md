# 项目开发要求
在未特殊指明情况下，禁止任何模拟器/真机验证！

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