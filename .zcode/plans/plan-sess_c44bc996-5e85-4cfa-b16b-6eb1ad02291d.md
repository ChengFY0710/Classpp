# 课表左右滑动翻周（跟手动画）

## 需求与已定选择

- 在课表**内容区**左右滑动 → 切换上一周 / 下一周；**上一周、本周、下一周**的课程卡片与网格线随手指横向移动；**顶栏完全不动**。
- 已确认：① 顶栏周数与日期在**拖动过半时**即切换；② 边界（第 1 周右滑 / 末周左滑）沿用 **Pager 系统默认拉伸反馈**，不写自定义回弹。
- 不需要新增依赖：`HorizontalPager` 位于 `androidx.compose.foundation`，项目已在用该 artifact 的 `LazyColumn`/`background`，编译类路径已具备。

## 方案：内容区套一层 Pager，每周一页

关键取舍——**Pager 放在 LazyColumn 的唯一 item 里，而不是把整个网格塞进 Pager 的每一页**：

- 纵向滚动（头部折叠、日期带渐隐、Haze 模糊）必须由三页共享。若每页各自持有 LazyColumn，拖动时相邻周会停在自己的滚动位置，与当前周错位，"跟手"就会露馅。
- 因此 Pager 的每页 = 「日期带 + 各节次行 + 尾部留白」，**不含纵向滚动**；外层仍是一个 LazyColumn（`state` 与 `contentPadding` 原样保留）滚动整个 Pager。
- 这样三页永远共享同一纵向位置与折叠状态，顶栏在 Pager 之外纹丝不动。
- 副产品：现有日期带渐隐 / `blurProgress` / 折叠逻辑**逐帧等价**，无需改动（Pager 恒为唯一可视 item，`firstVisibleItemIndex == 0` 的守卫语义不变，`firstVisibleItemScrollOffset` 仍是"内容上滚量"）。

## 改动清单（4 个文件）

### 1. `ui/schedule/ScheduleModels.kt` — 新增页面数据模型

```kotlin
/** 一周的整页渲染数据：日期带 + 该周课程；每周一页，由 Pager 按页号取用 */
data class WeekPageContent(
    val week: Int,
    val courses: List<Course>,
    val dates: List<Date>,
    val highlightDate: Date,
)
```

### 2. `ui/schedule/CourseGrid.kt` — 单 item LazyColumn + Pager

签名改为：

```kotlin
fun CourseGrid(
    pagerState: PagerState,
    timeSlots: List<TimeSlot>,
    contentForWeek: (Int) -> WeekPageContent,   // 页周号(1..totalWeeks) → 该周内容
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
)
```

- LazyColumn 内容从「日期带 + `items(timeSlots)` + 尾部留白」变成**单个 item**：
  ```kotlin
  val pageHeight = DateBandHeight + GridRowHeight * timeSlots.size + TrailingScrollSpace
  item(key = "weekPager") {
      HorizontalPager(
          state = pagerState,
          modifier = Modifier.fillMaxWidth().height(pageHeight),  // 显式高度：LazyColumn item 纵向约束无界
          beyondViewportPageCount = 1,                             // 拖动起始帧即有相邻周内容，无空白闪跳
          flingBehavior = PagerDefaults.flingBehavior(
              state = pagerState,
              pagerSnapDistance = PagerSnapDistance.atMost(1),      // 一次手势最多翻一周
          ),
      ) { page -> WeekPage(page + 1, remember(page, contentForWeek) { contentForWeek(page + 1) }, timeSlots, state) }
  }
  ```
- `WeekPage`（新私有）：`Column { DateBand(...); timeSlots.forEach { GridRow(...) }; Spacer(TrailingScrollSpace) }`。`DateBand`/`GridRow` 内部绘制逻辑不改。
- **接缝线**：静止时页右缘＝屏幕右缘不必画；拖动/翻页途中页右缘正是相邻周的分界，缺线会让分界处两列并成一宽列。在页级 `drawBehind` 里用 **draw 阶段**读 `pagerState.currentPageOffsetFraction`（≠0 才画）补一条右缘竖线，高度只到最后一个节次行（不含尾部留白）。draw 阶段读取不触发重组。
- `CourseGridPreview` 改用 `WeekPageContent` + `rememberPagerState`。

