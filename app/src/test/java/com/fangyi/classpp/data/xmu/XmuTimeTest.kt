package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.IsoDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UTC → Asia/Shanghai 截止换算（指导 §11.1）：跨日、秒截断、非法日期、设备时区无关。
 */
class XmuTimeTest {

    private fun assertDeadline(text: String?, year: Int, month: Int, day: Int, minute: Int) {
        val deadline = XmuTime.parseDeadline(text)!!
        assertEquals("date mismatch for $text", IsoDate.of(year, month, day), deadline.date)
        assertEquals("minute mismatch for $text", minute, deadline.minute)
    }

    @Test
    fun `实测样例 - 10点30分Z 转北京时间 18点30`() = assertDeadline("2026-11-04T10:30:00Z", 2026, 11, 4, 1110)

    @Test
    fun `实测样例 - 15点59分Z 转北京时间 23点59`() = assertDeadline("2026-11-05T15:59:00Z", 2026, 11, 5, 1439)

    @Test
    fun `跨日 - 16点整Z 转次日 0点`() = assertDeadline("2026-11-04T16:00:00Z", 2026, 11, 5, 0)

    @Test
    fun `跨日加秒截断 - 23点59分59秒Z 转次日 7点59`() = assertDeadline("2026-11-04T23:59:59Z", 2026, 11, 5, 479)

    @Test
    fun `无秒字段也接受`() = assertDeadline("2026-11-04T10:30Z", 2026, 11, 4, 1110)

    @Test
    fun `非法输入全部返回 null`() {
        assertNull(XmuTime.parseDeadline(null))
        assertNull(XmuTime.parseDeadline(""))
        assertNull(XmuTime.parseDeadline("2026-02-30T00:00:00Z")) // 不存在的日期
        assertNull(XmuTime.parseDeadline("2026-11-04 10:30:00")) // 非 ISO 形态
        assertNull(XmuTime.parseDeadline("2026-11-04T10:30:00+08:00")) // 带偏移的形态不接受
        assertNull(XmuTime.parseDeadline("2026-13-01T10:30:00Z")) // 月份越界
    }

    @Test
    fun `设备时区改 UTC-5 结果不变（固定东八区）`() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertDeadline("2026-11-04T10:30:00Z", 2026, 11, 4, 1110)
            assertDeadline("2026-11-04T16:00:00Z", 2026, 11, 5, 0)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `isOverdue 按北京时间分钟粒度比较`() {
        // 截止 = 北京时间 2026-11-04 18:30；同一刻不算过期，过一分钟即过期
        val deadline = XmuTime.parseDeadline("2026-11-04T10:30:00Z")!!
        fun millis(minuteOfDay: Int, dayOffset: Int = 0): Long {
            val cal = java.util.GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai"))
            cal.clear()
            cal.set(2026, 10, 4 + dayOffset, minuteOfDay / 60, minuteOfDay % 60, 0)
            return cal.timeInMillis
        }
        assertFalse(XmuTime.isOverdue(deadline, millis(18 * 60 + 29)))
        assertFalse(XmuTime.isOverdue(deadline, millis(18 * 60 + 30)))
        assertTrue(XmuTime.isOverdue(deadline, millis(18 * 60 + 31)))
        assertTrue(XmuTime.isOverdue(deadline, millis(0, dayOffset = 1)))
        assertFalse(XmuTime.isOverdue(deadline, millis(23 * 60 + 59, dayOffset = -1)))
    }
}
