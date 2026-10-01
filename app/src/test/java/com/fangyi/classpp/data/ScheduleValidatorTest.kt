package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.defaultSlotsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleValidatorTest {

    // 2026-03-02 周一 ~ 2026-06-19 周五，16 整周（diff=109, 109%7=4）
    private val termStart = IsoDate.parse("2026-03-02")
    private val termEnd = IsoDate.parse("2026-06-19")

    private fun schedule(
        courses: List<CourseEntry> = emptyList(),
        daysPerWeek: Int = 5,
        slots: List<TimeSlotDef> = DEFAULT_SLOTS,
        start: IsoDate = termStart,
        end: IsoDate = termEnd,
        name: String = "2026春",
    ) = Schedule(
        id = "s1", name = name, termStart = start, termEnd = end,
        daysPerWeek = daysPerWeek, slots = slots, courses = courses,
    )

    private fun course(
        id: String = "c1",
        day: Int = 3,
        startSlot: Int = 1,
        span: Int = 1,
        weeks: WeekPattern = WeekPattern.everyWeek(16),
        name: String = "微积分",
    ) = CourseEntry(
        id = id, name = name, teacher = "XX老师", location = "@学武楼 C201",
        dayOfWeek = day, startSlot = startSlot, span = span, weeks = weeks, color = CourseColor.Green,
    )

    private inline fun <reified T : ScheduleError> assertHas(errors: List<ScheduleError>) {
        assertTrue(
            "expected ${T::class.simpleName}, got: $errors",
            errors.any { it is T },
        )
    }

    private inline fun <reified T : ScheduleError> assertNo(errors: List<ScheduleError>) {
        assertFalse(
            "did not expect ${T::class.simpleName}, got: $errors",
            errors.any { it is T },
        )
    }

    // ---------- 设置规则 ----------

    @Test
    fun `valid schedule passes with zero errors`() {
        assertEquals(emptyList<ScheduleError>(), ScheduleValidator.validate(schedule()))
    }

    @Test
    fun `R1 start must be monday`() {
        assertHas<ScheduleError.TermNotMonday>(
            ScheduleValidator.validate(schedule(start = IsoDate.parse("2026-03-03"))),
        )
    }

    @Test
    fun `R2 term end may fall on any weekday`() {
        // 周三结尾也合法：学期末周只上到周三
        assertEquals(
            emptyList<ScheduleError>(),
            ScheduleValidator.validate(schedule(end = IsoDate.parse("2026-06-17"))),
        )
        // 周五、周日同样合法
        assertEquals(
            emptyList<ScheduleError>(),
            ScheduleValidator.validate(schedule(end = IsoDate.parse("2026-06-19"))),
        )
        assertEquals(
            emptyList<ScheduleError>(),
            ScheduleValidator.validate(schedule(end = IsoDate.parse("2026-06-21"))),
        )
    }

    @Test
    fun `R2 seven day mode may end on any weekday too`() {
        // 7 天模式不再要求周日结尾
        assertEquals(
            emptyList<ScheduleError>(),
            ScheduleValidator.validate(schedule(daysPerWeek = 7, end = IsoDate.parse("2026-06-19"))),
        )
        assertEquals(
            emptyList<ScheduleError>(),
            ScheduleValidator.validate(schedule(daysPerWeek = 7, end = IsoDate.parse("2026-06-21"))),
        )
    }

    @Test
    fun `R3 only rejects an end before the start`() {
        assertHas<ScheduleError.TermRangeInvalid>(
            ScheduleValidator.validate(
                schedule(start = IsoDate.parse("2026-03-02"), end = IsoDate.parse("2026-02-27")),
            ),
        )
        // 周三结尾不再算"非整周"
        assertNo<ScheduleError.TermRangeInvalid>(
            ScheduleValidator.validate(schedule(end = IsoDate.parse("2026-06-17"))),
        )
    }

    @Test
    fun `totalWeeks counts the week the end date falls in`() {
        // 周中结尾也归到它所在的教学周：周一是第 1 周的第 0 天，周三仍是第 1 周
        assertEquals(1, schedule(end = IsoDate.parse("2026-03-04")).totalWeeks)   // 周三
        assertEquals(1, schedule(end = IsoDate.parse("2026-03-08")).totalWeeks)   // 周日
        // 跨到下一周的第一天（周一）= 第 2 周
        assertEquals(2, schedule(end = IsoDate.parse("2026-03-09")).totalWeeks)
        // 16 周学期的周三 / 周日结尾都仍是第 16 周（周三停在周中，不额外多出一周）
        assertEquals(16, schedule(end = IsoDate.parse("2026-06-17")).totalWeeks)
        assertEquals(16, schedule(end = IsoDate.parse("2026-06-21")).totalWeeks)
    }

    @Test
    fun `R14 a course may not end after the term end`() {
        // 末周只上到周三（2026-06-17）：同周周五的课没了落点
        val wednesdayEnd = schedule(
            end = IsoDate.parse("2026-06-17"),
            courses = listOf(course(day = 5, weeks = WeekPattern.everyWeek(16))),
        )
        assertHas<ScheduleError.TermEndInvalid>(ScheduleValidator.validate(wednesdayEnd))

        // 周一/周三的课仍放得下
        assertNo<ScheduleError.TermEndInvalid>(
            ScheduleValidator.validate(
                wednesdayEnd.copy(
                    courses = listOf(course(day = 3, weeks = WeekPattern.everyWeek(16))),
                ),
            ),
        )
    }

    /**
     * 学期结束日提前后，课程"最后一次上到哪天"必须能报给用户：
     * 7 天课表把结束日收成周三时，周日那门课要能报出 2026-06-21，
     * 否则界面只剩一句没有日期的错误（这里曾经直接把内部 id / 英文 message 漏给用户）。
     */
    @Test
    fun `term end error reports the real last class date`() {
        val errors = ScheduleValidator.validate(
            schedule(
                daysPerWeek = 7,
                end = IsoDate.parse("2026-06-17"),
                courses = listOf(course(day = 7, weeks = WeekPattern.everyWeek(16))),
            ),
        )
        val termEndError = errors.filterIsInstance<ScheduleError.TermEndInvalid>().single()

        assertEquals(IsoDate.parse("2026-06-21"), termEndError.date)
        assertEquals(7, termEndError.dayOfWeek)
        assertEquals("c1", termEndError.courseId)
    }

    @Test
    fun `R4 daysPerWeek only 5 or 7`() {
        assertHas<ScheduleError.DaysPerWeekInvalid>(ScheduleValidator.validate(schedule(daysPerWeek = 6)))
        assertHas<ScheduleError.DaysPerWeekInvalid>(ScheduleValidator.validate(schedule(daysPerWeek = 4)))
        assertNo<ScheduleError.DaysPerWeekInvalid>(ScheduleValidator.validate(schedule(daysPerWeek = 7,
            end = IsoDate.parse("2026-06-21"))))
    }

    @Test
    fun `R5 slot count bounds`() {
        assertHas<ScheduleError.SlotCountInvalid>(ScheduleValidator.validate(schedule(slots = emptyList())))
        assertHas<ScheduleError.SlotCountInvalid>(ScheduleValidator.validate(schedule(slots = defaultSlotsFor(13))))
        assertNo<ScheduleError.SlotCountInvalid>(ScheduleValidator.validate(schedule(slots = defaultSlotsFor(12))))
    }

    @Test
    fun `R6 slot time format`() {
        val bad = listOf(TimeSlotDef("8:60", "9:40"))
        assertHas<ScheduleError.SlotTimeFormatInvalid>(ScheduleValidator.validate(schedule(slots = bad)))
        val bad2 = listOf(TimeSlotDef("8:00", "abc"))
        assertHas<ScheduleError.SlotTimeFormatInvalid>(ScheduleValidator.validate(schedule(slots = bad2)))
    }

    @Test
    fun `R7 slot order and overlap`() {
        // 倒序
        assertHas<ScheduleError.SlotOrderInvalid>(
            ScheduleValidator.validate(schedule(slots = listOf(TimeSlotDef("9:40", "8:00")))),
        )
        // 上一节结束晚于下一节开始（重叠）
        assertHas<ScheduleError.SlotOrderInvalid>(
            ScheduleValidator.validate(
                schedule(
                    slots = listOf(TimeSlotDef("8:00", "10:30"), TimeSlotDef("10:10", "11:50")),
                ),
            ),
        )
        // 首尾相接（0 分钟课间）合法
        assertNo<ScheduleError.SlotOrderInvalid>(
            ScheduleValidator.validate(
                schedule(slots = listOf(TimeSlotDef("8:00", "9:40"), TimeSlotDef("9:40", "11:10"))),
            ),
        )
    }

    @Test
    fun `R8 blank schedule name`() {
        assertHas<ScheduleError.InvalidScheduleName>(ScheduleValidator.validate(schedule(name = "  ")))
    }

    // ---------- 课程字段 ----------

    @Test
    fun `R9 day of week range respects daysPerWeek`() {
        // 5 天模式不允许周六/周日：课程星期越界单独成 DayOutOfWeek，便于 UI 说清"切5天被谁挡住"
        assertHas<ScheduleError.DayOutOfWeek>(
            ScheduleValidator.validate(schedule(courses = listOf(course(day = 6)))),
        )
        assertHas<ScheduleError.DayOutOfWeek>(
            ScheduleValidator.validate(schedule(courses = listOf(course(day = 7)))),
        )
        // 7 天模式允许周末（结束日改到周日，免得撞上"课排在学期结束之后"）
        val ok = ScheduleValidator.validate(
            schedule(
                courses = listOf(course(day = 7, weeks = WeekPattern.everyWeek(16))),
                daysPerWeek = 7,
                end = IsoDate.parse("2026-06-21"),
            ),
        )
        assertEquals(emptyList<ScheduleError>(), ok)
    }

    @Test
    fun `R10 span bounds`() {
        // 5 节制下 slot4+span5 → endSlot 8 越界
        assertHas<ScheduleError.CourseFieldInvalid>(
            ScheduleValidator.validate(schedule(courses = listOf(course(startSlot = 4, span = 5)))),
        )
        // span 上限 5（6 节制下也不允许 span=6）
        val sixSlots = schedule(
            courses = listOf(course(startSlot = 1, span = 6)),
            slots = defaultSlotsFor(6),
        )
        assertHas<ScheduleError.CourseFieldInvalid>(ScheduleValidator.validate(sixSlots))
        // startSlot=0 非法
        assertHas<ScheduleError.CourseFieldInvalid>(
            ScheduleValidator.validate(schedule(courses = listOf(course(startSlot = 0)))),
        )
        // 恰好用满：slot1+span5 → endSlot 5 合法
        val full = schedule(courses = listOf(course(startSlot = 1, span = 5)))
        assertNo<ScheduleError.CourseFieldInvalid>(ScheduleValidator.validate(full))
    }

    @Test
    fun `R11 weeks beyond term and segment shape`() {
        // 总周数 16，weeks 到 17 → 拒
        assertHas<ScheduleError.WeeksBeyondTerm>(
            ScheduleValidator.validate(
                schedule(courses = listOf(course(weeks = WeekPattern(listOf(WeekSegment(1, 17)))))),
            ),
        )
        // start > end → 段形态错误
        assertHas<ScheduleError.WeekSegmentInvalid>(
            ScheduleValidator.validate(
                schedule(courses = listOf(course(weeks = WeekPattern(listOf(WeekSegment(8, 3)))))),
            ),
        )
        // start < 1 → 段形态错误
        assertHas<ScheduleError.WeekSegmentInvalid>(
            ScheduleValidator.validate(
                schedule(courses = listOf(course(weeks = WeekPattern(listOf(WeekSegment(0, 4)))))),
            ),
        )
        // 恰好到 16 合法
        assertNo<ScheduleError.WeeksBeyondTerm>(
            ScheduleValidator.validate(
                schedule(courses = listOf(course(weeks = WeekPattern(listOf(WeekSegment(1, 16)))))),
            ),
        )
    }

    @Test
    fun `R8 blank course name`() {
        assertHas<ScheduleError.CourseFieldInvalid>(
            ScheduleValidator.validate(schedule(courses = listOf(course(name = "   ")))),
        )
    }

    // ---------- R12 同格周数重叠矩阵 ----------

    @Test
    fun `R12 same day span intersect with intersecting weeks conflicts`() {
        val a = course(id = "a", day = 3, startSlot = 1, span = 2)
        // 完全包含：slot2 与 a 的 slot1-2 相交
        val b = course(id = "b", day = 3, startSlot = 2, span = 1)
        val errors = ScheduleValidator.validate(schedule(courses = listOf(a, b)))
        assertHas<ScheduleError.GridConflict>(errors)

        // 共用同一格
        val c = course(id = "c", day = 3, startSlot = 1, span = 1)
        val d = course(id = "d", day = 3, startSlot = 1, span = 1)
        assertHas<ScheduleError.GridConflict>(ScheduleValidator.validate(schedule(courses = listOf(c, d))))
    }

    @Test
    fun `R12 odd and even weeks do not conflict`() {
        val odd = course(id = "a", day = 3, startSlot = 1,
            weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD))))
        val even = course(id = "b", day = 3, startSlot = 1,
            weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.EVEN))))
        assertNo<ScheduleError.GridConflict>(ScheduleValidator.validate(schedule(courses = listOf(odd, even))))
    }

    @Test
    fun `R12 ALL week course conflicts with ODD subset`() {
        val all = course(id = "a", day = 3, startSlot = 1,
            weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.ALL))))
        val odd = course(id = "b", day = 3, startSlot = 1,
            weeks = WeekPattern(listOf(WeekSegment(1, 8, Parity.ODD))))
        assertHas<ScheduleError.GridConflict>(ScheduleValidator.validate(schedule(courses = listOf(all, odd))))
    }

    @Test
    fun `R12 adjacent spans do not conflict`() {
        val a = course(id = "a", day = 3, startSlot = 1, span = 1)
        val b = course(id = "b", day = 3, startSlot = 2, span = 1)
        assertNo<ScheduleError.GridConflict>(ScheduleValidator.validate(schedule(courses = listOf(a, b))))
    }

    @Test
    fun `R12 different days never conflict`() {
        val a = course(id = "a", day = 2, startSlot = 1, span = 2)
        val b = course(id = "b", day = 4, startSlot = 1, span = 2)
        assertNo<ScheduleError.GridConflict>(ScheduleValidator.validate(schedule(courses = listOf(a, b))))
    }

    @Test
    fun `R12 disjoint weeks same cell pass`() {
        val first = course(id = "a", day = 3, startSlot = 1,
            weeks = WeekPattern(listOf(WeekSegment(1, 8))))
        val second = course(id = "b", day = 3, startSlot = 1,
            weeks = WeekPattern(listOf(WeekSegment(9, 16))))
        assertNo<ScheduleError.GridConflict>(ScheduleValidator.validate(schedule(courses = listOf(first, second))))
    }

    // ---------- 整体行为 ----------

    @Test
    fun `returns all errors not fail-fast`() {
        val errors = ScheduleValidator.validate(
            schedule(
                name = "  ",
                daysPerWeek = 6,
                start = IsoDate.parse("2026-03-03"),
                courses = listOf(course(name = "")),
            ),
        )
        assertHas<ScheduleError.InvalidScheduleName>(errors)
        assertHas<ScheduleError.TermNotMonday>(errors)
        assertHas<ScheduleError.DaysPerWeekInvalid>(errors)
        assertHas<ScheduleError.CourseFieldInvalid>(errors)
        assertTrue("expected multiple errors, got $errors", errors.size >= 3)
    }

    @Test
    fun `grid conflict detected even when settings invalid`() {
        val errors = ScheduleValidator.validate(
            schedule(
                daysPerWeek = 6,
                courses = listOf(
                    course(id = "a", startSlot = 1, span = 2),
                    course(id = "b", startSlot = 2, span = 1),
                ),
            ),
        )
        assertHas<ScheduleError.GridConflict>(errors)
    }
}
