package com.fangyi.classpp.data.notification

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.ui.settings.NotificationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * [NotificationPlanner] 排期纯函数测试：固定「今天 = 2026-10-12（周一）」「现在 = 当天 10:00」，
 * 断言课程周规律/学期边界/提前量、待办三类资格与上限。
 */
class NotificationPlannerTest {

    private val today = IsoDate.of(2026, 10, 12) // 周一
    private val now = localMillis(today, 6 * 60) // 今天 6:00（早于默认节次，全天窗口内都算未来）
    private val texts = NotificationTexts(
        courseText = "%1\$s 上课 · %2\$s",
        courseTextNoLocation = "%1\$s 上课",
        todoDeadlineText = "%1\$s 截止",
        todoStartText = "%1\$s 开始",
        todoEndText = "%1\$s 结束",
        datePrefix = "%1\$d月%2\$d日 ",
    )

    // ——— 课程 ———

    @Test
    fun courseWithinTermPlannedWeekly() {
        val schedule = schedule(
            termStart = IsoDate.of(2026, 10, 12),
            termEnd = IsoDate.of(2026, 11, 8), // 4 周
            course = course(name = "数学", dayOfWeek = 1, weeks = WeekPattern.everyWeek(4), location = "A101"),
        )
        val planned = plan(schedule, emptyList(), settings(), now)
        // 窗口 14 天（10/12–10/25）内：第 1 周（10/12）与第 2 周（10/19）两个周一
        assertEquals(2, planned.size)
        assertEquals("C:c1:${today.epochDay}", planned[0].key)
        assertEquals(
            localMillis(today, 8 * 60 - 30), // 8:00 上课，提前 30 分钟
            planned[0].triggerAtMillis,
        )
        assertEquals(localMillis(today, 8 * 60), planned[0].eventAtMillis) // 实况倒计时目标
        assertEquals(NotificationKind.Course, planned[0].kind)
        assertEquals("数学", planned[0].title)
        assertEquals("8:00-9:40 上课 · A101", planned[0].text)
    }

    @Test
    fun pastOccurrencesSkipped() {
        val schedule = schedule(
            termStart = IsoDate.of(2026, 10, 12),
            termEnd = IsoDate.of(2026, 11, 8),
            course = course(name = "数学", dayOfWeek = 1, weeks = WeekPattern.everyWeek(4)),
        )
        // 现在 = 9:00：今天 7:30 的提醒时刻已过，只排下周
        val planned = plan(schedule, emptyList(), settings(), localMillis(today, 9 * 60))
        assertEquals(listOf("C:c1:${today.plus(7).epochDay}"), planned.map { it.key })
    }

    @Test
    fun parityFiltersWeeks() {
        fun plan(parity: Parity): List<PlannedNotification> = plan(
            schedule(
                termStart = IsoDate.of(2026, 10, 12),
                termEnd = IsoDate.of(2026, 11, 8),
                course = course(
                    name = "体育",
                    dayOfWeek = 3, // 周三：10/14（第 1 周）与 10/21（第 2 周）都在窗口内
                    weeks = WeekPattern(listOf(WeekSegment(1, 4, parity))),
                ),
            ),
            emptyList(),
            settings(lead = 0),
            now,
        )
        // ODD 只排第 1 周的周三、EVEN 只排第 2 周的周三（key 第 3 段 = 日期 epochDay）
        assertEquals(
            listOf(IsoDate.of(2026, 10, 14).epochDay),
            plan(Parity.ODD).map { it.key.split(":")[2].toLong() },
        )
        assertEquals(
            listOf(IsoDate.of(2026, 10, 21).epochDay),
            plan(Parity.EVEN).map { it.key.split(":")[2].toLong() },
        )
    }

    @Test
    fun beforeTermAndAfterTermSkipped() {
        // 学期下周一开始：本周（第 0 周）跳过，窗口内的下周（第 1 周）照排
        val before = plan(
            schedule(
                termStart = IsoDate.of(2026, 10, 19),
                termEnd = IsoDate.of(2026, 11, 15),
                course = course(name = "数学", dayOfWeek = 1, weeks = WeekPattern.everyWeek(4)),
            ),
            emptyList(),
            settings(lead = 0),
            now,
        )
        assertEquals(listOf("C:c1:${today.plus(7).epochDay}"), before.map { it.key })

        // termEnd 在今天之前：总周数 0，学期外全跳过
        val after = plan(
            schedule(
                termStart = IsoDate.of(2026, 9, 1),
                termEnd = IsoDate.of(2026, 10, 11),
                course = course(name = "数学", dayOfWeek = 1, weeks = WeekPattern.everyWeek(6)),
            ),
            emptyList(),
            settings(lead = 0),
            now,
        )
        assertTrue(after.isEmpty())
    }

