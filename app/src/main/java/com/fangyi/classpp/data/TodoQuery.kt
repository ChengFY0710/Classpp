package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoUrgency

/**
 * 待办检索过滤器：条件之间取"与"，集合条件取"或"（任一命中）。
 * 纯数据 + 纯函数，UI 在 StateFlow 上组合使用（如"今天/本周/已完成"分组）。
 *
 * @param keyword 匹配名称/备注/地点/步骤标题，大小写不敏感；空白视为无关键字
 * @param tags 标签任一命中即通过；空集 = 不限
 * @param urgencies 紧急程度任一命中即通过；空集 = 不限
 * @param completed 完成情况；null = 不限
 * @param datesFrom/datesTo 待办日期范围（含端点）：待办的任一日期落在区间内即通过
 * @param deadlineFrom/deadlineTo 截止日范围（含端点）：无截止日期的待办不通过
 */
data class TodoFilter(
    val keyword: String = "",
    val tags: Set<String> = emptySet(),
    val urgencies: Set<TodoUrgency> = emptySet(),
    val completed: Boolean? = null,
    val datesFrom: IsoDate? = null,
    val datesTo: IsoDate? = null,
    val deadlineFrom: IsoDate? = null,
    val deadlineTo: IsoDate? = null,
)

/** 按过滤器筛选；默认 [TodoFilter]（全字段缺省）原样返回全部待办 */
fun List<Todo>.filterTodos(filter: TodoFilter): List<Todo> {
    val keyword = filter.keyword.trim()
    return filter { todo ->
        if (filter.completed != null && todo.completed != filter.completed) return@filter false
        if (filter.urgencies.isNotEmpty() && todo.urgency !in filter.urgencies) return@filter false
        if (filter.tags.isNotEmpty() && todo.tags.none { it in filter.tags }) return@filter false

        if (filter.datesFrom != null || filter.datesTo != null) {
            val hit = todo.dates.any { date ->
                (filter.datesFrom == null || date.epochDay >= filter.datesFrom.epochDay) &&
                    (filter.datesTo == null || date.epochDay <= filter.datesTo.epochDay)
            }
            if (!hit) return@filter false
        }

        if (filter.deadlineFrom != null || filter.deadlineTo != null) {
            val deadline = todo.deadlineDate ?: return@filter false
            if (filter.deadlineFrom != null && deadline.epochDay < filter.deadlineFrom.epochDay) return@filter false
            if (filter.deadlineTo != null && deadline.epochDay > filter.deadlineTo.epochDay) return@filter false
        }

        if (keyword.isNotEmpty()) {
            val haystack = buildString {
                append(todo.name).append('\n')
                append(todo.note).append('\n')
                append(todo.location).append('\n')
                todo.steps.forEach { append(it.title).append('\n') }
            }
            if (!haystack.contains(keyword, ignoreCase = true)) return@filter false
        }
        true
    }
}

/**
 * 待办排序键。各 comparator 均稳定（同键保持原列表次序）。降序（默认）按各字段
 * 「更要紧的在前」定义：
 * - [Urgency]：紧急度高在前（非常急 → … → 不急 → 无）；
 * - [Deadline]：截止早的在前，无截止的排最后（升序整体反序，无截止改排最前）；
 * - [Created]：创建早的在前（等得久的排前面）；
 * - [Name]：名称字典序（A→Z）。
 * 升序 = 降序整体反序（[comparator] 的 descending = false）。
 */
enum class TodoSort {
    Deadline,
    Urgency,
    Created,
    Name,
}

/** 紧急程度权重：数值越大紧急度越低（Critical 最小、None 最大） */
private val URGENCY_RANK: Map<TodoUrgency, Int> = mapOf(
    TodoUrgency.Critical to 0,
    TodoUrgency.High to 1,
    TodoUrgency.Medium to 2,
    TodoUrgency.Low to 3,
    TodoUrgency.None to 4,
)

/**
 * [TodoSort] 对应的 comparator（[descending] = 降序，默认 true）。
 * 各字段的降序定义见 [TodoSort] 注释。
 */
fun TodoSort.comparator(descending: Boolean = true): Comparator<Todo> {
    val base = when (this) {
        TodoSort.Deadline -> compareBy<Todo>(
            { it.deadlineDate?.epochDay ?: Long.MAX_VALUE },
            { it.deadlineMinute ?: Int.MAX_VALUE },
        )
        // 降序 = 紧急度高在前 = 权重值小（Critical=0）的在前
        TodoSort.Urgency -> compareBy { URGENCY_RANK.getValue(it.urgency) }
        TodoSort.Created -> compareBy { it.createdAtMillis }
        TodoSort.Name -> compareBy { it.name }
    }
    return if (descending) base else base.reversed()
}

/** 过滤后的常用入口：按 [sort] 与方向排序返回新列表 */
fun List<Todo>.sortedFor(sort: TodoSort, descending: Boolean = true): List<Todo> =
    sortedWith(sort.comparator(descending))

/**
 * 是否已逾期：只有截止日期能判定——截止日早于 [today]，或正是 [today] 且截止时刻
 * 已过（[nowMinute] 为当天分钟数 0..1439）。无截止日期、或截止日当天无时刻（按全天算，
 * 当天未过完）都不算逾期；已完成的待办也不逾期（完成态优先于过期）。
 *
 * [today]/[nowMinute] 由调用方取（UI 在组合期取当前时刻，测试注入固定值）。
 */
fun Todo.isOverdue(today: IsoDate, nowMinute: Int): Boolean {
    if (completed) return false
    val deadline = deadlineDate ?: return false
    return when {
        deadline.epochDay < today.epochDay -> true
        deadline.epochDay > today.epochDay -> false
        else -> deadlineMinute != null && deadlineMinute < nowMinute
    }
}
