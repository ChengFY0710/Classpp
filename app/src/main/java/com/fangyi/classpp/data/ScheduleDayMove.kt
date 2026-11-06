package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.newUuid

/**
 * 一次「整天调休」的计算结果：把某周某天真正要上的全部课次，整体平移到目标周/星期，
 * 每门课保持原节次。
 *
 * 不新增数据模型：一次调休展开成每门课一条 [CourseAdjustment]，
 * 课表渲染、日视图、上课提醒、分享/备份的下游口径全部与单次调课一致。
 */
data class DayMovePlan(
    /** 原上课日当天真正要上的课次（含被别的调课移入的调课卡），按起始节排序 */
    val occurrences: List<CourseOccurrence> = emptyList(),
    /** 需要新增或更新的调课记录 */
    val upserts: List<CourseAdjustment> = emptyList(),
    /** 需要删除的调课记录 id：调课卡被移回「原周 + 原星期 + 原节次」时还原为常规课 */
    val removals: List<String> = emptyList(),
    /** 只与本次候选记录相关的校验错误（已按候选 id / 原课次过滤） */
    val errors: List<ScheduleError> = emptyList(),
)

/**
 * 计算一次整天调休。
 *
 * - 源课次走 [Schedule.occurrencesOn]：该周不上、或已被别的调课移走的常规课不会出现；
 * - 调课卡跟着平移：保留 id / fromWeek / 目标节次跨度，只改目标周与目标星期；
 *   恰好移回课程原位置时改为删除该记录（等价于取消这次单次调课）；
 * - 候选表整体过 [ScheduleValidator.validateCourses]，只取与候选相关的错误，
 *   历史遗留的无关错误不会把当前面板卡死（同单次调课编辑面板的过滤口径）。
 *
 * [sourceWeek]/[targetWeek] 越界或 [sourceDay]/[targetDay] 不在 1..7 时返回空方案，
 * 调用方按「当天没有课」处理。
 */
fun buildDayMovePlan(
    schedule: Schedule,
    sourceWeek: Int,
    sourceDay: Int,
    targetWeek: Int,
    targetDay: Int,
): DayMovePlan {
    if (sourceWeek !in 1..schedule.totalWeeks || sourceDay !in 1..7 ||
        targetWeek !in 1..schedule.totalWeeks || targetDay !in 1..7
    ) {
        return DayMovePlan()
    }

    // 与 ScheduleValidator 的目标日期算法同源：周一为一周第一天
    val sourceDate = schedule.termStart.mondayOfWeek() +
        (sourceWeek - 1) * 7 + (sourceDay - 1)
    val occurrences = schedule.occurrencesOn(sourceDate)
    if (occurrences.isEmpty()) return DayMovePlan()

    val upserts = mutableListOf<CourseAdjustment>()
    val removals = mutableListOf<String>()

    for (occurrence in occurrences) {
        val course = occurrence.course
        val existing = occurrence.adjustment
        if (existing == null) {
            // 常规课：新建一条把该课次平移到目标日，节次跨度原样保留
            upserts += CourseAdjustment(
                id = newUuid(),
                courseId = course.id,
                fromWeek = sourceWeek,
                toWeek = targetWeek,
                toDayOfWeek = targetDay,
                toStartSlot = course.startSlot,
                toSpan = course.span,
            )
        } else {
            // 调课卡：跟着当天整体平移，只改目标周/星期；回到课程原位置时直接撤销记录
            val returnsHome = targetWeek == existing.fromWeek &&
                targetDay == course.dayOfWeek &&
                existing.toStartSlot == course.startSlot &&
                existing.toSpan == course.span
            when {
                returnsHome -> removals += existing.id
                existing.toWeek == targetWeek && existing.toDayOfWeek == targetDay -> Unit
                else -> upserts += existing.copy(
                    toWeek = targetWeek,
                    toDayOfWeek = targetDay,
                )
            }
        }
    }

    // 防御：常规课若已有同课次调课记录（正常不会出现在源日），先按课次清理再建，
    // 避免候选表同时出现两条 (courseId, fromWeek) 相同的记录
    val createdKeys = upserts
        .filter { created -> schedule.adjustments.none { it.id == created.id } }
        .mapTo(HashSet()) { it.courseId to it.fromWeek }
    if (createdKeys.isNotEmpty()) {
        schedule.adjustments
            .filter { it.id !in removals && (it.courseId to it.fromWeek) in createdKeys }
            .forEach { removals += it.id }
    }

    val replaced = removals.toSet() + upserts.mapTo(HashSet()) { it.id }
    val candidate = schedule.copy(
        adjustments = schedule.adjustments.filterNot { it.id in replaced } + upserts,
    )
    val candidateIds = upserts.mapTo(HashSet()) { it.id }
    val candidateKeys = upserts.mapTo(HashSet()) { it.courseId to it.fromWeek }

    val errors = ScheduleValidator.validateCourses(candidate).filter { error ->
        when (error) {
            is ScheduleError.AdjustmentConflict -> error.adjustmentId in candidateIds
            is ScheduleError.AdjustmentTargetInvalid -> error.adjustmentId in candidateIds
            is ScheduleError.AdjustmentUnchanged -> error.adjustmentId in candidateIds
            is ScheduleError.AdjustmentSourceInvalid -> error.adjustmentId in candidateIds
            is ScheduleError.AdjustmentDuplicate ->
                (error.courseId to error.fromWeek) in candidateKeys
            else -> false
        }
    }

    return DayMovePlan(
        occurrences = occurrences,
        upserts = upserts,
        removals = removals,
        errors = errors,
    )
}
