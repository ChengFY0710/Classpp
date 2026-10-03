package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class IsoDateTest {

    @Test
    fun `parse and toString roundtrip`() {
        assertEquals("2026-03-02", IsoDate.parse("2026-03-02").toString())
        assertEquals("1970-01-01", IsoDate.parse("1970-01-01").toString())
        assertEquals("2028-02-29", IsoDate.parse("2028-02-29").toString())
        // 补零年份形态
        assertEquals("0001-01-01", IsoDate.parse("0001-01-01").toString())
    }

    @Test
    fun `invalid date strings are rejected`() {
        listOf("2026-2-1", "2026-13-01", "2026-02-30", "", "2026-03-0", "not-a-date", "2026/03/02")
            .forEach { text ->
                assertNull("should reject: $text", IsoDate.parseOrNull(text))
                assertThrows(IllegalArgumentException::class.java) { IsoDate.parse(text) }
            }
    }

    @Test
    fun `leap day rules`() {
        assertEquals("2028-02-29", IsoDate.parse("2028-02-29").toString())
        assertNull(IsoDate.parseOrNull("2027-02-29"))
    }

    @Test
    fun `iso day of week anchors`() {
        // 1970-01-01 是周四（=4）
        assertEquals(4, IsoDate.parse("1970-01-01").isoDayOfWeek())
        // 项目学期起点 2026-03-02 必须是周一（=1）
        assertEquals(1, IsoDate.parse("2026-03-02").isoDayOfWeek())
        assertEquals(5, IsoDate.parse("2026-03-06").isoDayOfWeek())  // 同周五
        assertEquals(7, IsoDate.parse("2026-03-08").isoDayOfWeek())  // 同周日
        assertEquals(1, IsoDate.parse("2026-03-09").isoDayOfWeek())  // 下周一
    }

    @Test
    fun `mondayOfWeek maps any day to its calendar week's monday`() {
        // 周一不动，周中/周日都回到同一周的周一
        assertEquals("2026-03-02", IsoDate.parse("2026-03-02").mondayOfWeek().toString())
        assertEquals("2026-03-02", IsoDate.parse("2026-03-04").mondayOfWeek().toString())  // 周三
        assertEquals("2026-03-02", IsoDate.parse("2026-03-08").mondayOfWeek().toString())  // 周日
        assertEquals("2026-03-09", IsoDate.parse("2026-03-13").mondayOfWeek().toString())  // 下周周五
        // 跨月/跨年
        assertEquals("2026-02-23", IsoDate.parse("2026-03-01").mondayOfWeek().toString())  // 周日
        assertEquals("2025-12-29", IsoDate.parse("2026-01-01").mondayOfWeek().toString())  // 周四
    }

    @Test
    fun `day arithmetic across months and years`() {
        val a = IsoDate.parse("2026-03-02")
        val b = IsoDate.parse("2026-06-19")
        assertEquals(109L, b - a)
        assertEquals(-109L, a - b)
        assertEquals("2026-06-19", (a + 109).toString())
        assertEquals("2025-03-02", (a - 365).toString())
        assertEquals(7L, IsoDate.parse("2026-03-09") - a)
    }

    @Test
    fun `json serializer encodes as iso string`() {
        val date = IsoDate.parse("2026-03-02")
        val json = ScheduleJson.encodeStorage(date)
        assertEquals("\"2026-03-02\"", json)
        assertEquals(date, ScheduleJson.decodeStorage<IsoDate>(json))
    }

    @Test
    fun `json serializer rejects invalid string`() {
        assertThrows(SerializationException::class.java) {
            ScheduleJson.decodeStorage<IsoDate>("\"2026-02-30\"")
        }
        assertThrows(SerializationException::class.java) {
            ScheduleJson.decodeStorage<IsoDate>("\"garbage\"")
        }
    }

    @Test
    fun `distinct dates are not equal`() {
        assertNotEquals(IsoDate.parse("2026-03-02"), IsoDate.parse("2026-03-03"))
    }
}
