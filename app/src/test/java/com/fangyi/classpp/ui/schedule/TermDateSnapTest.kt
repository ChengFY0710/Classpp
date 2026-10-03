package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.model.IsoDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 周数 ↔ 结束日推算：保持开始日不变，结束日只整周平移、星期几不变。
 * 周数一律按**日历周**对齐（两端各取所在周的周一相减，与 Schedule.totalWeeks 同式）。
 * 样本：2026-03-02 周一 ~ 2026-06-19 周五 = 16 周；周日尾样本 2026-06-21；
 * 周三开学样本 2026-03-04（第 1 周 = 03-02~03-08）。
 */
class TermDateSnapTest {

    private val start = IsoDate.parse("2026-03-02")
    private val fridayEnd = IsoDate.parse("2026-06-19")
    private val sundayEnd = IsoDate.parse("2026-06-21")

    private fun weeksBetween(start: IsoDate, end: IsoDate): Int =
        ((end.mondayOfWeek() - start.mondayOfWeek()).toInt() / 7) + 1

    @Test
    fun `shrink to one week keeps monday start friday end`() {
        val end = endForTotalWeeks(start, fridayEnd, 1)

        assertEquals(IsoDate.parse("2026-03-06"), end)
        assertEquals(5, end.isoDayOfWeek())
        assertEquals(1, weeksBetween(start, end))
    }

    @Test
    fun `grow to thirty weeks keeps friday and whole weeks`() {
        val end = endForTotalWeeks(start, fridayEnd, 30)

        assertEquals(fridayEnd + 14 * 7, end)
        assertEquals(5, end.isoDayOfWeek())
        assertEquals(30, weeksBetween(start, end))
    }

    @Test
    fun `same weeks returns same end`() {
        assertEquals(fridayEnd, endForTotalWeeks(start, fridayEnd, 16))
    }

    @Test
    fun `sunday end stays sunday when shrinking to one week`() {
        val end = endForTotalWeeks(start, sundayEnd, 1)

        assertEquals(IsoDate.parse("2026-03-08"), end)
        assertEquals(7, end.isoDayOfWeek())
    }

    @Test
    fun `shifts by whole weeks only`() {
        val end = endForTotalWeeks(start, fridayEnd, 10)

        assertEquals(fridayEnd - 6 * 7, end)
        // 仍落在整周边界（周一 + 4 天 = 周五）
        assertEquals(4, Math.floorMod((end - start).toInt(), 7))
    }

    /** 周三结尾也允许：结束日只整周平移，学期末周可以只上到周中 */
    @Test
    fun `mid week end shifts by whole weeks too`() {
        val wednesdayEnd = IsoDate.parse("2026-06-17")
        val end = endForTotalWeeks(start, wednesdayEnd, 10)

        assertEquals(wednesdayEnd - 6 * 7, end)
        assertEquals(3, end.isoDayOfWeek())
        assertEquals(10, weeksBetween(start, end))
    }

    /** 开学日不是周一时同样成立：03-04 周三开学，收成 1 周仍停在周五（03-06） */
    @Test
    fun `non-monday start shifts end by whole weeks too`() {
        val wednesdayStart = IsoDate.parse("2026-03-04")

        assertEquals(
            IsoDate.parse("2026-03-06"),
            endForTotalWeeks(wednesdayStart, fridayEnd, 1),
        )
        assertEquals(
            30,
            weeksBetween(wednesdayStart, endForTotalWeeks(wednesdayStart, fridayEnd, 30)),
        )
        assertEquals(5, endForTotalWeeks(wednesdayStart, fridayEnd, 16).isoDayOfWeek())
    }
}
