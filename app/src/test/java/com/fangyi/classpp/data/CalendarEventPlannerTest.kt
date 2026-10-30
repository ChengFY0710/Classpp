package com.fangyi.classpp.data

import com.fangyi.classpp.data.calendar.CalendarEventPlanner
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TodoTimeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventPlannerTest {

    private val d1 = IsoDate.parse("2026-06-18")
    private val d2 = IsoDate.parse("2026-06-22") // 与 d1 不连续

    @Test
    fun `none time kind yields no drafts`() {
        val todo = testTodo(name = "纯备忘", dates = listOf(d1), timeKind = TodoTimeKind.None)
        assertTrue(CalendarEventPlanner.drafts(todo).isEmpty())
    }

    @Test
    fun `all day yields one draft per date`() {
        val todo = testTodo(name = "运动会", dates = listOf(d1, d2), timeKind = TodoTimeKind.AllDay, location = "田径场")
        val drafts = CalendarEventPlanner.drafts(todo)

        assertEquals(2, drafts.size)
        assertEquals(listOf(d1, d2), drafts.map { it.date })
        drafts.forEach {
            assertTrue(it.allDay)
            assertNull(it.startMinute)
            assertEquals("运动会", it.title)
            assertEquals("田径场", it.location)
        }
    }

    @Test
    fun `period shares the same minutes across dates`() {
        val todo = testTodo(
            name = "补课",
            dates = listOf(d1, d2),
            timeKind = TodoTimeKind.Period,
            startMinute = 540,
            endMinute = 600,
        )
        val drafts = CalendarEventPlanner.drafts(todo)

        assertEquals(2, drafts.size)
        drafts.forEach {
            assertFalse(it.allDay)
            assertEquals(540, it.startMinute)
            assertEquals(600, it.endMinute)
        }
    }

    @Test
    fun `description carries note and step checklist`() {
        val todo = testTodo(
            name = "实验",
            dates = listOf(d1),
            timeKind = TodoTimeKind.AllDay,
            note = "带上数据",
            steps = listOf(testStep("s1", title = "打印讲义"), testStep("s2", title = "写结论", done = true)),
        )
        val description = CalendarEventPlanner.drafts(todo).single().description

        assertEquals("带上数据\n- [ ] 打印讲义\n- [x] 写结论", description)
    }

    @Test
    fun `description is steps only when note blank`() {
        val todo = testTodo(
            name = "实验",
            dates = listOf(d1),
            timeKind = TodoTimeKind.AllDay,
            note = "   ",
            steps = listOf(testStep("s1", title = "打印讲义")),
        )
        assertEquals("- [ ] 打印讲义", CalendarEventPlanner.drafts(todo).single().description)
    }

    @Test
    fun `no steps and no note gives empty description`() {
        val todo = testTodo(name = "实验", dates = listOf(d1), timeKind = TodoTimeKind.AllDay)
        assertEquals("", CalendarEventPlanner.drafts(todo).single().description)
    }
}
