package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText

/**
 * 课表纯函数校验（R1–R13）。无 I/O、不修改输入，返回**全部**错误而非 fail-fast，
 * 供未来 UI 一次展示。
 *
 * 规则一览：
 *  R1 termStart 必须周一            R2 termEnd 必须周五/周日
 *  R2b daysPerWeek=7 ⇒ termEnd 必为周日
 *  R3 end≥start 且整周边界          R4 daysPerWeek ∈ {5,7}
 *  R5 节数 ∈ 1..12                  R6 时间格式
 *  R7 时间有序不重叠                R8 课表名非空白
 *  R9-R12 课程字段/周数范围          R13 同一天 span 相交且周数相交 ⇒ 冲突
 */
object ScheduleValidator {

    const val MIN_SLOTS = 1
    const val MAX_SLOTS = 12
    const val MAX_SPAN = 5

    /** 全量校验：设置 + 课程（含同格周数重叠） */
    fun validate(schedule: Schedule): List<ScheduleError> {
        val settings = validateSettingsOnly(schedule)
        return settings + courses(schedule, settingsValid = settings.isEmpty())
    }

    /** 只校验设置（学期/天数/节次/课表名），不查课程 —— 供 setTerm/setSlots 等快速路径 */
    fun validateSettingsOnly(schedule: Schedule): List<ScheduleError> {
        val errors = mutableListOf<ScheduleError>()
        val s = schedule

        // R8 课表名
        if (s.name.isBlank()) errors += ScheduleError.InvalidScheduleName

        // R1 起始必须周一
        if (s.termStart.isoDayOfWeek() != 1) {
            errors += ScheduleError.TermNotMonday(s.termStart)
        }

        // R2 / R2b 终止必须周五或周日；7 天模式必须周日（否则末周周末日期越界）
        val endDow = s.termEnd.isoDayOfWeek()
        val endOk = when {
            endDow == 7 -> true
            endDow == 5 -> s.daysPerWeek == 5
            else -> false
        }
        if (!endOk) errors += ScheduleError.TermEndInvalid(s.termEnd)

        // R3 end≥start 且落在整周边界（R1+R2 成立时 diff%7 自动 ∈ {4,6}，仍显式双保险）
        val diff = s.termEnd - s.termStart
        if (diff < 0 || Math.floorMod(diff, 7L) !in setOf(4L, 6L)) {
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

    /** 只校验课程字段与同格冲突（依赖设置的周界检查在设置非法时自动跳过） */
    fun validateCourses(schedule: Schedule): List<ScheduleError> =
        courses(schedule, settingsValid = validateSettingsOnly(schedule).isEmpty())

    private fun courses(schedule: Schedule, settingsValid: Boolean): List<ScheduleError> {
        val errors = mutableListOf<ScheduleError>()

        for (course in schedule.courses) {
            // R8 课程名非空白
            if (course.name.isBlank()) {
                errors += ScheduleError.CourseFieldInvalid(course.id, FieldReason.BlankName)
            }
            if (settingsValid) {
                // R9 星期范围（7 天模式才允许 6/7）
                if (course.dayOfWeek !in 1..schedule.daysPerWeek) {
                    errors += ScheduleError.CourseFieldInvalid(course.id, FieldReason.DayOutOfRange)
                }
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
            // R11 周数不超学期总周数（依赖合法学期）
            if (settingsValid) {
                val maxWeek = course.weeks.maxWeek()
                if (maxWeek != null && maxWeek > schedule.totalWeeks) {
                    errors += ScheduleError.WeeksBeyondTerm(course.id, schedule.totalWeeks)
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
}