    @Test
    fun leadCrossesMidnight() {
        val slots = listOf(TimeSlotDef("0:30", "1:00"))
        val planned = plan(
            schedule(
                termStart = IsoDate.of(2026, 10, 12),
                termEnd = IsoDate.of(2026, 11, 8),
                course = course(name = "夜课", dayOfWeek = 2, weeks = WeekPattern.everyWeek(4)),
                slots = slots,
            ),
            emptyList(),
            settings(lead = 60),
            now,
        )
        // 周二（明天）0:30 上课，提前 60 分钟 = 今天 23:30 触发；窗口内还有下周二同一节
        assertEquals(2, planned.size)
        assertEquals(localMillis(today, 23 * 60 + 30), planned[0].triggerAtMillis)
        assertEquals("C:c1:${today.plus(1).epochDay}", planned[0].key)
        assertEquals(localMillis(today.plus(7), 23 * 60 + 30), planned[1].triggerAtMillis)
        assertEquals("C:c1:${today.plus(8).epochDay}", planned[1].key)
    }

    // ——— 待办 ———

    @Test
    fun deadlineReminderWithDatePrefix() {
        val todos = listOf(
            todo(
                id = "t1",
                deadlineDate = IsoDate.of(2026, 10, 14), // 后天
                deadlineMinute = 17 * 60,
            ),
        )
        val planned = plan(
            schedule = null,
            todos = todos,
            settings = settings(todoType = NotificationSettings.TODO_TYPE_DEADLINE),
        )
        assertEquals(1, planned.size)
        assertEquals("T:t1:${IsoDate.of(2026, 10, 14).epochDay}:D", planned[0].key)
        assertEquals(localMillis(IsoDate.of(2026, 10, 14), 17 * 60 - 30), planned[0].triggerAtMillis)
        assertEquals(localMillis(IsoDate.of(2026, 10, 14), 17 * 60), planned[0].eventAtMillis)
        assertEquals(NotificationKind.TodoDeadline, planned[0].kind)
        assertEquals("10月14日 17:00 截止", planned[0].text)
    }

    @Test
    fun startAndEndOnlyForPeriodTodos() {
        val period = todo(
            id = "p1",
            timeKind = TodoTimeKind.Period,
            dates = listOf(IsoDate.of(2026, 10, 13)),
            startMinute = 14 * 60,
            endMinute = 16 * 60,
        )
        val allDay = todo(
            id = "a1",
            timeKind = TodoTimeKind.AllDay,
            dates = listOf(IsoDate.of(2026, 10, 13)),
        )
        val planned = plan(
            schedule = null,
            todos = listOf(period, allDay),
            settings = settings(todoType = NotificationSettings.TODO_TYPE_ALL),
        )
        // 时段待办产出开始/结束两个触发点；全天待办无精确时刻、不参与
        assertEquals(
            listOf("T:p1:${IsoDate.of(2026, 10, 13).epochDay}:S", "T:p1:${IsoDate.of(2026, 10, 13).epochDay}:E"),
            planned.map { it.key },
        )
        assertEquals(localMillis(IsoDate.of(2026, 10, 13), 14 * 60 - 30), planned[0].triggerAtMillis)
        assertEquals(localMillis(IsoDate.of(2026, 10, 13), 16 * 60 - 30), planned[1].triggerAtMillis)
        assertEquals(localMillis(IsoDate.of(2026, 10, 13), 14 * 60), planned[0].eventAtMillis)
        assertEquals(localMillis(IsoDate.of(2026, 10, 13), 16 * 60), planned[1].eventAtMillis)
    }

    @Test
    fun todoTypeSelectsReminderKind() {
        val todo = todo(
            id = "t1",
            deadlineDate = IsoDate.of(2026, 10, 14),
            deadlineMinute = 17 * 60,
            timeKind = TodoTimeKind.Period,
            dates = listOf(IsoDate.of(2026, 10, 13)),
            startMinute = 9 * 60,
            endMinute = 10 * 60,
        )
        fun planned(type: Int) = plan(
            schedule = null,
            todos = listOf(todo),
            settings = settings(todoType = type),
        ).map { it.kind }

        assertEquals(
            listOf(
                NotificationKind.TodoStart,
                NotificationKind.TodoEnd,
                NotificationKind.TodoDeadline,
            ),
            planned(NotificationSettings.TODO_TYPE_ALL),
        )
        assertEquals(listOf(NotificationKind.TodoDeadline), planned(NotificationSettings.TODO_TYPE_DEADLINE))
        assertEquals(listOf(NotificationKind.TodoStart), planned(NotificationSettings.TODO_TYPE_START))
        assertEquals(listOf(NotificationKind.TodoEnd), planned(NotificationSettings.TODO_TYPE_END))
    }

