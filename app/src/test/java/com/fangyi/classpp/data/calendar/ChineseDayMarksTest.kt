package com.fangyi.classpp.data.calendar

import com.fangyi.classpp.data.model.IsoDate
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日程页节日/节气文案：覆盖农历节日、除夕大小月、24 节气、重合日优先级与数据范围。
 * 期望日期均已用寿星天文历公开实现交叉核对。
 */
class ChineseDayMarksTest {

    private fun mark(year: Int, month: Int, day: Int): String? =
        ChineseDayMarks.markOf(IsoDate.of(year, month, day))

    @Test
    fun `lunar festivals`() {
        assertEquals("春节", mark(2024, 2, 10))
        assertEquals("元宵", mark(2024, 2, 24))
        assertEquals("端午", mark(2024, 6, 10))
        assertEquals("七夕", mark(2024, 8, 10))
        assertEquals("中秋", mark(2024, 9, 17))
        assertEquals("重阳", mark(2024, 10, 11))
        assertEquals("腊八", mark(2024, 1, 18))
        assertEquals("腊八", mark(2025, 1, 7))
        assertEquals("腊八", mark(2026, 1, 26))
    }

    @Test
    fun `new years eve handles 29 and 30 day twelfth months`() {
        // 2023 年腊月 30 天
        assertEquals("除夕", mark(2024, 2, 9))
        // 2024、2025 年腊月 29 天
        assertEquals("除夕", mark(2025, 1, 28))
        assertEquals("除夕", mark(2026, 2, 16))
        // 次日即春节
        assertEquals("春节", mark(2024, 2, 10))
        assertEquals("春节", mark(2025, 1, 29))
        assertEquals("春节", mark(2026, 2, 17))
    }

    @Test
    fun `fixed date festivals`() {
        assertEquals("元旦", mark(2026, 1, 1))
        assertEquals("国庆", mark(2026, 10, 1))
    }

    @Test
    fun `solar terms`() {
        assertEquals("立春", mark(2024, 2, 4))
        assertEquals("清明", mark(2024, 4, 4))
        assertEquals("立春", mark(2025, 2, 3))
        assertEquals("霜降", mark(2026, 10, 23))
        assertEquals("冬至", mark(2026, 12, 22))
    }

    @Test
    fun `ordinary days have no mark`() {
        assertNull(mark(2026, 10, 22))
        assertNull(mark(2026, 10, 24))
        assertNull(mark(2024, 2, 3))
        assertNull(mark(2024, 4, 3))
    }

    @Test
    fun `festival wins over solar term on overlapping days`() {
        // 2030-06-05 端午 + 芒种
        assertEquals("芒种", ChineseDayMarks.solarTermOf(IsoDate.of(2030, 6, 5)))
        assertEquals("端午", mark(2030, 6, 5))
        // 2027-10-08 重阳 + 寒露
        assertEquals("寒露", ChineseDayMarks.solarTermOf(IsoDate.of(2027, 10, 8)))
        assertEquals("重阳", mark(2027, 10, 8))
    }

    @Test
    fun `leap months do not repeat festivals`() {
        // 2025 闰六月初一、2028 闰五月初五都不是节日
        assertNull(mark(2025, 7, 25))
        assertNull(mark(2028, 6, 27))
    }

    @Test
    fun `solar terms close to midnight use corrected dates`() {
        // 低精度太阳黄经的 12 个贴午夜修正中的代表项
        assertEquals("惊蛰", mark(2014, 3, 6))
        assertNull(mark(2014, 3, 5))
        assertEquals("小暑", mark(2016, 7, 7))
        assertNull(mark(2016, 7, 6))
        assertEquals("雨水", mark(1923, 2, 19))
        assertNull(mark(1923, 2, 20))
        assertEquals("冬至", mark(1951, 12, 23))
    }

    @Test
    fun `out of range dates have no mark`() {
        assertNull(mark(1899, 12, 31))
        assertNull(mark(2102, 1, 1))
    }

    @Test
    fun `every supported year has 24 solar terms`() {
        var epochDay = IsoDate.of(1900, 1, 1).epochDay
        val end = IsoDate.of(2100, 12, 31).epochDay
        var year = 1900
        var count = 0
        while (epochDay <= end) {
            val date = IsoDate(epochDay)
            val currentYear = date.yearMonthDay().first
            if (currentYear != year) {
                assertEquals("year $year", 24, count)
                year = currentYear
                count = 0
            }
            if (ChineseDayMarks.solarTermOf(date) != null) count++
            epochDay++
        }
        assertEquals("year $year", 24, count)
    }

    @Test
    fun `all marks match the authoritative checksum`() {
        // 期望值由寿星天文历实现（lunar_python）逐日生成：1900-02-01～2100-12-31
        // 共 6714 个节日/节气，任一日文案变化都会让校验和失配。
        val digest = MessageDigest.getInstance("SHA-256")
        var epochDay = IsoDate.of(1900, 2, 1).epochDay
        val end = IsoDate.of(2100, 12, 31).epochDay
        while (epochDay <= end) {
            val text = ChineseDayMarks.markOf(IsoDate(epochDay))
            if (text != null) {
                digest.update("$epochDay:$text\n".toByteArray(Charsets.UTF_8))
            }
            epochDay++
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals("d44e7cabee51442fcccb95602b7be3d445e48563a92c7a0ee20a05c0aa365b4d", actual)
    }

    @Test
    fun `mark text is two chars and covered by the font subset`() {
        // tools/fonts/subset-chars.txt 的全部用字；文案超出这个集合会回退系统无衬线。
        val subsetChars = (
            "立春雨水惊蛰春分清明谷雨立夏小满芒种夏至小暑大暑立秋处暑白露秋分寒露霜降" +
                "立冬小雪大雪冬至小寒大寒元旦春节除夕元宵端午七夕中秋重阳国庆腊八0123456789"
            ).toSet()
        var epochDay = IsoDate.of(2024, 1, 1).epochDay
        val end = IsoDate.of(2026, 12, 31).epochDay
        while (epochDay <= end) {
            val text = ChineseDayMarks.markOf(IsoDate(epochDay))
            if (text != null) {
                assertEquals("mark '$text'", 2, text.length)
                assertTrue("mark '$text' not in font subset", text.all { it in subsetChars })
            }
            epochDay++
        }
    }
}
