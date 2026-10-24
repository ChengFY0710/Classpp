package com.fangyi.classpp.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重复规则周期展开（[occurrencesBetween]）的口径测试：
 * 预设档位锚定锚日期属性、自定义档位按单位/间隔/方格展开、跨月收拢、区间过滤。
 */
class RepeatScheduleTest {

    private fun d(text: String): IsoDate = IsoDate.parse(text)

    private fun expand(rule: RepeatRule, anchor: String, start: String, endExclusive: String): List<String> =
        rule.occurrencesBetween(d(anchor), d(start), d(endExclusive)).map { it.toString() }

    // ---------- 预设档位 ----------

    @Test
    fun `daily preset expands every day from anchor`() {
        val rule = RepeatRule(type = RepeatType.Daily)
        // 区间含端点起点、不含终点：3/10..3/16 七天
        assertEquals(
            listOf("2026-03-10", "2026-03-11", "2026-03-12", "2026-03-13", "2026-03-14", "2026-03-15", "2026-03-16"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-10", endExclusive = "2026-03-17"),
        )
    }

    @Test
    fun `weekly preset anchors to weekday`() {
        val rule = RepeatRule(type = RepeatType.Weekly)
        // 2026-03-02 是周一（测试学期起点），3/10 周二 → 区间内的周二
        assertEquals(
            listOf("2026-03-10", "2026-03-17", "2026-03-24", "2026-03-31"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-01", endExclusive = "2026-04-01"),
        )
    }

    @Test
    fun `monthly preset clamps to month end`() {
        val rule = RepeatRule(type = RepeatType.Monthly)
        // 锚 1/31：短月收拢到当月最后一天（2026 非闰年，2 月 = 2/28）
        assertEquals(
            listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30"),
            expand(rule, anchor = "2026-01-31", start = "2026-01-01", endExclusive = "2026-05-01"),
        )
    }

    @Test
    fun `yearly preset anchors to month and day`() {
        val rule = RepeatRule(type = RepeatType.Yearly)
        assertEquals(
            listOf("2026-03-10", "2027-03-10", "2028-03-10"),
            expand(rule, anchor = "2026-03-10", start = "2026-01-01", endExclusive = "2029-01-01"),
        )
    }

    // ---------- 自定义档位 ----------

    @Test
    fun `custom week with interval and marks`() {
        val rule = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Week,
            interval = 2,
            marks = setOf(1, 3), // 周一、周三
        )
        // 锚 3/11（周三）：第 0 期周一 3/9 早于锚被剔，周三 3/11 入列；下一期跳一周到 3/23、3/25
        assertEquals(
            listOf("2026-03-11", "2026-03-23", "2026-03-25"),
            expand(rule, anchor = "2026-03-11", start = "2026-03-11", endExclusive = "2026-04-01"),
        )
    }

    @Test
    fun `custom month with interval and marks`() {
        val rule = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Month,
            interval = 2,
            marks = setOf(1, 15),
        )
        // 锚 1/10：1/1 早于锚被剔；步进隔月 → 1/15、3/1、3/15
        assertEquals(
            listOf("2026-01-15", "2026-03-01", "2026-03-15"),
            expand(rule, anchor = "2026-01-10", start = "2026-01-10", endExclusive = "2026-04-01"),
        )
    }

