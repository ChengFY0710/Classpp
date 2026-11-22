package com.fangyi.classpp.data.model

/**
 * [termEndForFewerWeeks] 的结果：
 * 放得下 → [candidateEnd]（恒落在目标周内，totalWeeks 恰为目标周数）；
 * 放不下（被剪掉的日期里还有课）→ [blockingWeek] 点名最后上课所在的教学周，供 toast 提示。
 */
data class TermShrinkOutcome(
    val candidateEnd: IsoDate?,
    val blockingWeek: Int?,
)

/**
 * 学期周数**缩小**到 [targetWeeks] 周时的候选结束日（周数滚轮确认步的纯函数）。
 *
 * 规则：目标周内找「最早没课的一天」——
 * `candidateEnd = max(目标周周一, 全部课程最后一次上课日的次日)`。
 * 末周只上到周中的学期（如 17 周收成 16 周、16 周的课上到周五）可以直接收成整周数，
 * 结束日取周六（最后一个上课日的次日）；目标周之后完全没课时取目标周周一。
 * 此前固定整周平移（结束日与旧结束日同星期几），会停在某个仍有课的周中而整体失败。
 *
 * 口径约定：
 * - 「最后一次上课日」与校验器 R14/WeeksBeyondTerm 同式：取 [WeekPattern.maxWeek]（段末周
 *   即算，奇偶段不逐周展开），日期 = 第 1 周周一 + (段末周-1)×7 + 星期偏移；
 * - 含隐藏的周末课（设置类 mutator 按严格口径判定，周末排了课同样是真实占用）；
 * - 放不下 = 最后上课日晚于目标周周六（目标周满排到周日，或更晚的周仍有课），
 *   [TermShrinkOutcome.blockingWeek] 即最后上课日所在教学周。
 *
 * 调用方在 candidateEnd 非空后仍需走整表校验（草稿调课等仍可能拦截）。
 */
fun termEndForFewerWeeks(
    termStart: IsoDate,
    targetWeeks: Int,
    courses: List<CourseEntry>,
): TermShrinkOutcome {
    val firstMonday = termStart.mondayOfWeek()
    val targetMonday = firstMonday + (targetWeeks - 1) * 7
    val targetSunday = targetMonday + 6

    // 全部课程的最后一次上课日（口径见 KDoc；无课恒放行）
    var lastClassDay: Long = Long.MIN_VALUE
    for (course in courses) {
        val maxWeek = course.weeks.maxWeek() ?: continue
        val date = firstMonday + (maxWeek - 1) * 7 + (course.dayOfWeek - 1)
        if (date.epochDay > lastClassDay) lastClassDay = date.epochDay
    }

    // 目标周之后已无课：结束日取目标周周一（最早没课的一天，总周数恰为 targetWeeks）
    if (lastClassDay == Long.MIN_VALUE || lastClassDay < targetMonday.epochDay) {
        return TermShrinkOutcome(candidateEnd = targetMonday, blockingWeek = null)
    }
    val earliestFree = IsoDate(lastClassDay) + 1
    return if (earliestFree.epochDay > targetSunday.epochDay) {
        // 最后一次上课落在目标周周日或更晚：收不成目标周数，点名它所在的教学周
        val blockingWeek = ((IsoDate(lastClassDay).mondayOfWeek() - firstMonday).toInt() / 7) + 1
        TermShrinkOutcome(candidateEnd = null, blockingWeek = blockingWeek)
    } else {
        TermShrinkOutcome(candidateEnd = earliestFree, blockingWeek = null)
    }
}
