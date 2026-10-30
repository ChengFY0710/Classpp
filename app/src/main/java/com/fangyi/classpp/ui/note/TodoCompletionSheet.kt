package com.fangyi.classpp.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.fangyi.classpp.R
import com.fangyi.classpp.data.displayOverdueOccurrences
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.ui.LocalToday
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.DayCalendar
import com.fangyi.classpp.ui.components.DayCalendarBackAction
import com.fangyi.classpp.ui.components.DayCalendarDayStatus
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SheetInfoCard
import com.fangyi.classpp.ui.components.SheetInfoEntry
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.components.rememberDayCalendarFoldState
import com.fangyi.classpp.ui.theme.ClassppTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween

/**
 * 重复待办的「完成情况」浮层：详情页「重复」分组里的「完成情况」导航行唤起，
 * 叠在详情浮层之上（[TodoDetailSheet] 负责两段式挂载与 covered 动效）。
 *
 * 内容是一组无题 [CardSection]，包两个组件：
 * 1. [DayCalendar]（复用日历卡，向后兼容扩展）：重复规则涉及的天数带 Primary 小蓝球；
 *    已完成/已逾期日期的圆球按状态上色——已完成 urgentGreen 底 + 白字、已逾期未完成
 *    error 底 + 白字（状态色覆盖所有已完成期，含今天与未来的提前完成；合并口径下勾选
 *    的就是当期，常常落在今天或下一期），只有本月日期上状态色（前后月补位保持灰字 +
 *    小点）。底栏回跳按钮改成「回到本月」（浏览其他月份时出现，点击收回今天所在月）。
 *    状态球可点：绿球点一下取消完成（回落红球/常规色）、红球点一下补完成（变绿），经
 *    [onOccurrenceCompletedChange] 交宿主落库（仓库按逐期集合结算，迟到补完成不撤销已落账逾期）。
 * 2. [SheetInfoCard]：统计**已结算的期数**——已完成（含今天与未来的提前完成）+ 已逾期；
 *    完成率 = 已完成 /（已完成 + 已逾期）。今天的应完成日期既未完成、也未逾期时不进分母
 *    （仍可能完成/逾期）。值色走默认 Primary。
 *
 * 逾期数据走展示口径（[displayOverdueOccurrences]）：仓库落账集合 + 锚日到昨天的实时补算，
 * 跨天驻留不用等下一次写库刷新。日历不挂折叠手势（传入宿主 [rememberDayCalendarFoldState]），
 * 竖向拖拽只滚浮层内容。
 *
 * 顶栏只有右侧「关闭」胶囊（纯展示浮层，无确认动作）；返回键/遮罩/下拉关闭都只请求关闭，
 * 播完退场动画才由宿主卸载（两段式，同其余浮层）。
 */
