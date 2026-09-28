package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.FieldReason
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleValidator
import com.fangyi.classpp.data.model.CourseColor as DataCourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.data.model.weeksFromSelection
import com.fangyi.classpp.ui.theme.OnPrimaryContainer
import com.fangyi.classpp.ui.theme.Scrim

/** 周数方格：每行 6 个，宽度均分（末行补空位，保证每格同宽同高） */
private const val WeeksPerRow = 6
private val WeekCellGap = 6.dp
private val WeekCellShape = RoundedCornerShape(11.dp)
private val ColorSwatchSize = 36.dp
private val PanelShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

/**
 * 添加/编辑课程面板：[day] / [slot] 是点中的那一格（编辑态传被编辑课的星期与起始节）；
 * [schedule] 提供总周数与校验上下文；[draftCourses] 是编辑态草稿——冲突校验针对
 * "草稿 + 面板内容"，所以刚加、还没保存的课也能立刻报出冲突。
 *
 * [existing] 非空 = 编辑已有课程：字段预填、星期/起始节次锁定（仅内容可改）、
 * 标题切换、显示删除按钮；[onDelete] 点删除即回调（直接删草稿，无二次确认——
 * 取消编辑即可整体撤销）。两者都只作用于草稿，保存时才落库。
 *
 * **刻意不用 AlertDialog**：对话框是独立窗口，本机（Android 15+/16 的 edge-to-edge + 该 ROM）
 * 输入法接不进去（点了输入框不弹键盘）。改成和设置页同一条路——页内覆盖层，
 * 输入法行为与设置页一致，并且顺手解决了两件事：
 * - `imePadding()`：Android 15+ 已不再响应 `adjustSoftInputMode=adjustResize`，
 *   键盘会直接盖住内容，必须按 IME inset 自己让位；
 * - 打开即自动聚焦课程名并唤起键盘，用户可以直接打字，不必先点输入框。
 */
