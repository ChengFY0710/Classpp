package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.weekOf
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetCard
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 「设置调课」列表浮层：展示当前课表的全部单次调课，按原课次周次升序。
 *
 * 每条记录一行：课程名 + 「第5周 周三 第1-2节 → 第6周 周五 第3-4节」；
 * 行点击进同一个 [AdjustCourseSheet] 编辑，行尾删除按钮直接删草稿（取消编辑可整体还原）。
 * 已过去的原课次置灰，但仍保留在列表里（用户要求展示全部）。
 *
 * 列表读的是**有效课表**（编辑态 = 课程/调课草稿），删除同样只回调草稿。
 * [covered] 供宿主在编辑浮层叠上来时让本浮层缩小后退。
 */
@Composable
internal fun AdjustmentListSheet(
    visible: Boolean,
    schedule: Schedule,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onEdit: (CourseAdjustment) -> Unit,
    onDelete: (CourseAdjustment) -> Unit,
    covered: Boolean = false,
) {
    BackHandler(enabled = true) { onDismiss() }

    val rows = remember(schedule.adjustments, schedule.courses) {
        schedule.adjustments
            .sortedWith(compareBy({ it.fromWeek }, { it.toWeek }, { it.toDayOfWeek }))
            .mapNotNull { adjustment ->
                schedule.courses.firstOrNull { it.id == adjustment.courseId }
                    ?.let { course -> adjustment to course }
            }
    }
    val todayWeek = remember(schedule) { schedule.weekOf(IsoDate.today()) }

    OverlaySheet(
        title = stringResource(R.string.adjust_list_title),
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
        covered = covered,
        onDismissed = onDismissed,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            if (rows.isEmpty()) {
                SheetInfoBanner(text = stringResource(R.string.adjust_list_empty))
            } else {
                CardSection {
                    rows.forEach { (adjustment, course) ->
                        AdjustmentRow(
                            adjustment = adjustment,
                            course = course,
                            past = adjustment.fromWeek < todayWeek,
                            onClick = { onEdit(adjustment) },
                            onDelete = { onDelete(adjustment) },
                        )
                    }
                }
            }
        }
    }
}

/** 单条调课：整卡点击编辑，行尾删除（删除按钮的点击不会冒泡到卡片） */
@Composable
private fun AdjustmentRow(
    adjustment: CourseAdjustment,
    course: CourseEntry,
    past: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val weekdayNames = stringArrayResource(R.array.weekdays)
    val alpha = if (past) 0.55f else 1f
    val source = stringResource(
        R.string.adjust_source_summary,
        adjustment.fromWeek,
        weekdayNames.getOrElse(course.dayOfWeek - 1) { "" },
        slotRangeText(course.startSlot, course.span),
    )
    val target = stringResource(
        R.string.adjust_target_summary,
        adjustment.toWeek,
        weekdayNames.getOrElse(adjustment.toDayOfWeek - 1) { "" },
        slotRangeText(adjustment.toStartSlot, adjustment.toSpan),
    )

    SheetCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = course.name,
                style = ClassppTextTokens.listRowTitle,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.adjust_summary_arrow, source, target),
                style = ClassppTextTokens.settingsDescription,
                color = MaterialTheme.classppColors.secondaryText.copy(alpha = alpha),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_delete_filled),
                contentDescription = stringResource(R.string.edit_delete),
                tint = MaterialTheme.colorScheme.error.copy(alpha = alpha),
            )
        }
    }
}
