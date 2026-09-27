package com.fangyi.classpp.data

import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.SCHEDULE_FORMAT_VERSION
import com.fangyi.classpp.data.model.SharePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCodecTest {

    private val sourceCourses = listOf(
        testCourse(id = "c-fixed-1", name = "微积分"),
        testCourse(id = "c-fixed-2", name = "电路原理", day = 2, weeks = oddWeeks()),
    )
    private val source = testSchedule(id = "s-fixed", name = "2026春", courses = sourceCourses)

    @Test
    fun `export and import roundtrip preserves content except ids`() {
        val json = ShareCodec.encode(source, exportedAtMillis = 1789200000000L)

        val imported = when (val r = ShareCodec.decodeForImport(json)) {
            is ReadResult.Ok -> r.value
            is ReadResult.Err -> throw AssertionError("import failed: ${r.error.message}")
        }

        // id 全量重铸
        assertNotEquals(source.id, imported.id)
        assertTrue(source.courses.map { it.id }.none { it in imported.courses.map { c -> c.id } })

        // 内容等价（除 id）
        assertEquals(source.name, imported.name)
        assertEquals(source.termStart, imported.termStart)
        assertEquals(source.termEnd, imported.termEnd)
        assertEquals(source.daysPerWeek, imported.daysPerWeek)
        assertEquals(source.slots, imported.slots)
        assertEquals(source.showInactiveCourses, imported.showInactiveCourses)
        assertEquals(source.courses.map { it.copy(id = "") }, imported.courses.map { it.copy(id = "") })
    }

    @Test
    fun `exported payload carries formatVersion and kind`() {
        val json = ShareCodec.encode(source, exportedAtMillis = 1L)
        assertTrue(json.contains("\"kind\": \"classpp.schedule\""))
        assertTrue(json.contains("\"formatVersion\": $SCHEDULE_FORMAT_VERSION"))
    }

    @Test
    fun `invalid json is rejected`() {
        val r = ShareCodec.decodeForImport("{ not valid json")
        assertTrue(r is ReadResult.Err)
        assertTrue((r as ReadResult.Err).error is ScheduleError.ImportFormatInvalid)

        val notPayload = ShareCodec.decodeForImport("[1,2,3]")
        assertTrue(notPayload is ReadResult.Err)

        val missingField = ShareCodec.decodeForImport("""{"formatVersion":1}""")
        assertTrue(missingField is ReadResult.Err)
        assertTrue((missingField as ReadResult.Err).error is ScheduleError.ImportFormatInvalid)
    }

    @Test
    fun `future formatVersion is rejected`() {
        val json = ScheduleJson.encodeShare(
            SharePayload(formatVersion = 2, exportedAtMillis = 1L, schedule = source),
        )
        val r = ShareCodec.decodeForImport(json)
        assertTrue(r is ReadResult.Err)
        assertTrue((r as ReadResult.Err).error is ScheduleError.ImportVersionUnsupported)
    }

    @Test
    fun `wrong kind is rejected`() {
        val json = ScheduleJson.encodeShare(
            SharePayload(kind = "other.app.data", exportedAtMillis = 1L, schedule = source),
        )
        val r = ShareCodec.decodeForImport(json)
        assertTrue(r is ReadResult.Err)
        assertTrue((r as ReadResult.Err).error is ScheduleError.ImportKindMismatch)
    }

    @Test
    fun `invalid schedule content is rejected without touching library`() {
        // 周数 1..20 超过 16 周学期 → 校验拒绝
        val badCourse = testCourse(
            id = "c1",
            weeks = com.fangyi.classpp.data.model.WeekPattern(
                listOf(com.fangyi.classpp.data.model.WeekSegment(1, 20)),
            ),
        )
        val bad = testSchedule(id = "bad", courses = listOf(badCourse))
        val json = ShareCodec.encode(bad, exportedAtMillis = 1L)
        val r = ShareCodec.decodeForImport(json)
        assertTrue(r is ReadResult.Err)
        assertTrue((r as ReadResult.Err).error is ScheduleError.WeeksBeyondTerm)
    }

    @Test
    fun `duplicate ids inside payload are rebuilt to fresh uuids`() {
        val dup = testCourse(id = "same-id", name = "课A")
        val dup2 = testCourse(id = "same-id", name = "课B", day = 4)
        val payload = testSchedule(id = "p", courses = listOf(dup, dup2))
        val json = ShareCodec.encode(payload, exportedAtMillis = 1L)

        val imported = (ShareCodec.decodeForImport(json) as ReadResult.Ok).value
        val ids = imported.courses.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size)
        assertTrue(ids.none { it == "same-id" })
        assertNotEquals("p", imported.id)
    }

    @Test
    fun `pretty printed json is human readable`() {
        val json = ShareCodec.encode(source, exportedAtMillis = 1L)
        assertTrue(json.lines().size > 10)
        assertTrue(json.contains("\n"))
    }

    @Test
    fun `color survives roundtrip`() {
        val colored = testSchedule(
            id = "col",
            courses = listOf(testCourse(id = "c", color = CourseColor.Purple)),
        )
        val json = ShareCodec.encode(colored, exportedAtMillis = 1L)
        assertTrue(json.contains("\"color\": \"Purple\""))
        val imported = (ShareCodec.decodeForImport(json) as ReadResult.Ok).value
        assertEquals(CourseColor.Purple, imported.courses.single().color)
    }
}
