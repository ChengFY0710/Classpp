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
}
