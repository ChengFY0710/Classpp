package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.TagOpResult
import com.fangyi.classpp.data.TagRepository
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TagEntry
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.components.BottomDateSelectCard
import com.fangyi.classpp.ui.components.BottomTextFieldCard
import com.fangyi.classpp.ui.components.BottomTimeSelectCard
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DateSelection
import com.fangyi.classpp.ui.components.DateSelectionCard
import com.fangyi.classpp.ui.components.DeadlineCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.GroupCard
import com.fangyi.classpp.ui.components.GroupCardItem
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.RepeatFrequency
import com.fangyi.classpp.ui.components.RowChoiceCard
import com.fangyi.classpp.ui.components.SettingsCardwithIcon
import com.fangyi.classpp.ui.components.SettingsCardwithIconItem
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextArea
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.TagChoosingCard
import com.fangyi.classpp.ui.components.TagItem
import com.fangyi.classpp.ui.components.TimeRangeSlider
import com.fangyi.classpp.ui.components.UrgentFlagCard
import com.fangyi.classpp.ui.schedule.CourseColor
import com.fangyi.classpp.ui.schedule.barColor
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 时段滑块的初始区间：8:00–18:00（当天分钟数） */
private const val INITIAL_START_MINUTE = 480
private const val INITIAL_END_MINUTE = 1080

/**
 * 新建/编辑待办浮层：同一套表单，[existing] 区分模式（同 [com.fangyi.classpp.ui.schedule.AddCoursePanel]
 * 的 existing 语义），容器为 [OverlaySheet]，「确认」胶囊在右上（confirmAtEnd）。
 * 组装结果经 [onConfirm] 交回调用方落库——新建以 id 空串为底（仓库补 id/createdAt），
 * 编辑以 [existing] 为底 copy、原样保留 id/完成状态/子步骤/创建时间；实际执行
 * （addTodo / updateTodo / removeTodo）由调用方负责，这里纯 UI——除标签增删
 * 直写 TagRepository 外不碰仓库。
 *
 * 模式差异：标题「新建待办」/「编辑待办」；左上胶囊「取消」/ 红色「删除」（[onDelete] 非空时，
 * 同 AddCoursePanel）。删除先弹 [DeleteTodoConfirmDialog] 应用内二次确认（破坏性操作，
 * 同课表删除确认），确认才回调 [onDelete]；弹窗在场时本浮层经 [OverlaySheet] 的 covered
 * 缩小后退（同分层动效）。编辑初值整体回填。
 *
 * 表单按浮层内容规范排版：顶层块一律 [CardSection]——待办名是孤立的输入卡，收进无题分组；
 * 其余按语义分组：分组（[SettingsCardwithIcon] 待办分组入口 → [GroupPickSheet] 选择分组浮层）→
 * 提醒与优先级（截止日期 + 紧急旗帜）→ 日期与时间（DateSelectionCard + 无/全天/时段，
 * 选「时段」展开 TimeRangeSlider）→ 标签 → 更多（待办地点 + 备注）。顶层块间距走
 * [SheetSectionSpacingBetween]（12dp），组内卡间距由 CardSection 默认值负责（10dp）。
 *
 * 分组选择随表单落库：编辑初值从 [Todo.group] 回填，选中值进 [onConfirm] 组装的 [Todo]
 * （引用不存在的组由仓库归一化清回 null）；未选 = 「未分组」。计数口径与 [TodoGroupsSheet]
 * 一致（未分组 = `group == null` 的待办数、自定义分组 = 该组名的待办数）。
 * 选择分组浮层的左上「新建」可当场建组（命名卡 → 追加分组，**[onGroupsChange] 交给宿主落库**），
 * 建完留在列表里等用户点选，不自动选中。
 *
 * 待办名为空时点「确认」不创建：输入卡红描边报错（AddCoursePanel 同款交互），输入即清除。
 * 选了「全天/时段」但没选日期时点「确认」Toast 提示不关浮层（TodoValidator 的
 * DatesRequired 前置拦截）。表单字段随浮层卸载而复位（remember，旋转重建回默认值）。
 *
 * 日期卡的「重复」频率本期不落库（Todo 模型无重复字段），只存自定义日期本身。
 */
