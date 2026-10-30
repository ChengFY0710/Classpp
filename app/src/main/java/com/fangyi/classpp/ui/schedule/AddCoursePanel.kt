package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.FieldReason
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.model.CourseColor as DataCourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.firstUnusedColor
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.data.model.overlappingCourses
import com.fangyi.classpp.data.model.weeksFromSelection
import com.fangyi.classpp.data.model.weeksTakenByOthers
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ColorSwatchCard
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.RowChoiceCard
import com.fangyi.classpp.ui.components.SelectionGrid
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetInfoPillRow
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTextFieldGroup
import com.fangyi.classpp.ui.components.SheetTextFieldItem
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.isDarkTheme
import dev.chrisbanes.haze.rememberHazeState

/**
 * 添加/编辑课程面板：[day] / [slot] 是点中的那一格（编辑态传被编辑课的星期与起始节）；
 * [schedule] 提供总周数与校验上下文；[draftCourses] 是编辑态草稿——冲突校验针对
 * "草稿 + 面板内容"，所以刚加、还没保存的课也能立刻报出冲突。
 *
 * [existing] 非空 = 编辑已有课程：字段预填、星期/起始节次锁定（仅内容可改）、
 * 标题切换、显示删除按钮；[onDelete] 点删除即回调（直接删草稿，无二次确认——
 * 取消编辑即可整体撤销）。两者都只作用于草稿，保存时才落库。
 *
 * [alternateFrom] 非空 = 长按已有课新建**交替课程**（与 [existing] 互斥）：默认与源课同起止，
 * 结束节次可往下延伸，与范围有交集的课程都一起成为交替课程（不要求完整包住，只要周数不冲突）。
 * 周数默认取该组还没被占用的周、配色取组内没用过的第一个；组内其它课（含被源课跨节覆盖的
 * 续格课）已占的周在方格里灰显不可点——交替课程的周数不能重合。组内有跨节课程（span ≥ 2）时
 * 出现「开始节次」选择：组员的起止节次可以不一致，开始节次最早可选到组的最早节次、最晚不超过
 * 源课末节。
 * 编辑已有课时同样灰显组内其它课占用的周。
 *
 * **UI 层已按设计稿改造为顶栏浮层（见 [OverlaySheet]）**：
 * - 浮层距屏幕顶端 = 设备状态栏高度（`OverlaySheet` 默认读取 `WindowInsets.statusBars`），
 *   **键盘弹起不改变浮层位置与高度**——
 *   让位发生在滚动内容末尾，容器本身不动；
 * - 顶栏 = 左确认 / 中标题 / 右取消（编辑态删除），背景对滚动内容做渐变模糊并收敛在圆角内；
 * - 表单控件全部抽成可复用组件（输入框/行选择/浮层选择/颜色选择卡片）；
 * - 表单内容按浮层内容规范排版：顶层块一律 [CardSection]——课程名单卡与
 *   「教师/地点」两行合并卡同收在一个无题分组，其余是带题分组；位置信息胶囊行
 *   （星期 / 节次 / 起止时间，[SheetInfoPillRow]）是唯一的裸元素（规范里的直接文本例外）。
 *   顶层块间距走 [SheetSectionSpacingBetween]（12dp），组内卡间距由 CardSection 默认值负责。
 *
 * **生命周期**：[visible] 为 true 时面板从底部滑入；关闭流程是两段式——所有关闭入口
 * （确认/删除/返回/点遮罩/下拉）只回调 [onDismiss]（调用方据此清状态 → visible 变 false），
 * 浮层播完滑出动画后回调 [onDismissed]，调用方在这一刻才把面板移出组合，
 * 收场期间表单内容保持原样不闪空。
 *
 * **刻意不用 AlertDialog**：对话框是独立窗口，本机（Android 15+/16 的 edge-to-edge + 该 ROM）
 * 输入法接不进去（点了输入框不弹键盘）。改成和设置页同一条路——页内覆盖层，
 * 输入法行为与设置页一致，新建时打开即自动聚焦课程名并唤起键盘（编辑已有课不聚焦不弹键盘）。
 */
