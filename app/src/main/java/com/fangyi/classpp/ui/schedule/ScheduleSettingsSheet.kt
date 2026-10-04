package com.fangyi.classpp.ui.schedule

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.OpResult
import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.appendSlot
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.RowChoiceCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.settings.MultiLineRowSpacing
import com.fangyi.classpp.ui.settings.SettingRow
import com.fangyi.classpp.ui.settings.SettingsCard
import com.fangyi.classpp.ui.settings.SettingsSection
import com.fangyi.classpp.ui.settings.TermDatesCard
import com.fangyi.classpp.ui.settings.TimeChip
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.classppColors
import kotlinx.coroutines.launch

private val SectionSpacing = 18.dp

/**
 * 时间行 chip 容器高度 = 标签行高（bodyLarge lineHeight 24dp）：
 * 行高与两行间距按标签节奏计算，32dp 的 chip 蓝底保持原高、不计入行高。
 */
private val ChipRowHeight = 24.dp

private val oneLineControlHeight = 37.dp

/**
 * 「课表设置」浮层（编辑栏按钮打开）——原课表设置页的课表级设置整体迁入：
 * 课表名 / 学期起止 / 每周天数 / 节数与时间 / 显示非本周开关。
 *
 * 容器为 [OverlaySheet]：进出场动画、顶栏拖拽关闭与顶栏渐变模糊都由它提供。
 * 设置即时生效（无确认/取消语义），故无左确认胶囊，右上只留「关闭」；
 * 修改经注入的 [repository] 即时提交，Err 走系统 Toast（拒绝策略结构化提示），不占版面。
 *
 * 滚动、键盘让位（课表名输入框在场 → ContentScroll）由 OverlaySheet 内建，
 * 这里只负责内容与日期/时间对话框。
 */
@Composable
internal fun ScheduleSettingsSheet(
    schedule: Schedule,
    repository: ScheduleRepository,
    visible: Boolean,
    onDismissed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 错误一律走系统 Toast，不占版面；同一条出口会刷新上一条，连点不排队（见 AppToasts）
    fun showError(error: ScheduleError) {
        AppToasts.show(context, error.toMessage(context))
    }

    // 统一提交入口：Err 即时 toast，Ok 无事
    fun submit(result: OpResult) {
        (result as? OpResult.Err)?.let { showError(it.error) }
    }

    // 返回键关浮层（同切换课表浮层：组合顺序上晚于编辑态的取消 BackHandler，优先接管）
    BackHandler(enabled = true) { onDismiss() }

    OverlaySheet(
        title = stringResource(R.string.settings_title),
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.detail_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        imeBehavior = SheetImeBehavior.ContentScroll,
        modifier = modifier,
    ) {
        SettingsContent(
            schedule = schedule,
            onRename = { name ->
                scope.launch { submit(repository.renameSchedule(schedule.id, name)) }
            },
            onTerm = { start, end ->
                scope.launch { submit(repository.setTerm(schedule.id, start, end)) }
            },
            onDays = { days ->
                // 只改天数：周六/周日的课保留在数据里，5 天视图只是不画它们，
                // 切回 7 天原样出现——故切换不会失败，也无需联动改学期结束日
                scope.launch { submit(repository.setDaysPerWeek(schedule.id, days)) }
            },
            onSlots = { slots ->
                scope.launch { submit(repository.setSlots(schedule.id, slots)) }
            },
            onShowInactive = { show ->
                scope.launch { submit(repository.setShowInactiveCourses(schedule.id, show)) }
            },
        )
    }
}

