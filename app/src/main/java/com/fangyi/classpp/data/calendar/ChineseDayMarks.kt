package com.fangyi.classpp.data.calendar

import com.fangyi.classpp.data.model.IsoDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * 日程页大日期位置的节日/节气文案。
 *
 * 文案固定中文：用字全部来自 tools/fonts/subset-chars.txt（24 节气 + 常见节日 + 0-9），
 * 由 SerifDisplay（思源宋体 Heavy 子集）渲染，不随系统语言本地化。新增名称必须先补进
 * 字表再重跑 subset-font.py，否则运行时会静默回退系统无衬线。
 *
 * 支持范围 1900–2100；范围外返回 null，由调用方退回显示日号。
 */
object ChineseDayMarks {

    /** 当天应显示的节日/节气名；普通日期返回 null。节日优先于节气。 */
    fun markOf(date: IsoDate): String? = festivalOf(date) ?: solarTermOf(date)

    /** 公历/农历节日；无则 null。 */
    internal fun festivalOf(date: IsoDate): String? {
        val (year, month, day) = date.yearMonthDay()
        if (year !in SupportedYearRange) return null
        fixedFestivalOf(month, day)?.let { return it }

        val lunar = lunarDateOf(date) ?: return null
        // 闰月不重复过节：闰五月初五不是端午。
        if (lunar.isLeapMonth) return null
        if (lunar.month == 12 && lunar.day == lunarMonthDays(lunar.year, 12)) return "除夕"
        return lunarFestivalOf(lunar.month, lunar.day)
    }

    /** 24 节气；无则 null。 */
    internal fun solarTermOf(date: IsoDate): String? {
        val (year, month, _) = date.yearMonthDay()
        if (year !in SupportedYearRange) return null
        val firstIndex = (month - 1) * 2
        for (index in firstIndex..firstIndex + 1) {
            if (solarTermEpochDay(year, index) == date.epochDay) return SolarTermNames[index]
        }
        return null
    }

    private fun fixedFestivalOf(month: Int, day: Int): String? = when {
        month == 1 && day == 1 -> "元旦"
        month == 10 && day == 1 -> "国庆"
        else -> null
    }

    private fun lunarFestivalOf(month: Int, day: Int): String? = when {
        month == 1 && day == 1 -> "春节"
        month == 1 && day == 15 -> "元宵"
        month == 5 && day == 5 -> "端午"
        month == 7 && day == 7 -> "七夕"
        month == 8 && day == 15 -> "中秋"
        month == 9 && day == 9 -> "重阳"
        month == 12 && day == 8 -> "腊八"
        else -> null
    }

    // ---------------------------------------------------------------
    // 农历：1900–2100 标准 lunarInfo 表
    // 低 4 位 = 闰月号（0 为无闰月）；0x10000 = 闰月是否 30 天；
    // 0x8000..0x10 依次为 1–12 月是否 30 天。
    //
    // 表值以寿星天文历（lunar_python）为准；1900-02-01～2100-12-31 的全部节日/节气
    // 已逐日校验一致。2089、2097 两年的新月贴近午夜（距零点约 3 分钟），常见静态表
    // 未计 ΔT 会差一天，这里按 ΔT 后的日期修正。
    // ---------------------------------------------------------------
    private const val LunarMinYear = 1900
    private const val LunarMaxYear = 2100

    /** 1900-01-31 是农历 1900 年正月初一。 */
    private const val LunarBaseEpochDay = -25_537L

