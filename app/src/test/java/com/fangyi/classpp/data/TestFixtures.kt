package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment

/** 测试学期：2026-03-02 周一 ~ 2026-06-19 周五，共 16 整周 */
internal val TestTermStart: IsoDate = IsoDate.parse("2026-03-02")
internal val TestTermEnd: IsoDate = IsoDate.parse("2026-06-19")

/** 7 天模式学期：终止为周日 */
internal val TestTermEnd7: IsoDate = IsoDate.parse("2026-06-21")

internal fun testSchedule(
    id: String = "s1",
    name: String = "2026春",
    daysPerWeek: Int = 5,
    courses: List<CourseEntry> = emptyList(),
    showInactiveCourses: Boolean = true,
    start: IsoDate = TestTermStart,
    end: IsoDate = TestTermEnd,
) = Schedule(
    id = id,
    name = name,
    termStart = start,
    termEnd = end,
    daysPerWeek = daysPerWeek,
    courses = courses,
    showInactiveCourses = showInactiveCourses,
)

internal fun testCourse(
    id: String = "c1",
    name: String = "微积分",
    day: Int = 3,
    startSlot: Int = 1,
    span: Int = 1,
    weeks: WeekPattern = WeekPattern.everyWeek(16),
    color: CourseColor = CourseColor.Green,
) = CourseEntry(
    id = id, name = name, teacher = "XX老师", location = "@学武楼 C201",
    dayOfWeek = day, startSlot = startSlot, span = span, weeks = weeks, color = color,
)

internal fun oddWeeks(end: Int = 16, from: Int = 1): WeekPattern =
    WeekPattern(listOf(WeekSegment(from, end, Parity.ODD)))

internal fun evenWeeks(end: Int = 16, from: Int = 1): WeekPattern =
    WeekPattern(listOf(WeekSegment(from, end, Parity.EVEN)))

/** 可注入固定日期的时钟 */
internal class FakeClock(@Volatile var date: IsoDate) : Clock {
    override fun today(): IsoDate = date
}
