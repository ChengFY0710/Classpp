package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.pressFeedback
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 「切换课表」浮层（编辑栏中间按钮打开）——容器为 [OverlaySheet]，进出场动画与
 * 顶栏拖拽关闭由它提供，与课程编辑浮层同一套交互语言：
 * - 列表态：每张课表一张白卡（名称 + 学期起止 + 删除），当前课表主色高亮 + 对勾，
 *   点卡片切换（脏草稿走确认）；底部钉「导出 / 导入」两颗胶囊；左上「新建」经 [onCreate]
 *   打开新建表单浮层——表单是**独立浮层**（复用 NewScheduleSheet，调用方挂载、叠在本浮层
 *   之上），盖上来时本浮层经 [covered] 缩小后退（分层动效，同待办详情→编辑）。
 *
 * 切换/导出/导入/删除的实际执行由调用方（ScheduleScreen）负责，这里纯 UI；
 * 新建成功后的切换收口也在调用方（先关表单回列表再走切换，避免重复点创建）。
 * 删除先弹 [DeleteScheduleConfirmDialog] 确认（破坏性操作），确认后才回调 [onDelete]——
 * 确认框挂载在 OverlaySheet 之后（画到浮层卡片之上、后注册返回键），取消/返回只关它、
 * 留在浮层；关掉之后返回键才轮到浮层的关闭。
 *
 * 与 [com.fangyi.classpp.ui.components.OverlaySheet] 同一约定：页内覆盖层而非窗口类对话框，
 * 键盘不必让位（列表无输入框走 IgnoreIme；表单浮层自己走 ContentScroll）。
 */
@Composable
internal fun ScheduleSwitcherSheet(
    visible: Boolean,
    onDismissed: () -> Unit,
    schedules: List<Schedule>,
    activeScheduleId: String,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    covered: Boolean = false,
) {
    // 待删除的课表 id（非空 = 删除确认框打开）
    var deletingId by remember { mutableStateOf<String?>(null) }

    // 返回键关浮层；新建表单/删除确认框都后注册、比它先收到（见各自挂载处）
    BackHandler(enabled = true) {
        onDismiss()
    }

    OverlaySheet(
        title = stringResource(R.string.switcher_title),
        confirmLabel = stringResource(R.string.switcher_new_action),
        confirmIcon = R.drawable.ic_add_circle,
        // 「新建」打开表单浮层（调用方挂载，叠在本浮层之上）
        onConfirm = onCreate,
        rightAction = SheetTopAction(
            label = stringResource(R.string.switcher_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        // 新建表单盖上来时本浮层缩小后退（分层动效，与待办详情→编辑同一套）
        covered = covered,
        onDismissed = onDismissed,
        // 列表没有输入框，键盘不必让位（表单浮层自己走 ContentScroll）
        imeBehavior = SheetImeBehavior.IgnoreIme,
        bottomContent = {
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
        },
    ) {
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

    // 删除确认框：挂在 OverlaySheet 调用之后（画到浮层卡片之上），后注册 BackHandler——
    // 返回键先到它：取消/返回只关确认框、留在浮层，关掉后才轮到浮层的关闭。
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
        // M3 按钮的涟漪在组件内部硬编码、调用侧置不了 null：本阶段只叠加按压反馈——
        // 删除钮用独立按压源（宿主 SheetCard 的反馈是它自己的源，共享源规则下子钮不会跟着双亮），
        // pressFeedback 追加在 size 之后、最靠近按钮本体
        val press = remember { MutableInteractionSource() }
        IconButton(
            onClick = onDelete,
            interactionSource = press,
            modifier = Modifier
                .size(40.dp)
                .pressFeedback(press, CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = stringResource(R.string.edit_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
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
