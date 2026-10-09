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
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DateSelection
import com.fangyi.classpp.ui.components.DateSelectionCard
import com.fangyi.classpp.ui.components.DeadlineCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.RepeatFrequency
import com.fangyi.classpp.ui.components.RowChoiceCard
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
 * 同课表删除确认），确认才回调 [onDelete]。编辑初值整体回填。
 *
 * 表单分组（CardSection）：待办名 → 提醒与优先级（截止日期 + 紧急旗帜）→ 日期与时间
 * （DateSelectionCard + 无/全天/时段，选「时段」展开 TimeRangeSlider）→ 标签 →
 * 更多（待办地点 + 备注）。
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
                        ),
                    )
                }
            }
        },
        rightAction = if (existing != null && onDelete != null) {
            // 编辑模式左上改红色「删除」（同 AddCoursePanel）；关闭改走返回/下拉/点遮罩
            SheetTopAction(
                label = stringResource(R.string.edit_delete),
                icon = R.drawable.ic_delete_dismiss,
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
        imeBehavior = SheetImeBehavior.ContentScroll,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
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

            CardSection(title = stringResource(R.string.todo_section_reminder)) {
                DeadlineCard(
                    deadlineDate = deadlineDate,
                    deadlineMinute = deadlineMinute,
                    onChange = { date, minute ->
                        deadlineDate = date
                        deadlineMinute = minute
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

    // 删除二次确认：期望态 + 挂载态两段式（同浮层）；确认/取消先清请求播淡出，播完才卸载。
    // 画在表单浮层之后（组合顺序即绘制顺序）；后注册 BackHandler，返回键先关确认框、
    // 再关浮层（编辑叠在详情上时最后才轮到详情）
    var deleteRequested by remember { mutableStateOf(false) }
    var deleteMounted by remember { mutableStateOf(false) }
    if (deleteRequested) deleteMounted = true
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
