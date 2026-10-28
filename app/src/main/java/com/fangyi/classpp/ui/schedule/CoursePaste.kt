package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.ScheduleError
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.newUuid

/**
 * 以剪贴板快照为模板在目标格建课：id 换新、星期/起始节换成目标格，
 * 跨度等其余字段原样保留（跨节课程粘贴后仍跨节）。
 */
internal fun CourseEntry.pastedAt(day: Int, startSlot: Int): CourseEntry =
    copy(id = newUuid(), dayOfWeek = day, startSlot = startSlot)

/** 错误是否指向某门课：粘贴预检只拦与粘贴结果相关的错误，草稿里无关的旧错误不误伤 */
internal fun ScheduleError.involvesCourse(courseId: String): Boolean = when (this) {
    is ScheduleError.CourseFieldInvalid -> this.courseId == courseId
    is ScheduleError.WeekSegmentInvalid -> this.courseId == courseId
    is ScheduleError.WeeksBeyondTerm -> this.courseId == courseId
    is ScheduleError.TermEndInvalid -> this.courseId == courseId
    is ScheduleError.GridConflict -> a.id == courseId || b.id == courseId
    else -> false
}
