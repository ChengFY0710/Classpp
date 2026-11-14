package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.WeekPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程 / 小组件共用的「当天课程」纯函数：节次换分钟、跨节取末节结束、调课生效。
 */
class DayAgendaTest {

    private val termStart = IsoDate.of(2026, 9, 7) // 周一
    private val termEnd = IsoDate.of(2026, 12, 27)

    @Test
    fun agendaResolvesSlotTimesAndSortsByStart() {
        val schedule = schedule(
            courses = listOf(
                course(id = "late", name = "晚课", day = 4, startSlot = 5),
                course(id = "morning", name = "早课", day = 4, startSlot = 2, span = 2),
            ),
        )

        val agenda = schedule.agendaOn(IsoDate.of(2026, 9, 10)) // 周四，第 1 周

        assertEquals(listOf("morning", "late"), agenda.map { it.courseId })
        // 第 2 节开始、跨 2 节：结束取第 3 节结束（14:30-16:10）
        assertEquals(10 * 60 + 10, agenda[0].startMinute)
        assertEquals(16 * 60 + 10, agenda[0].endMinute)
        assertEquals(19 * 60 + 10, agenda[1].startMinute)
        assertEquals(20 * 60 + 50, agenda[1].endMinute)
    }

    @Test
    fun adjustmentMovesOccurrenceToTargetDayOnly() {
        val course = course(id = "moved", name = "调课", day = 1, startSlot = 1)
        val schedule = schedule(
            courses = listOf(course),
            adjustments = listOf(
                CourseAdjustment(
                    courseId = "moved",
                    fromWeek = 1,
                    toWeek = 1,
                    toDayOfWeek = 4,
                    toStartSlot = 3,
                    toSpan = 1,
                ),
            ),
        )

        val monday = schedule.agendaOn(IsoDate.of(2026, 9, 7))
        val thursday = schedule.agendaOn(IsoDate.of(2026, 9, 10))

        assertTrue("源课次应被调走：$monday", monday.isEmpty())
        assertEquals(1, thursday.size)
        assertEquals(14 * 60 + 30, thursday.single().startMinute)
        assertEquals(16 * 60 + 10, thursday.single().endMinute)
    }

    @Test
    fun invalidSlotTimesAreSkipped() {
        val schedule = schedule(
            slots = listOf(TimeSlotDef("无效", "9:40")),
            courses = listOf(course(id = "bad", name = "坏时间", day = 4, startSlot = 1)),
        )

        assertTrue(schedule.agendaOn(IsoDate.of(2026, 9, 10)).isEmpty())
    }

    @Test
    fun dateOutsideTermHasNoAgenda() {
        val schedule = schedule(courses = listOf(course(id = "c", name = "课", day = 4)))

        assertTrue(schedule.agendaOn(IsoDate.of(2026, 8, 27)).isEmpty())
        assertTrue(schedule.agendaOn(IsoDate.of(2027, 2, 4)).isEmpty())
    }

    private fun schedule(
        slots: List<TimeSlotDef> = com.fangyi.classpp.data.model.DEFAULT_SLOTS,
        courses: List<CourseEntry>,
        adjustments: List<CourseAdjustment> = emptyList(),
    ): Schedule = Schedule(
        id = "s",
        name = "测试课表",
        termStart = termStart,
        termEnd = termEnd,
        slots = slots,
        courses = courses,
        adjustments = adjustments,
    )

    private fun course(
        id: String,
        name: String,
        day: Int,
        startSlot: Int = 1,
        span: Int = 1,
    ): CourseEntry = CourseEntry(
        id = id,
        name = name,
        teacher = "",
        location = "教学楼 A101",
        dayOfWeek = day,
        startSlot = startSlot,
        span = span,
        weeks = WeekPattern.everyWeek(16),
        color = CourseColor.Blue,
    )
}
