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
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.isOverdue
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.ui.components.NoteCard
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetInfoCard
import com.fangyi.classpp.ui.components.SheetInfoEntry
import com.fangyi.classpp.ui.components.SheetTextArea
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.note.cardTimeText
import com.fangyi.classpp.ui.note.currentMinuteOfDay
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors

/**
 * 课程详情浮层：浏览态点课程卡弹出，只读展示课程名 / 上课位置（周几、节次、时间）、
 * 任课教师与上课地点（[SheetInfoCard]），并附备注输入（[SheetTextArea]）；
 * 备注之后追加该课程标签下的待办卡片（[NoteCard]，[todos] 为调用方按课程名命中的全部
 * 待办，空列表则整块不出现）。默认只显未完成；勾选框可切换完成（经 [onTodoCheckedChange]
 * 交调用方落盘），**本次打开期间勾选的**卡片原地变划线置灰并保留到关闭，重开才消失；
 * 打开时即已完成的不显示。卡片整卡点击无动作（不进待办详情）。
 *
 * 备注没有显式保存按钮（设计稿顶栏只有"关闭"）：**任意关闭路径**（关闭胶囊 / 系统返回 /
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
    todos: List<Todo> = emptyList(),
    onTodoCheckedChange: ((id: String, checked: Boolean) -> Unit)? = null,
) {
    // 本次打开期间勾选过的待办 id：无 key——浮层卸载（收场动画结束）即清空，
    // 换课程随调用方的 key(detail.id) 重建。勾选先本地记账，StateFlow 回流后
    // completed 变 true 仍靠它保留在展示列表里（划线置灰而非消失）。
    // 取消勾选的摘除不能同步做（见渲染处 LaunchedEffect）——落盘回流前
    // completed 还是 true，同步摘 id 会让卡片滤出→回流又滤入，闪一下
    var checkedThisSession by remember { mutableStateOf(emptySet<String>()) }
    // 备注本地态：键在课程 id 上，换目标浮层重建时初值跟着换；
    // 保存后仓库回流同 id 条目不重置（值本就一致）
    var note by rememberSaveable(entry.id) { mutableStateOf(entry.note) }
    val closeWithSave = {
        onNoteSave(note.trim())
        onDismiss()
    }
    // 系统返回 = 收起浮层而非退出 app；与关闭/下拉同走 closeWithSave，备注照常先落盘。
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
                text = entry.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp),
            )
            Text(
                text = cellInfo,
                fontSize = 15.sp,
                color = MaterialTheme.classppColors.secondaryText,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .offset(y = 2.dp),
            )
            // 教师 / 地点留空的字段不显示假数据；两者全空且无周数时不出现
            val infoEntries = buildList {
                if (entry.teacher.isNotBlank()) {
                    add(SheetInfoEntry(stringResource(R.string.detail_teacher), entry.teacher))
                }
                if (entry.location.isNotBlank()) {
                    add(SheetInfoEntry(stringResource(R.string.detail_location), entry.location))
                }
                // 上课周数：文案与编辑面板的周数摘要同款（weeksSummary，如「第 1-16 周」）
                add(SheetInfoEntry(stringResource(R.string.edit_course_weeks), weeksSummary(entry.weeks)))
            }
            if (infoEntries.isNotEmpty()) {
                SheetInfoCard(entries = infoEntries)
            }
            SheetTextArea(
                value = note,
                onValueChange = { note = it },
                placeholder = stringResource(R.string.detail_note_ph),
            )
            // 该课程标签下的待办：打开时即已完成的不显示；本次会话勾选的
            // （checkedThisSession）保留在列——StateFlow 回流后 completed=true，
            // 卡片转划线置灰而非消失，关闭浮层重开才移除。
            // 逾期"此刻"随重组取值（同 TodoList），跨截止时刻后重算标红；
            // completed 态 isOverdue 恒 false（完成优先于过期），无需特判
            val visibleTodos = todos.filter {
                !it.completed || it.id in checkedThisSession
            }
            if (visibleTodos.isNotEmpty()) {
                val today = IsoDate.today()
                val nowMinute = currentMinuteOfDay()
                visibleTodos.forEach { todo ->
                    key(todo.id) {
                        // 取消勾选的摘 id 延迟到回流确认：setCompleted 异步落盘，点击瞬间
                        // completed 仍为 true，此刻摘会让 !completed 与会话集同时踩空、
                        // 卡片闪没一下。等 StateFlow 回流 completed=false（卡片本就靠
                        // !completed 可见）再摘，任意时序都无空档；落盘失败则 completed
                        // 恒为 true、id 保留，与勾选样式自洽
                        LaunchedEffect(todo.completed) {
                            if (!todo.completed) checkedThisSession = checkedThisSession - todo.id
                        }
                        NoteCard(
                            title = todo.name,
                            time = todo.cardTimeText(TodoSort.Created),
                            tags = todo.tags,
                            urgency = todo.urgency,
                            completed = todo.completed,
                            overdue = todo.isOverdue(today, nowMinute),
                            onCheckedChange = { checked ->
                                // 只记"勾上"（先于回流，保勾选方向无空档）；"取消"的摘除
                                // 交给上面的 LaunchedEffect 等回流确认后再做
                                if (checked) checkedThisSession = checkedThisSession + todo.id
                                onTodoCheckedChange?.invoke(todo.id, checked)
                            },
                            onClick = null,
                        )
                    }
                }
            }
        }
    }
}
