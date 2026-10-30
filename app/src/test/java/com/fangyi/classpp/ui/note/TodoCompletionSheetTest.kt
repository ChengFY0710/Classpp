package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.testTodo
import com.fangyi.classpp.ui.components.DayCalendarDayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 完成情况浮层的状态口径（[completionDayStates]）：已完成期按记录上绿——包括今天与未来的
 * 提前完成（合并口径下勾选的就是当期，常落在今天/下一期）；逾期未完成期上红；今天未完成
 * 也未逾期时不进状态表。
 */
class TodoCompletionSheetTest {

    private val today = IsoDate.parse("2026-03-20")

    private fun daily(completed: List<IsoDate> = emptyList()) = testTodo(
        dates = listOf(IsoDate.parse("2026-03-02")),
        repeat = RepeatRule(type = RepeatType.Daily),
        completedOccurrences = completed,
    )

    @Test
    fun `completed occurrences include today and future early completions`() {
        val yesterday = IsoDate.parse("2026-03-19")
        val tomorrow = today + 1
        val states = daily(listOf(yesterday, today, tomorrow)).completionDayStates(today)
        assertEquals(DayCalendarDayStatus.Completed, states[yesterday])
        assertEquals(DayCalendarDayStatus.Completed, states[today])
        assertEquals(DayCalendarDayStatus.Completed, states[tomorrow])
    }

    @Test
    fun `missed occurrences are overdue and completion wins on late completion`() {
        val late = IsoDate.parse("2026-03-10")
        val todo = daily(listOf(late)).copy(overdueOccurrences = listOf(late))
        val states = todo.completionDayStates(today)
        // 迟到补完成：同时留在逾期集合里仍显完成
        assertEquals(DayCalendarDayStatus.Completed, states[late])
        assertEquals(DayCalendarDayStatus.Overdue, states[IsoDate.parse("2026-03-11")])
        // 今天未完成、也未逾期（i.e. 今天的分期还没算错过）：不进状态表
        assertFalse(today in states)
    }

    @Test
    fun `non-repeat todo has no per-occurrence states`() {
        assertTrue(testTodo(completed = true).completionDayStates(today).isEmpty())
    }
}
