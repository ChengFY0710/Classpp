package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.REPEAT_END_COUNT_DEFAULT
import com.fangyi.classpp.data.model.REPEAT_END_COUNT_MAX
import com.fangyi.classpp.data.model.REPEAT_END_COUNT_MIN
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.data.model.markRange
import com.fangyi.classpp.data.model.newUuid

/** 草稿里没解析出来的字段；仅用于测试与诊断（落库路径只关心 [AiTodoDraftMapping.canSave]） */
enum class AiDraftField { Name, Dates, PeriodTime, Deadline, Repeat }

/**
 * 一条草稿的映射结果。
 *
 * @param todo 总能构造——解析失败的字段退化为安全默认值（时段/截止成对置空、
 *   无日期的时刻语义降级为「无」），保证能通过 TodoValidator
 * @param invalidFields 标注哪些字段没解析出来
 */
data class AiTodoDraftMapping(
    val draft: AiTodoDraft,
    val todo: Todo,
    val invalidFields: Set<AiDraftField>,
) {
    /**
     * 是否可落库：只有**缺名称**是硬拦截（TodoValidator.BlankName 会让 `addTodos`
     * 整批失败、零落盘）。其余字段在映射阶段已降级为合法值——这是「AI 直接落库、
     * 无需确认」的前提：批次里只保留能通过校验的行。
     */
    val canSave: Boolean get() = AiDraftField.Name !in invalidFields
}

/**
 * 草稿 → [Todo] 的纯函数映射（重点单测对象，无 Android 依赖）。
 *
 * 规则（与 TodoValidator / TodoRepository 的不变式对齐，目标：**映射结果一定能通过
 * 校验**，否则 addTodos 的全量校验会让整批零落盘）：
 * - 名称：空白 → 标注 [AiDraftField.Name]，该行不参与落库；
 * - 日期：单条解析失败只丢坏值并标注；无有效日期时，时刻语义（全天/时段）整体降级
 *   为 [TodoTimeKind.None]（校验器要求有时刻就必须有日期）；
 * - 时段：start/end **成对**有效且 end > start 才成立（timeKind = Period）；否则标注
 *   [AiDraftField.PeriodTime]，此时 allDay = true 可退化为全天；
 * - 截止：日期与时刻**成对**出现（TodoValidator 硬约束），只有其一 → 两者皆空并标注；
 * - 重复：类型/单位/结束条件大小写不敏感匹配，越界值夹紧；Custom 的 marks 按单位
 *   合法域过滤；无有效日期时重复整体取消（没有锚日期的系列无法展开）；
 * - 截止随重复：仅在有重复且截止成立时保留，其余置 false（仓库归一化同口径）；
 * - 倒计时：仅在有截止时保留；
 * - 分组/标签：与现有清单做**包含匹配**（"学习"↔"学习方法"）优先取清单项；分组
 *   完全无匹配时保留原文——仓库归一化会把不存在的组清回 null；
 * - urgency：枚举名大小写不敏感匹配，非法值归 None；
 * - source = "ai"：沿用 Todo.source 语义（null=手建、"xmu"=厦大同步），列表卡可
 *   据此展示来源徽标。
 */
object AiTodoMapper {

    /** 映射一批草稿（顺序保持）；[knownGroups]/[knownTags] 来自仓库的当前清单 */
    fun map(
        drafts: List<AiTodoDraft>,
        knownGroups: List<String> = emptyList(),
        knownTags: List<String> = emptyList(),
    ): List<AiTodoDraftMapping> = drafts.map { mapOne(it, knownGroups, knownTags) }

    private fun mapOne(
        draft: AiTodoDraft,
        knownGroups: List<String>,
        knownTags: List<String>,
    ): AiTodoDraftMapping {
        val invalid = mutableSetOf<AiDraftField>()

        // 名称：TodoValidator 的第一条硬约束（BlankName），空白 = 该行不可落库
        val name = draft.name.trim()
        if (name.isBlank()) invalid += AiDraftField.Name

        // 日期：单条解析失败只丢坏值并标注，好值保留
        val parsedDates = draft.dates.mapNotNull { IsoDate.parseOrNull(it.trim()) }
        if (parsedDates.size < draft.dates.size) invalid += AiDraftField.Dates
        val dates = parsedDates.distinct().sortedBy { it.epochDay }

        // 时段：成对有效才成立；非法组合标注后由全天/无时刻兜底
        val startMinute = parseMinutes(draft.startTime)
        val endMinute = parseMinutes(draft.endTime)
        val periodValid = startMinute != null && endMinute != null && endMinute > startMinute
        if ((draft.startTime != null || draft.endTime != null) && !periodValid) {
            invalid += AiDraftField.PeriodTime
        }

        // 无有效日期时不能留时刻语义（TodoValidator.DatesRequired），降级为「无」
        val timeKind = when {
            dates.isEmpty() -> {
                if (periodValid || draft.allDay) invalid += AiDraftField.Dates
                TodoTimeKind.None
            }
            periodValid -> TodoTimeKind.Period
            draft.allDay -> TodoTimeKind.AllDay
            else -> TodoTimeKind.None
        }

        // 截止：app 端要求成对（TodoValidator），只有其一即整体待修正
        val deadlineDate = draft.deadlineDate?.trim()?.let { IsoDate.parseOrNull(it) }
        val deadlineMinute = parseMinutes(draft.deadlineTime)
        val deadlineProvided = draft.deadlineDate != null || draft.deadlineTime != null
        val deadlineValid = deadlineDate != null && deadlineMinute != null
        if (deadlineProvided && !deadlineValid) invalid += AiDraftField.Deadline

        // 重复：没有日期的系列无法展开，直接取消（否则用户在列表里看不到任何一期）
        val repeat = mapRepeat(draft.repeat, invalid)
        val effectiveRepeat = if (dates.isEmpty() && repeat.type != RepeatType.None) {
            invalid += AiDraftField.Dates
            RepeatRule()
        } else {
            repeat
        }

        val hasDeadline = deadlineValid
        val todo = Todo(
            id = newUuid(),
            name = name,
            dates = dates,
            repeat = effectiveRepeat,
            timeKind = timeKind,
            startMinute = if (timeKind == TodoTimeKind.Period) startMinute else null,
            endMinute = if (timeKind == TodoTimeKind.Period) endMinute else null,
            deadlineDate = if (hasDeadline) deadlineDate else null,
            deadlineMinute = if (hasDeadline) deadlineMinute else null,
            deadlineBeforeRepeat = deadlineValid &&
                effectiveRepeat.type != RepeatType.None &&
                draft.deadlineBeforeRepeat,
            deadlineBeforeDays = draft.deadlineBeforeDays.coerceAtLeast(0),
            location = draft.location.trim(),
            tags = normalizeList(draft.tags).map { fuzzyMatch(it, knownTags) ?: it }.distinct(),
            urgency = draft.urgency?.trim()?.let { value ->
                TodoUrgency.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
            } ?: TodoUrgency.None,
            note = draft.note,
            steps = normalizeList(draft.steps).map { TodoStep(id = newUuid(), title = it) },
            group = draft.group?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { fuzzyMatch(it, knownGroups) ?: it },
            countdown = draft.countdown && hasDeadline,
            pinned = draft.pinned,
            source = SOURCE_AI,
            // createdAtMillis 由仓库在 addTodo 时补齐；其余状态字段全走默认值
        )
        return AiTodoDraftMapping(draft, todo, invalid)
    }

