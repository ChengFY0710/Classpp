package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单次调课的派生口径：源周次消失、目标周次出现，常规周不受影响；
 * 周投影与日期投影共用同一套规则。
 */
class ScheduleOccurrencesTest {

    private val termStart = IsoDate.parse("2026-03-02") // 周一
    private val termEnd = IsoDate.parse("2026-06-19") // 16 周

    private fun course(
        id: String = "c1",
        day: Int = 3,
        startSlot: Int = 1,
        span: Int = 2,
        weeks: WeekPattern = WeekPattern.everyWeek(16),
    ) = CourseEntry(
        id = id,
        name = "微积分",
        teacher = "XX老师",
        location = "@学武楼 C201",
        dayOfWeek = day,
        startSlot = startSlot,
        span = span,
        weeks = weeks,
        color = CourseColor.Green,
    )

    private fun adjust(
        id: String = "a1",
        courseId: String = "c1",
        fromWeek: Int = 1,
        toWeek: Int = 2,
        toDay: Int = 4,
        toStartSlot: Int = 3,
        toSpan: Int = 2,
    ) = CourseAdjustment(
        id = id,
        courseId = courseId,
        fromWeek = fromWeek,
        toWeek = toWeek,
        toDayOfWeek = toDay,
        toStartSlot = toStartSlot,
        toSpan = toSpan,
    )

    private fun schedule(
        courses: List<CourseEntry> = listOf(course()),
        adjustments: List<CourseAdjustment> = listOf(adjust()),
    ) = Schedule(
        id = "s1",
        name = "2026春",
        termStart = termStart,
        termEnd = termEnd,
        courses = courses,
        adjustments = adjustments,
    )

    @Test
    fun `source week no longer contains the moved occurrence`() {
        val week1 = schedule().courses.occurrencesForWeek(1, termStart, termEnd, schedule().adjustments)
        assertTrue("source occurrence should disappear", week1.isEmpty())
    }

    @Test
    fun `target week injects an adjusted occurrence with effective position`() {
        val occurrences = schedule().courses.occurrencesForWeek(2, termStart, termEnd, schedule().adjustments)
        assertEquals(2, occurrences.size)

        val regular = occurrences.first { !it.adjusted }
        assertEquals(3, regular.dayOfWeek)
        assertEquals(1, regular.startSlot)

        val adjusted = occurrences.first { it.adjusted }
        assertEquals("a1", adjusted.adjustment?.id)
        assertEquals("c1", adjusted.course.id)
        assertEquals(4, adjusted.dayOfWeek)
        assertEquals(3, adjusted.startSlot)
        assertEquals(2, adjusted.span)
        assertEquals(4, adjusted.endSlot)
        // 原条目的位置仍保留在 course 上，供 UI 显示「原 周三 第1-2节」
        assertEquals(3, adjusted.course.dayOfWeek)
        assertEquals(1, adjusted.course.startSlot)
    }

    @Test
    fun `other weeks keep the regular course untouched`() {
        val week3 = schedule().courses.occurrencesForWeek(3, termStart, termEnd, schedule().adjustments)
        assertEquals(1, week3.size)
        assertFalse(week3.single().adjusted)
        assertTrue(week3.single().active)
    }

    @Test
    fun `same week move removes source and only shows the adjusted card`() {
        val sameWeek = adjust(toWeek = 1, toDay = 4, toStartSlot = 3)
        val week1 = schedule(adjustments = listOf(sameWeek))
            .courses
            .occurrencesForWeek(1, termStart, termEnd, listOf(sameWeek))
        assertEquals(1, week1.size)
        assertTrue(week1.single().adjusted)
        assertEquals(4, week1.single().dayOfWeek)
    }

    @Test
    fun `occurrencesOn drops the source date and adds the target date`() {
        val schedule = schedule()
        val sourceDate = termStart + 2 // 第 1 周周三
        val targetDate = termStart + 7 + 3 // 第 2 周周四
        assertTrue(schedule.occurrencesOn(sourceDate).isEmpty())

        val target = schedule.occurrencesOn(targetDate)
        assertEquals(1, target.size)
        assertTrue(target.single().adjusted)
        assertEquals(3, target.single().startSlot)
        assertEquals(4, target.single().endSlot)
    }

    @Test
    fun `occurrencesOn keeps the regular occurrence on its own weekday`() {
        val regularDate = termStart + 7 + 2 // 第 2 周周三
        val occurrences = schedule().occurrencesOn(regularDate)
        assertEquals(1, occurrences.size)
        assertFalse(occurrences.single().adjusted)
        assertEquals(1, occurrences.single().startSlot)
    }

    @Test
    fun `before term ignores adjustments and shows all courses inactive`() {
        val week0 = schedule().courses.occurrencesForWeek(0, termStart, termEnd, schedule().adjustments)
        assertEquals(1, week0.size)
        assertFalse(week0.single().active)
        assertNull(week0.single().adjustment)
    }

