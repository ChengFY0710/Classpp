package com.fangyi.classpp.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.OpResult
import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.appendSlot
import com.fangyi.classpp.ui.schedule.DateTarget
import com.fangyi.classpp.ui.schedule.TERM_DEFAULT_DAYS
import com.fangyi.classpp.ui.schedule.TERM_WEEKS_MIN
import com.fangyi.classpp.ui.schedule.TERM_WEEKS_MAX
import com.fangyi.classpp.ui.schedule.endForTotalWeeks
import com.fangyi.classpp.ui.schedule.snapTermEnd
import com.fangyi.classpp.ui.schedule.snapToMonday
import com.fangyi.classpp.ui.schedule.toPickerMillis
import com.fangyi.classpp.ui.schedule.toIsoDate
import com.fangyi.classpp.ui.theme.ClassppTheme
import kotlinx.coroutines.launch

private val SectionSpacing = 16.dp

/**
 * 设置页：全屏覆盖层（由 MainActivity 组合在底部导航之后），返回键/关闭按钮经 [onClose] 退出。
 *
 * - [repository] = null → 加载指示（仅首帧毫秒级）；
 * - 无激活课表 → 仅"新建课表"表单（创建入口随激活课表出现而消失，
 *   避免在无切换入口时创建出到不了的第二份课表）；
 * - 有激活课表 → 当前课表全套设置（学期/天数/节数时间/置灰开关）。
 *
 * 每项修改即时提交仓库：Ok → 清错误；Err → 顶部内联错误框（拒绝策略结构化提示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    repository: ScheduleRepository? = null,
) {
    BackHandler { onClose() }

    var error by remember { mutableStateOf<ScheduleError?>(null) }
    val scope = rememberCoroutineScope()

    // 统一提交入口：Ok 清错误，Err 存错误对象（展示时才译文案，见 toMessage）
    fun submit(result: OpResult) {
        error = (result as? OpResult.Err)?.error
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    // 零新依赖（项目无 material-icons）：文本返回按钮
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.cd_settings_close))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                repository == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> {
                    val schedule = repository.activeSchedule.collectAsState().value
                    error?.let { ErrorBox(it.toMessage(schedule?.daysPerWeek ?: 5)) }
                    if (schedule == null) {
                        CreateScheduleContent(
                            onConfirm = { name, start, end ->
                                error = null
                                scope.launch {
                                    when (val result = repository.createSchedule(name, start, end)) {
                                        is ReadResult.Ok -> onClose()
                                        is ReadResult.Err -> error = result.error
                                    }
                                }
                            },
                        )
                    } else {
                        val current = schedule
                        SettingsContent(
                            schedule = current,
                            onTerm = { start, end ->
                                error = null
                                scope.launch { submit(repository.setTerm(current.id, start, end)) }
                            },
                            onDays = { days ->
                                error = null
                                scope.launch {
                                    // 切 7 天而结束日非周日：先仅延长至同周周日（只加周不缩周，
                                    // 结构上不可能波及已有课程），再切天数，免去用户两步操作
                                    val endSunday = current.termEnd.isoDayOfWeek() != 7 &&
                                        days == 7
                                    if (endSunday) {
                                        val extended = current.termEnd +
                                            (7 - current.termEnd.isoDayOfWeek())
                                        submit(repository.setTerm(current.id, current.termStart, extended))
                                        if (error == null) {
                                            submit(repository.setDaysPerWeek(current.id, days))
                                        }
                                    } else {
                                        submit(repository.setDaysPerWeek(current.id, days))
                                    }
                                }
                            },
                            onSlots = { slots ->
                                error = null
                                scope.launch { submit(repository.setSlots(current.id, slots)) }
                            },
                            onShowInactive = { show ->
                                error = null
                                scope.launch {
                                    submit(repository.setShowInactiveCourses(current.id, show))
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 内联错误框：拒绝策略（如 CoursesOutOfRange）的结构化提示，新操作成功即由调用方清空 */
@Composable
private fun ErrorBox(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * 新建课表表单（仅无激活课表时出现）：名称 + 学期起止 + 创建。
 * 起止日期经 DatePicker 选择并**自动吸附**硬性校验规则（开始→周一、结束→周五），
 * 默认值为"今天所在周的周一 ~ 16 周后的周五"。
 */
@Composable
private fun CreateScheduleContent(
    onConfirm: (name: String, start: IsoDate, end: IsoDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultStart = remember { snapToMonday(IsoDate.today()) }
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultStart + TERM_DEFAULT_DAYS) }
    var picking by remember { mutableStateOf<DateTarget?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SectionSpacing, vertical = SectionSpacing),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.schedule_name_label)) },
            placeholder = { Text(stringResource(R.string.schedule_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TermDatesCard(
            start = start,
            end = end,
            onPickStart = { picking = DateTarget.Start },
            onPickEnd = { picking = DateTarget.End },
            onSetWeeks = { weeks -> end = endForTotalWeeks(start, end, weeks) },
        )
        Button(
            onClick = { onConfirm(name, start, end) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.create_schedule))
        }
    }

    // 选日对话框：开始吸附周一并整体平移结束日（保持整周对齐与学期长度）；结束按吸附规则
    picking?.let { target ->
        val initial = if (target == DateTarget.Start) start else end
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = millis.toIsoDate()
                        if (target == DateTarget.Start) {
                            val snapped = snapToMonday(picked)
                            end = end + (snapped - start).toInt()
                            start = snapped
                        } else {
                            end = snapTermEnd(picked, daysPerWeek = 5)
                        }
                    }
                    picking = null
                }) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