    @Test
    fun `custom day with interval steps from anchor`() {
        val rule = RepeatRule(type = RepeatType.Custom, unit = RepeatUnit.Day, interval = 3)
        assertEquals(
            listOf("2026-03-10", "2026-03-13", "2026-03-16"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-01", endExclusive = "2026-03-17"),
        )
    }

    // ---------- 区间与边界 ----------

    @Test
    fun `start and end bounds are respected`() {
        val rule = RepeatRule(type = RepeatType.Daily)
        // 起点之后的才计入；终点当天不含（逾期口径：当天的应完成日期不算错过）
        assertEquals(
            listOf("2026-03-12", "2026-03-13"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-12", endExclusive = "2026-03-14"),
        )
    }

    @Test
    fun `empty range returns nothing`() {
        val rule = RepeatRule(type = RepeatType.Daily)
        assertEquals(
            emptyList<String>(),
            expand(rule, anchor = "2026-03-10", start = "2026-03-10", endExclusive = "2026-03-10"),
        )
    }

    @Test
    fun `non repeat type yields nothing`() {
        val rule = RepeatRule(type = RepeatType.None)
        assertEquals(
            emptyList<String>(),
            expand(rule, anchor = "2026-03-10", start = "2026-03-10", endExclusive = "2026-04-10"),
        )
    }

    // ---------- 结束条件 ----------

    @Test
    fun `date end trims window exclusively`() {
        // 结束日期 3/13 当天不含（「该日期前停止」），统计终点再远也只到 3/12
        val rule = RepeatRule(
            type = RepeatType.Daily,
            endType = RepeatEndType.Date,
            endDate = d("2026-03-13"),
        )
        assertEquals(
            listOf("2026-03-10", "2026-03-11", "2026-03-12"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-10", endExclusive = "2026-04-10"),
        )
    }

    @Test
    fun `date end at or before anchor yields nothing`() {
        val rule = RepeatRule(
            type = RepeatType.Daily,
            endType = RepeatEndType.Date,
            endDate = d("2026-03-10"),
        )
        assertEquals(
            emptyList<String>(),
            expand(rule, anchor = "2026-03-10", start = "2026-03-10", endExclusive = "2026-04-10"),
        )
    }

    @Test
    fun `date end before first occurrence yields nothing`() {
        // 锚 3/11（周三）但方格只勾周二：首期 3/17，结束日期 3/16 前一期都没有
        val rule = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Week,
            marks = setOf(2),
            endType = RepeatEndType.Date,
            endDate = d("2026-03-16"),
        )
        assertEquals(
            emptyList<String>(),
            expand(rule, anchor = "2026-03-11", start = "2026-03-11", endExclusive = "2026-04-10"),
        )
    }

    @Test
    fun `count end keeps first n occurrences`() {
        val rule = RepeatRule(
            type = RepeatType.Daily,
            endType = RepeatEndType.Count,
            endCount = 3,
        )
        assertEquals(
            listOf("2026-03-10", "2026-03-11", "2026-03-12"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-10", endExclusive = "2026-04-10"),
        )
    }

    @Test
    fun `count end indexes from anchor regardless of window start`() {
        // 次数自系列锚日起数（3/10 起第 1 期），窗口从 3/11 起只影响过滤不影响计数：
        // 第 1 期 3/10 被窗口剔掉，但 3/13 已是第 4 期、超上限被剔
        val rule = RepeatRule(
            type = RepeatType.Daily,
            endType = RepeatEndType.Count,
            endCount = 3,
        )
        assertEquals(
            listOf("2026-03-11", "2026-03-12"),
            expand(rule, anchor = "2026-03-10", start = "2026-03-11", endExclusive = "2026-04-10"),
        )
    }

    @Test
    fun `count end counts clamped duplicates as one occurrence`() {
        // 锚 1/30、方格 30/31 号：2 月两者都收拢到 2/28，同日只记一期——
        // 上限 4 期 = 1/30、1/31、2/28、3/30（3/31 是第 5 期被剔），不做同日合并会少算一期
        val rule = RepeatRule(
            type = RepeatType.Custom,
            unit = RepeatUnit.Month,
            marks = setOf(30, 31),
            endType = RepeatEndType.Count,
            endCount = 4,
        )
        assertEquals(
            listOf("2026-01-30", "2026-01-31", "2026-02-28", "2026-03-30"),
            expand(rule, anchor = "2026-01-30", start = "2026-01-01", endExclusive = "2026-06-01"),
        )
    }

    @Test
    fun `count end exhausts series for next occurrence`() {
        val rule = RepeatRule(
            type = RepeatType.Daily,
            endType = RepeatEndType.Count,
            endCount = 3,
        )
        val anchor = d("2026-03-10")
        // 系列只到 3/12：从 3/13 起再无下一期；起点含端点语义下分期原样返回
        assertEquals(null, rule.nextOccurrenceAfter(anchor, d("2026-03-13")))
        assertEquals(d("2026-03-10"), rule.nextOccurrenceAfter(anchor, anchor - 1))
        assertEquals(d("2026-03-12"), rule.nextOccurrenceAfter(anchor, d("2026-03-12")))
    }
}
