package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText

/**
 * 课表纯函数校验（R2–R15）。无 I/O、不修改输入，返回**全部**错误而非 fail-fast，
 * 供未来 UI 一次展示。
 *
 * 规则一览：
 *  R2 termEnd 可落在任意一天（可半个教学周；termStart 同样可为任意星期几）
 *  R3 end≥start                     R4 daysPerWeek ∈ {5,7}
 *  R5 节数 ∈ [MIN_SLOTS]..[MAX_SLOTS]   R6 时间格式
 *  R7 时间有序不重叠                R8 课表名非空白
 *  R9-R12 课程字段/周数范围          R13 同一天 span 相交且周数相交 ⇒ 冲突
 *  R14 课程最后一次上课不得晚于 termEnd（按**日期**判定；termEnd 可停在周中）
 *  R15 单次调课：源课次成立、目标越界/无变化、同课次唯一、目标格与常规课/其他调课冲突
 * （原 R1「termStart 必须周一」已取消：开学日可为任意一天，周数一律按日历周对齐。）
 *
 * 另有两条"视图切换友好"约定：
 *  · termEnd 用**真实日期**判定课程落点，不再要求它是周五/周日；
 *  · 落在当前每周天数之外的课（5 天课表里的周六/周日课）视为"当前视图看不到"，
 *    **保留在数据里**且不参与 R10/R11/R14——5 天视图只是不画它们，切回 7 天即原样出现，
 *    所以 5/7 天切换永远不会因为课程而失败。
 */
object ScheduleValidator {

    const val MIN_SLOTS = 1
    const val MAX_SLOTS = 30
    const val MAX_SPAN = 5

    /** 全量校验：设置 + 课程（含同格周数重叠）+ 单次调课 */
    fun validate(schedule: Schedule): List<ScheduleError> {
        val settings = validateSettingsOnly(schedule)
        val settingsValid = settings.isEmpty()
        return settings + courses(schedule, settingsValid) + adjustments(schedule, settingsValid)
    }

    /** 只校验设置（学期/天数/节次/课表名），不查课程 —— 供 setTerm/setSlots 等快速路径 */
    fun validateSettingsOnly(schedule: Schedule): List<ScheduleError> {
        val errors = mutableListOf<ScheduleError>()
        val s = schedule

        // R8 课表名
        if (s.name.isBlank()) errors += ScheduleError.InvalidScheduleName

        // R2/R3 起止任意星期几都合法（开学日不再要求周一，结束日可停在周中），
        // 只要求结束日不早于开始日
        if (s.termEnd.epochDay < s.termStart.epochDay) {
            errors += ScheduleError.TermRangeInvalid(s.termStart, s.termEnd)
        }

        // R4 每周天数
        if (s.daysPerWeek != 5 && s.daysPerWeek != 7) {
            errors += ScheduleError.DaysPerWeekInvalid(s.daysPerWeek)
        }

        // R5 节数
        if (s.slotCount !in MIN_SLOTS..MAX_SLOTS) {
            errors += ScheduleError.SlotCountInvalid(s.slotCount)
        }

        // R6/R7 时间格式与顺序
        var prevEndMinutes: Int? = null
        s.slots.forEachIndexed { index, slot ->
            val start = TimeText.parseMinutes(slot.startTime)
            val end = TimeText.parseMinutes(slot.endTime)
            if (start == null) errors += ScheduleError.SlotTimeFormatInvalid(index + 1, slot.startTime)
            if (end == null) errors += ScheduleError.SlotTimeFormatInvalid(index + 1, slot.endTime)
            if (start != null && end != null) {
                if (start >= end) {
                    errors += ScheduleError.SlotOrderInvalid(
                        index + 1,
                        "start ${slot.startTime} must be before end ${slot.endTime}",
                    )
                }
                val prev = prevEndMinutes
                if (prev != null && prev > start) {
                    errors += ScheduleError.SlotOrderInvalid(
                        index + 1,
                        "starts at ${slot.startTime} before previous slot ends",
                    )
                }
                prevEndMinutes = end
            }
        }

        return errors
    }

    /** 只校验课程字段/同格冲突与调课（依赖设置的周界检查在设置非法时自动跳过） */
    fun validateCourses(schedule: Schedule): List<ScheduleError> {
        val settingsValid = validateSettingsOnly(schedule).isEmpty()
        return courses(schedule, settingsValid) + adjustments(schedule, settingsValid)
    }

