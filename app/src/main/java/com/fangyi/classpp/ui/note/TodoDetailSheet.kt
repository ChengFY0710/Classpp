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
 * 备注没有显式保存按钮（顶栏只有"关闭"）：**任意关闭路径**（关闭胶囊 / 系统返回 /
 * 下拉 / 点遮罩）都会先经 [onNoteSave] 落盘再关，输入不丢；调用方负责实际持久化与去重。
 *
 * 浮层框架是 [OverlaySheet]：两段式关闭（visible / onDismissed），confirmLabel 传 null
 * 隐藏左侧确认胶囊；键盘避让走默认的 ContentScroll（备注聚焦时内容滚动让位）。
 */
@Composable
internal fun TodoDetailSheet(
    visible: Boolean,
    todo: Todo,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onNoteSave: (String) -> Unit,
) {
    // 备注本地态：键在待办 id 上，换目标浮层重建时初值跟着换；
    // 保存后仓库回流同 id 条目不重置（值本就一致）
    var note by rememberSaveable(todo.id) { mutableStateOf(todo.note) }
    val closeWithSave = {
        onNoteSave(note.trim())
        onDismiss()
    }
    // 系统返回 = 收起浮层而非退出 app；与关闭/下拉同走 closeWithSave，备注照常先落盘。
    // enabled 跟 visible：出场动画期间不再拦截（visible 已 false，重复提交被挡在门外）
    BackHandler(enabled = visible, onBack = closeWithSave)

    OverlaySheet(
        title = stringResource(R.string.todo_detail_title),
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.detail_close),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = closeWithSave,
        ),
        onDismiss = closeWithSave,
        visible = visible,
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