/** 当前课表全套设置：学期 / 每周天数 / 节数与时间 / 显示。数据变更经回调即时提交仓库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    schedule: Schedule,
    onTerm: (start: IsoDate, end: IsoDate) -> Unit,
    onDays: (days: Int) -> Unit,
    onSlots: (slots: List<TimeSlotDef>) -> Unit,
    onShowInactive: (show: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<DateTarget?>(null) }
    // 正在编辑的节次时间；null = 无弹窗（每次确认/取消都回到 null，保证重开时状态新鲜）
    var editingSlot by remember { mutableStateOf<SlotEdit?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SectionSpacing, vertical = SectionSpacing),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        // TermDatesCard 自带 SectionCard 容器，勿再包一层（否则标题重复）
        TermDatesCard(
            start = schedule.termStart,
            end = schedule.termEnd,
            onPickStart = { picking = DateTarget.Start },
            onPickEnd = { picking = DateTarget.End },
            onSetWeeks = { weeks ->
                onTerm(schedule.termStart, endForTotalWeeks(schedule.termStart, schedule.termEnd, weeks))
            },
        )

        SectionCard(title = stringResource(R.string.section_days)) {
            val days = schedule.daysPerWeek
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = days == 5,
                    onClick = { if (days != 5) onDays(5) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(stringResource(R.string.days_5))
                }
                SegmentedButton(
                    selected = days == 7,
                    onClick = { if (days != 7) onDays(7) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) {
                    Text(stringResource(R.string.days_7))
                }
            }
        }

        SectionCard(title = stringResource(R.string.section_slots)) {
            // ＋：保留现有 slots 追加一节（上一节结束 +30 分钟课间、时长 100 分钟）；
            // －：保留前缀裁剪（合法表的前缀必合法，且保留用户已改时间）
            val appended = appendSlot(schedule.slots)
            val canAdd = appended != null && schedule.slotCount < 12   // 上限沿用 R5
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.slot_count_format, schedule.slotCount),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(
                    onClick = {
                        if (schedule.slotCount > 1) onSlots(schedule.slots.dropLast(1))
                    },
                    enabled = schedule.slotCount > 1,
                ) {
                    Text("－")
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = { appended?.let { s -> if (schedule.slotCount < 12) onSlots(s) } },
                    enabled = canAdd,
                ) {
                    Text("＋")
                }
            }
            // 仅在无法再加节（新节会越过 23:59）时提示禁用原因
            if (appended == null) {
                Text(
                    text = stringResource(R.string.slot_overflow_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            HorizontalDivider()
            schedule.slots.forEachIndexed { index, slot ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.slot_format, index + 1),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = slot.startTime,
                        modifier = Modifier
                            .clickable { editingSlot = SlotEdit(index, isStart = true) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = " – ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = slot.endTime,
                        modifier = Modifier
                            .clickable { editingSlot = SlotEdit(index, isStart = false) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        SectionCard(title = stringResource(R.string.section_display)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.show_inactive),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.show_inactive_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = schedule.showInactiveCourses,
                    onCheckedChange = onShowInactive,
                )
            }
        }
    }

    // 学期日期选择：开始吸附周一并平移结束日；结束按当前天数吸附（5→周五、7→周日）
    picking?.let { target ->
        val initial = if (target == DateTarget.Start) schedule.termStart else schedule.termEnd
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial.toPickerMillis())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = millis.toIsoDate()
                        if (target == DateTarget.Start) {
                            val snapped = snapToMonday(picked)
                            val newEnd = schedule.termEnd + (snapped - schedule.termStart).toInt()
                            onTerm(snapped, newEnd)
                        } else {
                            onTerm(schedule.termStart, snapTermEnd(picked, schedule.daysPerWeek))
                        }
                    }
                    picking = null
                }) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // 单节起止时间选择：TimePicker 结构性杜绝格式错误，确认后整表提交
    editingSlot?.let { edit ->
        val slot = schedule.slots[edit.index]
        val initialMinutes = TimeText.parseMinutes(if (edit.isStart) slot.startTime else slot.endTime) ?: 8 * 60
        val timeState = rememberTimePickerState(
            initialHour = initialMinutes / 60,
            initialMinute = initialMinutes % 60,
        )
        AlertDialog(
            onDismissRequest = { editingSlot = null },
            title = {
                Text(stringResource(R.string.slot_format, edit.index + 1))
            },
            text = {
                TimePicker(state = timeState)
            },
            confirmButton = {
                TextButton(onClick = {
                    val formatted = TimeText.format(timeState.hour * 60 + timeState.minute)
                    val updated = schedule.slots.toMutableList().also { list ->
                        val old = list[edit.index]
                        list[edit.index] = if (edit.isStart) {
                            old.copy(startTime = formatted)
                        } else {
                            old.copy(endTime = formatted)
                        }
                    }
                    onSlots(updated)
                    editingSlot = null
                }) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSlot = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** 学期起止 + 总周数三行（起止弹 DatePicker、周数弹输入对话框），单独成组件供创建表单复用 */
