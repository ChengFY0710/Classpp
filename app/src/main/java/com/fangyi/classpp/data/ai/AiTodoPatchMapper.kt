package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.data.model.hasEmptyWindow
import com.fangyi.classpp.data.model.newUuid

/**
 * 一条修改补丁的映射结果。
 *
 * @param todo 应用后的待办；null = 补丁不合法（调用方不得应用）
 * @param changed 是否真的改动了内容（合法但无变化的补丁无需写库）
 */
data class AiTodoPatchMapping(val todo: Todo?, val changed: Boolean)

/**
 * 修改补丁 → [Todo] 的纯函数映射（重点单测对象，无 Android 依赖）。
 *
 * 与新建草稿的宽容兜底不同，这里对**用户已确认的变更**采取严格校验：任何非法值都让
 * 整条补丁不可应用（`todo = null`），由调用方标记失败，绝不静默降级成别的值。
 *
 * 规则：
 * - null 字段 = 保持不变；字符串空串 = 清空（name 除外，空名非法）；数组空 = 清空；
 * - 日期给完整新列表；清空日期时时间语义降级为「无」，重复规则随之取消；
 * - 时段必须 start/end 成对且 end > start；allDay 与时段不可同时给；
 * - 截止必须日期+时刻成对；任一给空串即清除截止；
 * - 重复规则复用 [AiTodoMapper.mapRepeat]（大小写不敏感、越界夹紧），非法类型/单位
 *   整条补丁失败；无日期的重复请求失败；日期档窗口早于首期（空窗口，展不出任何一期）
 *   或多个日期 + 重复（系列单锚语义）同样整条失败，不静默降级或截断；
 * - 步骤按标题保留旧 id 与完成态，新标题生成新 id；
 * - 状态字段（id/完成态/创建时间/来源/回收站与归档标记）一律保留。
 */
object AiTodoPatchMapper {

