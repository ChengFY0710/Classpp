package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.model.IsoDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 周数 ↔ 结束日推算：保持开始日（周一）不变，结束日只整周平移、星期几不变。
 * 样本：2026-03-02 周一 ~ 2026-06-19 周五 = 16 周；周日尾样本 2026-06-21。
 */
class TermDateSnapTest {

    private val start = IsoDate.parse("2026-03-02")
    private val fridayEnd = IsoDate.parse("2026-06-19")
    private val sundayEnd = IsoDate.parse("2026-06-21")

    @Test
    fun `shrink to one week keeps monday start friday end`() {
        val end = endForTotalWeeks(start, fridayEnd, 1)

        assertEquals(IsoDate.parse("2026-03-06"), end)
        assertEquals(5, end.isoDayOfWeek())
        assertEquals(1, ((end - start).toInt() / 7) + 1)
    }

    @Test
    fun `grow to thirty weeks keeps friday and whole weeks`() {
        val end = endForTotalWeeks(start, fridayEnd, 30)

        assertEquals(fridayEnd + 14 * 7, end)
        assertEquals(5, end.isoDayOfWeek())
        assertEquals(30, ((end - start).toInt() / 7) + 1)
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
}
