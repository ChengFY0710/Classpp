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

    /**
     * 切换 5 天 / 7 天视图时，学期结束日只需收进新天数的列范围（只收不放）：
     * 7→5 把周日收成同周周五；5→7 保持不动（该周周日那一列本就空着，学期不必强行延长）。
     */
    @Test
    fun `end is clamped into the day count range when the view changes`() {
        // 7 → 5：周日落到周五（−2 天）
        assertEquals(fridayEnd, sundayEnd.lastTeachingDayOnOrBefore(daysPerWeek = 5))
        // 5 → 7：已在范围内，保持用户选的那一天
        assertEquals(fridayEnd, fridayEnd.lastTeachingDayOnOrBefore(daysPerWeek = 7))
        assertEquals(sundayEnd, sundayEnd.lastTeachingDayOnOrBefore(daysPerWeek = 7))
    }

    /** 周中任意一天都不动：结束日允许停在周中，这正是取消"必须周五/周日"后的新能力 */
    @Test
    fun `mid week dates are kept as they are`() {
        val wednesday = IsoDate.parse("2026-06-17")

        assertEquals(wednesday, wednesday.lastTeachingDayOnOrBefore(daysPerWeek = 5))
        assertEquals(wednesday, wednesday.lastTeachingDayOnOrBefore(daysPerWeek = 7))
    }

    /** 收拢永远留在同一教学周内：周日收到周五只差 2 天，不会跨周改变总周数 */
    @Test
    fun `clamping never leaves the week`() {
        val clamped = sundayEnd.lastTeachingDayOnOrBefore(daysPerWeek = 5)

        assertEquals(5, clamped.isoDayOfWeek())
        assertEquals(2L, sundayEnd.epochDay - clamped.epochDay)
    }
}
