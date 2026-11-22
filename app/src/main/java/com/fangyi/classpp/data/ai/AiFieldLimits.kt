package com.fangyi.classpp.data.ai

/**
 * AI 写入字段的长度上限（只约束 AI 入口，不扩展 [com.fangyi.classpp.data.TodoValidator]：
 * 手建 / 同步 / 导入的既有口径不受影响）。
 *
 * - 新建待办（自动落库、无确认）超限截断，并在映射结果里标注；
 * - 修改补丁（用户已确认）超限整条拒绝，与补丁的严格校验语义一致。
 *
 * 目的：AI 写入的超长名称/备注/几千个日期会被拼进每轮 system prompt，导致后续请求
 * 超上下文，模型自己也再难删除。
 */
internal object AiFieldLimits {
    const val Name = 200
    const val StepTitle = 200
    const val Tag = 50
    const val Group = 50
    const val Location = 200
    const val Note = 4_000
    const val Steps = 50
    const val Tags = 20
    const val Dates = 366
}
