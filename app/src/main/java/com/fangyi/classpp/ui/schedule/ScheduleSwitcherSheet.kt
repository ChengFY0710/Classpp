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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetPillButton
import com.fangyi.classpp.ui.components.SheetSectionSpacing
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.settings.TermDatesCard
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 「切换课表」浮层（编辑栏中间按钮打开）——容器为 [OverlaySheet]，进出场动画与
 * 顶栏拖拽关闭由它提供，与课程编辑浮层同一套交互语言：
 * - 列表态：每张课表一张白卡（名称 + 学期起止 + 删除），当前课表主色高亮 + 对勾，
 *   点卡片切换（脏草稿走确认）；底部钉「导出 / 导入」两颗胶囊；左上「新建」进表单态；
 * - 表单态：课表名输入 + 学期三行卡（[TermDatesCard]，与设置页同款），
 *   「取消」胶囊/返回键先回列表。
 *
 * 切换/导出/导入/删除的实际执行由调用方（ScheduleScreen）负责，这里纯 UI；
 * [createMode] 也由调用方持有：创建成功后先回列表态再走切换确认，避免重复点创建。
 * 删除先弹 [DeleteScheduleConfirmDialog] 确认（破坏性操作），确认后才回调 [onDelete]。
 *
 * 与 [com.fangyi.classpp.ui.components.OverlaySheet] 同一约定：页内覆盖层而非窗口类对话框，
 * 键盘接得进来（表单态走 ContentScroll 让位）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleSwitcherSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    schedules: List<Schedule>,
    activeScheduleId: String,
    createMode: Boolean,
    onCreateModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onCreateConfirm: (name: String, start: IsoDate, end: IsoDate) -> Unit,
) {
    // 表单字段随浮层卸载而复位（remember，旋转重建回默认值）
    var picking by remember { mutableStateOf<DateTarget?>(null) }
    var name by remember { mutableStateOf("") }
    val defaultStart = remember { snapToMonday(IsoDate.today()) }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultStart + TERM_DEFAULT_DAYS) }
    // 待删除的课表 id（非空 = 删除确认框打开）
    var deletingId by remember { mutableStateOf<String?>(null) }

    // 返回键：表单态先回列表，列表态才关浮层
    BackHandler(enabled = true) {
        if (createMode) onCreateModeChange(false) else onDismiss()
    }

    OverlaySheet(
        title = stringResource(if (createMode) R.string.switcher_new else R.string.switcher_title),
        confirmLabel = stringResource(if (createMode) R.string.edit_confirm else R.string.switcher_new_action),
        confirmIcon = if (createMode) {
            R.drawable.ic_checkmark_circle
        } else {
            R.drawable.ic_add_circle
        },
        onConfirm = {
            if (createMode) onCreateConfirm(name.trim(), start, end) else onCreateModeChange(true)
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = { if (createMode) onCreateModeChange(false) else onDismiss() },
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
        // 列表态没有输入框，键盘不必让位；表单态与课程面板同策略
        imeBehavior = if (createMode) SheetImeBehavior.ContentScroll else SheetImeBehavior.IgnoreIme,
        bottomContent = if (!createMode) {
            {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                ) {
                    SheetPillButton(
                        label = stringResource(R.string.switcher_export),
                        icon = R.drawable.ic_arrow_export_up,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        onClick = onExport,
                    )
                    SheetPillButton(
                        label = stringResource(R.string.switcher_import),
                        icon = R.drawable.ic_arrow_download,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        onClick = onImport,
                    )
                }
            }
        } else {
            null
        },
    ) {
        if (createMode) {
            CreateScheduleContent(
                name = name,
                onNameChange = { name = it },
                start = start,
                end = end,
                onPickStart = { picking = DateTarget.Start },
                onPickEnd = { picking = DateTarget.End },
                // 改周数 → 结束日整周平移（星期几不变），与设置页同一联动
                onSetWeeks = { weeks -> end = endForTotalWeeks(start, end, weeks) },
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacing)) {
                schedules.forEach { schedule ->
                    ScheduleCard(
                        schedule = schedule,
                        isActive = schedule.id == activeScheduleId,
                        onClick = { if (schedule.id != activeScheduleId) onSwitch(schedule.id) },
                        onDelete = { deletingId = schedule.id },
                    )
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

    // 删除确认框：后注册 BackHandler，返回键优先于浮层的回列表/关闭
    deletingId?.let { id ->
        DeleteScheduleConfirmDialog(
            scheduleName = schedules.firstOrNull { it.id == id }?.name.orEmpty(),
            onCancel = { deletingId = null },
            onConfirm = {
                deletingId = null
                onDelete(id)
            },
        )
    }
}

/** 一张课表卡：名称 + 学期起止 + 删除；当前课表名称主色高亮、尾部带对勾（点击切换） */
@Composable
private fun ScheduleCard(
    schedule: Schedule,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    SheetCard(onClick = onClick) {
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
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.switcher_date_range,
                    schedule.termStart.toSlashText(),
                    schedule.termEnd.toSlashText(),
                ),
                fontSize = 14.sp,
                color = MaterialTheme.classppColors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.edit_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        if (isActive) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/**
 * 新建课表表单（浮层内二级形态）：课表名输入 + 学期三行卡（设置页同款 [TermDatesCard]）。
 * 日期吸附规则与设置页一致（开始→周一并平移结束日），默认值同为“本周一 ~ 16 周后的周五”。
 */
@Composable
private fun CreateScheduleContent(
    name: String,
    onNameChange: (String) -> Unit,
    start: IsoDate,
    end: IsoDate,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onSetWeeks: (Int) -> Unit,
) {
    SheetTextField(
        label = stringResource(R.string.schedule_name_label),
        value = name,
        onValueChange = onNameChange,
        placeholder = stringResource(R.string.schedule_name_hint),
        imeAction = ImeAction.Done,
    )
    TermDatesCard(
        start = start,
        end = end,
        onPickStart = onPickStart,
        onPickEnd = onPickEnd,
        onSetWeeks = onSetWeeks,
    )
}

/**
 * 删除课表确认框：页内居中卡片（视觉同 [DiscardSwitchConfirmDialog]），点名课表并说明不可恢复。
 * 确认键用错误色标 destructive；返回键与遮罩点击都只取消。
 */
@Composable
private fun DeleteScheduleConfirmDialog(
    scheduleName: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    BackHandler { onCancel() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
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
                    text = stringResource(R.string.switcher_delete_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.switcher_delete_message, scheduleName),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onCancel) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                    TextButton(onClick = onConfirm) {
                        Text(
                            text = stringResource(R.string.edit_delete),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/** 日期 → `2026/9/7`（无前导零斜杠格式，列表副行用），走 [R.string.date_slash_format] */
@Composable
private fun IsoDate.toSlashText(): String {
    val parts = toString().split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: 0
    val month = parts.getOrNull(1)?.toIntOrNull() ?: 0
    val day = parts.getOrNull(2)?.toIntOrNull() ?: 0
    return stringResource(R.string.date_slash_format, year, month, day)
}

/**
 * 脏草稿切换确认框：页内居中卡片 + 遮罩，叠加在浮层**之后**组合——
 * 返回键时后注册的它先收到，优先于浮层与编辑取消。
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
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
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
