package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.TimeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教务排课结果 → Classpp 课表的映射：作息、周次位图、拆块、开学日推算与丢行规则 */
class XmuJwScheduleMapperTest {

    private val today = IsoDate.of(2026, 10, 31) // 周六，2026-2027 秋季学期第 8 周

    private fun raw(
        slots: List<XmuJwSkjcDto> = XmuJwTestFixtures.slots,
        courses: List<XmuJwPkjgDto> = XmuJwTestFixtures.courses,
        currentZc: Int? = 8,
        label: String = "2026-2027学年 秋季学期",
    ) = XmuJwScheduleRaw(
        semesterLabel = label,
        slots = slots,
        courses = courses,
        currentZc = currentZc,
        fallbackName = "教务课表",
        today = today,
    )

    @Test
    fun `slots drop noon row and keep px order`() {
        val slots = XmuJwScheduleMapper.buildSlots(XmuJwTestFixtures.slots)

        assertEquals(5, slots.size)
        assertEquals("8:00", slots[0].startTime)
        assertEquals(TimeText.format(14 * 60 + 30), slots[4].startTime)
    }

    @Test
    fun `courses map to entries with slot positions and weeks`() {
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw())!!

        // 大物B：节次代码 3..4 → 位置 3..4；1-16 全周
        val dawu = schedule.courses.first { it.name == "大学物理B（下）" }
        assertEquals(2, dawu.dayOfWeek)
        assertEquals(3, dawu.startSlot)
        assertEquals(2, dawu.span)
        assertEquals("李开航", dawu.teacher)
        assertEquals("学武楼（1号楼）A202", dawu.location)
        assertEquals(1..16, dawu.weeks.segments.single().start..dawu.weeks.segments.single().end)
        // 大物实验：节次代码 1..4 → 位置 1..4（跨 4 节不拆），备注随行
        val lab = schedule.courses.first { it.name == "大学物理实验" }
        assertEquals(1, lab.startSlot)
        assertEquals(4, lab.span)
        assertEquals("备注A", lab.note)
    }

    @Test
    fun `term start derives from current week and today`() {
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw())!!

        // 今天 2026-10-31（周六）所在周一 2026-10-26 −（8−1）×7 = 2026-09-07
        assertEquals(IsoDate.of(2026, 9, 7), schedule.termStart)
        // 总周数 = max(位图最大周 16, 当前周 8)；termEnd = 末周周日
        assertEquals(16, schedule.totalWeeks)
        assertEquals(schedule.termStart + 15 * 7 + 6, schedule.termEnd)
    }

    @Test
    fun `missing current week falls back to current monday`() {
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw(currentZc = null))!!

        assertEquals(IsoDate.of(2026, 10, 26), schedule.termStart)
    }

    @Test
    fun `out of range current week is clamped away`() {
        // 教务假期可能给 0/负数/超大值：不可用即兜底，不参与总周数
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw(currentZc = 99))!!

        assertEquals(IsoDate.of(2026, 10, 26), schedule.termStart)
    }

    @Test
    fun `week bitmap builds odd week pattern`() {
        val bitmap = (1..30).joinToString("") { if (it % 2 == 1) "1" else "0" }
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(
            raw(
                courses = listOf(
                    XmuJwPkjgDto(
                        courseName = "形势与政策（3）", teacher = "蔡婧蓉", location = "坤銮楼（2号楼）C206",
                        weekday = 1, startSlotCode = 1, endSlotCode = 2, weekBitmap = bitmap,
                    ),
                ),
                currentZc = 8,
            ),
        )!!

        val segment = schedule.courses.single().weeks.segments.single()
        assertEquals(1, segment.start)
        assertEquals(29, segment.end)
        assertEquals(Parity.ODD, segment.parity)
    }

    @Test
    fun `bitmap bits beyond week 30 are dropped`() {
        val bitmap = buildString { repeat(29) { append('0') }; append("11") }
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(
            raw(
                courses = listOf(
                    XmuJwPkjgDto(
                        courseName = "超长周次课", weekday = 3,
                        startSlotCode = 1, endSlotCode = 1, weekBitmap = bitmap,
                    ),
                ),
            ),
        )!!

        assertEquals(1, schedule.courses.size)
        assertEquals(1, schedule.courses[0].weeks.segments.size)
        assertEquals(30, schedule.courses[0].weeks.segments[0].end)
        assertEquals(30, schedule.totalWeeks)
    }

    @Test
    fun `long span splits into max span chunks with same weeks`() {
        // 7 连节（模拟极端排课）：单卡上限 5 节 → 拆 5+2
        val longSlots = XmuJwTestFixtures.slots +
            XmuJwSkjcDto(code = "6", name = "第6节", order = 6, startTime = 1525, endTime = 1610) +
            XmuJwSkjcDto(code = "7", name = "第7节", order = 7, startTime = 1640, endTime = 1725)
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(
            raw(
                slots = longSlots,
                courses = listOf(
                    XmuJwPkjgDto(
                        courseName = "超长实验", weekday = 6,
                        startSlotCode = 1, endSlotCode = 7, weekBitmap = "111000000000000000000000000000",
                    ),
                ),
            ),
        )!!

        val spans = schedule.courses.map { it.span }
        assertEquals(listOf(5, 2), spans)
        assertEquals(1, schedule.courses[0].startSlot)
        assertEquals(6, schedule.courses[1].startSlot)
        // 拆块共享同一周次
        assertEquals(schedule.courses[0].weeks, schedule.courses[1].weeks)
    }

    @Test
    fun `days per week follows weekend courses`() {
        val weekdayOnly = XmuJwScheduleMapper.toScheduleOrNull(raw())!!
        assertEquals(5, weekdayOnly.daysPerWeek)

        val withWeekend = XmuJwScheduleMapper.toScheduleOrNull(
            raw(courses = XmuJwTestFixtures.courses + XmuJwTestFixtures.courses[0].copy(weekday = 7)),
        )!!
        assertEquals(7, withWeekend.daysPerWeek)
    }

    @Test
    fun `identical rows dedupe into one entry`() {
        val duplicated = XmuJwTestFixtures.courses + XmuJwTestFixtures.courses[0]
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw(courses = duplicated))!!

        assertEquals(2, schedule.courses.size)
    }

    @Test
    fun `same course name shares one color`() {
        val sameCourseTwice = XmuJwTestFixtures.courses +
            XmuJwTestFixtures.courses[0].copy(weekday = 4, weekBitmap = "011000000000000000000000000000")
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw(courses = sameCourseTwice))!!

        assertEquals(
            schedule.courses.first { it.name == "大学物理B（下）" }.color,
            schedule.courses.last { it.name == "大学物理B（下）" }.color,
        )
    }

    @Test
    fun `defective rows are dropped individually`() {
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(
            raw(
                courses = listOf(
                    XmuJwPkjgDto(courseName = " ", weekday = 1), // 空名
                    XmuJwPkjgDto(courseName = "越界星期", weekday = 9), // 星期越界
                    XmuJwPkjgDto(courseName = "未知节次", weekday = 1, startSlotCode = 90, endSlotCode = 91), // 节次对不上
                    XmuJwPkjgDto(courseName = "无周次", weekday = 1, startSlotCode = 1, endSlotCode = 1, weekBitmap = "000000000000000000000000000000"), // 无周次
                    XmuJwPkjgDto(courseName = "引用中午", weekday = 1, startSlotCode = 12, endSlotCode = 12, weekBitmap = "100000000000000000000000000000"), // 中午非课节
                    XmuJwPkjgDto(courseName = "正常课", weekday = 2, startSlotCode = 3, endSlotCode = 4, weekBitmap = "110000000000000000000000000000"),
                ),
            ),
        )!!

        assertEquals(listOf("正常课"), schedule.courses.map { it.name })
    }

    @Test
    fun `empty courses or empty slots yield null`() {
        assertNull(XmuJwScheduleMapper.toScheduleOrNull(raw(courses = emptyList())))
        assertNull(XmuJwScheduleMapper.toScheduleOrNull(raw(slots = emptyList())))
    }

    @Test
    fun `blank label falls back to given name`() {
        val schedule = XmuJwScheduleMapper.toScheduleOrNull(raw(label = " "))!!

        assertEquals("教务课表", schedule.name)
    }
}