    /**
     * 重复规则映射：类型/单位/结束条件大小写不敏感；Custom 的 interval 夹紧到 1..99、
     * marks 按单位合法域过滤；结束日期解析失败回退「永不」并标注；次数档缺省用
     * [REPEAT_END_COUNT_DEFAULT]、夹紧到合法区间；打卡只随次数档成立。
     *
     * internal：新建草稿与修改补丁（[AiTodoPatchMapper]）共用同一套归一化口径。
     */
    internal fun mapRepeat(
        draft: AiRepeatDraft?,
        invalid: MutableSet<AiDraftField>,
    ): RepeatRule {
        if (draft == null) return RepeatRule()
        val type = matchEnum<RepeatType>(draft.type)
        if (type == null || type == RepeatType.None) {
            if (draft.type.isNotBlank()) invalid += AiDraftField.Repeat
            return RepeatRule()
        }

        val unit = matchEnum<RepeatUnit>(draft.unit) ?: RepeatUnit.Week
        val endType = when (matchEnum<RepeatEndType>(draft.endType)) {
            RepeatEndType.Date -> {
                val parsed = draft.endDate?.let { IsoDate.parseOrNull(it.trim()) }
                if (parsed != null) {
                    RepeatEndType.Date
                } else {
                    // 给了 endType=Date 却没有可用日期：回退永不（不丢整条待办）
                    invalid += AiDraftField.Repeat
                    RepeatEndType.Never
                }
            }
            RepeatEndType.Count -> RepeatEndType.Count
            else -> RepeatEndType.Never
        }
        val endDate = if (endType == RepeatEndType.Date) {
            draft.endDate?.let { IsoDate.parseOrNull(it.trim()) }
        } else {
            null
        }
        val endCount = if (endType == RepeatEndType.Count) {
            (draft.endCount ?: REPEAT_END_COUNT_DEFAULT)
                .coerceIn(REPEAT_END_COUNT_MIN, REPEAT_END_COUNT_MAX)
        } else {
            null
        }
        val custom = type == RepeatType.Custom
        return RepeatRule(
            type = type,
            unit = if (custom) unit else RepeatUnit.Week,
            interval = if (custom) (draft.interval ?: 1).coerceIn(1, 99) else 1,
            marks = if (custom) draft.marks.filter { it in unit.markRange }.toSet() else emptySet(),
            endType = endType,
            endDate = endDate,
            endCount = endCount,
            merge = draft.merge,
            checkIn = draft.checkIn && endType == RepeatEndType.Count,
        )
    }

    /** 枚举名大小写不敏感匹配；空白/非法返回 null */
    private inline fun <reified T : Enum<T>> matchEnum(value: String?): T? {
        val text = value?.trim() ?: return null
        if (text.isEmpty()) return null
        return enumValues<T>().firstOrNull { it.name.equals(text, ignoreCase = true) }
    }

    /** "HH:mm"（或 "H:mm"）→ 当天分钟数；非法返回 null */
    internal fun parseMinutes(text: String?): Int? {
        val t = text?.trim() ?: return null
        val parts = t.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }

    /** 逐条 trim、去空；原样序保持 */
    private fun normalizeList(values: List<String>): List<String> =
        values.map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 包含匹配：精确相等直接命中；否则取「清单项包含它或它包含清单项」的第一个命中
     * （如"学习"命中"学习方法"）。无命中返回 null，由调用方决定保留原文或置空。
     */
    internal fun fuzzyMatch(value: String, known: List<String>): String? {
        if (known.isEmpty()) return null
        known.firstOrNull { it == value }?.let { return it }
        return known.firstOrNull { it.contains(value) || value.contains(it) }
    }

    /** Todo.source 的 AI 来源标识（与 "xmu" 同层的取值，见 Todo.source KDoc） */
    const val SOURCE_AI = "ai"
}
