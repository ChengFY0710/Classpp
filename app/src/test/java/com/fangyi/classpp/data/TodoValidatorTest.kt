package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoValidatorTest {

    private val d1 = IsoDate.parse("2026-06-18")
    private val d2 = IsoDate.parse("2026-06-20")

    @Test
    fun `name only todo is valid`() {
        assertEquals(emptyList<TodoError>(), TodoValidator.validate(testTodo(name = "买书")))
    }

    @Test
    fun `blank name rejected`() {
        assertTrue(TodoValidator.validate(testTodo(name = "   ")).contains(TodoError.BlankName))
    }

    @Test
    fun `none time kind allows dates or no dates`() {
        assertEquals(emptyList<TodoError>(), TodoValidator.validate(testTodo(dates = listOf(d1))))
        assertEquals(emptyList<TodoError>(), TodoValidator.validate(testTodo()))
    }

    @Test
    fun `non-none time kind requires at least one date`() {
        val errors = TodoValidator.validate(testTodo(timeKind = TodoTimeKind.AllDay))
        assertTrue(errors.contains(TodoError.DatesRequired(TodoTimeKind.AllDay)))
    }

    @Test
    fun `all day with dates is valid`() {
        val todo = testTodo(dates = listOf(d1, d2), timeKind = TodoTimeKind.AllDay)
        assertEquals(emptyList<TodoError>(), TodoValidator.validate(todo))
    }

    @Test
    fun `period requires start before end within a day`() {
        fun period(start: Int?, end: Int?) = TodoValidator.validate(
            testTodo(dates = listOf(d1), timeKind = TodoTimeKind.Period, startMinute = start, endMinute = end),
        )

        assertTrue(period(null, 600).any { it is TodoError.TimePeriodInvalid })   // 缺开始
        assertTrue(period(600, null).any { it is TodoError.TimePeriodInvalid })   // 缺结束
        assertTrue(period(600, 600).any { it is TodoError.TimePeriodInvalid })    // 起止相等
        assertTrue(period(600, 500).any { it is TodoError.TimePeriodInvalid })    // 逆序
        assertTrue(period(-1, 600).any { it is TodoError.TimePeriodInvalid })     // 越下界
        assertTrue(period(600, 1440).any { it is TodoError.TimePeriodInvalid })   // 越上界（23:59 = 1439）
        assertEquals(emptyList<TodoError>(), period(540, 600))                    // 9:00-10:00
    }

    @Test
    fun `deadline needs date and minute together`() {
        val dateOnly = testTodo(deadlineDate = d1)
        assertTrue(dateOnly.anyErrIs(TodoError.DeadlineInvalid(TodoDeadlineReason.MissingMinute)))

        val minuteOnly = testTodo(deadlineMinute = 600)
        assertTrue(minuteOnly.anyErrIs(TodoError.DeadlineInvalid(TodoDeadlineReason.MissingDate)))

        val outOfRange = testTodo(deadlineDate = d1, deadlineMinute = 1440)
        assertTrue(outOfRange.anyErrIs(TodoError.DeadlineInvalid(TodoDeadlineReason.MinuteOutOfRange)))

        val paired = testTodo(deadlineDate = d1, deadlineMinute = 870)
        assertEquals(emptyList<TodoError>(), TodoValidator.validate(paired))
    }

    @Test
    fun `steps need titles and unique ids`() {
        val blankTitle = testTodo(steps = listOf(testStep(id = "s1", title = "  ")))
        assertTrue(blankTitle.anyErrIs(TodoError.StepInvalid(0, TodoStepReason.BlankTitle)))

        val duplicate = testTodo(
            steps = listOf(testStep(id = "s1"), testStep(id = "s1", title = "另一条")),
        )
        assertTrue(duplicate.anyErrIs(TodoError.StepInvalid(1, TodoStepReason.DuplicateId)))
    }

    @Test
    fun `tags must not be blank`() {
        val todo = testTodo(tags = listOf("课程", "   "))
        assertTrue(todo.anyErrIs(TodoError.TagInvalid(1)))
    }

    @Test
    fun `returns all errors not fail-fast`() {
        val todo = testTodo(
            name = "  ",
            timeKind = TodoTimeKind.Period,
            dates = emptyList(),
            startMinute = 540,
            endMinute = 600,
        )
        val errors = TodoValidator.validate(todo)
        assertEquals(2, errors.size)
        assertTrue(errors.contains(TodoError.BlankName))
        assertTrue(errors.contains(TodoError.DatesRequired(TodoTimeKind.Period)))
    }

    @Test
    fun `fully loaded todo is valid`() {
        val todo = testTodo(
            name = "实验报告",
            dates = listOf(d2, d1),
            timeKind = TodoTimeKind.Period,
            startMinute = 540,
            endMinute = 600,
            deadlineDate = d1,
            deadlineMinute = 1439,
            location = "实验楼 301",
            tags = listOf("物理"),
            urgency = TodoUrgency.Critical,
            note = "带上数据",
            steps = listOf(testStep(id = "s1"), testStep(id = "s2", title = "写结论")),
        )
        assertEquals(emptyList<TodoError>(), TodoValidator.validate(todo))
    }

    private fun Todo.anyErrIs(target: TodoError): Boolean = TodoValidator.validate(this).any {
        // data object / data class 相等比较：错误携带的上下文（index/reason 等）必须一致
        it == target
    }
}
