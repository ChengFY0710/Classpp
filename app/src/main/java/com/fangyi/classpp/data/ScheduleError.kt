package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate

/** 课程的轻量引用（错误上下文用，避免在错误里携带整门课） */
data class CourseRef(
    val id: String,
    val name: String,
    val dayOfWeek: Int,
    val startSlot: Int,
    val span: Int,
) {
    companion object {
        fun of(course: CourseEntry): CourseRef =
            CourseRef(course.id, course.name, course.dayOfWeek, course.startSlot, course.span)
    }
}

/** [ScheduleError.CourseFieldInvalid] 的具体原因 */
enum class FieldReason { BlankName, DayOutOfRange, SpanOutOfRange }

/**
 * 数据层全部结构化错误。message 供日志/断言，未来 UI 按子类模式匹配出文案。
 * 返回全部错误而非 fail-fast（[ScheduleValidator.validate]），便于 UI 一次展示。
 */
sealed class ScheduleError(val message: String) {

    data class TermNotMonday(val date: IsoDate) :
        ScheduleError("termStart $date is not a Monday")

    data class TermEndInvalid(val date: IsoDate) :
        ScheduleError("termEnd $date must be Friday or Sunday")

    data class TermRangeInvalid(val start: IsoDate, val end: IsoDate) :
        ScheduleError("termEnd $end must end the last week of $start (diff % 7 in {4,6})")

    data class DaysPerWeekInvalid(val value: Int) :
        ScheduleError("daysPerWeek must be 5 or 7, got $value")

    data class SlotCountInvalid(val size: Int) :
        ScheduleError("slot count must be in 1..12, got $size")

    data class SlotTimeFormatInvalid(val index: Int, val raw: String) :
        ScheduleError("slot #$index time '$raw' is not H:mm")

    data class SlotOrderInvalid(val index: Int, val detail: String) :
        ScheduleError("slot #$index invalid: $detail")

    data object InvalidScheduleName : ScheduleError("schedule name must not be blank")

    data class CourseFieldInvalid(val courseId: String, val reason: FieldReason) :
        ScheduleError("course $courseId invalid: $reason")

    data class WeekSegmentInvalid(val courseId: String, val index: Int) :
        ScheduleError("course $courseId week segment #$index invalid (start > end or start < 1)")

    data class WeeksBeyondTerm(val courseId: String, val maxWeek: Int) :
        ScheduleError("course $courseId weeks exceed term (max $maxWeek)")

    data class GridConflict(val dayOfWeek: Int, val a: CourseRef, val b: CourseRef) :
        ScheduleError("courses ${a.id} and ${b.id} overlap on day $dayOfWeek with intersecting weeks")

    /** 设置变更会波及已有课程时整体拒绝，绝不静默删课 */
    data class CoursesOutOfRange(val affected: List<CourseRef>) :
        ScheduleError("${affected.size} course(s) would fall out of range: " +
            affected.joinToString { it.name })

    data class NotFound(val id: String) : ScheduleError("schedule/course not found: $id")

    data class ImportFormatInvalid(val detail: String) :
        ScheduleError("share payload is not valid JSON: $detail")

    data class ImportVersionUnsupported(val version: Int) :
        ScheduleError("share payload formatVersion $version is newer than supported")

    data class ImportKindMismatch(val kind: String) :
        ScheduleError("share payload kind mismatch: '$kind'")

    data class PersistFailed(val detail: String) : ScheduleError("persist failed: $detail")
}

/** 变更操作结果：错误必须被调用方处理（如拒绝策略） */
sealed interface OpResult {
    data object Ok : OpResult
    data class Err(val error: ScheduleError) : OpResult
}

/** 读取/导出/导入等携带返回值的操作结果 */
sealed interface ReadResult<out T> {
    data class Ok<T>(val value: T) : ReadResult<T>
    data class Err(val error: ScheduleError) : ReadResult<Nothing>
}

/** 今天相对学期的位置；不钳制、不抛错，由调用方决定 UI 文案 */
sealed interface TermPosition {
    data object BeforeTerm : TermPosition
    data class InTerm(val week: Int) : TermPosition
    data object AfterTerm : TermPosition
}

/** 存储载入状态，供未来 UI 提示“已从备份恢复/已重置” */
enum class LoadState { Ready, RestoredFromBackup, ResetAfterCorruption }

/** “今天”的来源；测试注入 FakeClock */
fun interface Clock {
    fun today(): IsoDate

    companion object {
        val system: Clock = Clock { IsoDate.today() }
    }
}