    fun apply(
        existing: Todo,
        patch: AiTodoPatch,
        knownGroups: List<String> = emptyList(),
        knownTags: List<String> = emptyList(),
    ): AiTodoPatchMapping {
        // 长度上限：用户已确认的补丁遇到超长字段整条拒绝，不静默截断
        if (patch.exceedsFieldLimits()) return invalid()

        // 名称：空白是非法值（TodoValidator.BlankName），不静默沿用旧名
        val name = patch.name?.trim() ?: existing.name
        if (name.isBlank()) return invalid()

        // 日期：给就给完整新列表，任一条非法整条失败
        val dates: List<IsoDate> = if (patch.dates != null) {
            val parsed = mutableListOf<IsoDate>()
            for (raw in patch.dates) {
                parsed += IsoDate.parseOrNull(raw.trim()) ?: return invalid()
            }
            parsed.distinct().sortedBy { it.epochDay }
        } else {
            existing.dates
        }

        // 时段：allDay 与时段同时给属于自相矛盾；时段成对且 end > start
        val timeProvided = patch.startTime != null || patch.endTime != null
        if (timeProvided && patch.allDay != null) return invalid()
        val timeTouched = timeProvided || patch.allDay != null
        var timeKind = existing.timeKind
        var startMinute = existing.startMinute
        var endMinute = existing.endMinute
        if (timeTouched) {
            if (timeProvided) {
                val start = AiTodoMapper.parseMinutes(patch.startTime) ?: return invalid()
                val end = AiTodoMapper.parseMinutes(patch.endTime) ?: return invalid()
                if (end <= start) return invalid()
                timeKind = TodoTimeKind.Period
                startMinute = start
                endMinute = end
            } else if (patch.allDay == true) {
                timeKind = TodoTimeKind.AllDay
                startMinute = null
                endMinute = null
            } else {
                timeKind = TodoTimeKind.None
                startMinute = null
                endMinute = null
            }
        }
        // 有时刻语义就必须有日期：清空日期属于级联降级；显式改时刻却没日期是非法补丁
        if (timeKind != TodoTimeKind.None && dates.isEmpty()) {
            if (timeTouched) return invalid()
            timeKind = TodoTimeKind.None
            startMinute = null
            endMinute = null
        }
        if (timeKind != TodoTimeKind.Period) {
            startMinute = null
            endMinute = null
        }

        // 截止：日期+时刻成对；任一空串 = 清除
        var deadlineDate = existing.deadlineDate
        var deadlineMinute = existing.deadlineMinute
        if (patch.deadlineDate != null || patch.deadlineTime != null) {
            val dateText = patch.deadlineDate
            val timeText = patch.deadlineTime
            val clear = (dateText != null && dateText.isBlank()) ||
                (timeText != null && timeText.isBlank())
            if (clear) {
                deadlineDate = null
                deadlineMinute = null
            } else {
                if (dateText == null || timeText == null) return invalid()
                deadlineDate = IsoDate.parseOrNull(dateText.trim()) ?: return invalid()
                deadlineMinute = AiTodoMapper.parseMinutes(timeText) ?: return invalid()
            }
        }

        // 重复：显式给了就必须能映射出合法规则；type=None 是合法的「取消重复」
        // （mapRepeat 对显式 None 会标 invalid——那是新建草稿「多余字段」的标注口径，
        // 修改补丁里 None 是明确的清空指令，不能当成错误）
        var repeat = existing.repeat
        if (patch.repeat != null) {
            val repeatType = patch.repeat.type.trim()
            if (repeatType.isEmpty()) return invalid()
            if (repeatType.equals("None", ignoreCase = true)) {
                repeat = RepeatRule()
            } else {
                // 显式给出的 unit/endType 必须能识别：mapRepeat 对非法值会静默回落默认，
                // 用户确认的「每两周 / 到某日结束」就变成了别的规则
                if (!patch.repeat.unit.isNullOrBlank() &&
                    AiTodoMapper.repeatUnitOrNull(patch.repeat.unit) == null
                ) {
                    return invalid()
                }
                if (!patch.repeat.endType.isNullOrBlank() &&
                    AiTodoMapper.repeatEndTypeOrNull(patch.repeat.endType) == null
                ) {
                    return invalid()
                }
                val invalidFields = mutableSetOf<AiDraftField>()
                val mapped = AiTodoMapper.mapRepeat(patch.repeat, invalidFields)
                if (invalidFields.isNotEmpty()) return invalid()
                repeat = mapped
            }
        }
        if (dates.isEmpty() && repeat.type != RepeatType.None) {
            if (patch.repeat != null) return invalid()
            repeat = RepeatRule()
        }
        // 空窗口重复（日期档结束早于首期，展不出任何一期）与多日期 + 重复（系列单锚
        // 语义，TodoValidator 拒收）同属非法值：整条补丁失败，不静默把用户已确认的
        // 变更降级或截断（新建草稿降级/截断、修改补丁拒绝，两端口径见 AiTodoMapper）
        if (repeat.type != RepeatType.None) {
            val anchor = dates.minByOrNull { it.epochDay }
            if (anchor != null && repeat.hasEmptyWindow(anchor)) return invalid()
            if (dates.size > 1) return invalid()
        }

        // 紧急度：枚举名大小写不敏感；空串/非法值整条失败
        val urgency = patch.urgency?.let { value ->
            val text = value.trim()
            if (text.isEmpty()) return invalid()
            TodoUrgency.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
                ?: return invalid()
        } ?: existing.urgency

        val tags = patch.tags?.let { values ->
            values.map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { AiTodoMapper.fuzzyMatch(it, knownTags) ?: it }
        } ?: existing.tags

        val group = if (patch.group != null) {
            val trimmed = patch.group.trim()
            if (trimmed.isEmpty()) {
                null
            } else {
                // 不存在的分组直接拒绝：仓库归一化会把未知分组清空，静默"应用成功"会让
                // 待办从原分组消失却没进新分组。先 create_todo_groups 再引用。
                AiTodoMapper.fuzzyMatch(trimmed, knownGroups) ?: return invalid()
            }
        } else {
            existing.group
        }

        // 步骤：同名旧步骤保留 id 与完成态，新标题生成新 id；[] = 清空
        val steps = patch.steps?.let { titles ->
            val pool = existing.steps.toMutableList()
            titles.map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { title ->
                    val index = pool.indexOfFirst { it.title == title }
                    if (index >= 0) pool.removeAt(index) else TodoStep(id = newUuid(), title = title)
                }
        } ?: existing.steps

        val deadlineBeforeRepeat = (patch.deadlineBeforeRepeat ?: existing.deadlineBeforeRepeat) &&
            deadlineDate != null &&
            repeat.type != RepeatType.None &&
            !repeat.checkIn
        val deadlineBeforeDays = patch.deadlineBeforeDays?.coerceAtLeast(0)
            ?: existing.deadlineBeforeDays
        val countdown = (patch.countdown ?: existing.countdown) && deadlineDate != null

        val next = existing.copy(
            name = name,
            dates = dates,
            repeat = repeat,
            timeKind = timeKind,
            startMinute = startMinute,
            endMinute = endMinute,
            deadlineDate = deadlineDate,
            deadlineMinute = deadlineMinute,
            deadlineBeforeRepeat = deadlineBeforeRepeat,
            deadlineBeforeDays = deadlineBeforeDays,
            location = patch.location?.trim() ?: existing.location,
            tags = tags,
            urgency = urgency,
            note = patch.note ?: existing.note,
            steps = steps,
            group = group,
            countdown = countdown,
            pinned = patch.pinned ?: existing.pinned,
        )
        return AiTodoPatchMapping(todo = next, changed = next != existing)
    }

    /** 补丁字段超长：用户已确认的修改不静默截断，整条不可应用 */
    private fun AiTodoPatch.exceedsFieldLimits(): Boolean {
        if (name != null && name.trim().length > AiFieldLimits.Name) return true
        if (dates != null && dates.size > AiFieldLimits.Dates) return true
        if (note != null && note.length > AiFieldLimits.Note) return true
        if (location != null && location.trim().length > AiFieldLimits.Location) return true
        if (group != null && group.trim().length > AiFieldLimits.Group) return true
        if (tags != null && (
                tags.size > AiFieldLimits.Tags ||
                    tags.any { it.trim().length > AiFieldLimits.Tag }
                )
        ) {
            return true
        }
        if (steps != null && (
                steps.size > AiFieldLimits.Steps ||
                    steps.any { it.trim().length > AiFieldLimits.StepTitle }
                )
        ) {
            return true
        }
        return false
    }

    private fun invalid(): AiTodoPatchMapping = AiTodoPatchMapping(todo = null, changed = false)
}