    private fun courses(schedule: Schedule, settingsValid: Boolean): List<ScheduleError> {
        val errors = mutableListOf<ScheduleError>()

        for (course in schedule.courses) {
            // R8 课程名非空白
            if (course.name.isBlank()) {
                errors += ScheduleError.CourseFieldInvalid(course.id, FieldReason.BlankName)
            }
            // 落在当前每周天数之外的课（5 天课表里的周六/周日课）：切视图时**保留不删**，
            // 只是当前视图看不到，故不参与任何与天数/学期边界相关的校验——
            // 否则 7→5 的切换会被这些"藏起来的课"挡住，再切回 7 天就永远看不到了。
            val hidden = course.dayOfWeek !in 1..schedule.daysPerWeek
            if (settingsValid && !hidden) {
                // R10 起始节/跨度/末节
                val spanOk = course.span in 1..MAX_SPAN &&
                    course.startSlot in 1..schedule.slotCount &&
                    course.endSlot <= schedule.slotCount
                if (!spanOk) {
                    errors += ScheduleError.CourseFieldInvalid(course.id, FieldReason.SpanOutOfRange)
                }
            }

            // R11 周数段形态（不依赖学期）
            course.weeks.segments.forEachIndexed { index, segment ->
                if (segment.start < 1 || segment.start > segment.end) {
                    errors += ScheduleError.WeekSegmentInvalid(course.id, index)
                }
            }
            // R11/R14 周数与学期边界（依赖合法学期）：藏起来的课不校验（见上）
            if (settingsValid && !hidden) {
                val maxWeek = course.weeks.maxWeek()
                if (maxWeek != null && maxWeek > schedule.totalWeeks) {
                    // 顺带给出"按当前排课，这门课最后一次会上到哪天"，供 UI 直接说清原因
                    // （第 N 周的某天 = 第 1 周所在日历周的周一 + (N-1)*7 + 星期偏移）
                    val lastDate = schedule.termStart.mondayOfWeek() +
                        (maxWeek - 1) * 7 + (course.dayOfWeek - 1)
                    errors += ScheduleError.WeeksBeyondTerm(
                        courseId = course.id,
                        maxWeek = maxWeek,
                        lastDate = lastDate,
                    )
                }
                // R14 最后一次上课不得晚于学期结束日：termEnd 可停在周中，
                // 此时末周里晚于它的那几天上课的课就没有落点了（如 5 天课表的末周只上到周三，
                // 周四/周五的课即越界）。用真实日期判定，与"末周可只上一部分"完全自洽。
                val lastWeek = course.weeks.maxWeek()
                if (lastWeek != null && lastWeek in 1..schedule.totalWeeks) {
                    val lastDay = schedule.termStart.mondayOfWeek() +
                        (lastWeek - 1) * 7 + (course.dayOfWeek - 1)
                    if (lastDay.epochDay > schedule.termEnd.epochDay) {
                        errors += ScheduleError.TermEndInvalid(
                            courseId = course.id,
                            dayOfWeek = course.dayOfWeek,
                            date = lastDay,
                        )
                    }
                }
            }
        }

        // R13 同一天 span 相交且周数相交 ⇒ 冲突（每对只报一次）
        val byDay = schedule.courses.groupBy { it.dayOfWeek }
        for ((_, dayCourses) in byDay) {
            for (i in dayCourses.indices) {
                for (j in i + 1 until dayCourses.size) {
                    val a = dayCourses[i]
                    val b = dayCourses[j]
                    val spanIntersects =
                        a.startSlot <= b.endSlot && b.startSlot <= a.endSlot
                    if (spanIntersects && a.weeks.intersects(b.weeks)) {
                        errors += ScheduleError.GridConflict(
                            dayOfWeek = a.dayOfWeek,
                            a = a.toRef(),
                            b = b.toRef(),
                        )
                    }
                }
            }
        }

        return errors
    }

