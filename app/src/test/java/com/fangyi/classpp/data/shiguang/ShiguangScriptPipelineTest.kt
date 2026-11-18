package com.fangyi.classpp.data.shiguang

import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.WeekSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拾光脚本结果解析 → 课表映射 → 分享 JSON 校验的完整链路 */
class ShiguangScriptPipelineTest {

    private val okJson = """
        {
          "ok": true,
          "unknownField": "ignored",
          "courses": [
            {
              "name": "高等数学",
              "teacher": "张三",
              "position": "A101",
              "day": 1,
              "weeks": [1, 2, 3, 4, 5, 6, 7, 8],
              "startSection": 1,
              "endSection": 6
            },
            {
              "name": "大学英语",
              "teacher": "李四",
              "position": "B202",
              "day": 6,
              "weeks": [1, 3, 5, 7],
              "startSection": 3,
              "endSection": 3
            },
            {
              "name": "期末考试",
              "day": 3,
              "weeks": [9],
              "isCustomTime": true,
              "customStartTime": "19:00",
              "customEndTime": "21:00"
            }
          ],
          "timeSlots": [
            { "number": 1, "startTime": "08:00", "endTime": "08:45" },
            { "number": 2, "startTime": "09:00", "endTime": "09:45" },
            { "number": 3, "startTime": "10:00", "endTime": "10:45" },
            { "number": 4, "startTime": "11:00", "endTime": "11:45" },
            { "number": 5, "startTime": "12:00", "endTime": "12:45" },
            { "number": 6, "startTime": "13:00", "endTime": "13:45" }
          ],
          "config": {
            "semesterStartDate": "2026-03-02",
            "semesterTotalWeeks": 16,
            "defaultClassDuration": 45,
            "defaultBreakDuration": 10
          }
        }
    """.trimIndent()

    @Test
    fun `ok result maps to a valid schedule`() {
        val result = parseShiguangScriptResult(okJson)!!
        assertTrue(result.ok)

        val schedule = ShiguangScheduleMapper.toScheduleOrNull(result, "重庆大学")!!
        assertEquals("重庆大学", schedule.name)
        assertEquals(IsoDate.of(2026, 3, 2), schedule.termStart)
        assertEquals(IsoDate.of(2026, 6, 21), schedule.termEnd)
        assertEquals(16, schedule.totalWeeks)
        assertEquals(45, schedule.slotDurationMinutes)
        assertEquals(7, schedule.daysPerWeek) // 周六有课
        assertEquals(6, schedule.slots.size)

        // 6 节跨度超过单卡上限 5：拆成 1-5 / 6-6；自定义时间的考试被丢弃
        val math = schedule.courses.filter { it.name == "高等数学" }
        assertEquals(listOf(1 to 5, 6 to 6), math.map { it.startSlot to it.endSlot })
        val english = schedule.courses.single { it.name == "大学英语" }
        assertEquals(
            listOf(WeekSegment(1, 7, Parity.ODD)),
            english.weeks.segments,
        )
        assertEquals(3, schedule.courses.size)
    }

    @Test
    fun `mapped schedule passes share codec validation`() {
        val schedule = ShiguangScheduleMapper.toScheduleOrNull(
            parseShiguangScriptResult(okJson)!!,
            "重庆大学",
        )!!
        val json = ShareCodec.encode(schedule, System.currentTimeMillis())
        val imported = ShareCodec.decodeForImport(json)
        assertTrue("import failed: $imported", imported is ReadResult.Ok)
        assertEquals(3, (imported as ReadResult.Ok).value.courses.size)
    }

    @Test
    fun `error result is parsed and not mapped`() {
        val result = parseShiguangScriptResult(
            """{"ok":false,"errorCode":"NO_COURSES","error":""}""",
        )!!
        assertEquals(ShiguangErrorCode.NO_COURSES, result.errorCode)
        assertNull(ShiguangScheduleMapper.toScheduleOrNull(result, "重庆大学"))
    }

    @Test
    fun `empty course list is not mapped`() {
        val result = parseShiguangScriptResult("""{"ok":true,"courses":[]}""")!!
        assertNull(ShiguangScheduleMapper.toScheduleOrNull(result, "重庆大学"))
    }

    @Test
    fun `malformed json is rejected`() {
        assertNull(parseShiguangScriptResult("not json"))
        assertNull(parseShiguangScriptResult(""))
    }
}
