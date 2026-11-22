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

    /**
     * 新建课表的默认结束日按日历周锚定（末周周五）：任一星期几开学，默认总周数都恰为
     * 16 整周。此前用固定天数 `start + 109` 推默认值，周四~周日开学时会落到第 17 周。
     */
    @Test
    fun `default term end yields sixteen weeks for any start weekday`() {
        for (sample in listOf(
            IsoDate.parse("2026-03-02"), // 周一
            IsoDate.parse("2026-03-03"), // 周二
            IsoDate.parse("2026-03-04"), // 周三
            IsoDate.parse("2026-03-05"), // 周四
            IsoDate.parse("2026-03-07"), // 周六
            IsoDate.parse("2026-03-08"), // 周日
        )) {
            val end = defaultTermEnd(sample)
            assertEquals(
                "start=$sample 默认总周数应为 ${TERM_DEFAULT_WEEKS}",
                TERM_DEFAULT_WEEKS,
                totalWeeks(sample, end),
            )
        }
        // 默认结束日一律落在末周周五（与「周一开学 + 109 天」的旧观感一致）
        assertEquals(IsoDate.parse("2026-06-19"), defaultTermEnd(IsoDate.parse("2026-03-02")))
        assertEquals(IsoDate.parse("2026-06-19"), defaultTermEnd(IsoDate.parse("2026-03-04")))
        assertEquals(IsoDate.parse("2026-06-19"), defaultTermEnd(IsoDate.parse("2026-03-08")))
    }

    /**
     * 改开学日的结束日联动：随开学日的日历周锚点**整周**平移，周数与结束日星期几都
     * 保持。样本学期 9-4（周五）~ 12-19（周六）共 16 周。
     */
    @Test
    fun `shifting the start to the next week shifts the end by one whole week`() {
        // 9-4(周五) → 9-7(周一)：锚点跨了一整周，结束日随之 +7（12-26 周六），
        // 学期仍是 16 周——按天平移只会 +3（12-22），末周周五的课（12-25）被甩出学期外
        val oldStart = IsoDate.parse("2026-09-04")
        val end = IsoDate.parse("2026-12-19")

        val newEnd = endForShiftedStart(oldStart, IsoDate.parse("2026-09-07"), end)

        assertEquals(IsoDate.parse("2026-12-26"), newEnd)
        assertEquals(6, newEnd.isoDayOfWeek()) // 结束日仍是周六（isoDayOfWeek: 1=周一 … 6=周六）
        assertEquals(16, totalWeeks(IsoDate.parse("2026-09-07"), newEnd))
    }

    @Test
    fun `shifting the start within the same week keeps the end untouched`() {
        // 9-4(周五) → 9-1(周二)：仍在同一日历周，锚点不动、结束日不动，周数不变
        val oldStart = IsoDate.parse("2026-09-04")
        val end = IsoDate.parse("2026-12-19")

        val newEnd = endForShiftedStart(oldStart, IsoDate.parse("2026-09-01"), end)

        assertEquals(end, newEnd)
        assertEquals(16, totalWeeks(IsoDate.parse("2026-09-01"), newEnd))
    }

    @Test
    fun `shifting the start back whole weeks shifts the end back too`() {
        // 9-4(周五) → 8-21(周五)：提前两周开学，结束日同步前移两周（12-5 周六）
        val oldStart = IsoDate.parse("2026-09-04")
        val end = IsoDate.parse("2026-12-19")

        val newEnd = endForShiftedStart(oldStart, IsoDate.parse("2026-08-21"), end)

        assertEquals(IsoDate.parse("2026-12-05"), newEnd)
        assertEquals(6, newEnd.isoDayOfWeek())
        assertEquals(16, totalWeeks(IsoDate.parse("2026-08-21"), newEnd))
    }
}
