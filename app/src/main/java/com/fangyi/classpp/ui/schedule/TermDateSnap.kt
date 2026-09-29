package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.model.IsoDate

/** 学期日期选择目标 */
enum class DateTarget { Start, End }

/** 学期默认长度：16 周 = 15×7 + 4 天（周五结尾） */
const val TERM_DEFAULT_DAYS = 109

/** 吸附到所在 ISO 周的周一（周日回退到本周一） */
fun snapToMonday(date: IsoDate): IsoDate = date + (1 - date.isoDayOfWeek())

/** 吸附到合法结束日：5 天模式→周五、7 天模式→周日（同周回退或顺延） */
fun snapTermEnd(date: IsoDate, daysPerWeek: Int): IsoDate = if (daysPerWeek == 7) {
    date + (7 - date.isoDayOfWeek())
} else {
    date + (5 - date.isoDayOfWeek())
}

/** 学期周数选择允许范围（周数对话框的数字校验边界） */
const val TERM_WEEKS_MIN = 1
const val TERM_WEEKS_MAX = 30

/**
 * 保持开始日不变，把结束日换算到 [weeks] 整周：结束日只整周平移，
 * 星期几不变（周五仍周五、周日仍周日），整周边界天然保持。
 */
fun endForTotalWeeks(start: IsoDate, end: IsoDate, weeks: Int): IsoDate {
    val currentWeeks = ((end - start).toInt() / 7) + 1   // 与 Schedule.totalWeeks 同式
    return end + (weeks - currentWeeks) * 7
}
