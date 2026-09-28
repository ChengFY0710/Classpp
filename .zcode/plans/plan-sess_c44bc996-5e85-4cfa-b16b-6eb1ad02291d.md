# 课表编辑模式（本轮：进入编辑态 + 加课 + 保存/取消）

## 需求与已定选择

点顶栏左上角编辑按钮进入编辑态：顶栏折叠收起、日期层消失、日期行与周数胶囊消失、底部导航栏消失，改为三个按钮（保存 / 切换课表 / 取消）；空格子显示"添加卡片"（上起止时间、中间加号），点它弹出添加课程弹窗（课程名 / 教师 / 上课地点 / 上课周数 / 卡片颜色）。

已确认：**上课周数用方格点选**（复用周数选择器的小方块视觉 + 全选/单周/双周快捷）；**本轮只做加课**（点已有卡片无反应，不做编辑/删除）；**切换课表按钮置空**。

我另外定了这几条（可在评审时否决）：

- 编辑态**固定在当前查看的那一周**（设计稿里编辑态没有周次指示，故禁用左右翻周；加课的周次由弹窗里的周数决定，不靠"翻到那周"）。
- 本周不上的**置灰卡片视为该格空着**：保留卡片可见（能看出另一单双周占着），点它同样打开添加弹窗——否则那一格永远加不了课。
- **保存成功后退出编辑态**；**取消 / 返回键都丢弃草稿**（不发任何写盘请求）。
- 编辑态不加过渡动画（与设置页覆盖层一致，瞬时切换）；需要的话后续补。
- 颜色取设计稿近似值：保存绿、取消红新增为主题色，切换课表用现有 `primary`。

## 状态模型：草稿在 UI 层，仓库只做落盘

数据层**没有事务/草稿/批量接口**，每次 mutator 都是"校验 → 整库原子写盘 → 发布内存态"，且 `upsertCourse` 空 id 会自动生成 UUID、按 id 存在则替换否则追加。所以：

- **草稿 = 一份 `List<CourseEntry>` 快照**（进入编辑态时从 `schedule.courses` 拷贝），网格在编辑态直接渲染草稿——所见即草稿。
- **取消**：丢弃草稿即可，全程零写盘（不需要回滚接口）。
- **保存**：先拿 `schedule.copy(courses = 草稿)` 跑 `ScheduleValidator.validateCourses` 预检（防部分保存），通过后对**新增的课**逐条 `repository.upsertCourse(scheduleId, course)`（每条课自带 uuid，一次一条原子写），全部成功才退出编辑态；任一步失败用现有 `error_persist_failed` 文案提示并留在编辑态。多门新增若中途 I/O 失败会部分落盘 —— 预检已排除校验类失败，要严格原子可后续加一个批量 mutator（备注里记了）。
- **弹窗内校验**针对**草稿**（不是已保存的课表），所以"刚加但还没保存的课"造成的冲突能立刻报出来。

草稿状态用一个 `ScheduleEditSession`（沿用 `CollapseState`/`rememberCollapseState` 的房子风格）+ JSON Saver，**旋转/进程重建不丢未保存的添加**；离开编辑态即销毁（`remember(editing)`）。

## 界面构成

### 编辑栏（新文件 `ui/schedule/ScheduleEditBar.kt`）

三按钮一行（高 56dp，与现有顶栏行同高）+ 星期行，几何完全对齐现有头部，这样列宽与网格天然齐平：

```
┌─────────────────────────────────────────────┐
│ (✔日历) 保存      切换课表      取消 (✖日历) │  ← 56dp：左组 CenterStart、中居中、右组 CenterEnd
├─────────────────────────────────────────────┤
│   一     二     三     四     五             │  ← 星期行：与 ScheduleHeader 同 padding(10/7)、15sp SemiBold
└─────────────────────────────────────────────┘
```

- 左/右两组各自是**整块可点**（图标 + 文字，17sp SemiBold，与周数胶囊一致），图标 30dp：`ic_calendar_checkmark`（绿）、`ic_calendar_cancel`（红）——这两个 drawable **已存在且未被使用**，正是为此预留。
- 中间"切换课表"用 `primary` 蓝、纯文本，本轮点击为空实现。
- 编辑栏不参与折叠、不做 Haze 模糊；`surface` 实底。
- 星期行**不做今日高亮**（编辑态没有"今天"语义，设计稿也是全黑）。

进入编辑态后网格的 `contentPadding.top` 改为编辑栏实测高度（复用现有 `onGloballyPositioned` 机制）；`nestedScroll` 折叠连接不再挂（编辑态不折叠）。

### 添加卡片（`CourseCard.kt` 内新增）

与课程卡片同骨架、零新逻辑：`Column` + `Arrangement.SpaceBetween`，上=节次开始时间、下=结束时间（11sp Medium，设计稿的浅蓝 `#B1C4EE`），中间是 `ic_add` 图标（40dp，`primary` 蓝，水平居中）；**无左侧色条**；`clip(RoundedCornerShape(6.dp)) + background(surface) + clickable`。内边距取 `start = 7.dp`（= 色条 3 + 内缩 4），使两种卡片的起止时间左对齐在同一竖线上。

### 添加弹窗（新文件 `ui/schedule/AddCourseDialog.kt`）

M3 `AlertDialog`：标题"添加课程" + 一行格子信息（如「周三 · 第 3 节 · 14:30-16:10」），正文可滚动，依次是：

