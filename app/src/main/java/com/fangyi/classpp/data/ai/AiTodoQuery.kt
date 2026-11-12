package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.occurrencesBetween

/**
 * 待办与某一天的命中判定：提示词的「今日摘要」与只读工具共用同一口径。
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
 * - 未完成且有截止日，截止日早于 [date]；
 * - 重复待办有早于 [date] 的逾期期次（`overdueOccurrences` 由仓库维护）。
 */
internal fun Todo.isOverdueOn(date: IsoDate): Boolean {
    if (completed) return false
    val deadline = deadlineDate
    if (deadline != null && deadline.epochDay < date.epochDay) return true
    return overdueOccurrences.any { it.epochDay < date.epochDay }
}
