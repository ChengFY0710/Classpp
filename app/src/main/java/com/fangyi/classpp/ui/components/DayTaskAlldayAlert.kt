package com.fangyi.classpp.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.R
import com.fangyi.classpp.data.TodoSort
import com.fangyi.classpp.data.comparator
import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.ui.note.repeatCardText
import com.fangyi.classpp.ui.note.toCardDateLabel
import com.fangyi.classpp.ui.theme.ClassppTheme

/** 全天卡行序：紧急度高在前，同档按截止近的在前（截止不上行，仅作稳定排序） */
internal val DayAlertAllDayRowOrder: Comparator<Todo> = TodoSort.Urgency.comparator()

/**
 * 全天任务提醒卡的候选：查看日命中、时刻形态为全天、未完成，且未落入 [DayTodoAlert] 的
 * 截止窗口——截止临近/已逾期的全天任务优先由待办提醒卡显示，两张卡不重复同一期。
 * 重复待办按分期粒度展开，只取 [viewDate] 当期。
 */
internal fun List<Todo>.allDayAlertCandidates(today: IsoDate, viewDate: IsoDate): List<Todo> {
    // 没有全天任务时跳过整次重复展开（本卡最常见的空态）
    if (none { it.timeKind == TodoTimeKind.AllDay }) return emptyList()
    return expandRepeatOccurrences(viewDate)
        .filter { todo ->
            !todo.completed &&
                todo.timeKind == TodoTimeKind.AllDay &&
                viewDate in todo.dates &&
                !todo.isDayTodoAlertCandidate(today, viewDate)
        }
        .sortedWith(DayAlertAllDayRowOrder)
}

/**
 * 日程页的全天任务提醒卡：与 [DayTodoAlert] 共用同一外壳 [DayAlertCard]（合并白卡、
 * 10dp 顶距、行镜与进出场、完成回显与停一拍落库、行点击绑定），只收查看日命中的
 * [TodoTimeKind.AllDay] 未完成待办——时间轴只排有起止时刻（Period）的待办，本卡是
 * 全天任务在日程页的入口。
 *
 * 候选与 [DayTodoAlert] 互斥：落在截止窗口（截止 ≤ [viewDate] + 2 天或已逾期）的全天
 * 任务由待办提醒卡显示，本卡让位，同一期不会出现两次。重复待办按分期粒度去重——
 * 当天这期在本卡、同系列的其他拖欠期仍可能在待办提醒卡。
 *
 * 行内容与待办提醒卡同款（NoteCard），但**不显示截止文案、倒计时与逾期红**：本卡只回答
 * 「这一天有哪些全天任务」；时间项只拼查看日 +「全天」（不取 todo.cardTimeText()——
 * 非重复多日期任务会把全部日期都拼进来）。无候选时整卡折叠，与上方卡片的 10dp 一并归零。
 *
 * @param todos 活跃待办（仓库原样；删除由仓库分流，不在流里）
 * @param today 今天（跨零点自动跳天的 [com.fangyi.classpp.ui.LocalToday]），用于判定
 *   DayTodoAlert 的「已逾期」让位口径
 * @param viewDate 卡片锚定日期：只显示命中这天的全天任务，默认 = 今天
 * @param tagColors 标签配色表（标签名 → 颜色，同待办页口径）
 * @param onComplete 勾选完成回调：组件只做回显与停一拍，落库路由（分期/整条）在宿主
 * @param onTodoClick 行点击回调（传分期副本）：宿主唤起待办详情；null = 行不可点
 */
@Composable
fun DayTaskAlldayAlert(
    todos: List<Todo>,
    today: IsoDate,
    viewDate: IsoDate = today,
    tagColors: Map<String, Color>,
    onComplete: (Todo) -> Unit,
    modifier: Modifier = Modifier,
    onTodoClick: ((Todo) -> Unit)? = null,
) {
    val candidates = remember(todos, today, viewDate) {
        todos.allDayAlertCandidates(today, viewDate)
    }
    DayAlertCard(
        candidates = candidates,
        onComplete = onComplete,
        modifier = modifier,
        onTodoClick = onTodoClick,
        rowOrder = DayAlertAllDayRowOrder,
    ) { todo, completed, onCheckedChange, onClick ->
        DayTaskAlldayAlertRowContent(
            todo = todo,
            viewDate = viewDate,
            completed = completed,
            tagColors = tagColors,
            onCheckedChange = onCheckedChange,
            onClick = onClick,
        )
    }
}

/**
 * 单条全天任务行：[NoteCard] 的提醒卡接线，与 [DayTodoAlert] 行同款（12dp 行内距、直角
 * 按压高亮、外缘圆角由外卡裁出），但截止、倒计时、逾期红三位缺席——本卡只展示标题、
 * 查看日 +「全天」、重复、标签、紧急度与勾选。
 */
