package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
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
}