@Composable
fun AddCoursePanel(
    visible: Boolean,
    day: Int,
    slot: TimeSlot,
    schedule: Schedule,
    draftCourses: List<CourseEntry>,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onConfirm: (CourseEntry) -> Unit,
    existing: CourseEntry? = null,
    onDelete: (() -> Unit)? = null,
    alternateFrom: CourseEntry? = null,
) {
    // 编辑态从 existing 预填；面板随 if 离开组合即销毁，重开时初值重新生效
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var teacher by rememberSaveable { mutableStateOf(existing?.teacher ?: "") }
    var location by rememberSaveable { mutableStateOf(existing?.location ?: "") }
    // 开始节次：仅新建交替课程面板出现选择项（组内有跨节课时，见 showStartSlot），
    // 默认与源课同起始；编辑/新建普通课程锁定为所在格
    var startSlotId by rememberSaveable(slot.id) {
        mutableStateOf(existing?.startSlot ?: alternateFrom?.startSlot ?: slot.id)
    }
    // 结束节次：key=slot.id，重开面板（或旋转恢复后目标格变化）即复位；
    // 编辑态初值取被编辑课的末节，新建交替课时默认跟随源课（可再往下延伸）
    var endSlotId by rememberSaveable(slot.id) {
        mutableStateOf(existing?.endSlot ?: alternateFrom?.endSlot ?: slot.id)
    }
    // 与草稿当前范围（开始节次..结束节次）相交的同天其它课 = 交替面（排除正在编辑的这门自己）：
    // 跨节吞掉的续格课也在内——它们一起成为交替课程，起止节次各自不变
    val groupOthers = draftCourses.overlappingCourses(day, startSlotId, endSlotId)
        .filterNot { it.id == existing?.id }
    // 交替面占用的周：不能选（周数重合即冲突），新建时把整组都算作占用。
    // 随节次选择即时变化：把结束节次拉到某门课上，那一课的周就地灰显
    val blockedWeeks = draftCourses.weeksTakenByOthers(
        selfId = existing?.id ?: "",
        dayOfWeek = day,
        startSlot = startSlotId,
        endSlot = endSlotId,
        totalWeeks = schedule.totalWeeks,
    )
    var selectedWeeks by rememberSaveable {
        mutableStateOf(
            when {
                existing != null -> (1..schedule.totalWeeks).filter { existing.weeks.contains(it) }
                // 新建交替课：默认取互补的那些周（单周 ↔ 双周这类交替一步到位）
                alternateFrom != null -> (1..schedule.totalWeeks).filterNot { it in blockedWeeks }
                // 新建普通课程：默认不选，让用户主动勾选——确认时没选会被校验拦下并 toast
                else -> emptyList()
            },
        )
    }
    // 色块用渲染层枚举，落库时按 name 桥接（同 ScheduleAdapters）。
    // 新建交替课时取组内没用过的首个配色：与当周课同色的话，上下两段色条分辨不出来
    var color by rememberSaveable {
        mutableStateOf(
            when {
                existing != null -> CourseColor.valueOf(existing.color.name)
                alternateFrom != null -> draftCourses.firstUnusedColor(day, startSlotId, endSlotId)
                else -> CourseColor.entries.first()
            },
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    // 新建交替课的"组"：与源课 span 相交的同天课程（含源课自己）
    val alternateGroup = alternateFrom?.let { source ->
        draftCourses.overlappingCourses(day, source.startSlot, source.endSlot)
    }.orEmpty()
    // 组内有一门跨节（span ≥ 2）时组员的起止节次可能不一致，开始节次才需要可选——
    // 让新交替课对齐到不同成员（如源课只占第 4 节、组里的跨节课占 3-4 节，可选 3 或 4 起始）
    val showStartSlot = alternateFrom != null && alternateGroup.any { it.span >= 2 }
    // 开始节次菜单：下限取组的最早节次（新交替课不能探出组的占用范围顶端）与跨度上限，
    // 上限不超过源课末节——结束节次延伸后开始节次仍留在源课范围内，新范围才始终与源课相交
    val groupEarliestSlot = alternateGroup.minOfOrNull { it.startSlot } ?: slot.id
    val startMenuItems = run {
        val maxSlot = minOf(endSlotId, alternateFrom?.endSlot ?: endSlotId)
        val minSlot = maxOf(groupEarliestSlot, endSlotId - ScheduleValidator.MAX_SPAN + 1, 1)
        (minSlot..maxSlot).map { id -> id to stringResource(R.string.slot_format, id) }
    }

    val weekdays = stringArrayResource(R.array.weekdays)
    val weekdayName = weekdays[(day - 1).coerceIn(weekdays.indices)]
    val slotLabel = if (endSlotId == startSlotId) {
        stringResource(R.string.slot_format, startSlotId)
    } else {
        stringResource(R.string.slot_range_format, startSlotId, endSlotId)
    }
    // 恢复的节次理论上不越界，仍钳一下防进程重建后的极端情况
    val startDef = schedule.slots[(startSlotId - 1).coerceIn(schedule.slots.indices)]
    val endDef = schedule.slots[(endSlotId - 1).coerceIn(schedule.slots.indices)]
    val timeRange = stringResource(
        R.string.time_range_format,
        startDef.startTime,
        endDef.endTime,
    )
    // 已选周 → 周模式：提交时作为 CourseEntry.weeks 落库
    val weeks = weeksFromSelection(selectedWeeks)

    // 结束节次菜单：新建交替课默认与源课一致，可往下延伸把途经课程包进范围（只伸不缩）；
    // 其余场景（新建普通课 / 编辑）从起始节起按跨度上限列出
    val maxEnd = minOf(schedule.slotCount, startSlotId + ScheduleValidator.MAX_SPAN - 1)
    val minEnd = if (alternateFrom != null) {
        maxOf(startSlotId, alternateFrom.endSlot)
    } else {
        startSlotId
    }
    val endMenuItems = (minEnd..maxEnd).map { id -> id to stringResource(R.string.slot_format, id) }

    // 文案先取出来：校验发生在 lambda 里，那里不能再调 stringResource
    val errorNameBlank = stringResource(R.string.error_course_name_blank)
    val errorWeeksEmpty = stringResource(R.string.error_weeks_empty)
    val errorWeeksTaken = stringResource(R.string.error_alternate_weeks_taken)
    val errorWeeksBeyondTermFormat = stringResource(R.string.error_weeks_beyond_term_course)
    val errorTermEndFormat = stringResource(R.string.error_term_end_course)
    val errorSpanFormat = stringResource(R.string.error_span_out_of_range)
    val errorConflictFormat = stringResource(R.string.error_grid_conflict)
    val errorUnexpected = stringResource(R.string.error_unexpected)

    // 节次选择校验：普通新建/编辑时，范围调整后若与某门被涉课的周数重合，点名报冲突——
    // "周数冲突的不能设置跨节"在选择层就拦下，不用等确认时的整草稿校验。
    // 新建交替课不在这里拦：延伸包进的课程自动占周、已选周随之剔除（见 dropCoveredWeeks），
    // 剩下的周数与整组天然不冲突
    fun rangeConflictMessage(start: Int, end: Int): String? {
        if (alternateFrom != null) return null
        return draftCourses.firstOrNull { course ->
            course.id != existing?.id &&
                course.dayOfWeek == day &&
                course.startSlot <= end && course.endSlot >= start &&
                selectedWeeks.any { course.weeks.contains(it) }
        }?.let { errorConflictFormat.format(it.name) }
    }

    // 范围变化后：新包进课程的占用周从已选周里剔除——它们自动成为交替课，新课只剩剩下的周
    // （非交替场景维持"周数冲突就拦下"的交互，见 rangeConflictMessage）
    fun dropCoveredWeeks(start: Int, end: Int) {
        if (alternateFrom == null) return
        val blocked = draftCourses.weeksTakenByOthers(
            selfId = existing?.id ?: "",
            dayOfWeek = day,
            startSlot = start,
            endSlot = end,
            totalWeeks = schedule.totalWeeks,
        )
        selectedWeeks = selectedWeeks.filterNot { it in blocked }
    }

    val nameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val context = LocalContext.current

    // 校验失败 = 系统 Toast（不占版面）+ error 状态（驱动课程名红框）。
    // 同一条出口会刷新上一条，连点确认不会排队刷屏（见 AppToasts）
    fun fail(message: String) {
        error = message
        AppToasts.show(context, message)
    }
    // 新建（含长按新建交替课）打开面板就把光标放进课程名并唤起输入法；
    // 编辑已有课程不自动聚焦——进来多半只想改周数/配色这类开关，别急着弹键盘打扰
    if (existing == null) {
        LaunchedEffect(Unit) {
            nameFocus.requestFocus()
            keyboard?.show()
        }
    }
    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关面板
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    fun submit() {
        if (selectedWeeks.isEmpty()) {
            fail(errorWeeksEmpty)
            return
        }
        // 交替课程的周数不能重合：方格里已灰显点不动，这里兜住进程重建后恢复出的越界选择
        if (selectedWeeks.any { it in blockedWeeks }) {
            fail(errorWeeksTaken)
            return
        }
        // 结束节次已在菜单层限制，这里再钳一次（跨度 ≤ MAX_SPAN 且不超总节数）
        val start = startSlotId.coerceIn(1, schedule.slotCount)
        val end = endSlotId.coerceIn(
            start,
            minOf(schedule.slotCount, start + ScheduleValidator.MAX_SPAN - 1),
        )
        val entry = CourseEntry(
            // 编辑态沿用原 id（写回时按 id 覆盖），添加态生成新 id
            id = existing?.id ?: newUuid(),
            name = name.trim(),
            teacher = teacher.trim(),
            location = location.trim(),
            dayOfWeek = day,
            startSlot = start,
            span = end - start + 1,
            weeks = weeks,
            // 色块用的是渲染层的 CourseColor（同包、自带配色），落库时按 name 桥接（同 ScheduleAdapters）
            color = DataCourseColor.valueOf(color.name),
        )
        // 校验集合排除被编辑的课自己：草稿里它还是旧内容，不排除会跟新内容自相冲突
        val others = draftCourses.filterNot { it.id == existing?.id }
        val draft = schedule.copy(courses = others + entry)
        val conflict = ScheduleValidator.validateCourses(draft).firstOrNull()
        // 课程类错误一律点名到课程，别把内部 id / 英文 message 漏给用户；
        // 报错的那门不一定是面板正在编辑的这门——学期被改短后，可能是草稿里另一门先越界，
        // 所以按 courseId 回查草稿拿它自己的名字（新课的 id 刚生成，回查必落空 → 用输入框的值）。
        val message = conflict?.let { e ->
            val errorCourse = draft.courses.firstOrNull { c ->
                when (e) {
                    is ScheduleError.CourseFieldInvalid -> c.id == e.courseId
                    is ScheduleError.WeeksBeyondTerm -> c.id == e.courseId
                    is ScheduleError.TermEndInvalid -> c.id == e.courseId
                    else -> false
                }
            }
            val courseName = errorCourse?.name ?: entry.name.ifBlank { existing?.name ?: "" }
            when (e) {
                is ScheduleError.CourseFieldInvalid -> when (e.reason) {
                    FieldReason.BlankName -> errorNameBlank
                    FieldReason.SpanOutOfRange -> errorSpanFormat.format(courseName)
                }
                is ScheduleError.WeeksBeyondTerm ->
                    errorWeeksBeyondTermFormat.format(courseName, e.lastDate, schedule.totalWeeks)
                // 末周只上到某天时，晚于它的那天就放不下：按日期说清楚，不打印 UUID
                is ScheduleError.TermEndInvalid ->
                    errorTermEndFormat.format(courseName, e.date)
                is ScheduleError.GridConflict -> {
                    // 新课是列表里最后一个，冲突对里另一个就是已存在的课
                    val other = if (e.a.id == entry.id) e.b else e.a
                    errorConflictFormat.format(other.name)
                }
                else -> errorUnexpected
            }
        }
        if (message == null) {
            // 无冲突 = 校验通过：顺手清掉上一轮的红框
            error = null
            onConfirm(entry)
        } else {
            fail(message)
        }
    }

    // 行选择卡片高亮：跟随实际周数选择——全部 / 全奇数 / 全偶数，自定义组合不高亮
    val weekChoiceIndex = when {
        selectedWeeks.isEmpty() -> null
        selectedWeeks.size == schedule.totalWeeks -> 0
        selectedWeeks.size == (schedule.totalWeeks + 1) / 2 &&
            selectedWeeks.all { it % 2 == 1 } -> 1
        selectedWeeks.size == schedule.totalWeeks / 2 &&
            selectedWeeks.all { it % 2 == 0 } && schedule.totalWeeks >= 2 -> 2
        else -> null
    }

    // 共享采样源：浮层滚动列是它的采样源，叠在本浮层上的选择行菜单（起止节次）拿同一份
    // 做卡片磨砂背景模糊；同时交给浮层本身，顶栏磨砂与菜单背景模糊同源（NewTodoSheet 同款接线）
    val sheetHazeState = rememberHazeState()

    OverlaySheet(
        title = stringResource(
            when {
                existing != null -> R.string.edit_edit_course
                alternateFrom != null -> R.string.edit_new_alternate
                else -> R.string.edit_add_course
            },
        ),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消/删除在左（顶栏胶囊随位置镜像图标排布）
        confirmAtEnd = true,
        onConfirm = { submit() },
        rightAction = if (existing != null && onDelete != null) {
            SheetTopAction(
                label = stringResource(R.string.edit_delete),
                icon = R.drawable.ic_delete_filled,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = onDelete,
            )
        } else {
            SheetTopAction(
                label = stringResource(R.string.settings_cancel),
                icon = R.drawable.ic_dismiss_circle,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                onClick = onDismiss,
            )
        },
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 位置信息胶囊行（星期 / 节次 / 起止时间）：浮层规范里的"直接文本"例外，
            // 不包分组——三枚白底胶囊代替原来的「当前：…」灰字行
            SheetInfoPillRow(values = listOf(weekdayName, slotLabel, timeRange))

            // 课程名单卡 + 教师/地点两行合并卡：同收一个无题分组，组内 10dp 卡间距。
            // 合并卡整卡描边：任一教师/地点行聚焦时整卡蓝框（行间切换不闪断）
            CardSection {
                SheetTextField(
                    label = stringResource(R.string.edit_course_name),
                    value = name,
                    onValueChange = { name = it; error = null },
                    placeholder = stringResource(R.string.edit_course_name_ph),
                    isError = error == errorNameBlank,
                    imeAction = ImeAction.Next,
                    onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                    focusRequester = nameFocus,
                )
                SheetTextFieldGroup(
                    fields = listOf(
                        SheetTextFieldItem(
                            label = stringResource(R.string.edit_course_teacher),
                            value = teacher,
                            onValueChange = { teacher = it; error = null },
                            placeholder = stringResource(R.string.edit_course_teacher_ph),
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        ),
                        SheetTextFieldItem(
                            label = stringResource(R.string.edit_course_location),
                            value = location,
                            onValueChange = { location = it; error = null },
                            placeholder = stringResource(R.string.edit_course_location_ph),
                            imeAction = ImeAction.Done,
                            onImeAction = { focusManager.clearFocus() },
                        ),
                    ),
                )
            }

            CardSection(title = stringResource(R.string.edit_end_slot_section)) {
                // 开始节次：仅组内有跨节课时出现（组员起止可以不一致），与结束节次同一套选择卡
                if (showStartSlot) {
                    SettingsCard(
                        hazeState = sheetHazeState,
                        items = listOf(
                            SettingsCardItem.Select(
                                label = stringResource(R.string.edit_start_slot),
                                value = startSlotId.toString(),
                                items = startMenuItems,
                                selectedId = startSlotId,
                                onPick = { id ->
                                    val message = rangeConflictMessage(id, endSlotId)
                                    if (message == null) {
                                        startSlotId = id
                                        dropCoveredWeeks(id, endSlotId)
                                        error = null
                                    } else {
                                        fail(message)
                                    }
                                },
                            ),
                        ),
                    )
                }
                SettingsCard(
                    hazeState = sheetHazeState,
                    items = listOf(
                        SettingsCardItem.Select(
                            label = stringResource(R.string.edit_end_slot),
                            value = endSlotId.toString(),
                            items = endMenuItems,
                            selectedId = endSlotId,
                            onPick = { id ->
                                val message = rangeConflictMessage(startSlotId, id)
                                if (message == null) {
                                    endSlotId = id
                                    dropCoveredWeeks(startSlotId, id)
                                    error = null
                                } else {
                                    fail(message)
                                }
                            },
                        ),
                    ),
                )
                SheetInfoBanner(
                    text = stringResource(
                        R.string.edit_span_hint,
                        startSlotId,
                        endSlotId,
                        endSlotId - startSlotId + 1,
                    ),
                )
            }

            CardSection(title = stringResource(R.string.edit_course_weeks)) {
                SelectionGrid(
                    values = (1..schedule.totalWeeks).toList(),
                    labels = (1..schedule.totalWeeks).map { it.toString() },
                    selected = selectedWeeks.toSet(),
                    blocked = blockedWeeks,
                    onToggle = { week ->
                        selectedWeeks = if (week in selectedWeeks) {
                            selectedWeeks - week
                        } else {
                            (selectedWeeks + week).sorted()
                        }
                        error = null
                    },
                )
                RowChoiceCard(
                    options = listOf(
                        stringResource(R.string.edit_weeks_all),
                        stringResource(R.string.edit_weeks_odd),
                        stringResource(R.string.edit_weeks_even),
                    ),
                    selectedIndex = weekChoiceIndex,
                    onSelect = { index ->
                        // 再按一下已选中的快捷 = 取消选中：清空回到「尚未选中」，可重新勾选
                        if (index == weekChoiceIndex) {
                            selectedWeeks = emptyList()
                            error = null
                        } else {
                            val weeksForChoice = when (index) {
                                0 -> (1..schedule.totalWeeks).toList()
                                1 -> (1..schedule.totalWeeks).filter { it % 2 == 1 }
                                else -> (1..schedule.totalWeeks).filter { it % 2 == 0 }
                            }
                            applyShortcut(
                                weeks = weeksForChoice,
                                blocked = blockedWeeks,
                                errorText = errorWeeksTaken,
                                apply = { selectedWeeks = it },
                                onError = { fail(it) },
                            )
                        }
                    },
                )
                // 同格的其它交替课占了哪些周：说清灰显方格是被谁占的
                groupOthers.forEach { other ->
                    SheetInfoBanner(
                        text = stringResource(
                            R.string.edit_alternate_taken_hint,
                            other.name,
                            weeksSummary(other.weeks),
                        ),
                    )
                }
            }

            CardSection(title = stringResource(R.string.edit_course_color)) {
                ColorSwatchCard(
                    // 色板随主题取深浅取值：所见即卡片竖条的实际颜色
                    colors = CourseColor.entries.map { it.barColor(MaterialTheme.isDarkTheme) },
                    selectedIndex = CourseColor.entries.indexOf(color),
                    onSelect = { index ->
                        CourseColor.entries.getOrNull(index)?.let { color = it }
                        error = null
                    },
                )
            }
        }
    }
}

/**
 * 周数快捷（全选/单周/双周）：先按快捷取周，再剔掉被同格其它交替课占用的周；
 * 剔完为空说明这个快捷下没有任何可选的周，报错而不动当前选择
 * （取消选中的空选择由"再次点击已高亮项"提供，见调用处的 onSelect）。
 */
private fun applyShortcut(
    weeks: List<Int>,
    blocked: Set<Int>,
    errorText: String,
    apply: (List<Int>) -> Unit,
    onError: (String) -> Unit,
) {
    val usable = weeks.filterNot { it in blocked }
    if (usable.isEmpty()) onError(errorText) else apply(usable)
}

/** 已选周数的可读文案：`第 1-12 周`、`第 1-15 周（单周）`；空选择给"尚未选择"（课程详情复用） */
@Composable
internal fun weeksSummary(pattern: WeekPattern): String {
    val noneText = stringResource(R.string.edit_weeks_none)
    if (pattern.segments.isEmpty()) return noneText
    val separator = stringResource(R.string.edit_weeks_separator)
    val oneFormat = stringResource(R.string.edit_weeks_one)
    val rangeFormat = stringResource(R.string.edit_weeks_range)
    val oddSuffix = stringResource(R.string.edit_weeks_suffix_odd)
    val evenSuffix = stringResource(R.string.edit_weeks_suffix_even)
    return pattern.segments.joinToString(separator) { segment ->
        val range = if (segment.start == segment.end) {
            oneFormat.format(segment.start)
        } else {
            rangeFormat.format(segment.start, segment.end)
        }
        when (segment.parity) {
            Parity.ALL -> range
            Parity.ODD -> range + oddSuffix
            Parity.EVEN -> range + evenSuffix
        }
    }
}
