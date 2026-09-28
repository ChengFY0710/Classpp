## 目标

编辑模式：点已有课程卡（单节/置灰/跨节卡含续格覆盖区）→ 打开编辑浮层面板（预填、标题「编辑课程」），可改课名/教师/地点/结束节次/周数/颜色（星期与起始节次锁定），面板内新增「删除」按钮（直接删草稿，无二次确认）。删除/修改随「保存」落库，随「取消」整体撤销。

## 改动清单

### 1. 渲染模型加 id — `ui/schedule/ScheduleModels.kt` + `ScheduleAdapters.kt`
- `Course` 末尾加 `val id: String = ""`（默认值不破坏 MockCourses 位置参数；预览 mock 非编辑态不可点，无影响）
- `CourseEntry.toUiCourse(active)`（ScheduleAdapters.kt:21-30）透传 `id = id`，更新过时注释

### 2. 网格点击接线 — `ui/schedule/CourseGrid.kt`
- `CourseGrid` 新参数 `onEditClick: ((courseId: String) -> Unit)? = null`
- GridRow（:355-409）：span==1 分支的 CourseCard `onClick = if (editMode && onEditClick != null) { { onEditClick(course.id) } } else null`。**语义变化**：置灰卡从「点它去添加」改为「点它去编辑」；`add` 仅保留给空格 AddCourseCard
- WeekPage 叠加层（:248-252）：span>1 卡同样接 `onEditClick(course.id)`（整张跨节卡可点，覆盖续格区域）；替换原「置灰可加」逻辑
- 更新相关注释（GridRow/WeekPage/CourseCard.kt:66-67 的 onClick 语义说明）

### 3. 草稿会话扩展 — `ui/schedule/ScheduleEditSession.kt`
- 新增 `update(id, entry)`、`remove(id)`（courses 整表替换，触发网格重组）
- 新增 diff：`updatedCourses()`（id 在快照里且内容有变的）、`removedCourseIds()`（快照里有、草稿里没有的）；`initial` 快照已留存，**Saver 无需改**（KDoc :15-18 本就预留此扩展）
- 新增 `ScheduleEditSessionTest`：add/update/remove、三个 diff、Saver 往返（编辑+删除后恢复）

### 4. 编辑面板 — `ui/schedule/AddCoursePanel.kt`
- 签名加 `existing: CourseEntry? = null`、`onDelete: (() -> Unit)? = null`
- 状态预填（面板随 `if` 离开组合即重置，rememberSaveable 初值生效）：name/teacher/location 取 existing；`selectedWeeks` 由 `existing.weeks` 展开为 1..totalWeeks 过滤列表；color 按枚举 name 直转；`endSlotId` 初值 = existing.endSlot
- 标题：existing 非空用新字符串，否则原 `edit_add_course`；头部节次信息公式不变（day/slot 由 ScheduleScreen 传 entry 的值）
- submit()：`id = existing?.id ?: newUuid()`；冲突校验集合改 `draftCourses.filterNot { it.id == existing?.id } + entry`（排除自己，避免 R13 自冲突）；星期/起始节次不提供修改控件
- 按钮行（:358-372）：existing 非空时行首加「删除」TextButton（`colorScheme.error` 色），直接回调 onDelete；保留右侧 取消/确定
- strings（values/ + values-en/ 同位插入）：`edit_edit_course`（编辑课程 / Edit course）、`edit_delete`（删除 / Delete）

### 5. 屏幕接线 — `ui/schedule/ScheduleScreen.kt`
- 新增 `var editTargetId by rememberSaveable { mutableStateOf("") }`（"" = 关闭）；`onEditClick = { editTargetId = it }` 传给 CourseGrid
- `val editingEntry = editSession?.courses?.firstOrNull { it.id == editTargetId }`——**删除后自然变 null，面板自动关闭**
- 面板分支（:394-410）改两路：editingEntry 非空 → `AddCoursePanel(day = entry.dayOfWeek, slot = 起始节 TimeSlot, existing = entry, onDelete = { editSession.remove(id); editTargetId = "" }, onConfirm = { editSession.update(entry.id, it); editTargetId = "" })`；否则走原 addTarget 添加流（不变）
- onSaveEdit（:280-317）：预校验整份草稿不变；落库改为 `addedCourses() + updatedCourses()` 逐个 `upsertCourse`，再对 `removedCourseIds()` 逐个 `repository.removeCourse`（仓库 API 已存在、无 UI 调用方）；任一 Err → toast（复用 `toEditMessage`）并保持编辑态

### 6. 验证
- `:app:testDebugUnitTest` 全量（新增 ScheduleEditSessionTest）
- `:app:compileDebugKotlin`
- 手测：①点单节/置灰/跨节卡 → 预填正确、标题「编辑课程」②跨节卡续格区域可点 ③改结束节次 → 跨行渲染与续格抑制即时正确 ④删除 → 卡消失面板关、保存后持久化 ⑤编辑+删除后「取消」→ 全部还原 ⑥改成与别的课冲突 → 「与…时间冲突」⑦空格添加流回归 ⑧旋转/杀进程恢复草稿与面板