    @Test
    fun completedTodosExcluded() {
        val planned = plan(
            schedule = null,
            todos = listOf(
                todo(id = "t1", deadlineDate = IsoDate.of(2026, 10, 14), deadlineMinute = 17 * 60)
                    .copy(completed = true),
            ),
            settings = settings(),
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun categoryCapLimitsAlarms() {
        // 100 条未来截止 → 截到上限 64
        val todos = (1..100).map { index ->
            todo(
                id = "t$index",
                deadlineDate = IsoDate.of(2026, 10, 20),
                deadlineMinute = 17 * 60,
            )
        }
        val planned = plan(
            schedule = null,
            todos = todos,
            settings = settings(todoType = NotificationSettings.TODO_TYPE_DEADLINE, lead = 0),
        )
        assertEquals(NotificationPlanner.CATEGORY_CAP, planned.size)
    }

    // ——— 开关语义 ———

    @Test
    fun masterOffPlannedNothing() {
        val planned = plan(
            schedule = schedule(
                termStart = IsoDate.of(2026, 10, 12),
                termEnd = IsoDate.of(2026, 11, 8),
                course = course(name = "数学", dayOfWeek = 1, weeks = WeekPattern.everyWeek(4)),
            ),
            todos = listOf(todo(id = "t1", deadlineDate = IsoDate.of(2026, 10, 14), deadlineMinute = 17 * 60)),
            settings = settings(enabled = false),
        )
        assertTrue(planned.isEmpty())
    }

    @Test
    fun categoryTogglesIndependently() {
        val schedule = schedule(
            termStart = IsoDate.of(2026, 10, 12),
            termEnd = IsoDate.of(2026, 11, 8),
            course = course(name = "数学", dayOfWeek = 1, weeks = WeekPattern.everyWeek(4)),
        )
        val todos = listOf(todo(id = "t1", deadlineDate = IsoDate.of(2026, 10, 14), deadlineMinute = 17 * 60))
        val courseOnly = plan(
            schedule,
            todos,
            settings(todoType = NotificationSettings.TODO_TYPE_DEADLINE, courseEnabled = true, todoEnabled = false),
        )
        assertTrue(courseOnly.all { it.kind == NotificationKind.Course })
        val todoOnly = plan(
            schedule,
            todos,
            settings(todoType = NotificationSettings.TODO_TYPE_DEADLINE, courseEnabled = false, todoEnabled = true),
        )
        assertTrue(todoOnly.all { it.kind != NotificationKind.Course })
    }

    // ——— 构造与工具 ———

    private fun settings(
        enabled: Boolean = true,
        lead: Int = 30,
        todoType: Int = NotificationSettings.TODO_TYPE_ALL,
        courseEnabled: Boolean = true,
        todoEnabled: Boolean = true,
    ) = NotificationSettings(
        enabled = enabled,
        courseEnabled = courseEnabled,
        courseLeadMinutes = lead,
        todoEnabled = todoEnabled,
        todoLeadMinutes = lead,
        todoType = todoType,
    )

    private fun schedule(
        termStart: IsoDate,
        termEnd: IsoDate,
        course: CourseEntry,
        slots: List<TimeSlotDef> = DEFAULT_SLOTS,
    ) = Schedule(
        id = "s1",
        name = "测试课表",
        termStart = termStart,
        termEnd = termEnd,
        slots = slots,
        courses = listOf(course),
    )

    private fun course(
        name: String,
        dayOfWeek: Int,
        weeks: WeekPattern,
        location: String = "",
        slots: List<TimeSlotDef> = DEFAULT_SLOTS,
    ) = CourseEntry(
        id = "c1",
        name = name,
        teacher = "",
        location = location,
        dayOfWeek = dayOfWeek,
        startSlot = 1,
        span = 1,
        weeks = weeks,
        color = CourseColor.Blue,
    ).let {
        // slots 影响不到 CourseEntry 本身，参数仅用于测试可读性（时间断言基于 DEFAULT_SLOTS[0]）
        require(slots.isNotEmpty())
        it
    }

    private fun todo(
        id: String,
        deadlineDate: IsoDate? = null,
        deadlineMinute: Int? = null,
        timeKind: TodoTimeKind = TodoTimeKind.None,
        dates: List<IsoDate> = emptyList(),
        startMinute: Int? = null,
        endMinute: Int? = null,
    ) = Todo(
        id = id,
        name = "测试待办",
        dates = dates,
        timeKind = timeKind,
        startMinute = startMinute,
        endMinute = endMinute,
        deadlineDate = deadlineDate,
        deadlineMinute = deadlineMinute,
    )

    private fun plan(
        schedule: Schedule?,
        todos: List<Todo>,
        settings: NotificationSettings,
        nowMillis: Long = now,
    ): List<PlannedNotification> =
        NotificationPlanner.plan(schedule, todos, settings, texts, nowMillis, today)

    private fun localMillis(date: IsoDate, minuteOfDay: Int): Long {
        val utc = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = date.epochDay * 86_400_000L
        }
        return GregorianCalendar().apply {
            clear()
            set(
                utc.get(GregorianCalendar.YEAR),
                utc.get(GregorianCalendar.MONTH),
                utc.get(GregorianCalendar.DAY_OF_MONTH),
                minuteOfDay / 60,
                minuteOfDay % 60,
                0,
            )
        }.timeInMillis
    }
}
