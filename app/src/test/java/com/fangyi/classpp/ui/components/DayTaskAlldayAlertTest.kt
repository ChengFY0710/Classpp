package com.fangyi.classpp.ui.components

import com.fangyi.classpp.data.expandRepeatOccurrences
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全天任务提醒卡的候选口径（[allDayAlertCandidates]）与两张提醒卡的让位谓词
 * （[isDayTodoAlertCandidate]）：全天/查看日命中/未完成筛选、DayTodoAlert 优先、
 * 重复分期展开与行序（DayTaskAlldayAlert.kt）。
 */
class DayTaskAlldayAlertTest {

    private val today = IsoDate.of(2026, 10, 28)

    private fun allDay(
        id: String,
        dates: List<IsoDate>,
        repeat: RepeatRule = RepeatRule(),
        completed: Boolean = false,
        completedOccurrences: List<IsoDate> = emptyList(),
        deadline: IsoDate? = null,
        urgency: TodoUrgency = TodoUrgency.None,
    ) = Todo(
        id = id,
        name = id,
        dates = dates,
        repeat = repeat,
        completedOccurrences = completedOccurrences,
        timeKind = TodoTimeKind.AllDay,
        deadlineDate = deadline,
        urgency = urgency,
        completed = completed,
    )

    private fun List<Todo>.ids(): List<String> = map { it.id }

    @Test
    fun allDayTaskOnViewDateIncluded() {
        val todos = listOf(allDay("a", listOf(today)))
        assertEquals(listOf("a"), todos.allDayAlertCandidates(today, today).ids())
    }

    @Test
    fun periodAndNoneTasksExcluded() {
        val todos = listOf(
            allDay("all-day", listOf(today)),
            Todo(
                id = "period",
                name = "period",
                dates = listOf(today),
                timeKind = TodoTimeKind.Period,
                startMinute = 8 * 60,
                endMinute = 9 * 60,
            ),
            Todo(id = "none", name = "none", dates = listOf(today), timeKind = TodoTimeKind.None),
        )
        assertEquals(listOf("all-day"), todos.allDayAlertCandidates(today, today).ids())
    }

    @Test
    fun taskNotOnViewDateExcluded() {
        val todos = listOf(allDay("tomorrow", listOf(today + 1)))
        assertTrue(todos.allDayAlertCandidates(today, today).isEmpty())
    }

    @Test
    fun completedTaskExcluded() {
        val todos = listOf(allDay("done", listOf(today), completed = true))
        assertTrue(todos.allDayAlertCandidates(today, today).isEmpty())
    }

    @Test
    fun multiDateTaskAppearsOnce() {
        val todos = listOf(allDay("multi", listOf(today - 1, today)))
        val result = todos.allDayAlertCandidates(today, today)
        assertEquals(listOf("multi"), result.ids())
        assertEquals(listOf(today - 1, today), result.single().dates)
    }

    @Test
    fun repeatingDailyOnlyViewDateOccurrenceIncluded() {
        val todos = listOf(
            allDay(
                id = "daily",
                dates = listOf(today - 2),
                repeat = RepeatRule(type = RepeatType.Daily),
            ),
        )
        val result = todos.allDayAlertCandidates(today, today)
        assertEquals(listOf("daily"), result.ids())
        assertEquals(listOf(today), result.single().dates)
    }

    @Test
    fun repeatingOccurrenceCompletedOnViewDateExcluded() {
        val todos = listOf(
            allDay(
                id = "daily",
                dates = listOf(today - 2),
                repeat = RepeatRule(type = RepeatType.Daily),
                completedOccurrences = listOf(today),
            ),
        )
        assertTrue(todos.allDayAlertCandidates(today, today).isEmpty())
    }

    @Test
    fun deadlineWithinTodoAlertWindowDeferred() {
        val windowEdge = allDay("edge", listOf(today), deadline = today + 2)
        val outside = allDay("outside", listOf(today), deadline = today + 3)
        val noDeadline = allDay("no-deadline", listOf(today))
        val todos = listOf(windowEdge, outside, noDeadline)
        assertEquals(listOf("outside", "no-deadline"), todos.allDayAlertCandidates(today, today).ids())
    }

    @Test
    fun overdueDeadlineDeferredWhenViewingPastDate() {
        val viewDate = today - 8
        val todos = listOf(allDay("overdue", listOf(viewDate), deadline = today - 3))
        assertTrue(todos.allDayAlertCandidates(today, viewDate).isEmpty())
    }

    @Test
    fun urgencyOrderHighFirst() {
        val todos = listOf(
            allDay("none", listOf(today), urgency = TodoUrgency.None),
            allDay("low", listOf(today), urgency = TodoUrgency.Low),
            allDay("critical", listOf(today), urgency = TodoUrgency.Critical),
        )
        assertEquals(listOf("critical", "low", "none"), todos.allDayAlertCandidates(today, today).ids())
    }

