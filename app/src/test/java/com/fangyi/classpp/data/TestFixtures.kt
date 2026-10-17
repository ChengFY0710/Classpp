package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
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

// ---------- 待办 ----------

/** 可注入固定毫秒时刻的时钟（TodoRepository.nowMillis） */
internal class FakeNow(var millis: Long = 1_000_000L) : () -> Long {
    override fun invoke(): Long = millis
    fun advanceBy(by: Long) { millis += by }
}

internal fun testStep(id: String = "st1", title: String = "做第 1 题", done: Boolean = false) =
    TodoStep(id = id, title = title, done = done)

internal fun testTodo(
    id: String = "",
    name: String = "高数作业",
    dates: List<IsoDate> = emptyList(),
    repeat: RepeatRule = RepeatRule(),
    completedOccurrences: List<IsoDate> = emptyList(),
    timeKind: TodoTimeKind = TodoTimeKind.None,
    startMinute: Int? = null,
    endMinute: Int? = null,
    deadlineDate: IsoDate? = null,
    deadlineMinute: Int? = null,
    deadlineBeforeRepeat: Boolean = false,
    deadlineBeforeDays: Int = 0,
    location: String = "",
    tags: List<String> = emptyList(),
    urgency: TodoUrgency = TodoUrgency.Low,
    note: String = "",
    steps: List<TodoStep> = emptyList(),
    completed: Boolean = false,
    completedAtMillis: Long? = null,
    createdAtMillis: Long = 0,
    group: String? = null,
    countdown: Boolean = false,
) = Todo(
    id = id,
    name = name,
    dates = dates,
    repeat = repeat,
    completedOccurrences = completedOccurrences,
    timeKind = timeKind,
    startMinute = startMinute,
    endMinute = endMinute,
    deadlineDate = deadlineDate,
    deadlineMinute = deadlineMinute,
    deadlineBeforeRepeat = deadlineBeforeRepeat,
    deadlineBeforeDays = deadlineBeforeDays,
    location = location,
    tags = tags,
    urgency = urgency,
    note = note,
    steps = steps,
    completed = completed,
    completedAtMillis = completedAtMillis,
    createdAtMillis = createdAtMillis,
    group = group,
    countdown = countdown,
)

/** FakeNow 毫秒值对应的本地日历日期（正午锚定，避开时区/DST 边界）；测试里推时钟用 */
internal fun millisOf(date: IsoDate): Long {
    val utc = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = date.epochDay * 86_400_000L
    }
    return java.util.GregorianCalendar().apply {
        clear()
        set(
            utc.get(java.util.GregorianCalendar.YEAR),
            utc.get(java.util.GregorianCalendar.MONTH),
            utc.get(java.util.GregorianCalendar.DAY_OF_MONTH),
            12, 0, 0,
        )
    }.timeInMillis
}