1. 课程名 / 教师 / 上课地点：`OutlinedTextField`（singleLine，房子风格与设置页一致）；
2. 上课周数：`1..totalWeeks` 方格（复用 `WeekCell` 视觉：44dp 方块、选中=primary 底白字、圆角 11dp），配 `[全选] [单周] [双周]` 三个快捷（直接设定选择集）；下方一行摘要文本（如「已选：第 1-12 周」）；
3. 颜色：8 个色块圆点（36dp，取自 `CourseColor.barColor`，选中加描边）；
4. 错误行（复用设置页 `ErrorBox` 的红色样式）。

确定时：先查"至少选一周"（校验器不会报空 pattern，这里必须自己拦），再构造 `CourseEntry(id = newUuid(), …, span = 1, weeks = weeksFromSelection(选中集), color = 选定色)`，对 `草稿 + 新课` 跑 `validateCourses`，有错就地显示（含现有课程名，如「与「概率统计」时间冲突」），无错则加入草稿并关闭。默认值：三项文本为空、周数**全选**（保证加完立刻能在当前周看见）、颜色 `Blue`。

### 周数选择集 → `WeekPattern`（纯逻辑，放数据层并加单测）

新文件 `data/model/WeekSelection.kt`：

- `weeksFromSelection(weeks: Collection<Int>): WeekPattern`：排序后贪心合并——连续段合成 `ALL`，等差 2 的段合成 `ODD`/`EVEN`（如 `1,3,5 → [1-5,ODD]`、`1,2,3 → [1-3,ALL]`），输出与选中集合**逐一等价**（用 `contains` 断言）。
- `WeekPattern.toDisplayText(): String`：反向的展示文案（`1-8、10-12 单周`），供摘要行用。
- 这两个函数按 `TimeText` 的风格写：纯函数、不抛异常。

## 改动清单

| 文件 | 改动 |
| --- | --- |
| `MainActivity.kt` | 新增 `editing`（`rememberSaveable`），传给 `ScheduleScreen(editing, onEditingChange)`；`BottomNavBar` 在 `editing` 时不组合 |
| `ui/schedule/ScheduleScreen.kt` | 编辑态分支：创建/销毁 `ScheduleEditSession`、草稿 provider、编辑栏与网格的接线、弹窗宿主、保存/取消逻辑、`BackHandler`、编辑态禁用翻周与折叠 |
| `ui/schedule/ScheduleEditSession.kt`（新） | 草稿 + 新增集合 + JSON Saver |
| `ui/schedule/ScheduleEditBar.kt`（新） | 编辑栏 |
| `ui/schedule/AddCourseDialog.kt`（新） | 添加弹窗（含周数方格、色块、错误显示） |
| `ui/schedule/CourseCard.kt` | `AddCourseCard`；`CourseColor.barColor` 由 private 改 internal；`CourseCard` 增加可选 `onClick`（编辑态给置灰卡片用） |
| `ui/schedule/CourseGrid.kt` | 新增 `editMode` / `onAddClick` / `showDates` 三个参数：编辑态空位渲染添加卡片、隐藏日期带（页高相应减少）、`beyondViewportPageCount` 取 0 |
| `ui/schedule/ScheduleAdapters.kt` | 抽出 `CourseEntry.toUiCourse(active)`，供草稿映射复用 |
| `ui/theme/Color.kt` | 新增 `SaveGreen` / `CancelRed`（设计稿取色近似值） |
| `data/model/WeekSelection.kt`（新） | 选择集 ⇄ `WeekPattern` 的合并与文案 |
| `res/values/strings.xml`、`values-en/strings.xml` | 新增：保存/切换课表/取消/添加课程/课程名/教师/上课地点/上课周数/颜色/全选/单周/双周/已选格式，以及"课程名不能为空""请至少选择一周""与「%1$s」时间冲突" |
| `app/src/test/.../WeekSelectionTest.kt`（新） | 合并等价性（含单双周、边界、空集）与文案格式化 |

## 验证

1. `.\gradlew.bat :app:compileDebugKotlin`、`:app:testDebugUnitTest`（新增周数合并测试）。
2. 打包就地安装到设备。
3. 上机清单：
   - 进入编辑态：编辑栏取代顶栏（图标行/日期行/周数胶囊消失）、日期带消失、底部导航栏消失、星期行还在且与网格列对齐；网格仍可上下滚动。
   - 空位显示添加卡片（时间与课程卡片左对齐、加号居中）；点加号 → 弹窗格子信息正确。
   - 填名/教师/地点、点选周数（含全选/单周/双周快捷）、选色 → 确定 → 卡片立刻出现在网格（未保存）；连加两门。
   - 保存 → 退出编辑态；切 tab 再回来 / 冷启动后仍在（已真正落盘）。
   - 再加一门后取消（或按返回键）→ 编辑态退出且该课未落盘。
   - 冲突：对同一格、周数有交集再加一门 → 弹窗内报「与「XXX」时间冲突」。
   - 旋转屏幕：未保存的添加仍在（草稿 Saver）。
   - 空草稿点保存 → 直接退出，不写盘。
   - 回归：普通态左右翻周、头部折叠、Haze 模糊、tab 平移、周数弹窗均不受影响。

## 备注

- 多门新增的保存是"逐条原子写"，非整批原子；要严格原子可加 `setCourses(scheduleId, courses)` 单次 commit 的批量 mutator（本轮不加，避免动数据层契约与其测试）。
- 加号大小、添加卡片浅蓝、保存绿/取消红都是设计稿近似值，改常量即可。
- 若后续做"编辑/删除已有课程"：同一弹窗加预填 + 删除按钮即可，需给课程卡片加点击并把 `CourseEntry.id` 传到 UI 层（现在 UI `Course` 模型里没有 id）。