@Composable
internal fun NewTodoSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    tagRepository: TagRepository?,
    scheduleRepository: ScheduleRepository?,
    // 分组列表（顺序即显示序，置顶项须构成连续前缀）与待办列表（计数用）：
    // 由宿主从 TodoRepository 的 StateFlow 传入（同 TodoGroupsSheet）
    groups: List<TodoGroup> = emptyList(),
    todos: List<Todo> = emptyList(),
    // 分组整表写回入口（同 TodoGroupsSheet 的同名参数，宿主可直接传 commitGroups）：
    // 选择分组浮层的左上「新建」经它把「分组表 + 新组」落库；本浮层只追加不重命名，
    // renames 恒传空表。缺省空实现（预览等无需落库的场景）
    onGroupsChange: (groups: List<TodoGroup>, renames: Map<String, String>) -> Unit = { _, _ -> },
    onConfirm: (todo: Todo) -> Unit,
    existing: Todo? = null,
    onDelete: (() -> Unit)? = null,
) {
    // 表单初值：编辑模式从 existing 整体回填（快照语义，挂载时取一次）
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nameBlank by remember { mutableStateOf(false) }
    var deadlineDate by remember { mutableStateOf(existing?.deadlineDate) }
    var deadlineMinute by remember { mutableStateOf(existing?.deadlineMinute) }
    var urgency by remember { mutableStateOf(existing?.urgency ?: TodoUrgency.None) }
    var dateSelection by remember { mutableStateOf(existing.toDateSelection()) }
    var dateExpanded by remember { mutableStateOf(false) }
    var timeKind by remember { mutableStateOf(existing?.timeKind ?: TodoTimeKind.None) }
    var startMinute by remember { mutableIntStateOf(existing?.startMinute ?: INITIAL_START_MINUTE) }
    var endMinute by remember { mutableIntStateOf(existing?.endMinute ?: INITIAL_END_MINUTE) }
    var location by remember { mutableStateOf(existing?.location ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var selectedTagNames by remember { mutableStateOf(existing?.tags?.toSet() ?: emptySet()) }

    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val datesRequiredMessage = stringResource(R.string.todo_error_dates_required)

    // 标签数据：仓库为 null 仅首帧毫秒级（MainActivity 启动即装配），此时给空流兜底
    val userTagEntries by remember(tagRepository) {
        tagRepository?.tags ?: MutableStateFlow(emptyList())
    }.collectAsState()
    val primaryColor = MaterialTheme.colorScheme.primary
    val userTags = userTagEntries.map { it.toTagItem(primaryColor) }
    val activeSchedule by remember(scheduleRepository) {
        scheduleRepository?.activeSchedule ?: MutableStateFlow<Schedule?>(null)
    }.collectAsState()
    // 课程标签由课表数据派生（同名课去重），不入库（TagRepository 同约定）；
    // 课程色取 UI 层枚举的 barColor——两套 CourseColor 同名同值，经 name 直转（ScheduleAdapters 同款）
    val courseTags = remember(activeSchedule) {
        activeSchedule?.courses
            ?.distinctBy { it.name }
            ?.map { TagItem(name = it.name, color = CourseColor.valueOf(it.color.name).barColor) }
            ?: emptyList()
    }

    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关浮层
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    // 删除二次确认：期望态 + 挂载态两段式（同浮层）；确认/取消先清请求播淡出，播完才卸载。
    // 声明在 OverlaySheet 之前：确认框在场时经 covered 让本浮层缩小后退（分层动效）
    var deleteRequested by remember { mutableStateOf(false) }
    var deleteMounted by remember { mutableStateOf(false) }
    if (deleteRequested) deleteMounted = true

    // 表单内容的 haze 采样源：OverlaySheet 顶栏磨砂用，叠在其上的四枚滚轮弹窗
    // （截止日期/自定义日期/截止时刻/时段时刻）也拿它做卡片磨砂背景模糊——同一状态同源采样
    val sheetHazeState = rememberHazeState()

    // 截止时刻滚轮弹窗（BottomTimeSelectCard）：期望态 + 挂载态两段式（同删除确认框），
    // 声明在 OverlaySheet 之前——弹窗在场时经 covered 让本浮层缩小后退（分层动效）
    var deadlineTimeVisible by remember { mutableStateOf(false) }
    var deadlineTimeMounted by remember { mutableStateOf(false) }
    var deadlineTimeInitial by remember { mutableIntStateOf(0) }
    var deadlineTimeCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    // 截止日期滚轮弹窗（BottomDateSelectCard）：确认把日期交回 DeadlineCard 的链式
    // 流程再弹时刻轮；「清除」把截止回写为 null 对（模型约束成对出现/同时为空）并关闭。
    // 「清除」胶囊的在场与否取挂载时的快照（deadlineClearAvailable，见下）：它跟着截止
    // 状态派生的话，点按清空后胶囊会在出场滑出中途凭空消失
    var deadlineDateVisible by remember { mutableStateOf(false) }
    var deadlineDateMounted by remember { mutableStateOf(false) }
    var deadlineDateInitial by remember { mutableStateOf(IsoDate.today()) }
    var deadlineDateCallback by remember { mutableStateOf<((IsoDate) -> Unit)?>(null) }
    var deadlineClearAvailable by remember { mutableStateOf(false) }

    // 「清除」回调：回写 null 对（模型约束截止日期与时刻成对出现/同时为空）并停掉日期步的
    // 待确认回调——清除即终态，不会再接着弹时刻轮；请求关闭后由弹卡两段式播完出场
    val clearDeadline: () -> Unit = {
        deadlineDateVisible = false
        deadlineDateCallback = null
        deadlineDate = null
        deadlineMinute = null
    }

    // 待办「自定义日期」滚轮弹窗（DateSelectionCard 的自定义行上报）
    var customDateVisible by remember { mutableStateOf(false) }
    var customDateMounted by remember { mutableStateOf(false) }
    var customDateInitial by remember { mutableStateOf(IsoDate.today()) }
    var customDateCallback by remember { mutableStateOf<((IsoDate) -> Unit)?>(null) }

    // 时段起止时刻滚轮弹窗（TimeRangeSlider 的胶囊点按上报）：确认的分钟数经 onPicked
    // 原路交回滑块，由它按顺序约束夹紧后经 onRangeChange 提交
    var rangeTimeVisible by remember { mutableStateOf(false) }
    var rangeTimeMounted by remember { mutableStateOf(false) }
    var rangeTimeInitial by remember { mutableIntStateOf(0) }
    var rangeTimeCallback by remember { mutableStateOf<((Int) -> Unit)?>(null) }

    // 待办分组选择：selectedGroupName 为 null = 未分组，编辑模式从 existing.group 回填，
    // 确认时随 Todo 组装落库（引用完整性由仓库归一化维护）。选择分组浮层期望态 + 挂载态
    // 两段式（同滚轮弹窗），声明在 OverlaySheet 之前——浮层在场时经 covered 让本浮层缩小后退
    var selectedGroupName by remember { mutableStateOf(existing?.group) }
    var groupPickVisible by remember { mutableStateOf(false) }
    var groupPickMounted by remember { mutableStateOf(false) }

    OverlaySheet(
        title = stringResource(if (existing == null) R.string.todo_new_title else R.string.todo_edit_title),
        confirmLabel = stringResource(R.string.edit_confirm),
        // 确认在右、取消/删除在左（同新建课表浮层）
        confirmAtEnd = true,
        onConfirm = {
            if (name.isBlank()) {
                nameBlank = true
            } else {
                // 相对档位在落库前解析成具体日期（今天/明天），重复频率本期不存
                val dates = when (val selection = dateSelection) {
                    DateSelection.None -> emptyList()
                    DateSelection.Today -> listOf(IsoDate.today())
                    DateSelection.Tomorrow -> listOf(IsoDate.today() + 1)
                    is DateSelection.Custom -> listOf(selection.date)
                }
                if (timeKind != TodoTimeKind.None && dates.isEmpty()) {
                    AppToasts.show(context, datesRequiredMessage)
                } else {
                    // 编辑以 existing 为底 copy：id/完成状态/子步骤/创建时间原样保留，只覆盖表单字段
                    onConfirm(
                        (existing ?: Todo(id = "", name = "")).copy(
                            name = name.trim(),
                            dates = dates,
                            timeKind = timeKind,
                            startMinute = if (timeKind == TodoTimeKind.Period) startMinute else null,
                            endMinute = if (timeKind == TodoTimeKind.Period) endMinute else null,
                            deadlineDate = deadlineDate,
                            deadlineMinute = deadlineMinute,
                            location = location.trim(),
                            tags = selectedTagNames.toList(),
                            urgency = urgency,
                            note = note,
                            group = selectedGroupName,
                        ),
                    )
                }
            }
        },
        rightAction = if (existing != null && onDelete != null) {
            // 编辑模式左上改红色「删除」（同 AddCoursePanel）；关闭改走返回/下拉/点遮罩
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
        covered = deleteRequested || deadlineDateVisible || customDateVisible || rangeTimeVisible ||
            deadlineTimeVisible || groupPickVisible,
        onDismissed = onDismissed,
        imeBehavior = SheetImeBehavior.ContentScroll,
        // 共享采样源：叠在本浮层上的滚轮弹窗拿它做卡片磨砂背景模糊（见 sheetHazeState 声明处）
        hazeState = sheetHazeState,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 待办名是孤立的输入卡：无题 CardSection 收拢（单子项，卡内间距不生效）
            CardSection {
                SheetTextField(
                    label = stringResource(R.string.todo_name_label),
                    value = name,
                    onValueChange = {
                        name = it
                        nameBlank = false
                    },
                    placeholder = stringResource(R.string.todo_name_hint),
                    imeAction = ImeAction.Done,
                    // 键盘「完成」收起键盘，创建走右上「确认」
                    onImeAction = { focusManager.clearFocus() },
                    isError = nameBlank,
                )
            }

            CardSection(title = stringResource(R.string.todo_action_group)) {
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder,
                            label = stringResource(R.string.todo_groups_title),
                            // 右侧当前分组：未选 = 默认「未分组」（选中值随确认落库，见类 KDoc）
                            value = selectedGroupName ?: stringResource(R.string.todo_group_ungrouped),
                            onClick = {
                                // 同卡片行进弹层前收键盘（DeadlineCard 同款），再挂载选择分组浮层
                                focusManager.clearFocus()
                                keyboard?.hide()
                                groupPickMounted = true
                                groupPickVisible = true
                            },
                        ),
                    ),
                )
            }

            CardSection(title = stringResource(R.string.todo_section_reminder)) {
                DeadlineCard(
                    deadlineDate = deadlineDate,
                    deadlineMinute = deadlineMinute,
                    onChange = { date, minute ->
                        deadlineDate = date
                        deadlineMinute = minute
                    },
                    // 日期步：挂载日期轮（画在本浮层之后），初值由卡片给出
                    onDatePickRequest = { initialDate, onPicked ->
                        deadlineDateInitial = initialDate
                        deadlineDateCallback = onPicked
                        // 「清除」胶囊的在场快照：此刻截止已设才给清除（整个弹窗生命周期内
                        // 稳定，见声明处）
                        deadlineClearAvailable = deadlineDate != null && deadlineMinute != null
                        deadlineDateMounted = true
                        deadlineDateVisible = true
                    },
                    // 时刻步：挂载时刻轮（画在本浮层之后），初值由卡片给出
                    onTimePickRequest = { initialMinute, onPicked ->
                        deadlineTimeInitial = initialMinute
                        deadlineTimeCallback = onPicked
                        deadlineTimeMounted = true
                        deadlineTimeVisible = true
                    },
                )
                UrgentFlagCard(
                    selected = urgency,
                    onSelect = { urgency = it },
                )
            }

            CardSection(title = stringResource(R.string.todo_section_date_time)) {
                DateSelectionCard(
                    value = dateSelection,
                    expanded = dateExpanded,
                    onValueChange = { dateSelection = it },
                    onExpandedChange = { dateExpanded = it },
                    // 自定义日期步：挂载日期轮（画在本浮层之后），初值由卡片给出
                    onCustomDatePickRequest = { initialDate, onPicked ->
                        customDateInitial = initialDate
                        customDateCallback = onPicked
                        customDateMounted = true
                        customDateVisible = true
                    },
                )
                RowChoiceCard(
                    options = listOf(
                        stringResource(R.string.todo_time_none),
                        stringResource(R.string.todo_time_all_day),
                        stringResource(R.string.todo_time_period),
                    ),
                    selectedIndex = when (timeKind) {
                        TodoTimeKind.None -> 0
                        TodoTimeKind.AllDay -> 1
                        TodoTimeKind.Period -> 2
                    },
                    onSelect = { index ->
                        timeKind = when (index) {
                            1 -> TodoTimeKind.AllDay
                            2 -> TodoTimeKind.Period
                            else -> TodoTimeKind.None
                        }
                    },
                    // 选「时段」展开滑块（RowChoiceCard 展开插槽，SheetComponentsPreviews 同款接法）；
                    // 滑块无状态：分钟数由本浮层持有（0 ≤ 开始 < 结束 ≤ 1439 由滑块保证），
                    // 每次拖动/钟表确认都整体提交开始与结束两个值
                    expandContent = if (timeKind == TodoTimeKind.Period) {
                        {
                            Spacer(modifier = Modifier.size(6.dp))
                            TimeRangeSlider(
                                startMinutes = startMinute,
                                endMinutes = endMinute,
                                onRangeChange = { start, end ->
                                    startMinute = start
                                    endMinute = end
                                },
                                // 点按胶囊：挂载时刻轮（画在本浮层之后），初值由滑块给出；
                                // 确认的分钟数经 onPicked 原路交回滑块夹紧后提交
                                onTimePickRequest = { initialMinute, _, onPicked ->
                                    rangeTimeInitial = initialMinute
                                    rangeTimeCallback = onPicked
                                    rangeTimeMounted = true
                                    rangeTimeVisible = true
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
            }

            CardSection(title = stringResource(R.string.todo_section_tags)) {
                TagChoosingCard(
                    userTags = userTags,
                    courseTags = courseTags,
                    selectedNames = selectedTagNames,
                    onSelectionChange = { selectedTagNames = it },
                    // 增删直写标签库：成功才同步选中态（重名等失败静默忽略，同组件约定）
                    onAddTag = { tagName ->
                        scope.launch {
                            if (tagRepository?.addTag(tagName) is TagOpResult.Ok) {
                                selectedTagNames = selectedTagNames + tagName
                            }
                        }
                    },
                    onDeleteTag = { tagName ->
                        scope.launch {
                            if (tagRepository?.removeTag(tagName) is TagOpResult.Ok) {
                                selectedTagNames = selectedTagNames - tagName
                            }
                        }
                    },
                )
            }

            CardSection(title = stringResource(R.string.todo_section_more)) {
                SheetTextField(
                    label = stringResource(R.string.todo_location_label),
                    value = location,
                    onValueChange = { location = it },
                    placeholder = stringResource(R.string.todo_location_hint),
                    imeAction = ImeAction.Done,
                    onImeAction = { focusManager.clearFocus() },
                )
                SheetTextArea(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = stringResource(R.string.todo_note_hint),
                )
            }
        }
    }

    // 选择分组浮层画在表单浮层之后（组合顺序即绘制顺序），叠在表单之上；后注册 BackHandler，
    // 返回键先关选择浮层、再轮到表单出口。选中任意分组/未分组即回写入口值并请求关闭；
    // 点遮罩、下拉、关闭胶囊、返回同走 onDismiss（两段式，同滚轮弹窗）。
    // 不传表单的 sheetHazeState：本浮层（含其上的命名卡）自建采样源、只模糊自身内容——
    // 共享表单的采样源会把下拖时露出的表单内容模糊进本层顶栏（见 GroupPickSheet 的 KDoc）
    if (groupPickMounted) {
        GroupPickSheet(
            todos = todos,
            groups = groups,
            visible = groupPickVisible,
            onDismiss = { groupPickVisible = false },
            onDismissed = { groupPickMounted = false },
            onGroupsChange = onGroupsChange,
            onPick = { name ->
                selectedGroupName = name
                groupPickVisible = false
            },
        )
    }

    // 截止日期滚轮弹窗画在表单浮层之后（组合顺序即绘制顺序）。确认把日期交回
    // DeadlineCard 的链式流程再弹时刻轮；「清除」回写 null 对（模型约束成对出现/同时为空）
    // 后关闭，截止卡随即显示「无」；取消/遮罩/返回只请求关闭，播完退场才卸载
    // （两段式，同下方的时刻轮）
    if (deadlineDateMounted) {
        BottomDateSelectCard(
            visible = deadlineDateVisible,
            onDismiss = { deadlineDateVisible = false },
            onDismissed = {
                deadlineDateMounted = false
                deadlineDateCallback = null
            },
            hazeState = sheetHazeState,
            title = stringResource(R.string.deadline_card_label),
            initialDate = deadlineDateInitial,
            // 已设截止才出现「清除」（挂载快照，见声明处）：弹卡在滚轮下方私有渲染那枚
            // 整宽白胶囊，点按只把回调交上来（见 clearDeadline）
            onClear = if (deadlineClearAvailable) clearDeadline else null,
            onConfirm = { date ->
                deadlineDateVisible = false
                deadlineDateCallback?.invoke(date)
                deadlineDateCallback = null
            },
        )
    }

    // 待办「自定义日期」滚轮弹窗：确认经 DateSelectionCard 的回调回写
    if (customDateMounted) {
        BottomDateSelectCard(
            visible = customDateVisible,
            onDismiss = { customDateVisible = false },
            onDismissed = {
                customDateMounted = false
                customDateCallback = null
            },
            hazeState = sheetHazeState,
            initialDate = customDateInitial,
            onConfirm = { date ->
                customDateVisible = false
                customDateCallback?.invoke(date)
                customDateCallback = null
            },
        )
    }

    // 截止时刻滚轮弹窗画在表单浮层之后（组合顺序即绘制顺序）；后注册 BackHandler，
    // 返回键先关弹窗、再关浮层。确认把时/分折算成一天内的分钟数交回 DeadlineCard 的
    // 暂存流程一并回写；取消/遮罩/返回只请求关闭，播完退场才卸载（两段式，同删除确认框）
    if (deadlineTimeMounted) {
        BottomTimeSelectCard(
            visible = deadlineTimeVisible,
            onDismiss = { deadlineTimeVisible = false },
            onDismissed = {
                deadlineTimeMounted = false
                deadlineTimeCallback = null
            },
            title = stringResource(R.string.deadline_card_time_title),
            initialHour = deadlineTimeInitial / 60,
            initialMinute = deadlineTimeInitial % 60,
            hazeState = sheetHazeState,
            onConfirm = { hour, minute ->
                deadlineTimeVisible = false
                deadlineTimeCallback?.invoke(hour * 60 + minute)
                deadlineTimeCallback = null
            },
        )
    }

    // 时段起止时刻滚轮弹窗：确认分钟数经 onPicked 原路交回 TimeRangeSlider（夹紧后提交），
    // 取消/遮罩/返回只请求关闭，播完退场才卸载（两段式，同删除确认框）
    if (rangeTimeMounted) {
        BottomTimeSelectCard(
            visible = rangeTimeVisible,
            onDismiss = { rangeTimeVisible = false },
            onDismissed = {
                rangeTimeMounted = false
                rangeTimeCallback = null
            },
            initialHour = rangeTimeInitial / 60,
            initialMinute = rangeTimeInitial % 60,
            hazeState = sheetHazeState,
            onConfirm = { hour, minute ->
                rangeTimeVisible = false
                rangeTimeCallback?.invoke(hour * 60 + minute)
                rangeTimeCallback = null
            },
        )
    }

    // 删除确认框画在表单浮层之后（组合顺序即绘制顺序）；后注册 BackHandler，
    // 返回键先关确认框、再关浮层（编辑叠在详情上时最后才轮到详情）
    if (deleteMounted) {
        DeleteTodoConfirmDialog(
            todoName = existing?.name.orEmpty(),
            visible = deleteRequested,
            onDismissed = { deleteMounted = false },
            onCancel = { deleteRequested = false },
            onConfirm = {
                deleteRequested = false
                onDelete?.invoke()
            },
        )
    }
}

/** 标签库条目 → 选择卡条目：无自定义色时跟随主题 Primary（TagEntry 同约定） */
private fun TagEntry.toTagItem(fallbackColor: Color): TagItem =
    TagItem(name = name, color = colorArgb?.let { Color(it) } ?: fallbackColor)

/**
 * 编辑回填：待办日期 → 日期卡选择态——空 → 无；恰为今天/明天 → 相对档位；其余 → 自定义首日。
 * 表单是单日期 UI（多日期仅导入可达，本期无导入入口），确认时只写回这一个日期；
 * 重复频率不落库（Todo 无该字段），回填给卡内默认值即可。
 */
private fun Todo?.toDateSelection(): DateSelection = when {
    this == null || dates.isEmpty() -> DateSelection.None
    dates.size == 1 && dates[0] == IsoDate.today() -> DateSelection.Today
    dates.size == 1 && dates[0] == IsoDate.today() + 1 -> DateSelection.Tomorrow
    else -> DateSelection.Custom(dates.first(), RepeatFrequency.Weekly)
}

/**
 * 删除待办确认框：页内居中白卡（[ConfirmDialogCard]）+ 遮罩，叠加在编辑浮层**之后**组合——
 * 返回键时后注册的它先收到，优先于浮层与详情。两段式关闭见 [FadeOverlayDialog]：
 * 确认/取消先清请求播淡出，播完才卸载；名称取挂载快照，淡出期间不闪空。
 */
@Composable
private fun DeleteTodoConfirmDialog(
    todoName: String,
    visible: Boolean,
    onDismissed: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    FadeOverlayDialog(
        visible = visible,
        onDismiss = onCancel,
        onDismissed = onDismissed,
        cardHorizontalPadding = 32.dp,
    ) {
        ConfirmDialogCard(
            title = stringResource(R.string.todo_delete_title),
            message = stringResource(R.string.todo_delete_message, todoName),
        ) {
            DialogPillButton(
                label = stringResource(R.string.settings_cancel),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onCancel,
            )
            DialogPillButton(
                label = stringResource(R.string.edit_delete),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onConfirm,
            )
        }
    }
}

/**
 * 选择分组浮层：编辑/新建浮层「待办分组」入口进入，内容结构参考 [TodoGroupsSheet] 的分组区——
 * 「分组」小标题下未分组单行卡（[SettingsCardwithIcon]）+ 自定义分组 [GroupCard]（非编辑态，
 * 长按不进编辑）。**点击任意行即选中并关闭**（[onPick] 带回组名，null = 未分组）。
 * 顶栏左上「新建」蓝胶囊（[OverlaySheet] 的确认槽，同 [TodoGroupsSheet] 普通态的新建按钮：
 * 文案「新建」+ ic_add_circle + primary 底）、右上「关闭」白胶囊同 [TodoGroupsSheet] 普通态样式。
 *
 * 「新建」走 [BottomTextFieldCard] 命名卡（两段式挂载，同 [TodoGroupsSheet] 的新建流程）：
 * 挂载即聚焦弹键盘、确认 = 追加 `TodoGroup(name)`（pinned = false，落列表末尾——保持置顶项
 * 构成连续前缀的 [TodoGroup] 不变式）经 [onGroupsChange] 整表写回，落库失败的 Toast 由宿主
 * 统一弹；**建完留在列表里等用户点选，不自动选中**。空名/重名时确认胶囊置灰（点击与键盘
 * Done 同一闸门）。命名卡画在本浮层之后（组合顺序即绘制顺序），在场时本浮层经 covered
 * 缩小后退（同 [TodoGroupsSheet] 的层级动效）；它的 BackHandler 后注册，返回键先关命名卡。
 *
 * 两段式关闭（visible / onDismissed），系统返回与点遮罩、下拉、关闭胶囊同走 [onDismiss]；
 * 列表型浮层走 [SheetImeBehavior.IgnoreIme]（命名卡的键盘让位由弹卡框架自己接管），返回键
 * 由本组合的 BackHandler 接（后注册，先于表单浮层的出口）。**弹卡磨砂**：本组件自建 hazeState
 * 一并交给 [OverlaySheet] 与命名卡——浮层滚动列作采样源，顶栏磨砂与命名卡背景模糊同源；
 * 刻意不复用表单浮层的 sheetHazeState，那样顶栏会把下拖时露出的表单内容模糊进来。
 *
 * **计数口径**同 [TodoGroupsSheet]：未分组 = `group == null` 的待办数、
 * 自定义分组 = 该组名的待办数（新建即得 0）。
 *
 * @param onGroupsChange 分组整表写回入口（同 [TodoGroupsSheet] 的同名参数）：本浮层只追加
 *   分组，[Map] 形参恒传空表
 * @param onPick 选中回调：组名（null = 未分组），由调用方回写入口值并请求关闭
 */
@Composable
private fun GroupPickSheet(
    todos: List<Todo>,
    groups: List<TodoGroup>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onGroupsChange: (groups: List<TodoGroup>, renames: Map<String, String>) -> Unit,
    onPick: (String?) -> Unit,
) {
    // 新建分组弹卡：期望态 + 挂载态两段式（同 TodoGroupsSheet），普通态顶栏「新建」触发；
    // createDraft 是受控草稿（打开时清空），确认才追加进分组表并交 [onGroupsChange] 落库
    var createRequested by remember { mutableStateOf(false) }
    var createMounted by remember { mutableStateOf(false) }
    var createDraft by remember { mutableStateOf("") }
    if (createRequested) createMounted = true

    // haze 采样源：自建（见类 KDoc 为何不复用表单浮层的 sheetHazeState）后同时交给本浮层
    // 与命名卡——浮层滚动列作采样源，顶栏磨砂与弹卡背景模糊同源（同 TodoGroupsSheet 的接法），
    // 命名卡因此是磨砂玻璃质感而不是平底卡
    val pickHazeState = rememberHazeState()

    BackHandler(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.todo_group_pick_title),
        // 左上「新建」（同 TodoGroupsSheet 普通态）：点开命名卡，不当作确认语义——本浮层
        // 没有需要确认的动作，确认槽只借位置与配色（与分组浮层一致的蓝胶囊）
        confirmLabel = stringResource(R.string.todo_groups_create),
        confirmIcon = R.drawable.ic_add_circle,
        onConfirm = {
            createDraft = ""
            createMounted = true
            createRequested = true
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.todo_groups_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        // 命名卡在场时本浮层缩小后退（用可见请求态而非挂载态，退场时与上层收场同帧反向恢复，
        // 同 TodoGroupsSheet 的 covered 约定）
        covered = createRequested,
        onDismissed = onDismissed,
        // 列表型浮层：没有输入框，键盘不必让位（同 TodoGroupsSheet）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        // 采样源交给容器（见 pickHazeState 声明处）：顶栏磨砂与命名卡的背景模糊同源
        hazeState = pickHazeState,
    ) {
        // 分区小标题与卡间距同 TodoGroupsSheet 的分组区
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            CardSection(title = stringResource(R.string.todo_action_group)) {
                // 未分组单行卡：group == null 的待办数（引用完整性由仓库归一化维护）
                SettingsCardwithIcon(
                    items = listOf(
                        SettingsCardwithIconItem(
                            icon = R.drawable.ic_folder_hint,
                            label = stringResource(R.string.todo_group_ungrouped),
                            value = todos.count { it.group == null }.toString(),
                            onClick = { onPick(null) },
                        ),
                    ),
                )
                // 自定义分组：GroupCard 非编辑态（长按不进编辑），点击即选择；
                // 分组以名字为身份键，计数 = 该组名的待办数
                if (groups.isNotEmpty()) {
                    GroupCard(
                        items = groups.map { group ->
                            GroupCardItem(
                                id = group.name,
                                label = group.name,
                                value = todos.count { it.group == group.name }.toString(),
                                pinned = group.pinned,
                            )
                        },
                        onItemClick = { onPick(it) },
                    )
                }
            }
        }
    }

    // 新建分组命名卡画在本浮层之后（组合顺序即绘制顺序），叠在浮层之上：挂载即自动聚焦
    // 弹键盘、整卡被键盘顶起（BottomTextFieldCard 框架自带）；确认 = 追加分组经
    // [onGroupsChange] 整表写回落库，落库失败的 Toast 由宿主统一处理
    if (createMounted) {
        val createName = createDraft.trim()
        BottomTextFieldCard(
            visible = createRequested,
            onDismiss = { createRequested = false },
            onDismissed = { createMounted = false },
            title = stringResource(R.string.todo_group_create_title),
            placeholder = stringResource(R.string.todo_group_create_hint),
            value = createDraft,
            onValueChange = { createDraft = it },
            // 实时校验：空名或与现有分组重名时确认钮置灰（同 TodoGroupsSheet 的新建弹卡）
            confirmEnabled = createName.isNotEmpty() && createName !in groups.map { it.name },
            // 卡片磨砂：采样源同本浮层顶栏（见 pickHazeState 声明处）
            hazeState = pickHazeState,
            onConfirm = {
                createRequested = false
                onGroupsChange(groups + TodoGroup(createName), emptyMap())
            },
        )
    }
}
