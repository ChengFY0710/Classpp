package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind

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

        // 标签：trim 后非空（重复/空白由仓库归一化，这里只拦非法值）
        todo.tags.forEachIndexed { index, tag ->
            if (tag.isBlank()) errors += TodoError.TagInvalid(index)
        }

        return errors
    }
}
