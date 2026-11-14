package com.fangyi.classpp.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.ui.theme.WidgetColors
import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小组件的窗口选择 / 当前节判定 / 边界刷新时间：全部是纯函数，不依赖 Android 运行时。
 */
class TodayCoursesWidgetModelTest {

    private val paletteColors = WidgetColors(
        dark = false,
        surface = Color.White,
        outline = Color.Gray,
        onSurface = Color.Black,
        secondaryText = Color.Gray,
        primary = Color.Blue,
        currentRowBackground = Color.Cyan,
    )

    private val fiveRows = listOf(
        row("a", 8 * 60, 9 * 60 + 40),
        row("b", 10 * 60 + 10, 11 * 60 + 50),
        row("c", 14 * 60 + 30, 16 * 60 + 10),
        row("d", 16 * 60 + 40, 18 * 60 + 20),
        row("e", 19 * 60 + 10, 20 * 60 + 50),
    )

    @Test
    fun ongoingClassIsCenteredInWindow() {
        // 当前节是 c（索引 2）：显示 b + c + d
        assertEquals(
            listOf("b", "c", "d"),
            selectWindow(fiveRows, nowMinute = 15 * 60, limit = 3).map { it.courseId },
        )
    }

    @Test
    fun firstClassHasNoPreviousOne() {
        assertEquals(
            listOf("a", "b", "c"),
            selectWindow(fiveRows, nowMinute = 8 * 60 + 30, limit = 3).map { it.courseId },
        )
    }

    @Test
    fun lastClassBackfillsWindow() {
        assertEquals(
            listOf("c", "d", "e"),
            selectWindow(fiveRows, nowMinute = 20 * 60, limit = 3).map { it.courseId },
        )
    }

    @Test
    fun beforeFirstClassShowsUpcoming() {
        assertEquals(
            listOf("a", "b", "c"),
            selectWindow(fiveRows, nowMinute = 7 * 60, limit = 3).map { it.courseId },
        )
    }

    @Test
    fun afterDayShowsLastWindow() {
        assertEquals(
            listOf("c", "d", "e"),
            selectWindow(fiveRows, nowMinute = 23 * 60, limit = 3).map { it.courseId },
        )
    }

    @Test
    fun smallerAndLargerLimits() {
        assertEquals(
            listOf("b", "c"),
            selectWindow(fiveRows, nowMinute = 15 * 60, limit = 2).map { it.courseId },
        )
        assertEquals(fiveRows, selectWindow(fiveRows, nowMinute = 15 * 60, limit = 6))
        assertTrue(selectWindow(fiveRows, nowMinute = 15 * 60, limit = 0).isEmpty())
    }

    @Test
    fun ongoingBoundariesAreStartInclusiveEndExclusive() {
        val classC = fiveRows[2]
        assertFalse(isOngoing(classC.startMinute, classC.endMinute, classC.startMinute - 1))
        assertTrue(isOngoing(classC.startMinute, classC.endMinute, classC.startMinute))
        assertTrue(isOngoing(classC.startMinute, classC.endMinute, classC.endMinute - 1))
        assertFalse(isOngoing(classC.startMinute, classC.endMinute, classC.endMinute))
    }

    @Test
    fun stateWithoutScheduleCarriesWeekdayOnly() {
        val state = buildTodayCoursesWidgetState(
            schedule = null,
            today = IsoDate.of(2026, 9, 10),
            nowMinute = 12 * 60,
            colors = paletteColors,
        )

        assertFalse(state.hasSchedule)
        assertEquals(4, state.weekday)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun stateClampsWeekNumberAndMarksTheOngoingRow() {
        // 开学前：周号 0（与课表页一致）
        val before = buildTodayCoursesWidgetState(
            schedule = schedule(),
            today = IsoDate.of(2026, 8, 31),
            nowMinute = 0,
            colors = paletteColors,
        )
        assertEquals(0, before.weekNumber)

        // 学期内第 1 周、课程时间 10:10-11:50：10:30 时该行是当前节
        val inTerm = buildTodayCoursesWidgetState(
            schedule = schedule(),
            today = IsoDate.of(2026, 9, 10),
            nowMinute = 10 * 60 + 30,
            colors = paletteColors,
        )
        assertEquals(1, inTerm.weekNumber)
        assertEquals(listOf("c"), inTerm.entries.map { it.courseId })
        assertTrue(inTerm.entries.single().current)
        assertEquals(paletteColors.primary.toArgb(), inTerm.entries.single().barColor)
    }

    @Test
    fun snapshotRoundTripsThroughGlanceStateEncoding() {
        val state = buildTodayCoursesWidgetState(
            schedule = schedule(),
            today = IsoDate.of(2026, 9, 10),
            nowMinute = 10 * 60 + 30,
            colors = paletteColors,
        )

        assertEquals(state, decodeWidgetState(encodeWidgetState(state)))
        assertNull(decodeWidgetState("不是 JSON"))
    }

    @Test
    fun nextRefreshPicksNearestBoundary() {
        val schedule = schedule()
        val now = localMillis(2026, 9, 10, 9, 0)
        val next = localCalendar(nextRefreshMillis(schedule, now))

        assertEquals(10, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, next.get(Calendar.MINUTE))
        assertEquals(10, next.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun nextRefreshSkipsTheMinuteItJustFired() {
        val schedule = schedule()
        val now = localMillis(2026, 9, 10, 10, 10)
        val next = localCalendar(nextRefreshMillis(schedule, now))

        assertEquals(11, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(50, next.get(Calendar.MINUTE))
    }

    @Test
    fun nextRefreshFallsBackToMidnight() {
        val schedule = schedule()
        val now = localMillis(2026, 9, 10, 23, 0)
        val next = localCalendar(nextRefreshMillis(schedule, now))

        assertEquals(11, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
    }

    private fun row(id: String, start: Int, end: Int) = WidgetCourseRow(
        courseId = id,
        name = id,
        location = "",
        barColor = 0,
        startMinute = start,
        endMinute = end,
        current = false,
    )

    private fun schedule(): Schedule = Schedule(
        id = "s",
        name = "测试课表",
        termStart = IsoDate.of(2026, 9, 7),
        termEnd = IsoDate.of(2026, 12, 27),
        courses = listOf(
            CourseEntry(
                id = "c",
                name = "电路原理",
                teacher = "",
                location = "学武楼 A206",
                dayOfWeek = 4,
                startSlot = 2,
                weeks = WeekPattern.everyWeek(16),
                color = CourseColor.Purple,
            ),
        ),
    )

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        GregorianCalendar(year, month - 1, day, hour, minute, 0).timeInMillis

    private fun localCalendar(millis: Long): GregorianCalendar =
        GregorianCalendar().apply { timeInMillis = millis }
}
