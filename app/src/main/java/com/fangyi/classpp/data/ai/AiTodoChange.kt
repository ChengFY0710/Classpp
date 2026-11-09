package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import kotlinx.serialization.Serializable

/**
 * AI 对已有待办的修改补丁（JSON schema，system prompt 里声明的 updates.patch 契约）。
 *
 * 与 [AiTodoDraft] 的差别在**缺席语义**：这里所有字段可空，null = 用户没要求改这个字段；
 * 非 null = 新值。因此字符串给空串表示清空（name 除外，空名视为非法），数组给空列表
 * 表示清空。字段与 [com.fangyi.classpp.data.model.Todo] 的完整参数面对齐，应用时由
 * [AiTodoPatchMapper] 做严格校验——非法值整条变更不可应用（用户已确认，不能静默降级）。
 *
 * @param dates 要改就给完整新日期列表（替换原日期），[] = 清空
 * @param allDay true = 改为全天；false 且不给时段 = 取消时间
 * @param startTime/endTime "HH:mm"，成对且 end > start 才成立
 * @param deadlineDate/deadlineTime 成对；两者都给空串 = 清除截止
 * @param deadlineBeforeRepeat/deadlineBeforeDays 截止随重复；仓库归一化会清理不成立的组合
 * @param urgency Critical|High|Medium|Low|None；None = 清除
 * @param tags/group 名字串；标签做包含匹配，分组空串 = 取消分组
 * @param steps 新步骤标题列表（[] = 清空）：同名旧步骤保留 id 与完成态
 * @param repeat 重复规则；{"type":"None"} = 取消重复
 */
@Serializable
data class AiTodoPatch(
    val name: String? = null,
    val dates: List<String>? = null,
    val allDay: Boolean? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val deadlineBeforeRepeat: Boolean? = null,
    val deadlineBeforeDays: Int? = null,
    val urgency: String? = null,
    val tags: List<String>? = null,
    val group: String? = null,
    val note: String? = null,
    val steps: List<String>? = null,
    val location: String? = null,
    val countdown: Boolean? = null,
    val pinned: Boolean? = null,
    val repeat: AiRepeatDraft? = null,
)

/** 一条修改草稿：id 必须原样来自 system prompt 的 `[待办]` 行，patch 只含要改的字段 */
@Serializable
data class AiTodoUpdateDraft(
    val id: String = "",
    val patch: AiTodoPatch = AiTodoPatch(),
)

/** 一条删除草稿（软删除：移入回收站，可在回收站恢复） */
@Serializable
data class AiTodoDeleteDraft(val id: String = "")

/** 变更类型：当前只支持修改与删除（新建走 [AiTodoDraftPayload.todos]，立即落库） */
@Serializable
enum class AiTodoChangeKind { Update, Delete }

/**
 * 一条待确认变更的生命周期：Pending = 等待用户确认；Applied = 已写入；
 * Failed = 应用失败（目标消失 / patch 非法 / 落盘失败）；Dismissed = 用户忽略。
 */
@Serializable
enum class AiTodoChangeStatus { Pending, Applied, Failed, Dismissed }

/** 失败原因分类（文案由 UI 映射 stringResource，数据层不产出文案） */
@Serializable
enum class AiTodoChangeFailure { None, NotFound, InvalidPatch, InvalidDate, SaveFailed }

/**
 * 挂在 assistant 消息上的待确认变更（随 ai_chat.json 落盘）。
 *
 * @param todoName 生成草稿时的名称快照：删除后目标从活跃流消失，确认卡仍需要显示名称
 * @param patch 修改补丁；删除时为 null
 */
@Serializable
data class AiTodoChange(
    val kind: AiTodoChangeKind,
    val todoId: String,
    val todoName: String = "",
    val patch: AiTodoPatch? = null,
    val status: AiTodoChangeStatus = AiTodoChangeStatus.Pending,
    val failure: AiTodoChangeFailure = AiTodoChangeFailure.None,
)

/**
 * 完成/取消完成的草稿：与修改/删除不同，这类动作**解析后直接执行**（可撤销），
 * 不生成确认卡。
 *
 * @param id 必须是 [AiPromptBuilder] 注入的 [待办] 行里的 id
 * @param date 重复待办的期次日期（yyyy-MM-dd），必须是注入的「待完成期次/已完成期次」
 *   之一；普通待办省略
 */
@Serializable
data class AiTodoCompletionDraft(
    val id: String = "",
    val date: String? = null,
)

/** 完成/取消完成的执行结果：Applied = 已写入（可撤销）；Undone = 已撤销 */
@Serializable
enum class AiTodoCompletionStatus { Applied, Failed, Undone, UndoFailed }

/**
 * 挂在 assistant 消息上的完成/取消完成记录（随 ai_chat.json 落盘）。
 *
 * @param completed true = 标记完成，false = 取消完成
 * @param date 重复待办的期次日期；普通待办为 null
 * @param changed false = 执行时待办已处于目标状态（没有写库，也就无需撤销）
 * @param status 执行结果；Failed 时 [failure] 给出原因，UndoFailed 表示撤销失败但原操作仍成立
 */
@Serializable
data class AiTodoCompletion(
    val todoId: String,
    val todoName: String = "",
    val completed: Boolean,
    val date: IsoDate? = null,
    val changed: Boolean = true,
    val status: AiTodoCompletionStatus = AiTodoCompletionStatus.Applied,
    val failure: AiTodoChangeFailure = AiTodoChangeFailure.None,
)