@Composable
private fun TermDatesCard(
    start: IsoDate,
    end: IsoDate,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onSetWeeks: (weeks: Int) -> Unit,
) {
    // 与 Schedule.totalWeeks 同式；起止经吸附/校验保证整周边界，此值必为整数周
    val totalWeeks = ((end - start).toInt() / 7) + 1
    var pickingWeeks by remember { mutableStateOf(false) }

    SectionCard(title = stringResource(R.string.section_term)) {
        DateRow(
            label = stringResource(R.string.term_start),
            value = start.toString(),
            onClick = onPickStart,
        )
        Spacer(Modifier.height(8.dp))
        DateRow(
            label = stringResource(R.string.term_end),
            value = end.toString(),
            onClick = onPickEnd,
        )
        Spacer(Modifier.height(8.dp))
        DateRow(
            label = stringResource(R.string.term_weeks),
            value = stringResource(R.string.term_weeks_value, totalWeeks),
            onClick = { pickingWeeks = true },
        )
    }

    // 周数对话框：仅打开期间进入组合（关闭即出组合，重开以当前周数重置输入）；
    // 非法输入（空/0/>30）→ 字段 isError + 确定禁用；取消/外部点击丢弃
    if (pickingWeeks) {
        var input by remember { mutableStateOf(totalWeeks.toString()) }
        val weeks = input.toIntOrNull()?.takeIf { it in TERM_WEEKS_MIN..TERM_WEEKS_MAX }
        AlertDialog(
            onDismissRequest = { pickingWeeks = false },
            title = {
                Text(stringResource(R.string.term_weeks))
            },
            text = {
                OutlinedTextField(
                    value = input,
                    // 只留 ASCII 数字并截 2 位（1..30 至多两位），结构性杜绝 "-5"/"abc"/全角数字
                    onValueChange = { input = it.filter { c -> c in '0'..'9' }.take(2) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = weeks == null,
                    supportingText = { Text(stringResource(R.string.term_weeks_range)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = weeks != null,
                    onClick = {
                        weeks?.let(onSetWeeks)
                        pickingWeeks = false
                    },
                ) {
                    Text(stringResource(R.string.settings_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingWeeks = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/** 设置行：左标签、右日期值，整行可点 */
@Composable
private fun DateRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 分区卡片：标题 + 圆角卡容器（内容自带排版） */
@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                content = content,
            )
        }
    }
}

/** 正在编辑的节次时间：第 [index] 节的起（true）或止（false） */
private data class SlotEdit(val index: Int, val isStart: Boolean)

/**
 * [ScheduleError] → 本地化文案。覆盖设置页可达全集：
 * 课程类错误经仓库 rejection() 必归并为 CoursesOutOfRange，故其余分支只需兜底。
 */
@Composable
private fun ScheduleError.toMessage(daysPerWeek: Int): String = when (this) {
    is ScheduleError.TermNotMonday -> stringResource(R.string.error_term_not_monday)
    is ScheduleError.TermEndInvalid ->
        if (daysPerWeek == 7) {
            stringResource(R.string.error_term_end_days7)
        } else {
            stringResource(R.string.error_term_end)
        }

    is ScheduleError.TermRangeInvalid -> stringResource(R.string.error_term_range)
    is ScheduleError.DaysPerWeekInvalid -> stringResource(R.string.error_days_per_week)
    is ScheduleError.SlotCountInvalid -> stringResource(R.string.error_slot_count)
    is ScheduleError.SlotTimeFormatInvalid -> stringResource(R.string.error_slot_time_format)
    is ScheduleError.SlotOrderInvalid -> stringResource(R.string.error_slot_order)
    ScheduleError.InvalidScheduleName -> stringResource(R.string.error_schedule_name)
    is ScheduleError.CoursesOutOfRange -> stringResource(
        R.string.error_courses_out_of_range,
        affected.size,
        affected.take(3).joinToString { it.name },
    )

    is ScheduleError.NotFound -> stringResource(R.string.error_not_found)
    is ScheduleError.PersistFailed -> stringResource(R.string.error_persist_failed)
    else -> stringResource(R.string.error_generic, message)
}

@Preview(showBackground = true, name = "新建课表表单")
@Composable
private fun CreateScheduleContentPreview() {
    ClassppTheme {
        CreateScheduleContent(onConfirm = { _, _, _ -> })
    }
}

@Preview(showBackground = true, name = "课表设置列表")
@Composable
private fun SettingsContentPreview() {
    ClassppTheme {
        SettingsContent(
            schedule = Schedule(
                id = "preview",
                name = "2026春",
                termStart = IsoDate.parse("2026-03-02"),
                termEnd = IsoDate.parse("2026-06-19"),
            ),
            onTerm = { _, _ -> },
            onDays = {},
            onSlots = {},
            onShowInactive = {},
        )
    }
}
