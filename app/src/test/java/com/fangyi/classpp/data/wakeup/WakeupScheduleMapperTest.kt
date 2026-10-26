package com.fangyi.classpp.data.wakeup

import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeupScheduleMapperTest {

    //region 周列表 → 周段

    @Test
    fun `week runs split by parity`() {
        assertEquals(
            listOf(WeekSegment(1, 3)),
            WakeupScheduleMapper.weeksToSegments(listOf(1, 2, 3)),
        )
        assertEquals(
            listOf(WeekSegment(1, 7, Parity.ODD)),
            WakeupScheduleMapper.weeksToSegments(listOf(7, 5, 3, 1)),
        )
        assertEquals(
            listOf(WeekSegment(2, 6, Parity.EVEN)),
            WakeupScheduleMapper.weeksToSegments(listOf(2, 4, 6)),
        )
        assertEquals(
            listOf(WeekSegment(1, 2), WeekSegment(4, 5)),
            WakeupScheduleMapper.weeksToSegments(listOf(1, 2, 4, 5)),
        )
        assertEquals(
            listOf(WeekSegment(1, 3, Parity.ODD), WeekSegment(4, 4)),
            WakeupScheduleMapper.weeksToSegments(listOf(1, 3, 4)),
        )
        assertEquals(
            listOf(WeekSegment(1, 3), WeekSegment(6, 10, Parity.EVEN), WeekSegment(11, 11)),
            WakeupScheduleMapper.weeksToSegments(listOf(1, 2, 3, 6, 8, 10, 11)),
        )
        assertEquals(emptyList<WeekSegment>(), WakeupScheduleMapper.weeksToSegments(emptyList()))
        assertEquals(
            listOf(WeekSegment(5, 5)),
            WakeupScheduleMapper.weeksToSegments(listOf(5)),
        )
    }

    //endregion

    //region 载荷 → 课表

    private fun timeSlots(count: Int, startHour: Int = 8): List<WakeupTimeSlot> =
        (1..count).map { n ->
            val hour = startHour + (n - 1)
            WakeupTimeSlot(
                number = n,
                startTime = "%02d:00".format(hour),
                endTime = "%02d:45".format(hour),
            )
        }

    private fun fullPayload() = WakeupSharePayload(
        courses = listOf(
            WakeupCourse(
                name = "高等数学",
                teacher = "张三",
                position = "A101",
                day = 1,
                weeks = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
                startSection = 1,
                endSection = 11, // 超过单卡上限 5：应拆成 1-5 / 6-10 / 11-11 三块
            ),
            WakeupCourse(
                name = "大学英语",
                teacher = "李四",
                position = "B202",
                day = 6,
                weeks = listOf(1, 3, 5, 7), // 单周
                startSection = 3,
                endSection = 3,
            ),
        ),
        timeSlots = timeSlots(12),
        config = WakeupCourseConfig(
            semesterStartDate = "2026-03-02",
            semesterTotalWeeks = 16,
            defaultClassDuration = 45,
            defaultBreakDuration = 10,
        ),
    )

    @Test
    fun `maps payload to schedule with expected fields`() {
        val schedule = WakeupScheduleMapper.toScheduleOrNull(
            fullPayload(),
            "【Wakeup课表】张三的课表，分享口令为「AbCdEf123456」",
            "Wakeup 课表",
        )!!

        assertEquals("张三的课表", schedule.name)
        // 周六有课 → 7 天视图
        assertEquals(7, schedule.daysPerWeek)
        assertEquals(IsoDate.of(2026, 3, 2), schedule.termStart)
        assertEquals(IsoDate.of(2026, 6, 21), schedule.termEnd) // 16 周的周日
        assertEquals(16, schedule.totalWeeks)
        assertEquals(45, schedule.slotDurationMinutes)
        // 时间归一为无前导零形态
        assertEquals("8:00", schedule.slots.first().startTime)
        assertEquals("8:45", schedule.slots.first().endTime)
        assertEquals(12, schedule.slots.size)
    }

    @Test
    fun `spans beyond max are split into contiguous chunks`() {
        val schedule = WakeupScheduleMapper.toScheduleOrNull(fullPayload(), "", "Wakeup 课表")!!
        val math = schedule.courses.filter { it.name == "高等数学" }
        assertEquals(3, math.size)
        assertEquals(listOf(1 to 5, 6 to 10, 11 to 11), math.map { it.startSlot to it.endSlot })
        // 三块共用同一周规律与颜色
        val weeks = math.first().weeks
        assertTrue(math.all { it.weeks == weeks })
        assertTrue(math.map { it.color }.distinct().size == 1)
    }

    @Test
    fun `colors cycle per distinct course name`() {
        val schedule = WakeupScheduleMapper.toScheduleOrNull(fullPayload(), "", "Wakeup 课表")!!
        val colors = schedule.courses.associate { it.name to it.color }
        assertEquals(2, colors.size)
        assertTrue(colors.getValue("高等数学") != colors.getValue("大学英语"))
    }

    @Test
    fun `mapped schedule passes share codec validation`() {
        val json = WakeupScheduleMapper.toShareJsonOrNull(
            fullPayload(),
            "【Wakeup课表】张三的课表，分享口令为「AbCdEf123456」",
            "Wakeup 课表",
        )!!
        val imported = ShareCodec.decodeForImport(json)
        assertTrue("import failed: $imported", imported is ReadResult.Ok)
        val value = (imported as ReadResult.Ok).value
        assertEquals("张三的课表", value.name)
        assertEquals(3, value.courses.count { it.name == "高等数学" })
    }

    @Test
    fun `empty or fully invalid payload maps to null`() {
        assertNull(WakeupScheduleMapper.toScheduleOrNull(WakeupSharePayload(), "", "Wakeup 课表"))
        // 空名 / 星期越界 / 无周次 / 节次非法：全部丢弃 → null
        val invalid = WakeupSharePayload(
            courses = listOf(
                WakeupCourse(name = "  ", day = 1, weeks = listOf(1), startSection = 1, endSection = 2),
                WakeupCourse(name = "数学", day = 9, weeks = listOf(1), startSection = 1, endSection = 2),
                WakeupCourse(name = "英语", day = 2, weeks = emptyList(), startSection = 1, endSection = 2),
                WakeupCourse(name = "物理", day = 3, weeks = listOf(1), startSection = 0, endSection = 0),
            ),
            timeSlots = timeSlots(4),
        )
        assertNull(WakeupScheduleMapper.toScheduleOrNull(invalid, "", "Wakeup 课表"))
    }

    @Test
    fun `missing time slots fall back to generated defaults`() {
        val payload = WakeupSharePayload(
            courses = listOf(
                WakeupCourse(name = "数学", day = 1, weeks = listOf(1, 2), startSection = 1, endSection = 4),
            ),
            timeSlots = emptyList(),
        )
        val schedule = WakeupScheduleMapper.toScheduleOrNull(payload, "", "Wakeup 课表")!!
        assertEquals(4, schedule.slots.size)
        assertEquals("8:00", schedule.slots.first().startTime)
    }

    @Test
    fun `any invalid slot time discards the whole provided schedule`() {
        // 节次编号 = 列表位置，中途缺位会错位：一条非法则整体弃用，改用默认作息
        val payload = WakeupSharePayload(
            courses = listOf(
                WakeupCourse(name = "数学", day = 1, weeks = listOf(1, 2), startSection = 1, endSection = 3),
            ),
            timeSlots = listOf(
                WakeupTimeSlot(number = 1, startTime = "8:00", endTime = "8:45"),
                WakeupTimeSlot(number = 2, startTime = "8点50", endTime = "9:35"),
                WakeupTimeSlot(number = 3, startTime = "10:00", endTime = "10:45"),
            ),
        )
        val schedule = WakeupScheduleMapper.toScheduleOrNull(payload, "", "Wakeup 课表")!!
        assertEquals(3, schedule.slots.size)
        assertEquals("8:00", schedule.slots[0].startTime)
        assertEquals("10:10", schedule.slots[1].startTime) // DEFAULT_SLOTS 第 2 节
    }

    @Test
    fun `courses beyond slot count are dropped`() {
        val payload = WakeupSharePayload(
            courses = listOf(
                WakeupCourse(name = "数学", day = 1, weeks = listOf(1, 2), startSection = 1, endSection = 4),
                // 末节 35 超出 MAX_SLOTS：作息最多续生成到当天放不下为止，仍放不下 → 丢弃
                WakeupCourse(name = "英语", day = 2, weeks = listOf(1, 2), startSection = 4, endSection = 35),
            ),
            timeSlots = timeSlots(4),
            config = WakeupCourseConfig(defaultClassDuration = 45, defaultBreakDuration = 10),
        )
        val schedule = WakeupScheduleMapper.toScheduleOrNull(payload, "", "Wakeup 课表")!!
        // 英语末节 35 超出可生成的作息且放不下 → 丢弃；数学保留
        assertEquals(listOf("数学"), schedule.courses.map { it.name })
        assertTrue(schedule.slots.size >= 4)
        assertTrue(schedule.slots.size < 35)
    }

    @Test
    fun `lenient semester start date parsing`() {
        val payload = fullPayload().copy(
            config = fullPayload().config.copy(semesterStartDate = "2026-3-2"),
        )
        val schedule = WakeupScheduleMapper.toScheduleOrNull(payload, "", "Wakeup 课表")!!
        assertEquals(IsoDate.of(2026, 3, 2), schedule.termStart)
    }

    @Test
    fun `five day view when all courses within weekdays`() {
        val payload = fullPayload().copy(
            courses = listOf(
                WakeupCourse(
                    name = "高等数学",
                    day = 5,
                    weeks = listOf(1, 2),
                    startSection = 1,
                    endSection = 2,
                ),
            ),
        )
        val schedule = WakeupScheduleMapper.toScheduleOrNull(payload, "", "Wakeup 课表")!!
        assertEquals(5, schedule.daysPerWeek)
    }

    @Test
    fun `trailing placeholder slots are trimmed while ordered extras survive`() {
        // Wakeup 对未配置节次下发重叠占位作息：课程用到的节全保留（不移位），
        // 其后第一条无序占位处截断（后面即使有序也不再保留，占位垃圾不进网格）
        val payload = WakeupSharePayload(
            courses = listOf(
                WakeupCourse(name = "数学", day = 1, weeks = listOf(1, 2), startSection = 1, endSection = 2),
            ),
            timeSlots = listOf(
                WakeupTimeSlot(number = 1, startTime = "8:00", endTime = "8:45"),
                WakeupTimeSlot(number = 2, startTime = "8:55", endTime = "9:40"),
                WakeupTimeSlot(number = 3, startTime = "9:50", endTime = "10:35"),
                WakeupTimeSlot(number = 4, startTime = "9:00", endTime = "9:45"),
                WakeupTimeSlot(number = 5, startTime = "10:00", endTime = "10:45"),
            ),
        )
        val schedule = WakeupScheduleMapper.toScheduleOrNull(payload, "", "Wakeup 课表")!!
        assertEquals(3, schedule.slots.size)
        assertEquals("9:50", schedule.slots.last().startTime)
    }

    //endregion
}
