package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import java.util.GregorianCalendar

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
enum class FieldReason { BlankName, SpanOutOfRange }

/**
 * 数据层全部结构化错误。message 供日志/断言，未来 UI 按子类模式匹配出文案。
 * 返回全部错误而非 fail-fast（[ScheduleValidator.validate]），便于 UI 一次展示。
 */
sealed class ScheduleError(val message: String) {

    data class TermRangeInvalid(val start: IsoDate, val end: IsoDate) :
        ScheduleError("termEnd $end must not be before termStart $start (may end mid week)")

    /** 课程最后一次上课落在学期结束日之后（如切到 5 天后周六/周日的课没了落点） */
    data class TermEndInvalid(val courseId: String, val dayOfWeek: Int, val date: IsoDate) :
        ScheduleError("course $courseId ends on $date, after termEnd")

    data class DaysPerWeekInvalid(val value: Int) :
        ScheduleError("daysPerWeek must be 5 or 7, got $value")

    data class SlotCountInvalid(val size: Int) :
        ScheduleError("slot count must be in 1..12, got $size")

    data class SlotTimeFormatInvalid(val index: Int, val raw: String) :
        ScheduleError("slot #$index time '$raw' is not H:mm")

    data class SlotOrderInvalid(val index: Int, val detail: String) :
        ScheduleError("slot #$index invalid: $detail")

    data object InvalidScheduleName : ScheduleError("schedule name must not be blank")

    /** 保存自定义时间方案时名称为空 */
    data object InvalidTimeSchemeName : ScheduleError("time scheme name must not be blank")

    data class CourseFieldInvalid(val courseId: String, val reason: FieldReason) :
        ScheduleError("course $courseId invalid: $reason")

    data class WeekSegmentInvalid(val courseId: String, val index: Int) :
        ScheduleError("course $courseId week segment #$index invalid (start > end or start < 1)")

    /** 课程周数超出学期总周数；[lastDate] = 它按当前排课会上的最后一个日期 */
    data class WeeksBeyondTerm(val courseId: String, val maxWeek: Int, val lastDate: IsoDate) :
        ScheduleError("course $courseId weeks exceed term (max $maxWeek, last day $lastDate)")

    data class GridConflict(val dayOfWeek: Int, val a: CourseRef, val b: CourseRef) :
        ScheduleError("courses ${a.id} and ${b.id} overlap on day $dayOfWeek with intersecting weeks")

    /** 调课引用的课程不存在（文件被外部改写/导入脏数据） */
    data class AdjustmentCourseMissing(val adjustmentId: String, val courseId: String) :
        ScheduleError("adjustment $adjustmentId references missing course $courseId")

    /** 原课次不成立：fromWeek 越界，或该课当周本来就不上课 */
    data class AdjustmentSourceInvalid(
        val adjustmentId: String,
        val courseName: String,
        val fromWeek: Int,
    ) : ScheduleError("adjustment $adjustmentId source week $fromWeek is not an occurrence of '$courseName'")

    /** 目标越界；[detail] 仅供日志，不进 UI */
    data class AdjustmentTargetInvalid(
        val adjustmentId: String,
        val courseName: String,
        val detail: String,
    ) : ScheduleError("adjustment $adjustmentId target invalid: $detail")

    /** 调课前后是同一个时间（选了个寂寞） */
    data class AdjustmentUnchanged(val adjustmentId: String, val courseName: String) :
        ScheduleError("adjustment $adjustmentId target equals source")

    /** 同一门课的同一周只能有一条调课记录 */
    data class AdjustmentDuplicate(
        val courseId: String,
        val courseName: String,
        val fromWeek: Int,
    ) : ScheduleError("'$courseName' already has an adjustment for week $fromWeek")

    /** 调课目标格与常规课/另一条调课冲突；[conflictName] 供 UI 点名 */
    data class AdjustmentConflict(
        val adjustmentId: String,
        val courseName: String,
        val conflictName: String,
        val dayOfWeek: Int,
        val startSlot: Int,
        val endSlot: Int,
    ) : ScheduleError(
        "adjustment $adjustmentId ('$courseName') overlaps '$conflictName' on day $dayOfWeek slots $startSlot-$endSlot",
    )

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

/** “今天”与“当前时刻”的来源；测试注入 FakeClock */
fun interface Clock {
    fun today(): IsoDate

    /** 当天第几分钟（0..1439）；默认取系统本地时间，测试可覆写固定时刻 */
    fun minuteOfDay(): Int = systemMinuteOfDay()

    companion object {
        val system: Clock = Clock { IsoDate.today() }
    }
}

/** 系统本地时间当天第几分钟（0..1439）。minSdk 24 未开脱糖，用 Calendar 而非 java.time。 */
fun systemMinuteOfDay(millis: Long = System.currentTimeMillis()): Int {
    val cal = GregorianCalendar().apply { timeInMillis = millis }
    return cal.get(GregorianCalendar.HOUR_OF_DAY) * 60 + cal.get(GregorianCalendar.MINUTE)
}
