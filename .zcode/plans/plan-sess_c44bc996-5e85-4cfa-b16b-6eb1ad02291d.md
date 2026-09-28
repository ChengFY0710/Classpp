# 底部导航切 tab · 内容平移切换

## 需求与已定选择

点按底部导航栏 → 三页内容横向平移切换。已确认：

- **连续平移**：三页像一条连续带子一起平移；跨两个 tab（日程 ⇄ 待办）时中间的课表页会快速扫过；
- **300ms + FastOutSlowInEasing**（M3 shared axis 量级）；
- **仅点按触发**，不加 tab 间的滑动手势——既不与刚做的「内容区左右滑动翻周」抢手势，也免掉外层可滚动容器的嵌套。

## 现状与切入点

`MainActivity` 里三页常驻组合，非选中页由 `offscreenWhenHidden` 硬放到 `width * 2` 处；`AppTab` 的枚举顺序 = 导航栏渲染顺序 = 组合顺序（Agenda 0 / Timetable 1 / Todo 2）。所以只要把"硬放的固定偏移"换成"按序号差的动态偏移"，平移就自然成立。

## 实现（只改 `MainActivity.kt`）

1. `offscreenWhenHidden` → 新 helper `tabPage(tab, progress, selected)`：偏移 = `(tab.ordinal - progress.value) * 页宽`，用 `place()` 落到该位置；未选中页仍 `clearAndSetSemantics {}`（离屏页对 TalkBack 隐藏）。
2. 进度由 `animateFloatAsState(targetValue = selectedTab.ordinal.toFloat(), tween(300, FastOutSlowInEasing))` 提供；`selectedTab` 仍是 `rememberSaveable`，进程重建后直接落在该 tab、不补播动画。

```kotlin
val tabProgress = animateFloatAsState(
    targetValue = selectedTab.ordinal.toFloat(),
    animationSpec = tween(TabTransitionMillis, easing = FastOutSlowInEasing),
    label = "tabProgress",
)

Box(Modifier.fillMaxSize()) {
    AgendaScreen(Modifier.fillMaxSize().tabPage(AppTab.Agenda, tabProgress, selectedTab == AppTab.Agenda))
    ScheduleScreen(modifier = Modifier.fillMaxSize().tabPage(AppTab.Timetable, tabProgress, selectedTab == AppTab.Timetable), …)
    TodoScreen(Modifier.fillMaxSize().tabPage(AppTab.Todo, tabProgress, selectedTab == AppTab.Todo))
    BottomNavBar(…)          // 在页之上，位置不动
    if (showSettings) SettingsScreen(…)
}

/**
 * tab 页横向平移：按「自身序号 − 动画进度」× 页宽 定位，选中页恒在 0，
 * 三页因此像一条带子一起平移（旧页滑出、新页滑入，跨两 tab 时中间页扫过）。
 *
 * 三页常驻组合、只挪位置，不重建：若按 key 重建课表页，首帧 headerHeight
 * 回落到估算值再被校正，紧贴头部的日期带会明显跳闪一次。
 * 屏幕外的页面被窗口裁剪（不参与绘制），位置不在命中范围内（收不到点击/滚动），
 * 且对 TalkBack 隐藏。
 *
 * 动画值以 [State] 传入并在 measure 阶段读取：整段动画只重排，不重组（不带着三页调用点一起重组）。
 */
private const val TabTransitionMillis = 300

private fun Modifier.tabPage(tab: AppTab, progress: State<Float>, selected: Boolean): Modifier {
    val placed = this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val x = ((tab.ordinal - progress.value) * placeable.width).roundToInt()
        layout(placeable.width, placeable.height) { placeable.place(x, 0) }
    }
    return if (selected) placed else placed.clearAndSetSemantics {}
}
```

要点说明：

- 用 `place()` 而不是 `graphicsLayer { translationX }`：placement 是真实布局属性，坐标上报与 Haze 的背景采样都会跟着走，不会出现"页面动了、模糊背景没动"的错位。
- 动画值以 `State<Float>` 传进 modifier 内读取，而非在组合作用域里读后当参数传：否则每帧会重组整个 `MainActivity` 内容（连带三页调用点）。
- 新增导入：`animation.core.{animateFloatAsState, tween, FastOutSlowInEasing}`、`runtime.State`、`kotlin.math.roundToInt`；`offscreenWhenHidden` 连同其注释一并迁移删除（全项目仅此处引用）。

## 验证

1. `.\gradlew.bat :app:compileDebugKotlin`、`:app:testDebugUnitTest`。
2. 重新打包并就地安装到已连接设备（沿用 `adb install -r`，不清数据）。
3. 上机清单：
   - 点相邻 tab：方向与 tab 左右一致，旧页滑出、新页滑入；导航栏自身不动。
   - 点日程 ⇄ 待办：课表页快速扫过（预期）。
   - 课表页在滚动/折叠状态下切走再切回：滚动位置、折叠、周次都不丢，回来不闪。
   - 滚动过（头部已模糊）再切 tab：Haze 背景模糊不对齐/拖影则反馈，我改用另一种偏移方式。
   - 设置页覆盖层打开时切 tab 不受影响。

## 备注

若之后觉得"跨两 tab 时中间页扫过"不舒服，可改为只走一屏的 shared axis 风格（旧页滑出一屏、新页滑入一屏、中间页不出场），代价是多一层显隐与层级处理；随时可换。
