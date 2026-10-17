package com.fangyi.classpp.data.model

import java.util.GregorianCalendar
import java.util.TimeZone

/**
 * 重复规则的周期展开：把 [RepeatRule] 还原为"应完成日期"序列（重复待办 = 一组截止日期
 * 不同的虚拟独立待办，完成/逾期统计都数这条序列）。
 *
 * 系列从锚日期（待办的首个自定义日期）起；预设档位（每天/每周/每月/每年）= 间隔 1、
 * 无方格，锚定锚日期自身的属性（星期几 / 几号 / 月+日），自定义档位按单位/间隔/方格
 * 展开。纯函数不取时钟——区间由调用方给定（统计到哪算哪）。
 */

private val UTC: TimeZone = TimeZone.getTimeZone("UTC")
private const val MILLIS_PER_DAY = 86_400_000L

/** 周期步进的安全上限（防病态数据拖垮写库路径；实际业务远够不到） */
private const val MAX_PERIODS = 100_000

/**
 * 展开规则在 `[startInclusive, endExclusive)` 内的应完成日期（升序、去重，均 ≥ [anchor]
 * ——系列从锚日起）。
 *
 * @param anchor 系列锚日期（重复待办的首个自定义日期）
 * @param startInclusive 统计区间起点（含）；逾期结算传「水位 + 1 天」（未结算传锚日）
 * @param endExclusive 统计区间终点（不含）；逾期统计传「今天」——当天的应完成日期还不算错过
 */
fun RepeatRule.occurrencesBetween(
    anchor: IsoDate,
    startInclusive: IsoDate,
    endExclusive: IsoDate,
): List<IsoDate> {
    // 无重复没有序列可言
    if (type == RepeatType.None) return emptyList()
    if (anchor.epochDay >= endExclusive.epochDay) return emptyList()
    // 展开参数：预设档位映射到固定单位（间隔 1、无方格，锚定锚日期自身属性，unit/interval/
    // marks 字段不参与——它们的值恒为默认）；自定义档位读自身字段（间隔夹紧、方格按单位
    // 合法域过滤）
    val stepUnit = when (type) {
        RepeatType.Daily -> RepeatUnit.Day
        RepeatType.Weekly -> RepeatUnit.Week
        RepeatType.Monthly -> RepeatUnit.Month
        RepeatType.Yearly -> RepeatUnit.Year
        RepeatType.Custom -> unit
        RepeatType.None -> return emptyList()
    }
    val stepInterval = if (type == RepeatType.Custom) interval.coerceAtLeast(1) else 1
    val stepMarks = (if (type == RepeatType.Custom) marks else emptySet<Int>()).let { current ->
        when (stepUnit) {
            RepeatUnit.Week -> current.filter { it in 1..7 }
            RepeatUnit.Month -> current.filter { it in 1..31 }
            RepeatUnit.Year -> current.filter { it in 1..12 }
            RepeatUnit.Day -> emptySet()
        }
    }.toSortedSet()

    val hits = sortedSetOf(compareBy<IsoDate> { it.epochDay })
    when (stepUnit) {
        RepeatUnit.Day -> {
            var date = anchor
            var step = 0
            while (date.epochDay < endExclusive.epochDay && step < MAX_PERIODS) {
                if (date.epochDay >= startInclusive.epochDay) hits.add(date)
                date += stepInterval
                step++
            }
        }

        RepeatUnit.Week -> {
            // 锚日期所在周为第 0 期，按间隔整周步进；方格是周一=1..周日=7 的 ISO 星期
            val weekdays = stepMarks.ifEmpty { sortedSetOf(anchor.isoDayOfWeek()) }
            var weekStart = anchor.mondayOfWeek()
            var step = 0
            while (weekStart.epochDay < endExclusive.epochDay && step < MAX_PERIODS) {
                for (weekday in weekdays) {
                    val date = weekStart + (weekday - 1)
                    if (date.epochDay >= anchor.epochDay &&
                        date.epochDay >= startInclusive.epochDay &&
                        date.epochDay < endExclusive.epochDay
                    ) {
                        hits.add(date)
                    }
                }
                weekStart += 7 * stepInterval
                step++
            }
        }

        RepeatUnit.Month -> {
            // 月内几号；锚月为第 0 期，按间隔整月步进。日子超出当月长度收拢到当月最后一天
            //（如每月 31 号在 2 月落在 2/28），收拢可能撞日去重。循环按「当月 1 号」推进——
            // 方格日可以早于基准日（基准日是锚日期的几号），只要当月还可能与区间相交就得继续
            val monthDays = stepMarks.ifEmpty { sortedSetOf(anchor.dayOfMonth()) }
            var monthDate = anchor
            var step = 0
            while (monthDate.monthBegin().epochDay < endExclusive.epochDay && step < MAX_PERIODS) {
                for (day in monthDays) {
                    val date = monthDate.withDayOfMonthClamped(day)
                    if (date.epochDay >= anchor.epochDay &&
                        date.epochDay >= startInclusive.epochDay &&
                        date.epochDay < endExclusive.epochDay
                    ) {
                        hits.add(date)
                    }
                }
                monthDate = monthDate.plusMonths(stepInterval)
                step++
            }
        }

        RepeatUnit.Year -> {
            // 年内几月（方格），日取锚日期的几号（超出当月长度同样收拢）；按「当年 1 月」
            // 推进循环，理由同月档——方格月可以早于基准月
            val months = stepMarks.ifEmpty { sortedSetOf(anchor.monthOfYear()) }
            var yearDate = anchor
            var step = 0
            while (yearDate.yearBegin().epochDay < endExclusive.epochDay && step < MAX_PERIODS) {
                for (month in months) {
                    val date = yearDate.withDayOfMonthClamped(anchor.dayOfMonth(), month)
                    if (date.epochDay >= anchor.epochDay &&
                        date.epochDay >= startInclusive.epochDay &&
                        date.epochDay < endExclusive.epochDay
                    ) {
                        hits.add(date)
                    }
                }
                yearDate = yearDate.plusYears(stepInterval)
                step++
            }
        }
    }
    return hits.toList()
}

