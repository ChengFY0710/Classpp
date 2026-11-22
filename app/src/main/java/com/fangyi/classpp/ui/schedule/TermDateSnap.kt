package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.model.IsoDate

/** 学期默认长度（周）：新建课表的缺省总周数 */
const val TERM_DEFAULT_WEEKS = 16

/** 学期周数选择允许范围（周数滚轮/对话框的数值边界） */
const val TERM_WEEKS_MIN = 1
const val TERM_WEEKS_MAX = 30

/**
 * 新建课表的默认结束日：第 [weeks] 周的**周五**（按日历周对齐——第 1 周 = 开学日所在
 * 的周一~周日周）。此前用固定天数 `start + 109` 推默认值，开学日是周四~周日时会落到
 * 第 17 周；改为按周锚定后任一星期几开学，默认总周数都是 [weeks] 整周。
 */
fun defaultTermEnd(start: IsoDate, weeks: Int = TERM_DEFAULT_WEEKS): IsoDate =
    start.mondayOfWeek() + (weeks - 1) * 7 + 4

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
