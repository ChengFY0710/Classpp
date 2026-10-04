package com.fangyi.classpp.ui.schedule

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetInfoCard
import com.fangyi.classpp.ui.components.SheetInfoEntry
import com.fangyi.classpp.ui.components.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.components.SheetTextArea
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 课程详情浮层：浏览态点课程卡弹出，只读展示课程名 / 上课位置（周几、节次、时间）、
 * 任课教师与上课地点（[SheetInfoCard]），并附备注输入（[SheetTextArea]）。
 *
 * 备注没有显式保存按钮（设计稿顶栏只有"取消"）：**任意关闭路径**（取消胶囊 / 系统返回 /
 * 下拉 / 点遮罩）都会先经 [onNoteSave] 落盘再关，输入不丢；调用方负责实际持久化与去重。
 *
 * 浮层框架是 [OverlaySheet]：两段式关闭（visible / onDismissed），confirmLabel 传 null
 * 隐藏左侧确认胶囊。
 */
@Composable
fun CourseDetailSheet(
    visible: Boolean,
    entry: CourseEntry,
    schedule: Schedule,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onNoteSave: (String) -> Unit,
) {
    // 备注本地态：键在课程 id 上，换目标浮层重建时初值跟着换；
    // 保存后仓库回流同 id 条目不重置（值本就一致）
    var note by rememberSaveable(entry.id) { mutableStateOf(entry.note) }
    val closeWithSave = {
        onNoteSave(note.trim())
        onDismiss()
    }
    // 系统返回 = 收起浮层而非退出 app；与取消/下拉同走 closeWithSave，备注照常先落盘。
    // enabled 跟 visible：出场动画期间不再拦截（visible 已 false，重复提交被挡在门外）
    BackHandler(enabled = visible, onBack = closeWithSave)

    // 灰色"当前"行：与 AddCoursePanel 的 cellInfo 同一构造（星期数组 + 节次 + 起止时间）
    val weekdays = stringArrayResource(R.array.weekdays)
    val weekdayName = weekdays[(entry.dayOfWeek - 1).coerceIn(weekdays.indices)]
    val slotLabel = if (entry.span == 1) {
        stringResource(R.string.slot_format, entry.startSlot)
    } else {
        stringResource(R.string.slot_range_format, entry.startSlot, entry.endSlot)
    }
    val slots = schedule.slots
    val startDef = slots[(entry.startSlot - 1).coerceIn(slots.indices)]
    val endDef = slots[(entry.endSlot - 1).coerceIn(slots.indices)]
    val cellInfo = stringResource(
        R.string.edit_cell_info,
        weekdayName,
        slotLabel,
        "${startDef.startTime}-${endDef.endTime}",
    )

    OverlaySheet(
        title = stringResource(R.string.detail_title),
        confirmLabel = null,
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
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
                text = entry.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = cellInfo,
                fontSize = 15.sp,
                color = MaterialTheme.classppColors.secondaryText,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .offset(y = 2.dp),
            )
            // 教师 / 地点留空的字段不显示假数据；两者全空时整卡不出现
            val infoEntries = buildList {
                if (entry.teacher.isNotBlank()) {
                    add(SheetInfoEntry(stringResource(R.string.detail_teacher), entry.teacher))
                }
                if (entry.location.isNotBlank()) {
                    add(SheetInfoEntry(stringResource(R.string.detail_location), entry.location))
                }
            }
            if (infoEntries.isNotEmpty()) {
                SheetInfoCard(entries = infoEntries)
            }
            SheetTextArea(
                value = note,
                onValueChange = { note = it },
                placeholder = stringResource(R.string.detail_note_ph),
            )
        }
    }
}