### 3. `ui/schedule/ScheduleScreen.kt` — Pager 状态、双向同步、内容提供者

- 删除顶层 `courses`/`weekDates`（改为下面的 `contentForWeek`）；`timeSlots` 加 `remember(schedule)` 稳定实例，避免拖动期三页无谓重组。
- 在 `else`（`schedule != null`）分支内新增：

```kotlin
// 初始页即当前周；pager 只在有课表时创建，避免首帧从第 1 周跳变到当前周
val pagerState = rememberPagerState(initialPage = week - 1) {
    // 刻意读 StateFlow 的 State：pageCount 闭包在创建时被捕获，读局部值会拿到过期周数
    scheduleState.value?.totalWeeks ?: 1
}

// 每周页内容：纯内存读（无 I/O），provider 随 schedule 重建
val contentForWeek: (Int) -> WeekPageContent = remember(schedule, currentWeek, today) {
    { pageWeek -> WeekPageContent(...) }
}

// 手势 → 周次：currentPage 在拖动过半时翻转（已确认），顶栏周数/日期随之切换；
// 值未变时写回被 State 自身忽略，不产生额外重组
LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage }.collect { selectedWeek = it + 1 }
}

// 周次 → 翻页：周数弹窗、返回本周、学期范围钳制；相邻周做动画，跨多周直接落位
LaunchedEffect(week) {
    val target = week - 1
    val current = pagerState.currentPage
    if (current != target) {
        if (abs(current - target) == 1) pagerState.animateScrollToPage(target) else pagerState.scrollToPage(target)
    }
}
```

- 页内 `highlightDate` 规则与顶栏 `headerDate` 一致（本周＝今天、其它周＝该周周一），按 `pageWeek == currentWeek` 判定，保证选中页高亮列与顶栏一致。
- 现有 `LaunchedEffect(week) { if (selectedWeek != week) selectedWeek = week }`（初始化 + 钳制）保留不动。

### 4. 明确的设计决定

- **日期带跟手滑动**（不留在顶栏）。它是每周数据（日号每周不同），且它画的竖线正属于"边线"；若它不动，拖动时它的竖线会与各行竖线在接缝处断开。顶栏的星期行（周一…周五）保持不动，拖动时表现为日号从固定星期标签下滑过。
- 一次手势最多翻一周（`atMost(1)`），对应"上一周/下一周"；若该 API 在本版本签名不符，则退回默认 fling（默认同样限一周），编译期即可确认。

## 验证

1. `.\gradlew.bat :app:compileDebugKotlin`（若有 API 签名差异，在此暴露并修正）。
2. 手动清单：
   - 慢拖：卡片与网格线跟手横移，顶栏（图标行/日期行/星期行/胶囊）纹丝不动；拖过一半时顶栏周数与日期切换；松手回弹 / 落位两种走向都对。
   - 接缝：拖动途中相邻周分界处有竖线，两列不被并成一宽列；落位后无多余边线闪动。
   - 纵向不回归：上滑折叠头部 + 日期带渐隐 + 模糊渐入；下滑展开顺序（列表先到顶、日期带先恢复）不变；滚动范围与改动前一致。
   - 三页共享滚动：滚到中段再左右拖动，相邻周与当前周处于同一纵向位置。
   - 联动：周数弹窗选远处周（直接落位）、选相邻周（滑动动画）、返回本周；学期末/学期前首屏落在正确周且不闪跳。
   - 边界：第 1 周右滑 / 末周左滑表现为系统拉伸反馈，不越界。

## 风险与回退

- 若本版本 `PagerState.currentPage` 不在拖动过半时翻转（而在落位后），顶栏就退化为"停稳后更新"；届时改用 `currentPageOffsetFraction` 推导预测周号，一处改动的回退路径。
- 不涉及数据层与既有单元测试；仅 3 个 UI 文件 + 1 个模型文件。
- 可选收尾：按本仓库既有约定，把本计划落到 `.mimocode/plans/`。