/** 当前课表全套设置：课表名 / 学期 / 每周天数 / 节数与时间 / 显示。数据变更经回调即时提交仓库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    schedule: Schedule,
    onRename: (name: String) -> Unit,
    onTerm: (start: IsoDate, end: IsoDate) -> Unit,
    onDays: (days: Int) -> Unit,
    onSlots: (slots: List<TimeSlotDef>) -> Unit,
    onShowInactive: (show: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf<DateTarget?>(null) }
    // 正在编辑的节次时间；null = 无弹窗（每次确认/取消都回到 null，保证重开时状态新鲜）
    var editingSlot by remember { mutableStateOf<SlotEdit?>(null) }

    // 滚动由 OverlaySheet 的内容列承担，这里只排内容
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
    ) {
        // 课表名：独立输入卡（SheetTextField 自带「课表名称」标签，不需要分组标题）
        ScheduleNameField(name = schedule.name, onRename = onRename)

        // TermDatesCard 自带分组与卡片，勿再包一层（否则标题重复）
        TermDatesCard(
            start = schedule.termStart,
            end = schedule.termEnd,
            onPickStart = { picking = DateTarget.Start },
            onPickEnd = { picking = DateTarget.End },
            onSetWeeks = { weeks ->
                onTerm(schedule.termStart, endForTotalWeeks(schedule.termStart, schedule.termEnd, weeks))
            },
        )

        SettingsSection(title = stringResource(R.string.section_days)) {
            // RowChoiceCard 自带白卡与滑动蓝底动效，无需再套 SettingsCard；
            // selectedIndex 由状态推导恒非空 → 必有选中、无“再点取消”交互（点已选项 = 写回同值）
            RowChoiceCard(
                options = listOf(
                    stringResource(R.string.days_5),
                    stringResource(R.string.days_7),
                ),
                selectedIndex = if (schedule.daysPerWeek == 7) 1 else 0,
                onSelect = { index -> onDays(if (index == 1) 7 else 5) },
            )
        }

        SettingsSection(title = stringResource(R.string.section_slots)) {
            // 加：保留现有 slots 追加一节（上一节结束 +30 分钟课间、时长 100 分钟）；
            // 减：保留前缀裁剪（合法表的前缀必合法，且保留用户已改时间）
            val appended = appendSlot(schedule.slots)
            val canAdd = appended != null && schedule.slotCount < 12   // 上限沿用 R5
            // 单行卡：与「显示非本周课程」卡同一基础留白 → 两卡整高等高；
            // 溢出提示出现时，行↔提示、提示↔卡底也都是 12dp（见 CardContentPadding 公式）
            SettingsCard {
                SettingRow(
                    label = stringResource(R.string.slot_count_format, schedule.slotCount),
                    showChevron = false,
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    if (schedule.slotCount > 1) onSlots(schedule.slots.dropLast(1))
                                },
                                enabled = schedule.slotCount > 1,
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    stringResource(R.string.slot_decrease),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Spacer(modifier = Modifier.size(12.dp))
                            TextButton(
                                onClick = { appended?.let { s -> if (schedule.slotCount < 12) onSlots(s) } },
                                enabled = canAdd,
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    stringResource(R.string.slot_increase),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    },
                    modifier = Modifier.height(oneLineControlHeight),
                )
                // 仅在无法再加节（新节会越过 23:59）时提示禁用原因
                if (appended == null) {
                    Text(
                        text = stringResource(R.string.slot_overflow_desc),
                        // 基础留白垂直 6：行自带 6 + 此处 6 = 行文↔提示 12dp，提示↔卡底 6+6=12dp
                        modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 多行卡（每节一行「文字 + 时间控件」）：与学期卡共用 MultiLineRowSpacing → 行间 18dp
            SettingsCard(rowSpacing = MultiLineRowSpacing) {
                schedule.slots.forEachIndexed { index, slot ->
                    SettingRow(
                        label = stringResource(R.string.slot_format, index + 1),
                        showChevron = false,
                        trailing = {
                            // chip Background灰底保持 32dp 原高，但不计入行高：容器只按标签行高 24dp
                            // 参与行高与两行间距（同学期卡节奏），蓝底垂直居中向上下各溢出 4dp
                            OverflowHeightBox(ChipRowHeight) {
                                TimeChip(TimeText.formatDisplay(slot.startTime)) {
                                    editingSlot = SlotEdit(index, isStart = true)
                                }
                                Spacer(Modifier.width(12.dp))
                                TimeChip(TimeText.formatDisplay(slot.endTime)) {
                                    editingSlot = SlotEdit(index, isStart = false)
                                }
                            }
                        },
                    )
                }
            }
        }

        SettingsSection(title = stringResource(R.string.section_display)) {
            SettingsCard {
                SettingRow(
                    label = stringResource(R.string.show_inactive),
                    showChevron = false,
                    trailing = {
                        Switch(
                            checked = schedule.showInactiveCourses,
                            onCheckedChange = onShowInactive,
                        )
                    },
                    modifier = Modifier.height(oneLineControlHeight),
                )
            }
            Text(
                text = stringResource(R.string.show_inactive_desc),
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.classppColors.secondaryText,
            )
        }
    }

    // 学期日期选择：开始日任意一天可选，平移结束日保持学期长度；结束日同样
    // 任意一天都合法（可停在周中，末周就只上到那一天）
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
                            val newEnd = schedule.termEnd + (picked - schedule.termStart).toInt()
                            onTerm(picked, newEnd)
                        } else {
                            onTerm(schedule.termStart, picked)
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

/**
 * 课表名输入（浮层首项）：[SheetTextField] 卡片 + 本地草稿。
 * 焦点离开（点别处或键盘「完成」收起）时提交：清空视为放弃、静默回当前名；
 * 名字有变才回调 [onRename]——仓库校验失败走统一 toast，输入框保留用户文字便于修改。
 */
