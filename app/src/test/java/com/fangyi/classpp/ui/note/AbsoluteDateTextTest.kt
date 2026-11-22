package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.testTodo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 待办详情浮层日期段的契约（[toAbsoluteDateText]）：
 *
 * - 年份条件：本年省年份显 `3/12`（M/D），非本年带年份显 `2027/3/12`（YYYY/M/D）；
 * - 不补零：月、日一律不补前导零（`1/5` 而非 `01/05`），分钟补零由 TimeText.format 负责；
 * - 年份判定按 [today] 的年份，可注入以钉死「今年」（跨年界即换形态）。
 *
 * 断言直接引用 TodoScreen.kt 的 internal 纯函数，格式调整时这里同步失效。
 * [todoScheduledDateText]（详情「待办时间」的日期段取值）同在此处覆盖。
 */
class AbsoluteDateTextTest {

    /** 固定「今天」= 2026-10-15，本年即 2026 */
    private val today = IsoDate.of(2026, 10, 15)

    @Test
    fun thisYearOmitsYear() {
        assertEquals("3/12", IsoDate.of(2026, 3, 12).toAbsoluteDateText(today))
    }

    @Test
    fun otherYearsCarryYear() {
        assertEquals("2027/3/12", IsoDate.of(2027, 3, 12).toAbsoluteDateText(today))
        assertEquals("2025/12/31", IsoDate.of(2025, 12, 31).toAbsoluteDateText(today))
    }

    @Test
    fun newYearDaySwitchesFormatAtYearBoundary() {
        val lastDayOfYear = IsoDate.of(2026, 12, 31)
        assertEquals("2027/1/1", IsoDate.of(2027, 1, 1).toAbsoluteDateText(lastDayOfYear))
        assertEquals("12/31", lastDayOfYear.toAbsoluteDateText(lastDayOfYear))
    }

    @Test
    fun singleDigitMonthAndDayAreNotPadded() {
        assertEquals("1/5", IsoDate.of(2026, 1, 5).toAbsoluteDateText(today))
    }

    // ---------- 详情「待办时间」的日期段（todoScheduledDateText） ----------

    @Test
    fun scheduledDateTextShowsTheClickedOccurrenceForRepeatTodos() {
        // 重复待办 + 所点期次：只显示该期——从显示 3/23 的分期卡进详情，时间行也是 3/23
        val weekly = testTodo(
            id = "t1",
            name = "周报",
            dates = listOf(IsoDate.of(2026, 3, 2)),
            repeat = RepeatRule(type = RepeatType.Weekly),
        )
        assertEquals("3/23", todoScheduledDateText(weekly, IsoDate.of(2026, 3, 23), today))
    }

    @Test
    fun scheduledDateTextShowsAnchorDatesWithoutOccurrenceContext() {
        // 重复待办 + 无逐期上下文（AI 对话浮层）：保持存储的锚日期，不猜当前期
        val weekly = testTodo(
            id = "t1",
            name = "周报",
            dates = listOf(IsoDate.of(2026, 3, 2)),
            repeat = RepeatRule(type = RepeatType.Weekly),
        )
        assertEquals("3/2", todoScheduledDateText(weekly, null, today))
    }

    @Test
    fun scheduledDateTextShowsAllDatesForNonRepeatTodos() {
        // 非重复多日期：仍显示全部日期，occurrenceDate 不参与
        val plain = testTodo(
            id = "t2",
            name = "多日",
            dates = listOf(IsoDate.of(2026, 10, 14), IsoDate.of(2026, 10, 20)),
        )
        assertEquals("10/14、10/20", todoScheduledDateText(plain, IsoDate.of(2026, 10, 14), today))
        assertEquals("10/14、10/20", todoScheduledDateText(plain, null, today))
    }
}
