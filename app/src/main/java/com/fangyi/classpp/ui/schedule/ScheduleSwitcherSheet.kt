package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.ui.theme.Scrim

private val SheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val ListContainerShape = RoundedCornerShape(16.dp)
private val RowShape = RoundedCornerShape(12.dp)

/**
 * 「切换课表」底部浮层（编辑栏中间按钮打开）：
 * - 列表态：全部课表（名称 + 学期起止），当前课表主色高亮 + 对勾，点其它行切换；
 * - 底部三按钮：导出 / 导入 / 新建；新建在**同一浮层内**切到表单态（返回/遮罩先回列表）。
 *
 * 与 [AddCoursePanel] 同一约定：页内覆盖层而非 AlertDialog（对话框是独立窗口，
 * 该 ROM 上输入法与它有兼容问题）——新建表单有输入框，键盘必须接得进来。
 * 脏草稿确认、切换/导出/导入的实际执行由调用方（ScheduleScreen）负责，这里纯 UI；
 * [createMode] 也由调用方持有：创建成功后先回列表态再走切换确认，避免重复点创建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSwitcherSheet(
    schedules: List<Schedule>,
    activeScheduleId: String,
    createMode: Boolean,
    createError: String?,
    onCreateModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCreateConfirm: (name: String, start: IsoDate, end: IsoDate) -> Unit,
) {
    // 表单字段随浮层销毁而复位（与设置页新建表单同为 remember，旋转重建回默认值）
    var picking by remember { mutableStateOf<DateTarget?>(null) }
    var name by remember { mutableStateOf("") }
    val defaultStart = remember { snapToMonday(IsoDate.today()) }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultStart + TERM_DEFAULT_DAYS) }

    // 返回键/遮罩：表单态先回列表，列表态才关浮层
    BackHandler(enabled = true) {
        if (createMode) onCreateModeChange(false) else onDismiss()
    }

    val scrimInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim.copy(alpha = 0.32f))
            // 点空白处收起（无涟漪）；表单态回列表
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = { if (createMode) onCreateModeChange(false) else onDismiss() },
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吃掉落在面板上的点击，避免穿透到遮罩把面板关掉（按钮的消费优先）
                    .clickable(
                        interactionSource = panelInteraction,
                        indication = null,
                        onClick = {},
                    ),
                shape = SheetShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
                ) {
                    if (createMode) {
                        CreateScheduleForm(
                            name = name,
                            onNameChange = { name = it },
                            start = start,
                            end = end,
                            error = createError,
                            onPickStart = { picking = DateTarget.Start },
                            onPickEnd = { picking = DateTarget.End },
                            onCancel = { onCreateModeChange(false) },
                            onConfirm = { onCreateConfirm(name.trim(), start, end) },
                        )
                    } else {
                        ScheduleList(
                            schedules = schedules,
                            activeScheduleId = activeScheduleId,
                            onSwitch = onSwitch,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SheetActionButton(
                                label = stringResource(R.string.switcher_export),
                                modifier = Modifier.weight(1f),
                                onClick = onExport,
                            )
                            SheetActionButton(
                                label = stringResource(R.string.switcher_import),
                                modifier = Modifier.weight(1f),
                                onClick = onImport,
                            )
                            SheetActionButton(
                                label = stringResource(R.string.switcher_new),
                                modifier = Modifier.weight(1f),
                                onClick = { onCreateModeChange(true) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 选日对话框：与设置页同一套吸附（开始→周一并平移结束日；结束日不吸附，任意一天都合法）
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
                            end = picked
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

/** 课表列表：浅灰圆角容器内逐行列出，列表超高内滚动（三按钮固定在容器之外） */
@Composable
private fun ScheduleList(
    schedules: List<Schedule>,
    activeScheduleId: String,
    onSwitch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .clip(ListContainerShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        schedules.forEach { schedule ->
            ScheduleRow(
                schedule = schedule,
                isActive = schedule.id == activeScheduleId,
                onClick = { if (schedule.id != activeScheduleId) onSwitch(schedule.id) },
            )
        }
    }
}

/** 一行课表：名称 + 学期起止；当前课表名称主色高亮、右侧对勾 */
@Composable
private fun ScheduleRow(
    schedule: Schedule,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = schedule.name,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(
                    R.string.switcher_date_range,
                    schedule.termStart.toDisplayText(),
                    schedule.termEnd.toDisplayText(),
                ),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isActive) {
            Text(
                text = "✓",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 底部三按钮之一：主色文字、等宽均分 */
@Composable
private fun SheetActionButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 新建课表紧凑表单（浮层内二级形态）：名称 + 学期起止 + 创建。
 * 日期吸附规则与设置页一致（开始→周一、结束→周五），默认值同为“本周一 ~ 16 周后的周五”。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScheduleForm(
    name: String,
    onNameChange: (String) -> Unit,
    start: IsoDate,
    end: IsoDate,
    error: String?,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Text(
        text = stringResource(R.string.switcher_new),
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text(stringResource(R.string.schedule_name_label)) },
        placeholder = { Text(stringResource(R.string.schedule_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    FormDateRow(
        label = stringResource(R.string.term_start),
        value = start.toDisplayText(),
        onClick = onPickStart,
    )
    FormDateRow(
        label = stringResource(R.string.term_end),
        value = end.toDisplayText(),
        onClick = onPickEnd,
    )
    if (error != null) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = error,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(16.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_cancel))
        }
        Button(
            onClick = onConfirm,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.create_schedule))
        }
    }
}

/** 表单日期行：左标签、右日期值，整行可点 */
@Composable
private fun FormDateRow(
    label: String,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = value,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「放弃未保存的修改？」确认（切换课表时草稿有改动才出现）：
 * 与 [AlternatePickerDialog] 同一约定的页内居中卡片，组合在 [ScheduleSwitcherSheet]
 * 之后——返回键时后注册的它先收到，优先于浮层与编辑取消。
 */
@Composable
internal fun DiscardSwitchConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    BackHandler { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Scrim.copy(alpha = 0.32f))
            .clickable(
                interactionSource = scrimInteraction,
                indication = null,
                onClick = onCancel,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                // 吃掉落在卡片上的点击，避免穿透到遮罩
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text(
                    text = stringResource(R.string.switcher_discard_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.switcher_discard_message),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.switcher_discard_cancel))
                    }
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = stringResource(R.string.switcher_discard_confirm),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 日期 → 展示文本：`IsoDate.toString()` 是 `yyyy-MM-dd`，按 locale 走
 * [R.string.date_cn_format]（中文 `2026年03月02日`、英文 `3/2/2026`）。
 */
@Composable
private fun IsoDate.toDisplayText(): String {
    val parts = toString().split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val month = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val day = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return stringResource(R.string.date_cn_format, year, month, day)
}
