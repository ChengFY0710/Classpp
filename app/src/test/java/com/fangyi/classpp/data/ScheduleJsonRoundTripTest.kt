package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.SCHEDULE_FORMAT_VERSION
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.SharePayload
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleJsonRoundTripTest {

    private val fullSchedule = Schedule(
        id = "s-uuid-1",
        name = "2026春 课表",
        termStart = IsoDate.parse("2026-03-02"),
        termEnd = IsoDate.parse("2026-06-19"),
        daysPerWeek = 5,
        slots = DEFAULT_SLOTS,
        courses = listOf(
            CourseEntry(
                id = "c-uuid-1", name = "微积分 I-2", teacher = "XX老师", location = "@学武楼 C201",
                dayOfWeek = 3, startSlot = 1, span = 2,
                weeks = WeekPattern(listOf(WeekSegment(1, 8, Parity.ALL), WeekSegment(9, 16, Parity.EVEN))),
                color = CourseColor.Green,
            ),
            CourseEntry(
                id = "c-uuid-2", name = "电路原理", teacher = "XX老师", location = "@学武楼 C404",
                dayOfWeek = 2, startSlot = 3, span = 1,
                weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD))),
                color = CourseColor.Yellow,
            ),
        ),
        showInactiveCourses = false,
    )

    @Test
    fun `schedule roundtrips through storage json`() {
        val json = ScheduleJson.encodeStorage(fullSchedule)
        assertEquals(fullSchedule, ScheduleJson.decodeStorage<Schedule>(json))
    }

    @Test
    fun `schedule roundtrips through share json`() {
        val json = ScheduleJson.encodeShare(fullSchedule)
        assertEquals(fullSchedule, ScheduleJson.decodeShare<Schedule>(json))
    }

    @Test
    fun `iso dates serialize as yyyy-MM-dd strings`() {
        val json = ScheduleJson.encodeStorage(fullSchedule)
        assertTrue(json.contains("\"termStart\":\"2026-03-02\""))
        assertTrue(json.contains("\"termEnd\":\"2026-06-19\""))
    }

    @Test
    fun `unknown future fields are ignored on decode`() {
        val json = ScheduleJson.encodeStorage(fullSchedule)
        val injected = json.replaceFirst(
            "\"name\":",
            "\"futureField\":{\"nested\":42},\"name\":",
        )
        assertEquals(fullSchedule, ScheduleJson.decodeStorage<Schedule>(injected))
    }

    @Test
    fun `defaults are encoded to disk`() {
        val minimal = Schedule(
            id = "s2", name = "min",
            termStart = IsoDate.parse("2026-03-02"),
            termEnd = IsoDate.parse("2026-06-19"),
        )
        val json = ScheduleJson.encodeStorage(minimal)
        assertTrue("daysPerWeek must be persisted", json.contains("\"daysPerWeek\":5"))
        assertTrue("showInactiveCourses must be persisted", json.contains("\"showInactiveCourses\":true"))
        assertTrue("slots must be persisted", json.contains("\"startTime\":\"8:00\""))
        assertEquals(minimal, ScheduleJson.decodeStorage<Schedule>(json))
    }

    @Test
    fun `schedule file envelope roundtrips with formatVersion and active id`() {
        val file = ScheduleFile(
            formatVersion = SCHEDULE_FORMAT_VERSION,
            activeScheduleId = "s-uuid-1",
            schedules = listOf(fullSchedule),
        )
        val json = ScheduleJson.encodeStorage(file)
        assertTrue(json.contains("\"formatVersion\":1"))
        assertTrue(json.contains("\"activeScheduleId\":\"s-uuid-1\""))
        assertEquals(file, ScheduleJson.decodeStorage<ScheduleFile>(json))
    }

    @Test
    fun `share payload envelope roundtrips`() {
        val payload = SharePayload(
            exportedAtMillis = 1789200000000L,
            schedule = fullSchedule,
        )
        val json = ScheduleJson.encodeShare(payload)
        // prettyPrint 输出为 `"key": value`（冒号后有空格）
        assertTrue(json.contains("\"kind\": \"classpp.schedule\""))
        assertTrue(json.contains("\"exportedAtMillis\": 1789200000000"))
        assertEquals(payload, ScheduleJson.decodeShare<SharePayload>(json))
    }

    @Test
    fun `share json is pretty printed while storage is compact`() {
        val shareJson = ScheduleJson.encodeShare(fullSchedule)
        val storageJson = ScheduleJson.encodeStorage(fullSchedule)
        assertTrue("share json should be multi-line", shareJson.contains('\n'))
        assertTrue("storage json should be one line", !storageJson.contains('\n'))
    }

    @Test
    fun `parity and color enums serialize by name`() {
        val json = ScheduleJson.encodeStorage(fullSchedule)
        assertTrue(json.contains("\"parity\":\"EVEN\""))
        assertTrue(json.contains("\"parity\":\"ODD\""))
        assertTrue(json.contains("\"color\":\"Green\""))
        assertTrue(json.contains("\"color\":\"Yellow\""))
    }
}