    @Test
    fun `moving an odd week occurrence does not suppress the even partner`() {
        val odd = course(weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD))))
        val even = course(
            id = "c2",
            day = 3,
            startSlot = 1,
            span = 2,
            weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.EVEN))),
        )
        // 单周课的第一次（第 1 周）调到第 2 周周四：第 2 周的双周伙伴照常上课
        val adjustment = adjust(fromWeek = 1, toWeek = 2, toDay = 4, toStartSlot = 3)
        val schedule = schedule(courses = listOf(odd, even), adjustments = listOf(adjustment))

        val week2 = schedule.courses.occurrencesForWeek(2, termStart, termEnd, schedule.adjustments)
        val activeWeek2 = week2.filter { it.active }
        assertEquals(setOf("c1", "c2"), activeWeek2.map { it.course.id }.toSet())
        assertTrue(activeWeek2.first { it.course.id == "c1" }.adjusted)
        assertFalse(activeWeek2.first { it.course.id == "c2" }.adjusted)

        // 第 3 周：单周课照常是原课次（没有被第 1 周的调课影响）
        val week3 = schedule.courses.occurrencesForWeek(3, termStart, termEnd, schedule.adjustments)
        val activeWeek3 = week3.filter { it.active }
        assertEquals(listOf("c1"), activeWeek3.map { it.course.id })
        assertFalse(activeWeek3.single().adjusted)
    }

    // ---------- 学期日期边界（开学日周中 / 结束日周中） ----------

    /**
     * 开学日是周中某天时，第 1 周里早于开学日的那几天尚未开学：周投影按真实日期判定
     * active（置灰或隐藏），日期投影（日视图/通知/小组件）直接无课次。
     */
    @Test
    fun `pre term start days in week one are not active`() {
        // 周三 2026-03-04 开学：第 1 周的周一（03-02）在开学前
        val wednesdayStart = Schedule(
            id = "s1",
            name = "2026春",
            termStart = IsoDate.parse("2026-03-04"),
            termEnd = IsoDate.parse("2026-06-19"),
            courses = listOf(course(day = 1), course(id = "wed", day = 3)),
        )

        val week1 = wednesdayStart.courses
            .occurrencesForWeek(1, wednesdayStart.termStart, wednesdayStart.termEnd)
        assertEquals(2, week1.size)
        assertFalse("开学前的周一列不应视为要上课", week1.first { it.course.id == "c1" }.active)
        assertTrue("开学当天的周三列照常上课", week1.first { it.course.id == "wed" }.active)

        // 日期口径同判：开学前的周一无课次，开学当天正常返回
        assertTrue(wednesdayStart.occurrencesOn(IsoDate.parse("2026-03-02")).isEmpty())
        assertEquals(1, wednesdayStart.occurrencesOn(IsoDate.parse("2026-03-04")).size)
    }

    @Test
    fun `occurrencesOn stays empty after termEnd even for hidden weekend courses`() {
        // 5 天课表 + 周日课（隐藏）：termEnd 收到周三后，末周周日不该再产出课次
        // （通知排期/日视图/小组件共用这一口径，学期结束后不再提醒）
        val fiveDayEndWednesday = Schedule(
            id = "s1",
            name = "2026春",
            termStart = termStart,
            termEnd = IsoDate.parse("2026-06-17"), // 第 16 周周三
            daysPerWeek = 5,
            courses = listOf(course(day = 7), course(id = "wed", day = 3)),
        )

        assertTrue(fiveDayEndWednesday.occurrencesOn(IsoDate.parse("2026-06-21")).isEmpty())
        // termEnd 当天与第 1 周照常返回
        assertEquals(1, fiveDayEndWednesday.occurrencesOn(IsoDate.parse("2026-06-17")).size)
        assertEquals(1, fiveDayEndWednesday.occurrencesOn(termStart + 2).size)
        // termStart 之前（开学前的日期）同样无课次
        assertTrue(fiveDayEndWednesday.occurrencesOn(IsoDate.parse("2026-02-25")).isEmpty())
    }

    @Test
    fun `adjustment card targeting a date beyond termEnd is skipped`() {
        // 正常数据被 R15 拦住不会出现目标越界的调课；防御口径：越界目标不上卡
        val endWednesday = schedule(adjustments = emptyList())
            .copy(termEnd = IsoDate.parse("2026-06-17"))
        val beyond = adjust(fromWeek = 1, toWeek = 16, toDay = 7) // 第 16 周周日 > termEnd

        val week16 = endWednesday.courses.occurrencesForWeek(
            16,
            endWednesday.termStart,
            endWednesday.termEnd,
            listOf(beyond),
        )
        assertTrue("目标日期越出学期不应出现调课卡", week16.none { it.adjusted })
    }
}
