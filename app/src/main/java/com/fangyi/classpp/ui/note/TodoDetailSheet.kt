package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetInfoCard
import com.fangyi.classpp.ui.components.SheetInfoEntry
import com.fangyi.classpp.ui.components.SheetTextArea
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.TagChip
import com.fangyi.classpp.ui.components.flagLabel
import com.fangyi.classpp.ui.components.toDeadlineText
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors

/** 标签行内胶囊间距（同 NoteCard 属性行）；胶囊与文字的 4dp 在 TagChip 内部 */
private val TagRowSpacing = 12.dp

/**
 * 待办详情浮层：点待办卡弹出，结构对标 [com.fangyi.classpp.ui.schedule.CourseDetailSheet]——
 * 正文为待办标题、标题下方的标签行（[TagChip]，空标签不渲染）、四行只读信息
 * （[SheetInfoCard]：截止日期 / 紧急程度 / 待办时间 / 待办地点，四行常显，
 * 空值显灰「无」）与备注输入（[SheetTextArea]）。
 *
 * 顶栏左「编辑」右「关闭」：编辑把**最新快照**（备注 trim 后、可尚未落盘）经 [onEdit] 交回调用方，
 * 据此打开编辑待办浮层（叠在本浮层之上，本浮层保持打开）；备注在任意关闭路径
 * （关闭胶囊 / 系统返回 / 下拉 / 点遮罩）先经 [onNoteSave] 落盘再关，输入不丢；
 * 调用方负责实际持久化与去重。
 *
 * 浮层框架是 [OverlaySheet]：两段式关闭（visible / onDismissed）；键盘避让走默认的
 * ContentScroll（备注聚焦时内容滚动让位）。
 */
@Composable
internal fun TodoDetailSheet(
    visible: Boolean,
    todo: Todo,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onNoteSave: (String) -> Unit,
    onEdit: (Todo) -> Unit,
    // 被编辑浮层盖住：本层缩小后退（透传 OverlaySheet 的分层动效）
    covered: Boolean = false,
) {
    // 备注本地态：键在待办 id + 仓库 note 上——换目标重建时初值跟着换；编辑浮层改了备注
    // 并确认（仓库 note 变化）也触发重初始化，否则残留旧值会在关闭时把新值覆盖回去。
    // 打字不回流仓库（值未变不重置）；落盘后回流同值同样不重置
    var note by rememberSaveable(todo.id, todo.note) { mutableStateOf(todo.note) }
    val closeWithSave = {
        onNoteSave(note.trim())
        onDismiss()
    }
    // 系统返回 = 收起浮层而非退出 app；与关闭/下拉同走 closeWithSave，备注照常先落盘。
    // enabled 跟 visible：出场动画期间不再拦截（visible 已 false，重复提交被挡在门外）
    BackHandler(enabled = visible, onBack = closeWithSave)
    // 点「编辑」先收键盘：详情还挂在编辑浮层下层，焦点不清会把键盘带进编辑表单
    val focusManager = LocalFocusManager.current

    OverlaySheet(
        title = stringResource(R.string.todo_detail_title),
        confirmLabel = stringResource(R.string.detail_edit),
        confirmIcon = R.drawable.ic_edit,
        // 编辑图标单独调到 26dp（其余浮层维持默认 30dp）
        confirmIconSize = 26.dp,
        onConfirm = {
            focusManager.clearFocus()
            onEdit(todo.copy(note = note.trim()))
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.detail_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = closeWithSave,
        ),
        onDismiss = closeWithSave,
        visible = visible,
        covered = covered,
        onDismissed = onDismissed,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            Text(
                text = todo.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
            )
            if (todo.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(TagRowSpacing),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    todo.tags.forEach { tag -> TagChip(tag) }
                }
            }
            // 四行常显：截止日期与紧急程度是结构化字段（未设也占位），时间/地点同理——
            // 值一律右对齐走 fieldValue，空值占位灰字「无」（同 DeadlineCard 的未设态文案）
            val noneText = stringResource(R.string.deadline_card_none)
            SheetInfoCard(
                entries = listOf(
                    SheetInfoEntry(
                        label = stringResource(R.string.deadline_card_label),
                        value = todo.deadlineText() ?: noneText,
                    ),
                    SheetInfoEntry(
                        label = stringResource(R.string.sort_urgency),
                        value = todo.urgency.flagLabel(),
                    ),
                    SheetInfoEntry(
                        label = stringResource(R.string.todo_detail_time_label),
                        value = todo.scheduledText() ?: noneText,
                    ),
                    SheetInfoEntry(
                        label = stringResource(R.string.todo_location_label),
                        value = todo.location.ifBlank { noneText },
                    ),
                ),
            )
            SheetTextArea(
                value = note,
                onValueChange = { note = it },
                placeholder = stringResource(R.string.detail_note_ph),
            )
        }
    }
}

/** 截止值 `2026-9-7 8:00`（同 DeadlineCard 的展示格式）；未设截止返回 null（调用方显「无」） */
private fun Todo.deadlineText(): String? {
    val date = deadlineDate ?: return null
    val minute = deadlineMinute ?: return null
    return "${date.toDeadlineText()} ${TimeText.format(minute)}"
}

/**
 * 待办时间 `6月18日 14:00-16:00` / `6月18日 全天` / `6月18日、6月20日`；无日期也无时刻返回 null。
 *
 * 与列表卡片的 [cardTimeText] 分工：这里不省略"今天"的日期段（详情页要给出确定的日期），
 * 也不兜底截止值（截止日期有自己的行）。
 */
@Composable
private fun Todo.scheduledText(): String? {
    val timePart = when (timeKind) {
        TodoTimeKind.Period -> {
            val start = startMinute
            val end = endMinute
            if (start != null && end != null) "${TimeText.format(start)}-${TimeText.format(end)}" else null
        }
        TodoTimeKind.AllDay -> stringResource(R.string.todo_time_all_day)
        TodoTimeKind.None -> null
    }
    val datePart = dates.joinToString("、") { it.toCardDateText() }.takeIf { it.isNotEmpty() }
    return listOfNotNull(datePart, timePart)
        .joinToString(" ")
        .takeIf { it.isNotEmpty() }
}
