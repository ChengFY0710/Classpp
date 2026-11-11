package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.isArchived
import com.fangyi.classpp.data.model.isDeleted
import com.fangyi.classpp.data.model.nextOccurrenceAfter
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.occurrencesOn
import com.fangyi.classpp.data.weekOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 首版只读工具（设计稿 §4.3）：查某天、查待办、查某周课表。
 *
 * 全部是纯函数：只吃 [AiChatContext] 快照，不碰仓库、不落盘、不读时钟（`today` 由
 * 调用方注入）；结果统一由 [AiToolExecutor] 包成 `{ok,data}` 信封。
 *
 * 参数 schema 只用 `type/properties/required/enum/description`，保持自定义网关兼容面。
 */
internal object AiReadTools {

    // ---------- Schema ----------

    val dayPlanParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("date", "日期 yyyy-MM-dd；省略 = 今天")
        }
    }

    val searchTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("query", "名称/备注包含匹配（大小写不敏感）")
            enumProperty(
                "status",
                "状态：active（默认，未归档未删除）/completed/archived/deleted/all",
                listOf("active", "completed", "archived", "deleted", "all"),
            )
            stringProperty("group", "分组名精确匹配")
            stringProperty("tag", "标签精确匹配")
            stringProperty("dueFrom", "日期/截止的下界 yyyy-MM-dd")
            stringProperty("dueTo", "日期/截止的上界 yyyy-MM-dd")
            booleanProperty("includeDetail", "是否返回备注/步骤/地点（默认 false）")
            integerProperty("limit", "返回条数 1..30，默认 10")
        }
    }

    val weekScheduleParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            integerProperty("weekOffset", "周偏移：0=本周（默认），-1=上周，1=下周；范围 -4..4")
        }
    }

    // ---------- get_day_plan ----------

    suspend fun executeDayPlan(args: JsonObject, ctx: AiChatContext): JsonObject {
        val dateText = args.stringOrNull("date")
        val date = if (dateText.isNullOrBlank()) {
            ctx.today
        } else {
            IsoDate.parseOrNull(dateText)
                ?: return failure("invalid_arguments", "date 必须是 yyyy-MM-dd")
        }
        val schedule = ctx.schedule
            ?: return failure("not_found", "未设置课表")
        val week = schedule.weekOf(date)
        val courses = dayCourses(schedule, date)
        val todos = ctx.todos
            .filter { it.occursOn(date) }
            .sortedWith(todoComparator())
            .map { it.toDayPlanItem(date) }
        val freeSlots = freeSlots(schedule, date)
        return success(
            buildJsonObject {
                put("date", date.toString())
                put("weekday", weekdayZh(date))
                put("week", week)
                put("courses", buildJsonArray { courses.forEach { add(it) } })
                put("todos", buildJsonArray { todos.forEach { add(it) } })
                put("freeSlots", buildJsonArray { freeSlots.forEach { add(it) } })
            },
        )
    }

    private fun dayCourses(schedule: Schedule, date: IsoDate): List<JsonObject> =
        schedule.occurrencesOn(date).mapNotNull { occurrence ->
            val start = schedule.slots.getOrNull(occurrence.startSlot - 1) ?: return@mapNotNull null
            val end = schedule.slots.getOrNull(occurrence.endSlot - 1) ?: start
            buildJsonObject {
                put("name", occurrence.course.name)
                put("startTime", displayTime(start.startTime))
                put("endTime", displayTime(end.endTime))
                if (occurrence.course.location.isNotBlank()) {
                    put("location", occurrence.course.location)
                }
                if (occurrence.course.teacher.isNotBlank()) {
                    put("teacher", occurrence.course.teacher)
                }
            }
        }

    /** 当天课程区间在「首节开始..末节结束」内做差集；无课 = 整段空闲 */
    private fun freeSlots(schedule: Schedule, date: IsoDate): List<JsonObject> {
        val first = schedule.slots.firstOrNull() ?: return emptyList()
        val last = schedule.slots.lastOrNull() ?: return emptyList()
        val dayStart = TimeText.parseMinutes(first.startTime) ?: return emptyList()
        val dayEnd = TimeText.parseMinutes(last.endTime) ?: return emptyList()
        if (dayEnd <= dayStart) return emptyList()

        val busy = schedule.occurrencesOn(date).mapNotNull { occurrence ->
            val startSlot = schedule.slots.getOrNull(occurrence.startSlot - 1)
                ?: return@mapNotNull null
            val endSlot = schedule.slots.getOrNull(occurrence.endSlot - 1) ?: startSlot
            val start = TimeText.parseMinutes(startSlot.startTime) ?: return@mapNotNull null
            val end = TimeText.parseMinutes(endSlot.endTime) ?: return@mapNotNull null
            if (end <= start) null else IntRange(start, end)
        }.sortedBy { it.first }

        val merged = mutableListOf<IntRange>()
        for (range in busy) {
            val lastRange = merged.lastOrNull()
            if (lastRange != null && range.first <= lastRange.last) {
                if (range.last > lastRange.last) {
                    merged[merged.size - 1] = IntRange(lastRange.first, range.last)
                }
            } else {
                merged += range
            }
        }

        val result = mutableListOf<JsonObject>()
        var cursor = dayStart
        for (range in merged) {
            val start = range.first.coerceIn(dayStart, dayEnd)
            val end = range.last.coerceIn(dayStart, dayEnd)
            if (start > cursor) result += freeSlot(cursor, start)
            if (end > cursor) cursor = end
        }
        if (cursor < dayEnd) result += freeSlot(cursor, dayEnd)
        return result
    }

    private fun freeSlot(startMinute: Int, endMinute: Int): JsonObject = buildJsonObject {
        put("start", TimeText.formatDisplay(startMinute))
        put("end", TimeText.formatDisplay(endMinute))
        put("minutes", endMinute - startMinute)
    }

    /** 当天待办条目：只保留 id/name/completed/deadline，控制 token */
    private fun Todo.toDayPlanItem(date: IsoDate): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("completed", isCompletedOn(date))
        deadlineText()?.let { put("deadline", it) }
    }

    // ---------- search_todos ----------

    suspend fun executeSearchTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val status = args.stringOrNull("status")?.trim().orEmpty().ifBlank { STATUS_ACTIVE }
        if (status !in STATUS_VALUES) {
            return failure("invalid_arguments", "status 只能是 ${STATUS_VALUES.joinToString("/")}")
        }
        val limit = args.intOrNull("limit") ?: DEFAULT_SEARCH_LIMIT
        if (limit !in 1..MAX_SEARCH_LIMIT) {
            return failure("invalid_arguments", "limit 必须在 1..$MAX_SEARCH_LIMIT")
        }
        val dueFrom = args.stringOrNull("dueFrom")?.takeIf { it.isNotBlank() }?.let { text ->
            IsoDate.parseOrNull(text) ?: return failure("invalid_arguments", "dueFrom 必须是 yyyy-MM-dd")
        }
        val dueTo = args.stringOrNull("dueTo")?.takeIf { it.isNotBlank() }?.let { text ->
            IsoDate.parseOrNull(text) ?: return failure("invalid_arguments", "dueTo 必须是 yyyy-MM-dd")
        }
        if (dueFrom != null && dueTo != null && dueFrom.epochDay > dueTo.epochDay) {
            return failure("invalid_arguments", "dueFrom 不能晚于 dueTo")
        }
        val query = args.stringOrNull("query")?.trim()?.takeIf { it.isNotEmpty() }
        val group = args.stringOrNull("group")?.trim()?.takeIf { it.isNotEmpty() }
        val tag = args.stringOrNull("tag")?.trim()?.takeIf { it.isNotEmpty() }
        val includeDetail = args.booleanOrNull("includeDetail") ?: false

        val candidates = candidates(ctx, status)
            .filter { todo ->
                query == null ||
                    todo.name.contains(query, ignoreCase = true) ||
                    todo.note.contains(query, ignoreCase = true)
            }
            .filter { todo -> group == null || todo.group == group }
            .filter { todo -> tag == null || todo.tags.contains(tag) }
            .filter { todo -> dueFrom == null && dueTo == null || todo.occursWithin(dueFrom, dueTo) }
        val sorted = candidates.sortedWith(todoComparator())
        val shown = sorted.take(limit)
        return success(
            buildJsonObject {
                put("total", sorted.size)
                put("truncated", sorted.size > shown.size)
                put("items", buildJsonArray { shown.forEach { add(it.toSearchItem(includeDetail)) } })
            },
        )
    }

    private fun candidates(ctx: AiChatContext, status: String): List<Todo> = when (status) {
        STATUS_ACTIVE -> ctx.todos.filterNot { it.isDeleted || it.isArchived }
        STATUS_COMPLETED -> ctx.todos.filter { it.completed && !it.isDeleted && !it.isArchived }
        STATUS_ARCHIVED -> ctx.archivedTodos.filterNot { it.isDeleted }
        STATUS_DELETED -> ctx.deletedTodos
        else -> (ctx.todos + ctx.archivedTodos + ctx.deletedTodos).distinctBy { it.id }
    }

    private fun Todo.toSearchItem(includeDetail: Boolean): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        putJsonArray("dates") { dates.forEach { add(it.toString()) } }
        deadlineText()?.let { put("deadline", it) }
        put("urgency", urgency.name)
        group?.let { put("group", it) }
        if (tags.isNotEmpty()) {
            putJsonArray("tags") { tags.forEach { add(it) } }
        }
        put("completed", completed)
        put("pinned", pinned)
        if (repeat.type != RepeatType.None) {
            put("repeat", AiPromptBuilder.repeatText(repeat))
        }
        if (includeDetail) {
            if (note.isNotBlank()) put("note", note)
            if (location.isNotBlank()) put("location", location)
            if (steps.isNotEmpty()) {
                putJsonArray("steps") {
                    steps.forEach { step ->
                        add(
                            buildJsonObject {
                                put("title", step.title)
                                put("done", step.done)
                            },
                        )
                    }
                }
            }
        }
    }

    // ---------- get_week_schedule ----------

    suspend fun executeWeekSchedule(args: JsonObject, ctx: AiChatContext): JsonObject {
        val offset = args.intOrNull("weekOffset") ?: 0
        if (offset !in -4..4) {
            return failure("invalid_arguments", "weekOffset 必须在 -4..4")
        }
        val schedule = ctx.schedule
            ?: return failure("not_found", "未设置课表")
        val baseWeek = ctx.currentWeek ?: schedule.weekOf(ctx.today)
        val targetWeek = baseWeek + offset
        // 目标周的周一由学期起点推算（而非 today + offset）：越界周/学期外也不会读错周
        val monday = schedule.termStart.mondayOfWeek() + (targetWeek - 1) * 7
        val dayCount = schedule.daysPerWeek.coerceIn(1, 7)
        val days = (0 until dayCount).map { index ->
            val date = monday + index
            buildJsonObject {
                put("date", date.toString())
                put("weekday", weekdayZh(date))
                put("courses", buildJsonArray { dayCourses(schedule, date).forEach { add(it) } })
            }
        }
        return success(
            buildJsonObject {
                put("week", targetWeek)
                putJsonArray("dates") { days.forEach { add(it["date"] ?: JsonPrimitive("")) } }
                put("days", buildJsonArray { days.forEach { add(it) } })
            },
        )
    }

    // ---------- 共用 ----------

    /** 待办在这一天是否有安排：日期命中 / 截止命中 / 重复期次命中 */
    private fun Todo.occursOn(date: IsoDate): Boolean {
        if (deadlineDate == date) return true
        if (date in dates) return true
        if (repeat.type == RepeatType.None) return false
        val anchor = dates.minByOrNull { it.epochDay } ?: return false
        return repeat.occurrencesBetween(anchor, date, date + 1).isNotEmpty()
    }

    /** 这一天的完成态：整条完成，或重复待办该期已完成 */
    private fun Todo.isCompletedOn(date: IsoDate): Boolean =
        completed || (repeat.type != RepeatType.None && date in completedOccurrences)

    /** 待办是否落在 [from, to] 区间（日期 / 截止 / 重复期次任一命中；端点含） */
    private fun Todo.occursWithin(from: IsoDate?, to: IsoDate?): Boolean {
        if (from == null && to == null) return true
        val candidates = buildList {
            addAll(dates)
            deadlineDate?.let { add(it) }
        }
        if (candidates.any { date -> (from == null || date.epochDay >= from.epochDay) && (to == null || date.epochDay <= to.epochDay) }) {
            return true
        }
        if (repeat.type == RepeatType.None) return false
        val anchor = dates.minByOrNull { it.epochDay } ?: return false
        return when {
            from != null && to != null ->
                repeat.occurrencesBetween(anchor, from, to + 1).isNotEmpty()
            from != null -> {
                // nextOccurrenceAfter 起点含端点：传 from 即「从 from 起（含）」
                val next = repeat.nextOccurrenceAfter(anchor, from, emptySet()) ?: return false
                to == null || next.epochDay <= to.epochDay
            }
            to != null -> repeat.occurrencesBetween(anchor, anchor, to + 1).isNotEmpty()
            else -> true
        }
    }

    /** 未完成在前 → 截止/首个日期升序 → 创建时间倒序 */
    private fun todoComparator(): Comparator<Todo> = compareBy<Todo> { it.completed }
        .thenBy { todo ->
            (todo.deadlineDate ?: todo.dates.minByOrNull { it.epochDay } ?: FAR_FUTURE).epochDay
        }
        .thenByDescending { it.createdAtMillis }

    private fun Todo.deadlineText(): String? {
        val date = deadlineDate ?: return null
        val minute = deadlineMinute ?: return date.toString()
        return "$date ${TimeText.formatDisplay(minute)}"
    }

    private fun displayTime(text: String): String = TimeText.formatDisplay(text)

    private fun weekdayZh(date: IsoDate): String = WEEKDAY_ZH[date.isoDayOfWeek() - 1]

    private fun success(data: JsonObject): JsonObject = buildJsonObject {
        put("ok", true)
        put("data", data)
    }

    private fun failure(code: String, message: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.intOrNull(name: String): Int? =
        (this[name] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.booleanOrNull(name: String): Boolean? =
        (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(
        name: String,
        description: String,
    ) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.integerProperty(
        name: String,
        description: String,
    ) {
        putJsonObject(name) {
            put("type", "integer")
            put("description", description)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.booleanProperty(
        name: String,
        description: String,
    ) {
        putJsonObject(name) {
            put("type", "boolean")
            put("description", description)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.enumProperty(
        name: String,
        description: String,
        values: List<String>,
    ) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
            putJsonArray("enum") { values.forEach { add(it) } }
        }
    }

    private val WEEKDAY_ZH = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val FAR_FUTURE = IsoDate(Long.MAX_VALUE / 2)

    private const val STATUS_ACTIVE = "active"
    private const val STATUS_COMPLETED = "completed"
    private const val STATUS_ARCHIVED = "archived"
    private const val STATUS_DELETED = "deleted"
    private val STATUS_VALUES = listOf(
        STATUS_ACTIVE,
        STATUS_COMPLETED,
        STATUS_ARCHIVED,
        STATUS_DELETED,
        "all",
    )
    private const val DEFAULT_SEARCH_LIMIT = 10
    private const val MAX_SEARCH_LIMIT = 30
}