/**
 * 锚系列在 [fromExclusive] 之后（不含）的第一个不属于 [skip] 的分期——列表"下一个待办"
 * 的取值（跳过已完成的期：提前勾掉未来某期后窗口自动再前移）。一年一窗向前扫描，上限约
 * 220 窗（间隔 99 年的自定义规则也能扫到）；找不到返回 null。
 */
fun RepeatRule.nextOccurrenceAfter(
    anchor: IsoDate,
    fromExclusive: IsoDate,
    skip: Set<IsoDate> = emptySet(),
): IsoDate? {
    var cursor = fromExclusive
    var windows = 0
    while (windows < 220) {
        val windowEnd = cursor + 366
        occurrencesBetween(anchor, cursor, windowEnd).firstOrNull { it !in skip }?.let { return it }
        cursor = windowEnd
        windows++
    }
    return null
}

/**
 * 重复周期天数：锚系列相邻两期的实际相差天数（取锚日期起的前两期相减，随日历浮动——
 * 如每月 12 号，1→2 月与 2→3 月的周期不同）。某一步展开不到期时回退按单位换算
 * （天 = interval、周 = 7×interval、月 = 30×interval、年 = 365×interval），结果至少为 1。
 * 「下一次重复开始前 N 天」的选取上限取值（DeadlineSettingsSheet）。
 */
fun RepeatRule.cycleDays(anchor: IsoDate): Int {
    if (type == RepeatType.None) return 1
    val first = nextOccurrenceAfter(anchor, anchor - 1)
    val fromSchedule = first?.let { first ->
        nextOccurrenceAfter(anchor, first)?.let { (it.epochDay - first.epochDay).toInt() }
    }
    if (fromSchedule != null) return fromSchedule.coerceAtLeast(1)
    // 回退：预设档位映射到固定单位（同 occurrencesBetween 的展开口径），自定义档位读自身字段
    val unit = when (type) {
        RepeatType.Daily -> RepeatUnit.Day
        RepeatType.Weekly -> RepeatUnit.Week
        RepeatType.Monthly -> RepeatUnit.Month
        RepeatType.Yearly -> RepeatUnit.Year
        RepeatType.Custom -> unit
        RepeatType.None -> return 1
    }
    val step = if (type == RepeatType.Custom) interval.coerceAtLeast(1) else 1
    return when (unit) {
        RepeatUnit.Day -> step
        RepeatUnit.Week -> 7 * step
        RepeatUnit.Month -> 30 * step
        RepeatUnit.Year -> 365 * step
    }.coerceAtLeast(1)
}

// ---------- 日历运算辅助（UTC 日历日期，语义同 IsoDate；项目惯例规避 java.time） ----------

private fun IsoDate.calendar(): GregorianCalendar =
    GregorianCalendar(UTC).apply { timeInMillis = epochDay * MILLIS_PER_DAY }

private fun GregorianCalendar.toIsoDate(): IsoDate =
    IsoDate(Math.floorDiv(timeInMillis, MILLIS_PER_DAY))

/** [IsoDate] 的几号（1..31） */
private fun IsoDate.dayOfMonth(): Int =
    calendar().get(GregorianCalendar.DAY_OF_MONTH)

/** [IsoDate] 的月份（1..12） */
private fun IsoDate.monthOfYear(): Int =
    calendar().get(GregorianCalendar.MONTH) + 1

/** 月份步进（月末收拢：1/31 + 1 月 = 2/28，与日历直觉一致） */
private fun IsoDate.plusMonths(months: Int): IsoDate =
    calendar().apply { add(GregorianCalendar.MONTH, months) }.toIsoDate()

/** 年份步进（闰年 2/29 收拢，同 [plusMonths]） */
private fun IsoDate.plusYears(years: Int): IsoDate =
    calendar().apply { add(GregorianCalendar.YEAR, years) }.toIsoDate()

/** 所在月的 1 号（月档循环推进基准：只要当月与统计区间可能相交就得继续展开） */
private fun IsoDate.monthBegin(): IsoDate = this + (1 - dayOfMonth())

/** 所在年的 1 月 1 日（年档循环推进基准，同 [monthBegin]） */
private fun IsoDate.yearBegin(): IsoDate = withDayOfMonthClamped(1, month = 1)

/**
 * 把日期挪到同年同月的 [day] 日；超出当月长度收拢到当月最后一天（[IsoDate.of] 是严格
 * 构造，越界会抛异常）。[month] 缺省 = 保持原月份（年档展开用它切到方格月份）。
 */
private fun IsoDate.withDayOfMonthClamped(day: Int, month: Int = monthOfYear()): IsoDate {
    val cal = calendar()
    val year = cal.get(GregorianCalendar.YEAR)
    cal.set(GregorianCalendar.MONTH, month - 1)
    val lastDay = cal.getActualMaximum(GregorianCalendar.DAY_OF_MONTH)
    return IsoDate.of(year, month, day.coerceIn(1, lastDay))
}