    /**
     * 单次调课校验（R15）：
     *  - 课程存在，原课次成立（fromWeek 在学期内且该课当周有课）；
     *  - 目标周/日期/星期/节次在合法范围；toSpan 1..[MAX_SPAN] 且放得下；
     *  - 同一 (courseId, fromWeek) 唯一；目标与源完全相同视为无变化；
     *  - 目标格不与「该周真正在上的常规课」或另一条调课卡在时间上重叠。
     *
     * 目标周里被**别的调课**移走的常规课次是空出来的格子，不参与冲突判定；
     * 同一周同一格内改节次时源课次会被移除，也不与自身冲突。
     */
    private fun adjustments(schedule: Schedule, settingsValid: Boolean): List<ScheduleError> {
        if (schedule.adjustments.isEmpty()) return emptyList()
        val errors = mutableListOf<ScheduleError>()
        val coursesById = schedule.courses.associateBy { it.id }

        fun sourceExists(adj: CourseAdjustment): Boolean {
            val course = coursesById[adj.courseId] ?: return false
            if (settingsValid && adj.fromWeek !in 1..schedule.totalWeeks) return false
            return course.weeks.contains(adj.fromWeek)
        }

        // 目标周里被调走的常规课次：那些格子当周是空的，不算冲突
        val movedSources = schedule.adjustments
            .filter { sourceExists(it) }
            .mapTo(HashSet()) { it.courseId to it.fromWeek }

        val seen = HashSet<Pair<String, Int>>()
        for (adj in schedule.adjustments) {
            val course = coursesById[adj.courseId]
            if (course == null) {
                errors += ScheduleError.AdjustmentCourseMissing(adj.id, adj.courseId)
                continue
            }
            if (!seen.add(adj.courseId to adj.fromWeek)) {
                errors += ScheduleError.AdjustmentDuplicate(adj.courseId, course.name, adj.fromWeek)
                continue
            }
            if (!sourceExists(adj)) {
                errors += ScheduleError.AdjustmentSourceInvalid(adj.id, course.name, adj.fromWeek)
            }

            // 结构性目标字段（不依赖学期设置）
            if (adj.toSpan !in 1..MAX_SPAN || adj.toDayOfWeek !in 1..7) {
                errors += ScheduleError.AdjustmentTargetInvalid(
                    adj.id,
                    course.name,
                    "day ${adj.toDayOfWeek} span ${adj.toSpan}",
                )
                continue
            }

            var targetUsable = true
            if (settingsValid) {
                if (adj.toWeek !in 1..schedule.totalWeeks) {
                    errors += ScheduleError.AdjustmentTargetInvalid(
                        adj.id,
                        course.name,
                        "week ${adj.toWeek} out of 1..${schedule.totalWeeks}",
                    )
                    targetUsable = false
                } else {
                    val targetDate = schedule.termStart.mondayOfWeek() +
                        (adj.toWeek - 1) * 7 + (adj.toDayOfWeek - 1)
                    if (targetDate.epochDay > schedule.termEnd.epochDay) {
                        errors += ScheduleError.AdjustmentTargetInvalid(
                            adj.id,
                            course.name,
                            "target $targetDate after termEnd",
                        )
                        targetUsable = false
                    }
                }
                val targetEnd = adj.toStartSlot + adj.toSpan - 1
                if (adj.toStartSlot !in 1..schedule.slotCount || targetEnd > schedule.slotCount) {
                    errors += ScheduleError.AdjustmentTargetInvalid(
                        adj.id,
                        course.name,
                        "slots ${adj.toStartSlot}..$targetEnd out of 1..${schedule.slotCount}",
                    )
                    targetUsable = false
                }
                if (adj.toWeek == adj.fromWeek &&
                    adj.toDayOfWeek == course.dayOfWeek &&
                    adj.toStartSlot == course.startSlot &&
                    adj.toSpan == course.span
                ) {
                    errors += ScheduleError.AdjustmentUnchanged(adj.id, course.name)
                    targetUsable = false
                }
            }
            if (!targetUsable) continue

            val start = adj.toStartSlot
            val end = start + adj.toSpan - 1
            var conflict: ScheduleError? = null

            // 目标周该天真正的常规课（被别的调课移走的那一次不算）
            for (other in schedule.courses) {
                if (other.id == course.id && adj.toWeek == adj.fromWeek) continue
                if ((other.id to adj.toWeek) in movedSources) continue
                if (other.dayOfWeek != adj.toDayOfWeek) continue
                if (!other.weeks.contains(adj.toWeek)) continue
                if (start <= other.endSlot && other.startSlot <= end) {
                    conflict = ScheduleError.AdjustmentConflict(
                        adjustmentId = adj.id,
                        courseName = course.name,
                        conflictName = other.name,
                        dayOfWeek = adj.toDayOfWeek,
                        startSlot = start,
                        endSlot = end,
                    )
                    break
                }
            }

            // 与另一条调课卡的目标时间
            if (conflict == null) {
                for (other in schedule.adjustments) {
                    if (other.id == adj.id) continue
                    if (other.toWeek != adj.toWeek || other.toDayOfWeek != adj.toDayOfWeek) continue
                    val otherEnd = other.toStartSlot + other.toSpan - 1
                    if (start <= otherEnd && other.toStartSlot <= end) {
                        val otherName = coursesById[other.courseId]?.name ?: other.courseId
                        conflict = ScheduleError.AdjustmentConflict(
                            adjustmentId = adj.id,
                            courseName = course.name,
                            conflictName = otherName,
                            dayOfWeek = adj.toDayOfWeek,
                            startSlot = start,
                            endSlot = end,
                        )
                        break
                    }
                }
            }
            conflict?.let { errors += it }
        }
        return errors
    }
}
