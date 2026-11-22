package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.hasEmptyWindow
import com.fangyi.classpp.data.store.TodoAttachmentStore

/**
 * 待办全量校验：返回全部错误而非 fail-fast，便于 UI 一次展示。
 *
 * 与 ScheduleValidator 同约定：只校验"值本身合法"；归一化（名称/标签 trim、日期
 * distinct+排序、completedAtMillis 不变式）由仓库在写入前完成，校验看到的是
 * 归一化后的数据——但校验器自身也不拒绝未归一化的输入（trim 后非空即放行）。
 */
object TodoValidator {

    /** 一天最后一分钟数（23:59） */
    const val MAX_MINUTE_OF_DAY = 1439

    fun validate(todo: Todo): List<TodoError> {
        val errors = mutableListOf<TodoError>()

        if (todo.name.isBlank()) errors += TodoError.BlankName

        // 时间：有时刻语义就必须有落点日期
        if (todo.timeKind != TodoTimeKind.None && todo.dates.isEmpty()) {
            errors += TodoError.DatesRequired(todo.timeKind)
        }
        if (todo.timeKind == TodoTimeKind.Period) {
            val start = todo.startMinute
            val end = todo.endMinute
            if (start == null || end == null ||
                start !in 0..MAX_MINUTE_OF_DAY || end !in 0..MAX_MINUTE_OF_DAY || start >= end
            ) {
                errors += TodoError.TimePeriodInvalid(start, end)
            }
        }

        // 截止：日期与时刻成对出现，时刻必须在一天之内
        if (todo.deadlineDate != null && todo.deadlineMinute == null) {
            errors += TodoError.DeadlineInvalid(TodoDeadlineReason.MissingMinute)
        }
        if (todo.deadlineDate == null && todo.deadlineMinute != null) {
            errors += TodoError.DeadlineInvalid(TodoDeadlineReason.MissingDate)
        }
        if (todo.deadlineMinute != null && todo.deadlineMinute !in 0..MAX_MINUTE_OF_DAY) {
            errors += TodoError.DeadlineInvalid(TodoDeadlineReason.MinuteOutOfRange)
        }

        // 重复：有规则就必须能展开出至少一期——无锚日期（MissingDates）、多锚日期
        //（MultipleDates，系列单锚语义：展开只认最早日期，其余日期永不展示）或日期档
        // 窗口早于首期（EmptyWindow，见 hasEmptyWindow）的规则写入成功后出不了完整的卡，
        // 列表、详情、统计都看不到，也没有入口再编辑它。endType=Date 且 endDate 缺失已被
        // 仓库归一化回退成 Never，次数档归一化后 endCount >= 1，锚日存在即至少有一期，
        // 均无需在此重复报错
        if (todo.repeat.type != RepeatType.None) {
            val anchor = todo.dates.minByOrNull { it.epochDay }
            when {
                anchor == null -> errors += TodoError.RepeatInvalid(TodoRepeatReason.MissingDates)
                todo.dates.size > 1 -> errors += TodoError.RepeatInvalid(TodoRepeatReason.MultipleDates)
                todo.repeat.hasEmptyWindow(anchor) ->
                    errors += TodoError.RepeatInvalid(TodoRepeatReason.EmptyWindow)
            }
        }

        // 步骤：标题非空、id 唯一
        val stepIds = mutableSetOf<String>()
        todo.steps.forEachIndexed { index, step ->
            val reason = when {
                step.title.isBlank() -> TodoStepReason.BlankTitle
                !stepIds.add(step.id) -> TodoStepReason.DuplicateId
                else -> null
            }
            if (reason != null) errors += TodoError.StepInvalid(index, reason)
        }

        // 附件：显示名非空、id 唯一且可作目录名、fileName 为安全纯文件名
        val attachmentIds = mutableSetOf<String>()
        todo.attachments.forEachIndexed { index, attachment ->
            val reason = when {
                attachment.name.isBlank() -> TodoAttachmentReason.BlankName
                !TodoAttachmentStore.isSafeId(attachment.id) -> TodoAttachmentReason.InvalidId
                !TodoAttachmentStore.isSafeFileName(attachment.fileName) ->
                    TodoAttachmentReason.InvalidFileName
                !attachmentIds.add(attachment.id) -> TodoAttachmentReason.DuplicateId
                else -> null
            }
            if (reason != null) errors += TodoError.AttachmentInvalid(index, reason)
        }

        // 标签：trim 后非空（重复/空白由仓库归一化，这里只拦非法值）
        todo.tags.forEachIndexed { index, tag ->
            if (tag.isBlank()) errors += TodoError.TagInvalid(index)
        }

        return errors
    }
}
