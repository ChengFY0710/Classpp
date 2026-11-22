package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.outstandingOverdueOccurrences

/**
 * 待办的共享口径（命中判定与时间形态文本）：提示词的「今日摘要」与只读工具共用同一来源。
 *
 * 抽出来的原因（P3）：精简上下文后，今日摘要由 [AiPromptBuilder] 生成、单日明细由
 * [AiReadTools] 生成，两处若各写一份判定，模型会看到「摘要里有、工具里没有」的矛盾数据。
 */

/** 待办在这一天是否有安排：日期命中 / 截止命中 / 重复期次命中 */
internal fun Todo.occursOnDate(date: IsoDate): Boolean {
    if (deadlineDate == date) return true
    if (date in dates) return true
    if (repeat.type == RepeatType.None) return false
    val anchor = dates.minByOrNull { it.epochDay } ?: return false
    return repeat.occurrencesBetween(anchor, date, date + 1).isNotEmpty()
}

/** 这一天的完成态：整条完成，或重复待办该期已完成 */
internal fun Todo.isCompletedOnDate(date: IsoDate): Boolean =
    completed || (repeat.type != RepeatType.None && date in completedOccurrences)

/**
 * 到 [date] 为止仍未完成且已过期：
 * - 未完成且有**不随重复**的截止日，截止日早于 [date]——随重复的 `deadlineDate` 只是
 *   折算载体（可能是早已过期的初始值），不参与整条逾期判定，逐期口径由列表/详情持有；
 * - 重复待办有仍欠着的逾期期次（[outstandingOverdueOccurrences]：落账减去已完成、再
 *   补算尚未落账的漏期——迟到补完成不再算逾期，跨天未写库也能算出新漏期）。
 */
internal fun Todo.isOverdueOn(date: IsoDate): Boolean {
    if (completed) return false
    if (!deadlineBeforeRepeat) {
        val deadline = deadlineDate
        if (deadline != null && deadline.epochDay < date.epochDay) return true
    }
    if (repeat.type == RepeatType.None) return false
    return outstandingOverdueOccurrences(date).any { it.epochDay < date.epochDay }
}

/**
 * 待办的时间形态文本：`全天` / `14:00-16:00`；无时刻语义返回 null。
 *
 * 提示词的待办行与 `get_todo` 的 `time` 字段共用同一文本（同 [occursOnDate] 的抽取
 * 理由）：待办行此前不带时段，时段待办与全天待办在注入行里无法区分，用户问
 * 「下午几点做什么」时模型只能多走一轮工具或干脆答不出。
 */
internal fun Todo.timeText(): String? = when (timeKind) {
    TodoTimeKind.AllDay -> "全天"
    TodoTimeKind.Period -> {
        val start = startMinute
        val end = endMinute
        if (start != null && end != null) {
            "${TimeText.formatDisplay(start)}-${TimeText.formatDisplay(end)}"
        } else {
            null
        }
    }
    TodoTimeKind.None -> null
}
