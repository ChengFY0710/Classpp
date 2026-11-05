package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule

/**
 * 某门课在某周课表中的一次呈现。
 *
 * [adjustment] 非空 = 这是一张**调课卡**：它的有效上课位置取 [CourseAdjustment] 的目标值，
 * 而 [course] 仍保留原条目的星期/节次/跨度——UI 用原值显示"原 周三 第1-2节"。
 */
data class CourseOccurrence(
    val course: CourseEntry,
    /** 该周是否真上课（false = 本周不上，UI 按 showInactiveCourses 决定灰显或隐藏） */
    val active: Boolean,
    /** 非空 = 本次是单次调课卡 */
    val adjustment: CourseAdjustment? = null,
) {
    /** 有效星期：调课卡取目标星期，常规课取原值 */
    val dayOfWeek: Int get() = adjustment?.toDayOfWeek ?: course.dayOfWeek

    /** 有效起始节：调课卡取目标起始节 */
    val startSlot: Int get() = adjustment?.toStartSlot ?: course.startSlot

    /** 有效跨度：调课卡取目标跨度 */
    val span: Int get() = adjustment?.toSpan ?: course.span

    val endSlot: Int get() = startSlot + span - 1

    val adjusted: Boolean get() = adjustment != null
}

/**
 * [date] 落在第几个教学周（第 1 周 = termStart 所在日历周，可为 0 / 负数 / 超过总周数）。
 * 与 [ScheduleRepository.calendarWeek] 同一公式，供纯函数与下游共用。
 */
fun Schedule.weekOf(date: IsoDate): Int =
    ((date.mondayOfWeek() - termStart.mondayOfWeek()).toInt() / 7) + 1

/**
 * 第 [week] 周的全部课次：常规课 + 调入的调课卡，**源课次被调走则整条不再出现**。
 *
 * - `week < 1`（开学前）保持旧口径：全部课程 `active = false`，不应用调课；
 * - 常规课仍按 [CourseEntry.weeks] 决定 active；调课卡恒为 active（它是一次真实要上的课）；
 * - 顺序沿用原课程列表顺序，调课卡追加在后。交替课的"先添加者补位"规则因此不受影响
 *   （调课卡是在课卡，解析时先占位）。
 */
fun List<CourseEntry>.occurrencesForWeek(
    week: Int,
    adjustments: List<CourseAdjustment> = emptyList(),
): List<CourseOccurrence> {
    if (week < 1) return map { CourseOccurrence(it, active = false) }

    val movedSources = adjustments
        .filter { it.fromWeek == week }
        .mapTo(HashSet()) { it.courseId }

    val result = ArrayList<CourseOccurrence>(size + adjustments.size)
    for (course in this) {
        if (course.id in movedSources) continue
        result += CourseOccurrence(course, active = course.weeks.contains(week))
    }
    for (adjustment in adjustments) {
        if (adjustment.toWeek != week) continue
        val course = firstOrNull { it.id == adjustment.courseId } ?: continue
        result += CourseOccurrence(course, active = true, adjustment = adjustment)
    }
    return result
}

/**
 * [date] 这一天真正要上的课（含调课），按起始节排序。日视图、上课提醒与"下一节课"
 * 共用这一口径：源课次在源日期消失，调课卡在目标日期以目标节次出现。
 */
fun Schedule.occurrencesOn(date: IsoDate): List<CourseOccurrence> {
    val week = weekOf(date)
    if (week !in 1..totalWeeks) return emptyList()
    val day = date.isoDayOfWeek()

    val movedSources = adjustments
        .filter { it.fromWeek == week }
        .mapTo(HashSet()) { it.courseId }

    val result = ArrayList<CourseOccurrence>(courses.size + adjustments.size)
    for (course in courses) {
        if (course.dayOfWeek != day) continue
        if (!course.weeks.contains(week)) continue
        if (course.id in movedSources) continue
        result += CourseOccurrence(course, active = true)
    }
    for (adjustment in adjustments) {
        if (adjustment.toWeek != week || adjustment.toDayOfWeek != day) continue
        val course = courses.firstOrNull { it.id == adjustment.courseId } ?: continue
        result += CourseOccurrence(course, active = true, adjustment = adjustment)
    }
    return result.sortedBy { it.startSlot }
}