    private val LunarInfo = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0,
        0x09ad0, 0x055d2, 0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540,
        0x0d6a0, 0x0ada2, 0x095b0, 0x14977, 0x04970, 0x0a4b0, 0x0b4b5, 0x06a50,
        0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970, 0x06566, 0x0d4a0,
        0x0ea50, 0x16a95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x0c950,
        0x0d4a0, 0x1d8a6, 0x0b550, 0x056a0, 0x1a5b4, 0x025d0, 0x092d0, 0x0d2b2,
        0x0a950, 0x0b557, 0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5b0, 0x14573,
        0x052b0, 0x0a9a8, 0x0e950, 0x06aa0, 0x0aea6, 0x0ab50, 0x04b60, 0x0aae4,
        0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0, 0x096d0, 0x04dd5,
        0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b6a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46,
        0x0ab60, 0x09570, 0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58,
        0x05ac0, 0x0ab60, 0x096d5, 0x092e0, 0x0c960, 0x0d954, 0x0d4a0, 0x0da50,
        0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5, 0x0a950, 0x0b4a0,
        0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260,
        0x0ea65, 0x0d530, 0x05aa0, 0x076a3, 0x096d0, 0x04afb, 0x04ad0, 0x0a4d0,
        0x1d0b6, 0x0d250, 0x0d520, 0x0dd45, 0x0b5a0, 0x056d0, 0x055b2, 0x049b0,
        0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0, 0x14b63, 0x09370,
        0x049f8, 0x04970, 0x064b0, 0x168a6, 0x0ea50, 0x06b20, 0x1a6c4, 0x0aae0,
        0x092e0, 0x0d2e3, 0x0c960, 0x0d557, 0x0d4a0, 0x0da50, 0x05d55, 0x056a0,
        0x0a6d0, 0x055d4, 0x052d0, 0x0a9b8, 0x0a950, 0x0b4a0, 0x0b6a6, 0x0ad50,
        0x055a0, 0x0aba4, 0x0a5b0, 0x052b0, 0x0b273, 0x06930, 0x07337, 0x06aa0,
        0x0ad50, 0x14b55, 0x04b60, 0x0a570, 0x054e4, 0x0d160, 0x0e968, 0x0d520,
        0x0daa0, 0x16aa6, 0x056d0, 0x04ae0, 0x0a9d4, 0x0a2d0, 0x0d150, 0x0f252,
        0x0d520,
    )

    private data class LunarDate(
        val year: Int,
        val month: Int,
        val day: Int,
        val isLeapMonth: Boolean,
    )

    private fun lunarDateOf(date: IsoDate): LunarDate? {
        var offset = date.epochDay - LunarBaseEpochDay
        if (offset < 0L) return null

        var year = LunarMinYear
        while (year <= LunarMaxYear && offset >= lunarYearDays(year)) {
            offset -= lunarYearDays(year)
            year++
        }
        if (year > LunarMaxYear) return null

        val leapMonth = lunarLeapMonth(year)
        var remaining = offset.toInt()
        var month = 1
        var isLeapMonth = false
        while (month <= 12) {
            val days = if (isLeapMonth) lunarLeapDays(year) else lunarMonthDays(year, month)
            if (remaining < days) return LunarDate(year, month, remaining + 1, isLeapMonth)
            remaining -= days
            if (!isLeapMonth && leapMonth > 0 && month == leapMonth) {
                isLeapMonth = true
            } else {
                isLeapMonth = false
                month++
            }
        }
        return null
    }

    private fun lunarYearDays(year: Int): Int {
        val info = LunarInfo[year - LunarMinYear]
        var days = 348
        var bit = 0x8000
        while (bit >= 0x10) {
            if (info and bit != 0) days++
            bit = bit shr 1
        }
        return days + lunarLeapDays(year)
    }

    private fun lunarLeapMonth(year: Int): Int = LunarInfo[year - LunarMinYear] and 0xf

    private fun lunarLeapDays(year: Int): Int {
        if (lunarLeapMonth(year) == 0) return 0
        return if (LunarInfo[year - LunarMinYear] and 0x10000 != 0) 30 else 29
    }

    private fun lunarMonthDays(year: Int, month: Int): Int =
        if (LunarInfo[year - LunarMinYear] and (0x10000 shr month) != 0) 30 else 29

    // ---------------------------------------------------------------
    // 节气：太阳视黄经达到 15° 整数倍的时刻，按北京时间取日期。
    // 采用 Meeus 低精度太阳位置公式（误差约 0.01°），1900–2100 共 4824 个节气中
    // 只有 12 个贴近午夜的日期与权威历表差一天，按年份/序号修正。
    // ---------------------------------------------------------------
    private val SolarTermNames = arrayOf(
        "小寒", "大寒", "立春", "雨水", "惊蛰", "春分", "清明", "谷雨",
        "立夏", "小满", "芒种", "夏至", "小暑", "大暑", "立秋", "处暑",
        "白露", "秋分", "寒露", "霜降", "立冬", "小雪", "大雪", "冬至",
    )

    private val SupportedYearRange = 1900..2100

    private const val JulianDayAtUnixEpoch = 2440587.5
    private const val JulianDayAtEpochDayZero = 2440588L
    private const val BeijingOffsetDays = 8.0 / 24.0
    private const val MeanSolarMotionDegreesPerDay = 0.98564736

    private fun solarTermEpochDay(year: Int, index: Int): Long {
        // 初值取节气所在月份 15 日正午，牛顿迭代收敛到黄经等于目标值的时刻。
        var jd = JulianDayAtUnixEpoch + IsoDate.of(year, index / 2 + 1, 15).epochDay + 0.5
        val target = (285.0 + index * 15.0) % 360.0
        var iteration = 0
        while (iteration < 12) {
            val diff = shortestAngle(sunApparentLongitude(jd), target)
            jd += diff / MeanSolarMotionDegreesPerDay
            iteration++
            if (abs(diff) < 1e-9) break
        }
        val epochDay = floor(jd + BeijingOffsetDays + 0.5).toLong() - JulianDayAtEpochDayZero
        return epochDay + solarTermDayOffset(year, index)
    }

    private fun sunApparentLongitude(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        val meanLongitude = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val meanAnomaly = Math.toRadians(357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val equationOfCenter =
            (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(meanAnomaly) +
                (0.019993 - 0.000101 * t) * sin(2.0 * meanAnomaly) +
                0.000289 * sin(3.0 * meanAnomaly)
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val apparent = meanLongitude + equationOfCenter - 0.00569 - 0.00478 * sin(omega)
        return ((apparent % 360.0) + 360.0) % 360.0
    }

    private fun shortestAngle(from: Double, to: Double): Double =
        ((to - from + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** 低精度太阳黄经在贴近午夜的节气上差一天时的修正（天）。 */
    private fun solarTermDayOffset(year: Int, index: Int): Int = when (year to index) {
        (1912 to 18) -> 1 // 寒露
        (1917 to 22) -> 1 // 大雪
        (1923 to 3) -> -1 // 雨水
        (1950 to 7) -> -1 // 谷雨
        (1951 to 23) -> 1 // 冬至
        (2014 to 4) -> 1 // 惊蛰
        (2016 to 12) -> 1 // 小暑
        (2045 to 12) -> 1 // 小暑
        (2047 to 4) -> 1 // 惊蛰
        (2051 to 5) -> -1 // 春分
        (2082 to 1) -> 1 // 大寒
        (2097 to 8) -> 1 // 立夏
        else -> 0
    }
}