@Composable
fun AddCoursePanel(
    day: Int,
    slot: TimeSlot,
    schedule: Schedule,
    draftCourses: List<CourseEntry>,
    onDismiss: () -> Unit,
    onConfirm: (CourseEntry) -> Unit,
    existing: CourseEntry? = null,
    onDelete: (() -> Unit)? = null,
) {
    // 编辑态从 existing 预填；面板随 if 离开组合即销毁，重开时初值重新生效
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var teacher by rememberSaveable { mutableStateOf(existing?.teacher ?: "") }
    var location by rememberSaveable { mutableStateOf(existing?.location ?: "") }
    var selectedWeeks by rememberSaveable {
        mutableStateOf(
            existing?.let { e -> (1..schedule.totalWeeks).filter { e.weeks.contains(it) } }
                ?: (1..schedule.totalWeeks).toList(),
        )
    }
    // 色块用渲染层枚举，落库时按 name 桥接（同 ScheduleAdapters）
    var color by rememberSaveable {
        mutableStateOf(existing?.let { CourseColor.valueOf(it.color.name) } ?: CourseColor.Blue)
    }
    // 结束节次：key=slot.id，重开面板（或旋转恢复后目标格变化）即复位；
    // 编辑态初值取被编辑课的末节
    var endSlotId by rememberSaveable(slot.id) { mutableStateOf(existing?.endSlot ?: slot.id) }
    var error by remember { mutableStateOf<String?>(null) }

    val weekdays = stringArrayResource(R.array.weekdays)
    val weekdayName = weekdays[(day - 1).coerceIn(weekdays.indices)]
    val slotLabel = if (endSlotId == slot.id) {
        stringResource(R.string.slot_format, slot.id)
    } else {
        stringResource(R.string.slot_range_format, slot.id, endSlotId)
    }
    // 恢复的 endSlotId 理论上不越界，仍钳一下防进程重建后的极端情况
    val endDef = schedule.slots[(endSlotId - 1).coerceIn(schedule.slots.indices)]
    val cellInfo = stringResource(
        R.string.edit_cell_info,
        weekdayName,
        slotLabel,
        "${slot.startTime}-${endDef.endTime}",
    )
    val weeks = weeksFromSelection(selectedWeeks)

    // 文案先取出来：校验发生在 lambda 里，那里不能再调 stringResource
    val errorNameBlank = stringResource(R.string.error_course_name_blank)
    val errorWeeksEmpty = stringResource(R.string.error_weeks_empty)
    val errorWeeksBeyondTerm = stringResource(R.string.error_weeks_beyond_term)
    val errorConflictFormat = stringResource(R.string.error_grid_conflict)
    val errorGenericFormat = stringResource(R.string.error_generic)

    val nameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    // 打开面板就把光标放进课程名并唤起输入法
    LaunchedEffect(Unit) {
        nameFocus.requestFocus()
        keyboard?.show()
    }
    // 返回键优先收键盘（键盘收起后不返回给应用），键盘已收起时才关面板
    BackHandler {
        if (imeInsets.getBottom(density) > 0) keyboard?.hide() else onDismiss()
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }

    fun submit() {
        if (selectedWeeks.isEmpty()) {
            error = errorWeeksEmpty
            return
        }
        // 结束节次已在芯片层限制，这里再钳一次（跨度 ≤ MAX_SPAN 且不超总节数）
        val end = endSlotId.coerceIn(
            slot.id,
            minOf(schedule.slotCount, slot.id + ScheduleValidator.MAX_SPAN - 1),
        )
        val entry = CourseEntry(
            // 编辑态沿用原 id（写回时按 id 覆盖），添加态生成新 id
            id = existing?.id ?: newUuid(),
            name = name.trim(),
            teacher = teacher.trim(),
            location = location.trim(),
            dayOfWeek = day,
            startSlot = slot.id,
            span = end - slot.id + 1,
            weeks = weeks,
            // 色块用的是渲染层的 CourseColor（同包、自带配色），落库时按 name 桥接（同 ScheduleAdapters）
            color = DataCourseColor.valueOf(color.name),
        )
        // 校验集合排除被编辑的课自己：草稿里它还是旧内容，不排除会跟新内容自相冲突
        val others = draftCourses.filterNot { it.id == existing?.id }
        val conflict = ScheduleValidator
            .validateCourses(schedule.copy(courses = others + entry))
            .firstOrNull()
        error = when (conflict) {
            null -> null
            is ScheduleError.CourseFieldInvalid ->
                if (conflict.reason == FieldReason.BlankName) {
                    errorNameBlank
                } else {
                    errorGenericFormat.format(conflict.message)
                }
            is ScheduleError.WeeksBeyondTerm -> errorWeeksBeyondTerm
            is ScheduleError.GridConflict -> {
                // 新课是列表里最后一个，冲突对里另一个就是已存在的课
                val other = if (conflict.a.id == entry.id) conflict.b else conflict.a
                errorConflictFormat.format(other.name)
            }
            else -> errorGenericFormat.format(conflict.message)
        }
        if (error == null) onConfirm(entry)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim.copy(alpha = 0.32f))
            // 点空白处收起（无涟漪）
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 键盘弹起时整块面板抬到键盘之上
                .imePadding(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吃掉落在面板上的点击，避免穿透到遮罩把面板关掉（输入框/按钮的消费优先）
                    .clickable(
                        interactionSource = panelInteraction,
                        indication = null,
                        onClick = {},
                    ),
                shape = PanelShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (existing != null) R.string.edit_edit_course else R.string.edit_add_course,
                        ),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = cellInfo,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 内容超出可用高度时就地滚动（fill = false：内容少时面板不撑高）
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CourseField(
                            value = name,
                            onValueChange = { name = it; error = null },
                            label = stringResource(R.string.edit_course_name),
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                            modifier = Modifier.focusRequester(nameFocus),
                        )
                        CourseField(
                            value = teacher,
                            onValueChange = { teacher = it; error = null },
                            label = stringResource(R.string.edit_course_teacher),
                            imeAction = ImeAction.Next,
                            onImeAction = { focusManager.moveFocus(FocusDirection.Down) },
                        )
                        CourseField(
                            value = location,
                            onValueChange = { location = it; error = null },
                            label = stringResource(R.string.edit_course_location),
                            imeAction = ImeAction.Done,
                            onImeAction = { focusManager.clearFocus() },
                        )

                        SectionLabel(stringResource(R.string.edit_end_slot))
                        EndSlotChips(
                            startId = slot.id,
                            total = schedule.slotCount,
                            selected = endSlotId,
                            onSelect = { id ->
                                endSlotId = id
                                error = null
                            },
                        )

                        SectionLabel(stringResource(R.string.edit_course_weeks))
                        WeekSelectionGrid(
                            totalWeeks = schedule.totalWeeks,
                            selected = selectedWeeks,
                            onToggle = { week ->
                                selectedWeeks = if (week in selectedWeeks) {
                                    selectedWeeks - week
                                } else {
                                    (selectedWeeks + week).sorted()
                                }
                                error = null
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            WeekShortcut(
                                text = stringResource(R.string.edit_weeks_all),
                                onClick = {
                                    selectedWeeks = (1..schedule.totalWeeks).toList()
                                    error = null
                                },
                            )
                            WeekShortcut(
                                text = stringResource(R.string.edit_weeks_odd),
                                onClick = {
                                    selectedWeeks = (1..schedule.totalWeeks).filter { it % 2 == 1 }
                                    error = null
                                },
                            )
                            WeekShortcut(
                                text = stringResource(R.string.edit_weeks_even),
                                onClick = {
                                    selectedWeeks = (1..schedule.totalWeeks).filter { it % 2 == 0 }
                                    error = null
                                },
                            )
                        }
                        Text(
                            text = stringResource(R.string.edit_weeks_summary, weeksSummary(weeks)),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SectionLabel(stringResource(R.string.edit_course_color))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CourseColor.entries.forEach { entry ->
                                Box(
                                    modifier = Modifier
                                        .size(ColorSwatchSize)
                                        .clip(CircleShape)
                                        .background(entry.barColor)
                                        .then(
                                            if (entry == color) {
                                                Modifier.border(
                                                    2.dp,
                                                    MaterialTheme.colorScheme.primary,
                                                    CircleShape,
                                                )
                                            } else {
                                                Modifier
                                            },
                                        )
                                        .clickable { color = entry },
                                )
                            }
                        }

                        error?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 编辑态左侧删除（红色文字、直接生效：只删草稿，取消编辑可整体还原）
                        if (existing != null && onDelete != null) {
                            TextButton(onClick = onDelete) {
                                Text(
                                    text = stringResource(R.string.edit_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.settings_cancel))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { submit() }) {
                            Text(stringResource(R.string.settings_confirm))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一行课程输入框。除键盘动作外还做一件必要的事：**点了就重新 show 一次键盘**。
 * Compose 的输入框只在"获得焦点"时拉起键盘，已聚焦时再点它不会有任何反应，
 * 于是键盘一旦被收起（返回键收键盘、系统收起、误触遮罩）就再也唤不回来——
 * 上机实测踩到过。这里监听点按（交互源）主动补一次 show；键盘已在时该调用是空操作。
 */
@Composable
private fun CourseField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) keyboard?.show()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        interactionSource = interactionSource,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { onImeAction() },
            onDone = { onImeAction() },
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 结束节次芯片：分行排布，与周数方格同款视觉。
 * 只有 [startId]..min(total, startId + MAX_SPAN - 1) 可选（起始之前 / 跨度过长的置灰不可点）。
 */
@Composable
private fun EndSlotChips(
    startId: Int,
    total: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val maxEnd = minOf(total, startId + ScheduleValidator.MAX_SPAN - 1)
    Column(verticalArrangement = Arrangement.spacedBy(WeekCellGap)) {
        (1..total).chunked(WeeksPerRow).forEach { rowSlots ->
            Row(horizontalArrangement = Arrangement.spacedBy(WeekCellGap)) {
                rowSlots.forEach { id ->
                    val enabled = id in startId..maxEnd
                    val isSelected = id == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(WeekCellShape)
                            .background(
                                when {
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    enabled -> OnPrimaryContainer
                                    else -> OnPrimaryContainer.copy(alpha = 0.4f)
                                },
                            )
                            .then(
                                if (enabled) {
                                    Modifier.clickable { onSelect(id) }
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = id.toString(),
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                        )
                    }
                }
                repeat(WeeksPerRow - rowSlots.size) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp),
                    )
                }
            }
        }
    }
}

/** 周数方格：点一下选中 / 取消 */
@Composable
private fun WeekSelectionGrid(
    totalWeeks: Int,
    selected: List<Int>,
    onToggle: (Int) -> Unit,
) {
    val selectedSet = selected.toSet()
    Column(verticalArrangement = Arrangement.spacedBy(WeekCellGap)) {
        (1..totalWeeks).chunked(WeeksPerRow).forEach { rowWeeks ->
            Row(horizontalArrangement = Arrangement.spacedBy(WeekCellGap)) {
                rowWeeks.forEach { week ->
                    val isSelected = week in selectedSet
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(WeekCellShape)
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    OnPrimaryContainer
                                },
                            )
                            .clickable { onToggle(week) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = week.toString(),
                            fontSize = 18.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                repeat(WeeksPerRow - rowWeeks.size) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekShortcut(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(text, fontSize = 14.sp)
    }
}

/** 已选周数的可读文案：`第 1-12 周`、`第 1-15 周（单周）`；空选择给"尚未选择" */
@Composable
private fun weeksSummary(pattern: WeekPattern): String {
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
