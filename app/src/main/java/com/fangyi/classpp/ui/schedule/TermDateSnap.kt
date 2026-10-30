package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.model.IsoDate

/** 学期默认长度：16 周 = 15×7 + 4 天（任一星期几开学，总周数都落在 16 周） */
const val TERM_DEFAULT_DAYS = 109

/** 学期周数选择允许范围（周数滚轮/对话框的数值边界） */
const val TERM_WEEKS_MIN = 1
const val TERM_WEEKS_MAX = 30

/**
 * start~end 覆盖的**日历周**数（与 Schedule.totalWeeks 同式，按所在周的周一相减）。
 * 开学日是任意星期几时同样成立；周数滚轮的初值与「改周数平移结束日」都用它。
 */
fun totalWeeks(start: IsoDate, end: IsoDate): Int =
    ((end.mondayOfWeek() - start.mondayOfWeek()).toInt() / 7) + 1

/**
 * 保持开始日不变，把结束日换算到 [weeks] 整周：结束日只整周平移、星期几不变，
 * 故 start~end 覆盖的日历周数恰好等于 [weeks]。
 */
fun endForTotalWeeks(start: IsoDate, end: IsoDate, weeks: Int): IsoDate =
    end + (weeks - totalWeeks(start, end)) * 7