@Composable
internal fun TodoCompletionSheet(
    visible: Boolean,
    todo: Todo,
    // 点状态球切换某一期的完成状态：true = 补完成（红→绿）、false = 取消完成（绿→红）
    onOccurrenceCompletedChange: (date: IsoDate, completed: Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
) {
    val today = LocalToday
    // 状态色口径见 [completionDayStates]
    val dayStates = remember(todo, today) { todo.completionDayStates(today) }
    // 当前网格窗口（首尾都含补位日期）：初值按今天所在月的网格起点估算，之后由
    // DayCalendar 的 [onGridRangeChange] 回调接管——翻月只取显示中的 42 天，规则可无限展开
    val initialGridStart = remember(today) {
        val (year, month, _) = today.yearMonthDay()
        IsoDate.of(year, month, 1).mondayOfWeek()
    }
    var gridWindow by remember(today) { mutableStateOf(initialGridStart to (initialGridStart + 41)) }
    // 小蓝球 = 重复规则在窗口内的分期 ∪ 已落账的完成/逾期（规则改过后不再产生的历史日期仍算）
    val markedDates = remember(todo, gridWindow) {
        val (first, last) = gridWindow
        val startDay = first.epochDay
        val endDay = last.epochDay
        buildSet {
            todo.completedOccurrences.forEach { if (it.epochDay in startDay..endDay) add(it) }
            todo.overdueOccurrences.forEach { if (it.epochDay in startDay..endDay) add(it) }
            todo.dates.minByOrNull { it.epochDay }?.let { anchor ->
                addAll(todo.repeat.occurrencesBetween(anchor, first, last + 1))
            }
        }
    }
    // 完成情况统计已结算的期数：已完成（含今天与未来的提前完成）+ 已逾期；完成率 =
    // 已完成 / 已结算。今天的应完成日期既未完成、也未逾期时不进分母（仍可能完成/逾期）
    val completedCount = dayStates.count { it.value == DayCalendarDayStatus.Completed }
    val overdueCount = dayStates.count { it.value == DayCalendarDayStatus.Overdue }
    val settledTotal = completedCount + overdueCount
    val percent = if (settledTotal == 0) 0 else completedCount * 100 / settledTotal
    val scope = rememberCoroutineScope()

    BackHandler(enabled = visible, onBack = onDismiss)

    OverlaySheet(
        title = stringResource(R.string.todo_detail_completion),
        // 纯展示浮层：不渲染左侧确认胶囊，只有右侧关闭
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
        onDismissed = onDismissed,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            CardSection {
                DayCalendar(
                    markedDates = markedDates,
                    dayStates = dayStates,
                    // 只有带状态的球可点（已完成/已逾期，含今天与未来的提前完成）：点按
                    // 在完成/未完成间切换；无状态日期不出现按压反馈
                    clickableDates = dayStates.keys,
                    onDateClick = { date ->
                        dayStates[date]?.let { status ->
                            onOccurrenceCompletedChange(
                                date,
                                status != DayCalendarDayStatus.Completed,
                            )
                        }
                    },
                    // 「回到本月」需要非空回调才显示；点击由组件自己收回锚点
                    onBackToToday = {},
                    backAction = DayCalendarBackAction.CurrentMonth,
                    onGridRangeChange = { first, last -> gridWindow = first to last },
                    // 宿主驱动（不传内部手势）：浮层滚动时竖向拖拽只滚内容，不折叠日历
                    foldState = rememberDayCalendarFoldState(scope),
                    today = today,
                )
                SheetInfoCard(
                    entries = listOf(
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_completion_rate),
                            value = stringResource(R.string.todo_completion_percent, percent),
                        ),
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_detail_completed_count),
                            value = stringResource(R.string.todo_count_times, completedCount),
                        ),
                        SheetInfoEntry(
                            label = stringResource(R.string.todo_detail_overdue_count),
                            value = stringResource(R.string.todo_count_times, overdueCount),
                        ),
                    ),
                )
            }
        }
    }
}

/**
 * 完成情况浮层的状态口径：所有已完成期（含今天与未来的提前完成——合并口径下勾选的当期
 * 常常落在今天/下一期）标 [DayCalendarDayStatus.Completed]，锚日到今天之间错过未完成的分期
 * 标 [DayCalendarDayStatus.Overdue]（展示口径，含实时补算）；完成优先——迟到补完成同时
 * 留在逾期集合里的日期仍显完成。
 */
internal fun Todo.completionDayStates(today: IsoDate): Map<IsoDate, DayCalendarDayStatus> {
    val completed = completedOccurrences.toSet()
    val overdue = displayOverdueOccurrences(today)
    return buildMap {
        for (date in completed) put(date, DayCalendarDayStatus.Completed)
        for (date in overdue) {
            if (date !in completed) put(date, DayCalendarDayStatus.Overdue)
        }
    }
}

// ---------- 预览 ----------

/** 完成情况浮层预览：每日重复，已完成/逾期由示例数据现算（含今天与提前完成的一期） */
@Preview(showBackground = true, backgroundColor = 0xFFF2F4F6, widthDp = 390, heightDp = 900)
@Composable
private fun TodoCompletionSheetPreview() = ClassppTheme {
    val today = IsoDate.today()
    TodoCompletionSheet(
        visible = true,
        todo = Todo(
            id = "preview",
            name = "完成情况预览",
            dates = listOf(today - 25),
            repeat = RepeatRule(type = RepeatType.Daily),
            completedOccurrences = listOf(today - 24, today - 22, today - 21, today, today + 1),
            // 其余逾期由 displayOverdueOccurrences 实时补算
            overdueOccurrences = listOf(today - 20),
        ),
        onOccurrenceCompletedChange = { _, _ -> },
        onDismiss = {},
        onDismissed = {},
    )
}