    @Test
    fun noOverlapWithTodoAlertCandidates() {
        val todos = listOf(
            allDay("due-soon", listOf(today), deadline = today),
            allDay("window-edge", listOf(today), deadline = today + 2),
            allDay("far", listOf(today), deadline = today + 3),
            allDay("no-deadline", listOf(today)),
            allDay("overdue", listOf(today), deadline = today - 5),
            allDay(
                id = "daily",
                dates = listOf(today - 2),
                repeat = RepeatRule(type = RepeatType.Daily),
                deadline = today + 1,
            ),
        )
        val alertKeys = todos.expandRepeatOccurrences(today)
            .filter { it.isDayTodoAlertCandidate(today, today) }
            .map { it.id }
            .toSet()
        val allDayKeys = todos.allDayAlertCandidates(today, today).map { it.id }.toSet()
        assertTrue(alertKeys.isNotEmpty())
        assertTrue(allDayKeys.isNotEmpty())
        assertTrue((alertKeys intersect allDayKeys).isEmpty())
    }

    @Test
    fun todoAlertCandidateBoundaries() {
        assertFalse(allDay("no-deadline", listOf(today)).isDayTodoAlertCandidate(today, today))
        assertFalse(
            allDay("completed", listOf(today), completed = true, deadline = today)
                .isDayTodoAlertCandidate(today, today),
        )
        assertTrue(
            allDay("edge", listOf(today), deadline = today + 2)
                .isDayTodoAlertCandidate(today, today),
        )
        assertFalse(
            allDay("outside", listOf(today), deadline = today + 3)
                .isDayTodoAlertCandidate(today, today),
        )
        assertTrue(
            allDay("overdue", listOf(today - 8), deadline = today - 3)
                .isDayTodoAlertCandidate(today, today - 8),
        )
    }

    @Test
    fun overdueNotCarriedToOtherDatesWhenSwitchOff() {
        val viewDate = today + 3
        val overdue = allDay("overdue", listOf(today), deadline = today - 3)
        // 开关开（默认口径）：查看其他日期时逾期拖欠始终在场
        assertTrue(overdue.isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = true))
        assertTrue(overdue.isDayTodoAlertCandidate(today, viewDate))
        // 开关关：只收该日期起两天内截止的，逾期不再跨日期常驻
        assertFalse(overdue.isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false))
        // 未逾期且落在窗口内的仍显示
        assertTrue(
            allDay("due-soon", listOf(today), deadline = viewDate + 2)
                .isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false),
        )
    }

    @Test
    fun deadlineBeforeViewDateHiddenWhenSwitchOff() {
        // 远看未来日期：截止日虽晚于真实今天，但早于查看日，倒计时按锚定日显示「逾期N天」
        val viewDate = today + 11
        val dueBeforeView = allDay("due-before-view", listOf(viewDate), deadline = today + 2)
        assertTrue(dueBeforeView.isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = true))
        assertFalse(dueBeforeView.isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false))
        // 查看日当天截止（0 天）仍显示
        assertTrue(
            allDay("due-that-day", listOf(viewDate), deadline = viewDate)
                .isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false),
        )
    }

    @Test
    fun todayCardHidesOverdueWhenSwitchOff() {
        val overdue = allDay("overdue", listOf(today), deadline = today - 3)
        // 开关开：今天仍按「今明后天 + 逾期」原口径显示逾期拖欠
        assertTrue(overdue.isDayTodoAlertCandidate(today, today, showOverdueTodos = true))
        // 开关关：真实今天也不再显示已逾期待办
        assertFalse(overdue.isDayTodoAlertCandidate(today, today, showOverdueTodos = false))
        // 今天/明天/后天截止的仍显示
        assertTrue(
            allDay("due-today", listOf(today), deadline = today)
                .isDayTodoAlertCandidate(today, today, showOverdueTodos = false),
        )
        assertTrue(
            allDay("due-in-two", listOf(today), deadline = today + 2)
                .isDayTodoAlertCandidate(today, today, showOverdueTodos = false),
        )
        assertFalse(
            allDay("due-in-three", listOf(today), deadline = today + 3)
                .isDayTodoAlertCandidate(today, today, showOverdueTodos = false),
        )
    }

    @Test
    fun viewDateDueTodoStillShownWhenSwitchOff() {
        // 查看过去日期：当天截止（0 天）相对查看日未逾期，开关关闭也照常显示
        val viewDate = today - 8
        assertTrue(
            allDay("due-that-day", listOf(viewDate), deadline = viewDate)
                .isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false),
        )
        assertTrue(
            allDay("due-next-two", listOf(viewDate), deadline = viewDate + 2)
                .isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false),
        )
        // 相对查看日已逾期（截止早于查看日，哪怕晚于真实今天）→ 关闭时不显示
        assertFalse(
            allDay("overdue-before-view", listOf(viewDate), deadline = viewDate - 1)
                .isDayTodoAlertCandidate(today, viewDate, showOverdueTodos = false),
        )
    }

    @Test
    fun disabledSwitchShowsOverdueAllDayTaskOnItsDate() {
        val viewDate = today - 8
        val todos = listOf(allDay("overdue", listOf(viewDate), deadline = today - 3))
        // 开关开：逾期全天任务让位待办提醒卡
        assertTrue(todos.allDayAlertCandidates(today, viewDate).isEmpty())
        // 开关关：待办提醒卡不再收逾期，全天任务卡按查看日正常显示，不丢也不重复
        assertEquals(
            listOf("overdue"),
            todos.allDayAlertCandidates(today, viewDate, showOverdueTodos = false).ids(),
        )
    }
}
