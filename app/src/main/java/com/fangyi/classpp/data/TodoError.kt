package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.TodoTimeKind

/** [TodoError.StepInvalid] 的具体原因 */
enum class TodoStepReason { BlankTitle, DuplicateId }

/** [TodoError.AttachmentInvalid] 的具体原因 */
enum class TodoAttachmentReason { BlankName, InvalidId, InvalidFileName, DuplicateId }

/** [TodoError.DeadlineInvalid] 的具体原因 */
enum class TodoDeadlineReason { MissingMinute, MissingDate, MinuteOutOfRange }

/** [TodoError.RepeatInvalid] 的具体原因 */
enum class TodoRepeatReason {
    /** 有重复规则却没有任何日期——系列没有锚日期，展不出任何一期 */
    MissingDates,

    /** 日期档结束日期早于首个应完成日期——空窗口，写入成功也永远出不了卡 */
    EmptyWindow,
}

/**
 * 待办数据层全部结构化错误。message 供日志/断言，未来 UI 按子类模式匹配出文案。
 * 校验返回全部错误而非 fail-fast（[TodoValidator.validate]），便于 UI 一次展示。
 */
sealed class TodoError(val message: String) {

    data object BlankName : TodoError("todo name must not be blank")

    /** 时刻形态不是"无"却没有任何日期——时间没有落点 */
    data class DatesRequired(val timeKind: TodoTimeKind) :
        TodoError("timeKind $timeKind requires at least one date")

    data class TimePeriodInvalid(val startMinute: Int?, val endMinute: Int?) :
        TodoError("time period invalid: start=$startMinute end=$endMinute (need 0 <= start < end <= 1439)")

    data class DeadlineInvalid(val reason: TodoDeadlineReason) :
        TodoError("deadline invalid: $reason")

    /** 重复规则展不开任何一期（无锚日期 / 空窗口），写入会生成列表里永远不可见的死数据 */
    data class RepeatInvalid(val reason: TodoRepeatReason) :
        TodoError("repeat invalid: $reason")

    data class StepInvalid(val index: Int, val reason: TodoStepReason) :
        TodoError("step #$index invalid: $reason")

    data class AttachmentInvalid(val index: Int, val reason: TodoAttachmentReason) :
        TodoError("attachment #$index invalid: $reason")

    /** 附件流读取 / 复制到私有目录失败（权限失效、磁盘满等） */
    data class AttachmentImportFailed(val detail: String) :
        TodoError("attachment import failed: $detail")

    data class TagInvalid(val index: Int) : TodoError("tag #$index must not be blank")

    /** 新增待办撞上既有 id（正常只会由调用方 bug 触发） */
    data class DuplicateId(val id: String) : TodoError("todo id already exists: $id")

    data class NotFound(val id: String) : TodoError("todo not found: $id")

    data class ImportFormatInvalid(val detail: String) :
        TodoError("share payload is not valid JSON: $detail")

    data class ImportVersionUnsupported(val version: Int) :
        TodoError("share payload formatVersion $version is newer than supported")

    data class ImportKindMismatch(val kind: String) :
        TodoError("share payload kind mismatch: '$kind'")

    /** 空草稿列表（防御分支；正常待办都会产出事件，不会触发） */
    data object CalendarNoTime : TodoError("no calendar event to insert")

    /** 缺少 READ/WRITE_CALENDAR 运行时权限 */
    data object CalendarAccessDenied : TodoError("calendar permission not granted")

    data class CalendarInsertFailed(val detail: String) :
        TodoError("calendar insert failed: $detail")

    data class PersistFailed(val detail: String) : TodoError("persist failed: $detail")
}

/** 待办变更操作结果：错误必须被调用方处理 */
sealed interface TodoOpResult {
    data object Ok : TodoOpResult
    data class Err(val error: TodoError) : TodoOpResult
}

/** 待办读取/导出/导入等携带返回值的操作结果 */
sealed interface TodoReadResult<out T> {
    data class Ok<T>(val value: T) : TodoReadResult<T>
    data class Err(val error: TodoError) : TodoReadResult<Nothing>
}
