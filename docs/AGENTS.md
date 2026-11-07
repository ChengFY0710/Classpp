# 项目开发要求
在未特殊指明情况下，禁止任何模拟器/真机验证！

## Haze 磨砂规范
- 禁用 `HazeSourceSelection.All`。所有 `HazeInput.Sources` 一律使用 `HazeSourceSelection.Behind`
  （Haze 2 默认值）：只采样 z 序位于最近祖先 hazeSource 之后的源（无匹配祖先时回退为全部源），
  避免无视 z 序把前景 / 效果自身内容采进模糊。
- 采样输入统一通过 `ui/components/HazeBlurStyles.kt` 的 `hazeSources()` 构造，
  不要在调用点自行创建 `HazeInput.Sources`。