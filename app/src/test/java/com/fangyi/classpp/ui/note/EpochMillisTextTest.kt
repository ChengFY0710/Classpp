package com.fangyi.classpp.ui.note

import com.fangyi.classpp.data.model.IsoDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 时刻戳文本的契约（[epochMillisText]，待办详情浮层的创建/完成时间）：
 *
 * - 日期段与详情页其余时间同源（[toAbsoluteDateText]）：本年 `3/12`、非本年 `2027/3/12`，不补零；
 * - 时刻段走 TimeText.format：时**不**补零、分钟补足两位（`8:05`、`0:00`）；
 * - 时刻戳按传入时区换算日历日与当天分钟数（固定 UTC 后断言与设备时区无关）。
 *
 * 断言直接引用 TodoScreen.kt 的 internal 纯函数，格式调整时这里同步失效。
 */
class EpochMillisTextTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 固定「今天」= 2026-10-15，本年即 2026 */
    private val today = IsoDate.of(2026, 10, 15)

    /** `yyyy-MM-ddTHH:mmZ`（UTC）→ epoch millis，避免测试里散落魔法数 */
    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        IsoDate.of(year, month, day).epochDay * 86_400_000L + (hour * 60L + minute) * 60_000L

    @Test
    fun thisYearOmitsYearAndPadsOnlyMinutes() {
        assertEquals(
            "3/12 8:05",
            epochMillisText(utcMillis(2026, 3, 12, 8, 5), today, utc),
        )
    }

    @Test
    fun otherYearCarriesYear() {
        assertEquals(
            "2027/3/12 8:05",
            epochMillisText(utcMillis(2027, 3, 12, 8, 5), today, utc),
        )
    }

    @Test
    fun newYearMidnightSwitchesFormatAtYearBoundary() {
        assertEquals(
            "2027/1/1 0:00",
            epochMillisText(utcMillis(2027, 1, 1, 0, 0), IsoDate.of(2026, 12, 31), utc),
        )
    }
}