/** 测试共用 fixture（镜像真实抓包样本） */
private object XmuJwTestFixtures {
    val slots = listOf(
        XmuJwSkjcDto(code = "1", name = "第1节", order = 1, startTime = 800, endTime = 845),
        XmuJwSkjcDto(code = "2", name = "第2节", order = 2, startTime = 855, endTime = 940),
        XmuJwSkjcDto(code = "3", name = "第3节", order = 3, startTime = 1010, endTime = 1055),
        XmuJwSkjcDto(code = "4", name = "第4节", order = 4, startTime = 1105, endTime = 1150),
        XmuJwSkjcDto(code = "12", name = "中午", order = 12, startTime = 1200, endTime = 1400),
        XmuJwSkjcDto(code = "5", name = "第5节", order = 5, startTime = 1430, endTime = 1515),
    )

    val courses = listOf(
        XmuJwPkjgDto(
            courseName = "大学物理B（下）", courseCode = "C1", teacher = "李开航",
            location = "学武楼（1号楼）A202", weekday = 2,
            startSlotCode = 3, endSlotCode = 4,
            weekBitmap = "111111111111111100000000000000",
        ),
        XmuJwPkjgDto(
            courseName = "大学物理实验", courseCode = "C2", teacher = "程唤龄",
            location = "学武楼（1号楼）C201", weekday = 5,
            startSlotCode = 1, endSlotCode = 4,
            weekBitmap = "110000000000000000000000000000", note = "备注A",
        ),
    )
}