@Composable
private fun ScheduleNameField(name: String, onRename: (String) -> Unit) {
    // 草稿以当前名为 key：改名成功/切课表导致 name 变化时草稿归位，旋转重建同 current
    var draft by remember(name) { mutableStateOf(name) }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    SheetTextField(
        label = stringResource(R.string.schedule_name_label),
        value = draft,
        onValueChange = { draft = it },
        placeholder = stringResource(R.string.schedule_name_hint),
        imeAction = ImeAction.Done,
        // 键盘「完成」收起焦点 → 走下面的失焦提交
        onImeAction = { focusManager.clearFocus() },
        modifier = Modifier.onFocusEvent { state ->
            if (state.hasFocus) {
                hadFocus = true
            } else if (hadFocus) {
                hadFocus = false
                val trimmed = draft.trim()
                when {
                    trimmed.isEmpty() -> draft = name
                    trimmed != name -> onRename(trimmed)
                }
            }
        },
    )
}

/** 正在编辑的节次时间：第 [index] 节的起（true）或止（false） */
private data class SlotEdit(val index: Int, val isStart: Boolean)

/**
 * chip 容器：整体只占 [height] 参与父行行高与行间距；子项放开高度约束按实际尺寸
 * 测量、垂直居中向上下溢出——蓝底保持原高不被压扁，其高度不影响两行间距。
 * （固定高度的 Row/Box 会把约束收紧到容器高、子项被压扁，故用自定义测量。）
 */
@Composable
private fun OverflowHeightBox(height: Dp, content: @Composable () -> Unit) {
    Layout(
        content = content,
        measurePolicy = { measurables, constraints ->
            // 放开高度约束测量子项（宽度约束保持原样），容器高度固定为 height
            val placeables = measurables.map {
                it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            }
            val containerHeight = height.roundToPx()
            layout(placeables.sumOf { it.width }, containerHeight) {
                var x = 0
                for (placeable in placeables) {
                    // 垂直居中：32dp 子项在 24dp 容器内上下各溢出 4dp（负值放置不裁剪）
                    placeable.place(x, (containerHeight - placeable.height) / 2)
                    x += placeable.width
                }
            }
        },
    )
}

/**
 * [ScheduleError] → 本地化文案。课程类错误正常都经仓库 rejection() 归并为 CoursesOutOfRange，
 * 但兜底分支同样给中文结论，绝不把内部 id / 英文 message 直接显示给用户。
 * 非 Composable（context 版）：供 showError 在回调/协程里调用，结果走系统 Toast。
 * 设置页的「新建课表」空态同样复用（创建失败的出口）。
 */
internal fun ScheduleError.toMessage(context: Context): String = when (this) {
    // 起止日任意星期几都合法（开学日不再要求周一），只剩"早于开始日"一种非法
    is ScheduleError.TermRangeInvalid -> context.getString(R.string.error_term_range)
    is ScheduleError.DaysPerWeekInvalid -> context.getString(R.string.error_days_per_week)
    is ScheduleError.SlotCountInvalid -> context.getString(R.string.error_slot_count)
    is ScheduleError.SlotTimeFormatInvalid -> context.getString(R.string.error_slot_time_format)
    is ScheduleError.SlotOrderInvalid -> context.getString(R.string.error_slot_order)
    ScheduleError.InvalidScheduleName -> context.getString(R.string.error_schedule_name)
    is ScheduleError.CoursesOutOfRange -> context.getString(
        R.string.error_courses_out_of_range,
        affected.size,
        affected.take(3).joinToString { it.name },
    )

    is ScheduleError.NotFound -> context.getString(R.string.error_not_found)
    is ScheduleError.PersistFailed -> context.getString(R.string.error_persist_failed)
    // 真正没覆盖到的形态：不暴露内部 message，只给通用提示（细节进日志由调用方决定）
    else -> context.getString(R.string.error_unexpected)
}

@Preview(showBackground = true, name = "课表设置内容")
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
            onRename = {},
            onTerm = { _, _ -> },
            onDays = {},
            onSlots = {},
            onShowInactive = {},
        )
    }
}
