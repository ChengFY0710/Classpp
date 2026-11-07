package com.fangyi.classpp.data.alarm

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlarmClockPlannerTest {

    private val d1 = IsoDate.parse("2026-06-18")

    @Test
    fun `deadline minute wins over start minute`() {
        val todo = testTodo(
            name = "交报告",
            dates = listOf(d1),
            timeKind = TodoTimeKind.Period,
            startMinute = 8 * 60,
            endMinute = 9 * 60,
            deadlineDate = d1,
            deadlineMinute = 23 * 60 + 30,
        )

        assertEquals(23 * 60 + 30, AlarmClockPlanner.minuteOf(todo))
    }

    @Test
    fun `falls back to start minute without deadline minute`() {
        val todo = testTodo(
            name = "晨读",
            dates = listOf(d1),
            timeKind = TodoTimeKind.Period,
            startMinute = 7 * 60,
            endMinute = 8 * 60,
        )

        assertEquals(7 * 60, AlarmClockPlanner.minuteOf(todo))
    }

    @Test
    fun `returns null when neither deadline nor start minute exists`() {
        val todo = testTodo(name = "随手记", dates = listOf(d1), timeKind = TodoTimeKind.AllDay)

        assertNull(AlarmClockPlanner.minuteOf(todo))
    }

    @Test
    fun `repeat deadline keeps the stored minute`() {
        // 「下一次重复开始前」只折算日期，时刻沿用存储值：映射结果不受影响
        val todo = testTodo(
            name = "周报",
            dates = listOf(d1),
            deadlineDate = d1,
            deadlineMinute = 18 * 60,
            deadlineBeforeRepeat = true,
            deadlineBeforeDays = 2,
        )

        assertEquals(18 * 60, AlarmClockPlanner.minuteOf(todo))
    }
}
