package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.data.model.newUuid

/** 草稿里没解析出来的字段；UI 在草稿卡上标注「待人工修正」 */
enum class AiDraftField { Dates, PeriodTime, Deadline }

/**
 * 一条草稿的映射结果。
 *
 * @param todo 总能构造——解析失败的字段留默认值（时段/截止成对置空），用户在
 *   预填编辑器（NewTodoSheet）里补；[invalidFields] 标注哪些字段没解析出来
 */
data class AiTodoDraftMapping(
    val draft: AiTodoDraft,
    val todo: Todo,
    val invalidFields: Set<AiDraftField>,
)

/**
 * 草稿 → [Todo] 的纯函数映射（重点单测对象，无 Android 依赖）。
 *
 * 规则（与 TodoValidator / TodoRepository 的不变式对齐）：
 * - 时段：start/end **成对**有效且 end > start 才成立（timeKind = Period），否则两者
 *   皆空——Todo 只认成对时段；
 * - 截止：日期与时刻**成对**出现（TodoValidator 硬约束），只有其一或解析失败 →
 *   两者皆空并标注 [AiDraftField.Deadline]，交编辑器人工修正；
 * - 分组/标签：与现有清单做**包含匹配**（"学习"↔"学习方法"）优先取清单项；分组
 *   完全无匹配时保留原文——仓库归一化会把不存在的组清回 null，由用户在编辑器确认；
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

        // 日期：单条解析失败只丢坏值并标注，好值保留
        val parsedDates = draft.dates.mapNotNull { IsoDate.parseOrNull(it.trim()) }
        if (parsedDates.size < draft.dates.size) invalid += AiDraftField.Dates
        val dates = parsedDates.distinct().sortedBy { it.epochDay }

        // 时段：成对有效才成立
        val startMinute = parseMinutes(draft.startTime)
        val endMinute = parseMinutes(draft.endTime)
        val periodValid = startMinute != null && endMinute != null && endMinute > startMinute
        if ((draft.startTime != null || draft.endTime != null) && !periodValid) {
            invalid += AiDraftField.PeriodTime
        }

        // 截止：app 端要求成对（TodoValidator），只有其一即整体待修正
        val deadlineDate = draft.deadlineDate?.trim()?.let { IsoDate.parseOrNull(it) }
        val deadlineMinute = parseMinutes(draft.deadlineTime)
        val deadlineProvided = draft.deadlineDate != null || draft.deadlineTime != null
        val deadlineValid = deadlineDate != null && deadlineMinute != null
        if (deadlineProvided && !deadlineValid) invalid += AiDraftField.Deadline

        val todo = Todo(
            id = newUuid(),
            name = draft.name.trim(),
            dates = dates,
            timeKind = if (periodValid) TodoTimeKind.Period else TodoTimeKind.None,
            startMinute = if (periodValid) startMinute else null,
            endMinute = if (periodValid) endMinute else null,
            deadlineDate = if (deadlineValid) deadlineDate else null,
            deadlineMinute = if (deadlineValid) deadlineMinute else null,
            location = draft.location.trim(),
            tags = normalizeList(draft.tags).map { fuzzyMatch(it, knownTags) ?: it }.distinct(),
            urgency = draft.urgency?.trim()?.let { name ->
                TodoUrgency.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            } ?: TodoUrgency.None,
            note = draft.note,
            steps = normalizeList(draft.steps).map { TodoStep(id = newUuid(), title = it) },
            group = draft.group?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { fuzzyMatch(it, knownGroups) ?: it },
            source = SOURCE_AI,
            // createdAtMillis 由仓库在 addTodo 时补齐；其余状态字段全走默认值
        )
        return AiTodoDraftMapping(draft, todo, invalid)
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
