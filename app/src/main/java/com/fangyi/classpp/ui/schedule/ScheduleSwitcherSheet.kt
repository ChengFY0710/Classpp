package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetImeBehavior
import com.fangyi.classpp.ui.components.SheetPillButton
import com.fangyi.classpp.ui.components.SheetTextField
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.settings.TermDatesCard
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBottom
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
 * 删除先弹 [DeleteScheduleConfirmDialog] 确认（破坏性操作），确认后才回调 [onDelete]——
 * 确认框挂载在 OverlaySheet 之后（画到浮层卡片之上、后注册返回键），取消/返回只关它、
 * 留在浮层；关掉之后返回键才轮到浮层的「表单态回列表、列表态关浮层」。
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
    val defaultStart = remember { IsoDate.today() }
    var start by remember { mutableStateOf(defaultStart) }
    var end by remember { mutableStateOf(defaultStart + TERM_DEFAULT_DAYS) }
    // 待删除的课表 id（非空 = 删除确认框打开）
    var deletingId by remember { mutableStateOf<String?>(null) }

    // 返回键（删除确认框不在场时）：表单态先回列表，列表态才关浮层；
    // 删除确认框在场时它后注册、先收到（见函数尾挂载处）
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
            // 列表态是「关闭」；新建表单态的右上按钮语义是放弃表单回列表，仍为「取消」
            label = stringResource(if (createMode) R.string.settings_cancel else R.string.switcher_close),
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
                    horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
                ) {
                    SheetPillButton(
                        label = stringResource(R.string.switcher_export),
                        icon = R.drawable.ic_arrow_export_up,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        onClick = onExport,
                        iconSize = 25.dp,
                        topPadding = 10.dp,
                        bottomPadding = 10.dp,
                        startPadding = 15.dp,
                        endPadding = 10.dp,
                    )
                    SheetPillButton(
                        label = stringResource(R.string.switcher_import),
                        icon = R.drawable.ic_arrow_download,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        onClick = onImport,
                        iconSize = 25.dp,
                        topPadding = 10.dp,
                        bottomPadding = 10.dp,
                        startPadding = 15.dp,
                        endPadding = 10.dp,
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
            Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
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

    // 选日对话框：与设置页同一套联动（开始日任意一天可选、平移结束日保持学期长度；
    // 结束日同样任意一天都合法）
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
                            end = end + (picked - start).toInt()
                            start = picked
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

    // 删除确认框：挂在 OverlaySheet 调用之后（画到浮层卡片之上），后注册 BackHandler——
    // 返回键先到它：取消/返回只关确认框、留在浮层，关掉后才轮到浮层的回列表/关闭。
    // 挂载与可见性分离（同浮层两段式关闭）：确认/取消先清 deletingId 播淡出，播完才卸载
    var deleteDialogMounted by remember { mutableStateOf(false) }
    val deleteDialogVisible = deletingId != null
    if (deleteDialogVisible) deleteDialogMounted = true
    if (deleteDialogMounted) {
        DeleteScheduleConfirmDialog(
            scheduleName = schedules.firstOrNull { it.id == deletingId }?.name.orEmpty(),
            visible = deleteDialogVisible,
            onDismissed = { deleteDialogMounted = false },
            onCancel = { deletingId = null },
            onConfirm = {
                val id = deletingId
                deletingId = null
                id?.let(onDelete)
            },
        )
    }
}

/** 一张课表卡：名称 + 学期起止 + 删除；当前课表名称主色高亮、删除键左侧带对勾（点击切换） */
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
        if (isActive) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.edit_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 新建课表表单（浮层内二级形态）：课表名输入 + 学期三行卡（设置页同款 [TermDatesCard]）。
 * 日期联动规则与设置页一致（开始日任意一天可选并平移结束日），默认值为"今天 ~ 16 周后"。
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
    Spacer(Modifier.height(SheetSectionSpacingBottom))
    TermDatesCard(
        start = start,
        end = end,
        onPickStart = onPickStart,
        onPickEnd = onPickEnd,
        onSetWeeks = onSetWeeks,
    )
}

/**
 * 删除课表确认框：页内居中白卡（[ConfirmDialogCard]），点名课表并告知删除不可撤销。
 * 确认胶囊 error 红底白字标 destructive；返回键与遮罩点击都只取消。
 * 两段式关闭见 [FadeOverlayDialog]：确认/取消即清 deletingId（名称随之为空），
 * [shownName] 留住最后一份文案，淡出期间不闪空。
 */
@Composable
private fun DeleteScheduleConfirmDialog(
    scheduleName: String,
    visible: Boolean,
    onDismissed: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    var shownName by remember { mutableStateOf(scheduleName) }
    if (scheduleName.isNotEmpty()) shownName = scheduleName

    FadeOverlayDialog(
        visible = visible,
        onDismiss = onCancel,
        onDismissed = onDismissed,
        cardHorizontalPadding = 32.dp,
    ) {
        ConfirmDialogCard(
            title = stringResource(R.string.switcher_delete_title),
            message = stringResource(R.string.switcher_delete_message, shownName),
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
 * 脏草稿切换确认框：页内居中白卡（[ConfirmDialogCard]）+ 遮罩，叠加在浮层**之后**组合——
 * 返回键时后注册的它先收到，优先于浮层与编辑取消。确认胶囊走主色（非 destructive，区别于删除框）。
 */
@Composable
internal fun DiscardSwitchConfirmDialog(
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
            title = stringResource(R.string.switcher_discard_title),
            message = stringResource(R.string.switcher_discard_message),
        ) {
            DialogPillButton(
                label = stringResource(R.string.switcher_discard_cancel),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onCancel,
            )
            DialogPillButton(
                label = stringResource(R.string.switcher_discard_confirm),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.weight(1f),
                enabled = visible,
                onClick = onConfirm,
            )
        }
    }
}