@Composable
private fun DayTaskAlldayAlertRowContent(
    todo: Todo,
    viewDate: IsoDate,
    completed: Boolean,
    tagColors: Map<String, Color>,
    onCheckedChange: (Boolean) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    NoteCard(
        title = todo.name,
        time = listOf(viewDate.toCardDateLabel(), stringResource(R.string.todo_time_all_day))
            .joinToString(" "),
        repeat = todo.repeatCardText(),
        tags = todo.tags,
        tagColors = tagColors,
        urgency = todo.urgency,
        completed = completed,
        onCheckedChange = onCheckedChange,
        onClick = onClick,
        verticalPadding = DayAlertRowPaddingVertical,
        shape = RectangleShape,
    )
}

@Preview(showBackground = true, widthDp = 412, name = "全天任务提醒卡（今天多行）")
@Composable
private fun DayTaskAlldayAlertPreview() {
    ClassppTheme {
        DayTaskAlldayAlert(
            todos = listOf(
                // 无截止：本卡显示
                Todo(
                    id = "no-deadline",
                    name = "交水电费",
                    dates = listOf(IsoDate.of(2026, 10, 28)),
                    timeKind = TodoTimeKind.AllDay,
                    urgency = TodoUrgency.Medium,
                    tags = listOf("生活"),
                ),
                // 远期截止（> viewDate + 2）：本卡显示，不显示截止
                Todo(
                    id = "far-deadline",
                    name = "预约体检",
                    dates = listOf(IsoDate.of(2026, 10, 28)),
                    timeKind = TodoTimeKind.AllDay,
                    deadlineDate = IsoDate.of(2026, 11, 5),
                ),
                // 重复每天、锚在 10/26：只展开出 10/28 当期
                Todo(
                    id = "repeat",
                    name = "每周复盘",
                    dates = listOf(IsoDate.of(2026, 10, 26)),
                    repeat = RepeatRule(type = RepeatType.Daily),
                    timeKind = TodoTimeKind.AllDay,
                    urgency = TodoUrgency.Critical,
                ),
                // 多日期任务：只显示查看日这一天
                Todo(
                    id = "multi-date",
                    name = "整理相册",
                    dates = listOf(IsoDate.of(2026, 10, 27), IsoDate.of(2026, 10, 28)),
                    timeKind = TodoTimeKind.AllDay,
                ),
                // 截止落在待办提醒卡窗口（今天 + 1）：让位，不进本卡
                Todo(
                    id = "due-soon",
                    name = "写周报",
                    dates = listOf(IsoDate.of(2026, 10, 28)),
                    timeKind = TodoTimeKind.AllDay,
                    deadlineDate = IsoDate.of(2026, 10, 29),
                ),
                // 时段任务：不上本卡（时间轴负责）
                Todo(
                    id = "period",
                    name = "跑步",
                    dates = listOf(IsoDate.of(2026, 10, 28)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 18 * 60,
                    endMinute = 19 * 60,
                ),
                // 已完成：不进本卡
                Todo(
                    id = "completed",
                    name = "取快递",
                    dates = listOf(IsoDate.of(2026, 10, 28)),
                    timeKind = TodoTimeKind.AllDay,
                    completed = true,
                ),
                // 不在查看日：不进本卡
                Todo(
                    id = "tomorrow",
                    name = "交班费",
                    dates = listOf(IsoDate.of(2026, 10, 29)),
                    timeKind = TodoTimeKind.AllDay,
                ),
            ),
            today = IsoDate.of(2026, 10, 28),
            tagColors = emptyMap(),
            onComplete = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 412, name = "非今天（按 viewDate 取全天任务）")
@Composable
private fun DayTaskAlldayAlertViewDatePreview() {
    ClassppTheme {
        DayTaskAlldayAlert(
            todos = listOf(
                // 命中查看日：显示「后天 全天」
                Todo(
                    id = "view-date",
                    name = "交水电费",
                    dates = listOf(IsoDate.of(2026, 10, 30)),
                    timeKind = TodoTimeKind.AllDay,
                ),
                // 截止 = viewDate + 1：让位待办提醒卡
                Todo(
                    id = "due-soon",
                    name = "写周报",
                    dates = listOf(IsoDate.of(2026, 10, 30)),
                    timeKind = TodoTimeKind.AllDay,
                    deadlineDate = IsoDate.of(2026, 10, 31),
                ),
                // 时段任务：不上本卡
                Todo(
                    id = "period",
                    name = "跑步",
                    dates = listOf(IsoDate.of(2026, 10, 30)),
                    timeKind = TodoTimeKind.Period,
                    startMinute = 18 * 60,
                    endMinute = 19 * 60,
                ),
            ),
            today = IsoDate.of(2026, 10, 28),
            viewDate = IsoDate.of(2026, 10, 30),
            tagColors = emptyMap(),
            onComplete = {},
        )
    }
}
