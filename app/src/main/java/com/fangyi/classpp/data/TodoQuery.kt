package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.data.model.nextOccurrenceAfter
import com.fangyi.classpp.data.model.occurrencesBetween

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
 * 除 [Deadline] 外的模式共用同一次级并列键：同键并列时截止时间近的在前（截止日 +
 * 截止时刻，无截止的排该组最后）——截止越近越要紧，与「更要紧的在前」同向。
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
    // 截止并列键：截止日 + 截止时刻升序（近的在前），无截止排最后——
    // Deadline 模式的主键，也是其余模式的次级并列键（两处口径保持一致）
    val deadlineKey = compareBy<Todo>(
        { it.deadlineDate?.epochDay ?: Long.MAX_VALUE },
        { it.deadlineMinute ?: Int.MAX_VALUE },
    )
    val base = when (this) {
        TodoSort.Deadline -> deadlineKey
        // 非截止模式：自身维度为主键，同键并列按截止近的在前
        // （Urgency 降序 = 紧急度高在前 = 权重值小（Critical=0）的在前）
        TodoSort.Urgency -> compareBy<Todo> { URGENCY_RANK.getValue(it.urgency) }.then(deadlineKey)
        TodoSort.Created -> compareBy<Todo> { it.createdAtMillis }.then(deadlineKey)
        TodoSort.Name -> compareBy<Todo> { it.name }.then(deadlineKey)
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

/**
 * 重复待办的列表展开（显示层的"虚拟独立待办"）：未完成的重复待办替换为一组按规则展开的
 * 可见分期条目（Todo 副本——同 id、`dates` 换成分期日期、`completed` 标分期完成态），
 * 其余待办原样透传。分组、排序、勾选路由都在副本上进行（勾选经回调按 repeat 路由回
 * setOccurrenceCompleted）。
 *
 * 可见窗口跟随 [today] 动态变化、按时间顺序推进：
 * - 已完成分期：每期一条 `completed = true` 副本（列表落"已完成"组）；
 * - 有拖欠（应完成日期 ≤ 今天的未完成分期）时：只显示这些拖欠期——"下一个"不出现，
 *   按顺序补完拖欠后窗口才前移；
 * - 无拖欠时：显示未来最近的一期（"下一个"）——完成它自动再前移到再下一期；从已完成
 *   取消它则窗口回退到它（再下一期隐藏）。
 *
 * 「下一次重复开始前」的逐期折算：开着 [Todo.deadlineBeforeRepeat] 的重复待办，每条副本的
 * 截止日期经 [withOccurrenceDeadline] 改写为「下一期开始日期提前 N 天」（时刻沿用）——
 * 卡片文案、倒计时、逾期判定、排序过滤都读副本的截止，自动跟随；存储的原始截止不动
 * （仅作时刻与开关的载体）。
 */
fun List<Todo>.expandRepeatOccurrences(today: IsoDate): List<Todo> = buildList {
    for (todo in this@expandRepeatOccurrences) {
        // 非重复待办、整条完成的重复待办（历史遗留形态）原样透传
        if (todo.repeat.type == RepeatType.None || todo.completed) {
            add(todo)
            continue
        }
        val anchor = todo.dates.minByOrNull { it.epochDay } ?: run {
            add(todo)
            continue
        }
        val completed = todo.completedOccurrences.toSet()
        // 已完成分期：整期进"已完成"（含规则修改后不再是合法分期的历史日期——记录不丢）
        for (date in todo.completedOccurrences) {
            add(todo.copy(completed = true, dates = listOf(date)).withOccurrenceDeadline(anchor, date))
        }
        // 拖欠 = 应完成日期 ≤ 今天（含今天，当天未过完）的未完成分期
        val overdueWindow = todo.repeat.occurrencesBetween(anchor, anchor, today + 1)
            .filter { it !in completed }
        if (overdueWindow.isNotEmpty()) {
            // 有拖欠：只显示拖欠期（按期序），未来的"下一个"等拖欠清完再出现
            for (date in overdueWindow) {
                add(todo.copy(dates = listOf(date)).withOccurrenceDeadline(anchor, date))
            }
        } else {
            // 无拖欠：显示未来最近的一期
            todo.repeat.nextOccurrenceAfter(anchor, today + 1, skip = completed)?.let { date ->
                add(todo.copy(dates = listOf(date)).withOccurrenceDeadline(anchor, date))
            }
        }
    }
}

/**
 * 「下一次重复开始前」的逐期截止折算：该期的截止日期 = 下一期开始日期提前
 * [Todo.deadlineBeforeDays] 天（时刻沿用已设的截止时刻）；不早于该期自身日期——多标记的
 * 每周规则相邻间隔可能小于周期上限，提前天数会越过期日，此时收拢到期日当天。
 * 非「随重复」开关、无截止日期或展不开下一期（病态规则）时原样返回。
 * 仅在 [expandRepeatOccurrences] 的显示副本上调用，存储的原始截止不动。
 */
private fun Todo.withOccurrenceDeadline(anchor: IsoDate, date: IsoDate): Todo {
    if (!deadlineBeforeRepeat || deadlineDate == null) return this
    // fromExclusive 走 occurrencesBetween 的含端点起点：传 date + 1 才是「严格晚于该期」
    val next = repeat.nextOccurrenceAfter(anchor, date + 1) ?: return this
    val adjusted = next - deadlineBeforeDays
    return copy(deadlineDate = if (adjusted.epochDay < date.epochDay) date else adjusted)
}
