package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.isArchived
import com.fangyi.classpp.data.model.isDeleted
import com.fangyi.classpp.data.model.nextOccurrenceAfter
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.currentOccurrenceDeadline
import com.fangyi.classpp.data.occurrencesOn
import com.fangyi.classpp.data.weekOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 只读工具（设计稿 §4.3 + P3 的 `get_todo`）：查当前时刻、查单天/连续多天/离散多天、
 * 查待办、查某周课表、查单条详情。
 *
 * 全部是纯函数：只吃 [AiChatContext] 快照，不碰仓库、不落盘、不读系统时钟
 * （`today`/`nowMinute` 由调用方用同一时钟注入）；结果统一由 [AiToolExecutor] 包成
 * `{ok,data}` 信封。
 *
 * 参数 schema 只用 `type/properties/required/enum/description`，保持自定义网关兼容面。
 *
 * P3 起 system prompt 只注入今日摘要，`get_todo` 承担「拿到 id 之后看详情」这一步；
 * `find_free_slots` 没有单独拆出——`get_day_plan` 已返回带 minutes 的空闲时段，够用
 * 就不增加模型的选择成本（工具不是越多越好）。
 */
internal object AiReadTools {

    // ---------- Schema ----------

    val currentTimeParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
    }

    val dayPlanParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty(
                "date",
                "单日日期 yyyy-MM-dd；省略 date 且不给 from/to/dates = 今天；给了其他日期参数时忽略",
            )
            stringProperty(
                "from",
                "连续多天的起始日期 yyyy-MM-dd（含）；必须与 to 成对使用，跨度最多 " +
                    "$MAX_DAY_PLAN_DAYS 天；不能与 dates 同时传",
            )
            stringProperty(
                "to",
                "连续多天的结束日期 yyyy-MM-dd（含）；必须与 from 成对使用，跨度最多 " +
                    "$MAX_DAY_PLAN_DAYS 天；不能与 dates 同时传",
            )
            putJsonObject("dates") {
                put("type", "array")
                put(
                    "description",
                    "离散日期列表 yyyy-MM-dd；用于不连续的多天（如 11月4号、12月14号、2月3号），" +
                        "最多 $MAX_DAY_PLAN_DAYS 个，按传入顺序返回；不能与 from/to 同时传",
                )
                putJsonObject("items") { put("type", "string") }
            }
        }
    }

    val searchTodosParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("query", "名称/备注包含匹配（大小写不敏感）")
            enumProperty(
                "status",
                "状态：active（默认，未归档未删除，含已完成）/incomplete（仅未完成）/completed/" +
                    "archived（已归档）/deleted（回收站，仅在用户明确要求查看或恢复时用）/" +
                    "all（同 active，不含归档与回收站）",
                listOf("active", "incomplete", "completed", "archived", "deleted", "all"),
            )
            stringProperty("group", "分组名精确匹配")
            stringProperty("tag", "标签精确匹配")
            stringProperty("dueFrom", "日期/截止的下界 yyyy-MM-dd")
            stringProperty("dueTo", "日期/截止的上界 yyyy-MM-dd")
            stringProperty("completedFrom", "完成日期下界 yyyy-MM-dd（只匹配已完成条目，含当天）")
            stringProperty("completedTo", "完成日期上界 yyyy-MM-dd（只匹配已完成条目，含当天）")
            booleanProperty("includeDetail", "是否返回备注/步骤/地点（默认 false）")
            integerProperty(
                "limit",
                "预览/分页条数 1..30，默认 10；只有用户明确只要“最近 N 条/先看几条/下一页”时才传，" +
                    "否则不要传（不传 limit/offset = 默认完整返回）",
            )
            integerProperty(
                "offset",
                "分页偏移，0 起；只有继续翻页时才传「上一页 offset + 本页条数」；" +
                    "不传 limit/offset 时默认完整返回",
            )
            booleanProperty(
                "all",
                "true = 一次返回全部匹配结果（忽略 limit/offset）。默认即为完整返回，" +
                    "显式传 true 只用于强调完整/精确统计；" +
                    "结果过大时自动降级为精简字段（compact=true），仍装不下才返回 limit_exceeded",
            )
        }
    }

    val weekScheduleParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            integerProperty(
                "week",
                "学期周次（1 起）；已知具体第几周时传它，优先于 weekOffset",
            )
            integerProperty(
                "weekOffset",
                "相对当前周的偏移：0=本周（默认），-1=上周，1=下周；目标周须落在课表周次内",
            )
            booleanProperty(
                "overview",
                "true = 只返回课表概况（总周数、当前周、学期起止、每周天数），不返回每天课程；" +
                    "用于回答“一共几周/什么时候结课”，忽略 week 与 weekOffset",
            )
        }
    }

    val getTodoParameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            stringProperty("id", "待办 id，必须原样取自上下文或 search_todos 结果")
        }
        putJsonArray("required") { add("id") }
    }

    // ---------- get_current_time ----------

    /**
     * 当前日期与时刻（本地时区）。
     *
     * 时钟只经 [AiChatContext] 注入（`today` + `nowMinute`，仓库在每轮工具执行前用同一
     * 时钟刷新），函数本身不读系统时间：跨午夜的长对话里模型重新调用一次即可拿到新日期。
     */
    suspend fun executeCurrentTime(args: JsonObject, ctx: AiChatContext): JsonObject {
        val minute = ctx.nowMinute.coerceIn(0, MINUTES_PER_DAY - 1)
        val time = TimeText.formatDisplay(minute)
        return success(
            buildJsonObject {
                put("date", ctx.today.toString())
                put("weekday", weekdayZh(ctx.today))
                put("time", time)
                put("dateTime", "${ctx.today} $time")
                put("minuteOfDay", minute)
                put("partOfDay", partOfDayZh(minute))
                ctx.currentWeek?.let { put("week", it) }
            },
        )
    }

    /** 时段（24 小时制）：凌晨 0-4、早上 5-7、上午 8-10、中午 11-12、下午 13-17、晚上 18-23 */
    private fun partOfDayZh(minute: Int): String {
        val hour = minute / 60
        return when {
            hour < 5 -> "凌晨"
            hour < 8 -> "早上"
            hour < 11 -> "上午"
            hour < 13 -> "中午"
            hour < 18 -> "下午"
            else -> "晚上"
        }
    }

    // ---------- get_day_plan ----------

    suspend fun executeDayPlan(args: JsonObject, ctx: AiChatContext): JsonObject {
        val schedule = ctx.schedule
            ?: return failure("not_found", "未设置课表")
        val fromText = args.stringOrNull("from")
        val toText = args.stringOrNull("to")
        val datesElement = args["dates"]
        if (datesElement != null && (!fromText.isNullOrBlank() || !toText.isNullOrBlank())) {
            return failure("invalid_arguments", "dates 与 from/to 不能同时使用")
        }
        if (datesElement != null) {
            // 离散日期模式：一次查不连续的多天（如 11月4号、12月14号、2月3号）
            val raw = datesElement as? JsonArray
                ?: return failure("invalid_arguments", "dates 必须是 yyyy-MM-dd 字符串数组")
            if (raw.isEmpty()) {
                return failure("invalid_arguments", "dates 不能为空")
            }
            val dates = mutableListOf<IsoDate>()
            val seen = mutableSetOf<Long>()
            for ((index, element) in raw.withIndex()) {
                val text = (element as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return failure("invalid_arguments", "dates[$index] 必须是 yyyy-MM-dd 字符串")
                val date = IsoDate.parseOrNull(text)
                    ?: return failure("invalid_arguments", "dates[$index] 必须是 yyyy-MM-dd")
                // 去重但保留首次出现的顺序，结果按模型传入顺序返回
                if (seen.add(date.epochDay)) dates += date
            }
            if (dates.size > MAX_DAY_PLAN_DAYS) {
                return failure(
                    "invalid_arguments",
                    "一次最多查询 $MAX_DAY_PLAN_DAYS 天，请拆分日期",
                )
            }
            return success(
                buildJsonObject {
                    put(
                        "days",
                        buildJsonArray {
                            dates.forEach { add(dayPlanObject(schedule, it, ctx.todos, ctx.today)) }
                        },
                    )
                },
            )
        }
        if (!fromText.isNullOrBlank() || !toText.isNullOrBlank()) {
            // 范围模式：一次返回连续多天，避免模型逐天调用
            if (fromText.isNullOrBlank() || toText.isNullOrBlank()) {
                return failure("invalid_arguments", "from 与 to 必须成对使用")
            }
            val from = IsoDate.parseOrNull(fromText)
                ?: return failure("invalid_arguments", "from 必须是 yyyy-MM-dd")
            val to = IsoDate.parseOrNull(toText)
                ?: return failure("invalid_arguments", "to 必须是 yyyy-MM-dd")
            if (from.epochDay > to.epochDay) {
                return failure("invalid_arguments", "from 不能晚于 to")
            }
            val dayCount = (to - from).toInt() + 1
            if (dayCount > MAX_DAY_PLAN_DAYS) {
                return failure(
                    "invalid_arguments",
                    "一次最多查询 $MAX_DAY_PLAN_DAYS 天，请拆分范围",
                )
            }
            return success(
                buildJsonObject {
                    put("from", from.toString())
                    put("to", to.toString())
                    put(
                        "days",
                        buildJsonArray {
                            for (offset in 0 until dayCount) {
                                add(dayPlanObject(schedule, from + offset, ctx.todos, ctx.today))
                            }
                        },
                    )
                },
            )
        }

        val dateText = args.stringOrNull("date")
        val date = if (dateText.isNullOrBlank()) {
            ctx.today
        } else {
            IsoDate.parseOrNull(dateText)
                ?: return failure("invalid_arguments", "date 必须是 yyyy-MM-dd")
        }
        return success(dayPlanObject(schedule, date, ctx.todos, ctx.today))
    }

    /** 单日结构；单日与范围模式共用，保证两种返回的每天字段一致 */
    private fun dayPlanObject(
        schedule: Schedule,
        date: IsoDate,
        todos: List<Todo>,
        now: IsoDate,
    ): JsonObject {
        val week = schedule.weekOf(date)
        val courses = dayCourses(schedule, date)
        val dayTodos = todos
            .filter { it.occursOnDate(date) }
            .sortedWith(todoComparator())
            .map { it.toDayPlanItem(date, now) }
        val freeSlots = freeSlots(schedule, date)
        return buildJsonObject {
            put("date", date.toString())
            put("weekday", weekdayZh(date))
            put("week", week)
            put("courses", buildJsonArray { courses.forEach { add(it) } })
            put("todos", buildJsonArray { dayTodos.forEach { add(it) } })
            put("freeSlots", buildJsonArray { freeSlots.forEach { add(it) } })
        }
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
    private fun Todo.toDayPlanItem(date: IsoDate, now: IsoDate): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("completed", isCompletedOnDate(date))
        deadlineText(now)?.let { put("deadline", it) }
    }

    // ---------- search_todos ----------

    suspend fun executeSearchTodos(args: JsonObject, ctx: AiChatContext): JsonObject {
        val status = args.stringOrNull("status")?.trim().orEmpty().ifBlank { STATUS_ACTIVE }
        if (status !in STATUS_VALUES) {
            return failure("invalid_arguments", "status 只能是 ${STATUS_VALUES.joinToString("/")}")
        }
        // 显式给出的分页参数始终要合法（即使 all=true 会忽略它们）
        val limitProvided = args.containsKey("limit")
        val limit = args.intOrNull("limit") ?: DEFAULT_SEARCH_LIMIT
        if (limitProvided && limit !in 1..MAX_SEARCH_LIMIT) {
            return failure("invalid_arguments", "limit 必须在 1..$MAX_SEARCH_LIMIT")
        }
        val offset = args.intOrNull("offset") ?: 0
        if (offset < 0) {
            return failure("invalid_arguments", "offset 不能为负")
        }
        // 默认完整返回：只有模型显式传 limit/offset（明确只要少量或继续翻页）才走普通浏览。
        // 这样“查完整”不依赖模型记得传 all=true；显式 all=false 仍可强制分页。
        val pagingRequested = limitProvided || offset > 0
        val all = args.booleanOrNull("all") ?: !pagingRequested
        val dueFrom = args.stringOrNull("dueFrom")?.takeIf { it.isNotBlank() }?.let { text ->
            IsoDate.parseOrNull(text) ?: return failure("invalid_arguments", "dueFrom 必须是 yyyy-MM-dd")
        }
        val dueTo = args.stringOrNull("dueTo")?.takeIf { it.isNotBlank() }?.let { text ->
            IsoDate.parseOrNull(text) ?: return failure("invalid_arguments", "dueTo 必须是 yyyy-MM-dd")
        }
        if (dueFrom != null && dueTo != null && dueFrom.epochDay > dueTo.epochDay) {
            return failure("invalid_arguments", "dueFrom 不能晚于 dueTo")
        }
        val completedFrom = args.stringOrNull("completedFrom")?.takeIf { it.isNotBlank() }?.let { text ->
            IsoDate.parseOrNull(text) ?: return failure("invalid_arguments", "completedFrom 必须是 yyyy-MM-dd")
        }
        val completedTo = args.stringOrNull("completedTo")?.takeIf { it.isNotBlank() }?.let { text ->
            IsoDate.parseOrNull(text) ?: return failure("invalid_arguments", "completedTo 必须是 yyyy-MM-dd")
        }
        if (completedFrom != null && completedTo != null && completedFrom.epochDay > completedTo.epochDay) {
            return failure("invalid_arguments", "completedFrom 不能晚于 completedTo")
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
            .filter { todo -> completedFrom == null && completedTo == null || todo.completedWithin(completedFrom, completedTo) }
        // 完成相关查询按完成时刻倒序：与「最近完成了什么」的直觉一致，分页/区间也稳定
        val sorted = if (completedFrom != null || completedTo != null || status == STATUS_COMPLETED) {
            candidates.sortedWith(completedComparator())
        } else {
            candidates.sortedWith(todoComparator())
        }
        val shown = if (all) sorted else sorted.drop(offset).take(limit)
        val data = buildJsonObject {
            put("total", sorted.size)
            put("offset", if (all) 0 else offset)
            // 还有下一页（而不是「本页被 limit 截断」）：模型按 offset + items.size 继续取
            put("truncated", if (all) false else offset.toLong() + shown.size < sorted.size)
            put("items", buildJsonArray { shown.forEach { add(it.toSearchItem(includeDetail, ctx.today)) } })
        }
        if (all) {
            val chars = data.toString().length
            if (chars + RESULT_ENVELOPE_MARGIN > MAX_SEARCH_RESULT_CHARS) {
                // 完整集合装不下完整字段时，降级为精简字段（id/name/时间），
                // 优先保证「条数完整」；详情仍可用 get_todo 单条取。
                val compact = buildJsonObject {
                    put("total", sorted.size)
                    put("offset", 0)
                    put("truncated", false)
                    put("compact", true)
                    put("items", buildJsonArray {
                        sorted.forEach { add(it.toCompactSearchItem(ctx.today)) }
                    })
                }
                val compactChars = compact.toString().length
                if (compactChars + RESULT_ENVELOPE_MARGIN <= MAX_SEARCH_RESULT_CHARS) {
                    return success(compact)
                }
                // 连精简字段都装不下：显式失败，至少让模型拿到准确 total 并请用户缩小范围
                return failure(
                    "limit_exceeded",
                    "共 ${sorted.size} 条，精简结果 $compactChars 字符，超过单次上限 $MAX_SEARCH_RESULT_CHARS；" +
                        "请用 completedFrom/completedTo、dueFrom/dueTo、group 或 tag 缩小范围",
                )
            }
        }
        return success(data)
    }

    /**
     * 候选集：默认与 `all` 都只取活跃待办（未归档未删除，含已完成）。
     *
     * `completed` 对重复待办按期次口径：整条 `completed` 恒 false，期次有完成记录即入列
     * （部分完成的系列不是整条完成，结果项带 `completedOccurrences` 供模型区分）。
     *
     * `all` **不**并入归档/回收站：这两个空间只在 status 显式指定时返回，否则模型
     * 会把回收站条目当成活跃待办列进查询结果（`all` 是最容易被误用的取值）。
     */
    private fun candidates(ctx: AiChatContext, status: String): List<Todo> = when (status) {
        STATUS_ACTIVE, STATUS_ALL -> ctx.todos.filterNot { it.isDeleted || it.isArchived }
        STATUS_INCOMPLETE -> ctx.todos.filter { !it.completed && !it.isDeleted && !it.isArchived }
        STATUS_COMPLETED -> ctx.todos.filter {
            !it.isDeleted && !it.isArchived && (
                it.completed ||
                    (it.repeat.type != RepeatType.None && it.completedOccurrences.isNotEmpty())
                )
        }
        STATUS_ARCHIVED -> ctx.archivedTodos.filterNot { it.isDeleted }
        STATUS_DELETED -> ctx.deletedTodos
        else -> error("unreachable: status already validated")
    }

    private fun Todo.toSearchItem(includeDetail: Boolean, now: IsoDate): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        putJsonArray("dates") { dates.forEach { add(it.toString()) } }
        deadlineText(now)?.let { put("deadline", it) }
        put("urgency", urgency.name)
        group?.let { put("group", it) }
        if (tags.isNotEmpty()) {
            putJsonArray("tags") { tags.forEach { add(it.toString()) } }
        }
        put("completed", completed)
        if (completed) {
            // 完成日期按本地时区落天（与 TodoOverviewSheet 的完成记录口径一致）
            completedAtMillis?.let { put("completedAt", IsoDate.today(it).toString()) }
        } else if (repeat.type != RepeatType.None && completedOccurrences.isNotEmpty()) {
            // 重复待办部分完成：整条 completed 恒 false，已完成期次单独列出——
            // status=completed 的候选据此判断「哪些期做完了」，不会误读成整条完成
            putJsonArray("completedOccurrences") {
                completedOccurrences.sortedBy { it.epochDay }
                    .takeLast(MAX_OCCURRENCE_LINES)
                    .forEach { add(it.toString()) }
            }
        }
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

    /**
     * all 模式超预算时的精简条目：只保留识别与时间字段。
     *
     * 目标是把「完整条数」优先带回（`compact=true`），字段细节交给 `get_todo` 单条取；
     * 模型不能把精简结果当成包含备注/标签的完整详情。
     */
    private fun Todo.toCompactSearchItem(now: IsoDate): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        group?.let { put("group", it) }
        if (completed) {
            put("completed", true)
            completedAtMillis?.let { put("completedAt", IsoDate.today(it).toString()) }
        } else {
            // 随重复的截止按当前期折算（同 toSearchItem 的 deadline 口径）
            (currentOccurrenceDeadline(now) ?: deadlineDate ?: dates.minByOrNull { it.epochDay })
                ?.let { put("date", it.toString()) }
        }
    }

    // ---------- get_week_schedule ----------

    suspend fun executeWeekSchedule(args: JsonObject, ctx: AiChatContext): JsonObject {
        val schedule = ctx.schedule
            ?: return failure("not_found", "未设置课表")
        val totalWeeks = schedule.totalWeeks
        // 概况模式：回答“一共几周/什么时候结课”只需要学期信息，不必把整周课程带回来
        if (args.booleanOrNull("overview") == true) {
            return success(
                buildJsonObject {
                    put("overview", true)
                    put("totalWeeks", totalWeeks)
                    ctx.currentWeek?.let { put("currentWeek", it) }
                    put("termStart", schedule.termStart.toString())
                    put("termEnd", schedule.termEnd.toString())
                    put("daysPerWeek", schedule.daysPerWeek)
                },
            )
        }
        val requestedWeek = args.intOrNull("week")
        val targetWeek = if (requestedWeek != null) {
            // 绝对周次优先：学期外也能直接查任意一周，不依赖“当前第几周”
            if (requestedWeek !in 1..totalWeeks) {
                return failure("invalid_arguments", "week 必须在 1..$totalWeeks（课表共 $totalWeeks 周）")
            }
            requestedWeek
        } else {
            val offset = args.intOrNull("weekOffset") ?: 0
            val baseWeek = ctx.currentWeek ?: schedule.weekOf(ctx.today)
            val week = baseWeek + offset
            if (week !in 1..totalWeeks) {
                return failure(
                    "invalid_arguments",
                    "weekOffset=$offset 指向第 $week 周，超出课表范围" +
                        "（课表共 $totalWeeks 周，可查第 1..$totalWeeks 周）",
                )
            }
            week
        }
        // 目标周的周一由学期起点推算（而非 today + offset）：周次与日期始终按学期对齐
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
                put("totalWeeks", totalWeeks)
                putJsonArray("dates") { days.forEach { add(it["date"] ?: JsonPrimitive("")) } }
                put("days", buildJsonArray { days.forEach { add(it) } })
            },
        )
    }

    // ---------- get_todo ----------

    /**
     * 单条待办的完整详情：备注、步骤、重复规则与期次、所在状态（活跃/归档/回收站）。
     *
     * 精简上下文后模型常只有 id 与一行摘要；改期限、改备注、完成某一期时需要这些细节，
     * 否则只能凭猜测拼 patch。id 跨三个集合查找——归档/回收站条目也能看详情（但不代表
     * 可以直接修改，写工具仍会按状态返回 `wrong_state`）。
     */
    suspend fun executeGetTodo(args: JsonObject, ctx: AiChatContext): JsonObject {
        val id = args.stringOrNull("id")?.trim().orEmpty()
        if (id.isEmpty()) return failure("invalid_arguments", "id 不能为空")
        val todo = (ctx.todos + ctx.archivedTodos + ctx.deletedTodos).firstOrNull { it.id == id }
            ?: return failure("not_found", "没有 id=$id 的待办（可用 search_todos 查 id）")
        return success(todo.toDetail(ctx.today))
    }

    private fun Todo.toDetail(now: IsoDate): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put(
            "status",
            when {
                isDeleted -> STATUS_DELETED
                isArchived -> STATUS_ARCHIVED
                else -> STATUS_ACTIVE
            },
        )
        put("completed", completed)
        if (dates.isNotEmpty()) {
            putJsonArray("dates") { dates.forEach { add(it.toString()) } }
        }
        timeText()?.let { put("time", it) }
        deadlineText(now)?.let { put("deadline", it) }
        if (deadlineDate != null) {
            put("deadlineBeforeRepeat", deadlineBeforeRepeat)
            if (deadlineBeforeRepeat) put("deadlineBeforeDays", deadlineBeforeDays)
        }
        put("urgency", urgency.name)
        group?.let { put("group", it) }
        if (tags.isNotEmpty()) {
            putJsonArray("tags") { tags.forEach { add(it) } }
        }
        if (location.isNotBlank()) put("location", location)
        if (note.isNotBlank()) put("note", note.take(MAX_NOTE_CHARS))
        if (steps.isNotEmpty()) {
            putJsonArray("steps") {
                steps.take(MAX_STEP_LINES).forEach { step ->
                    add(
                        buildJsonObject {
                            put("title", step.title)
                            put("done", step.done)
                        },
                    )
                }
            }
        }
        // 布尔开关一律显式给出：模型要判断「取消置顶/关闭倒计时」时不能靠缺失字段猜
        put("pinned", pinned)
        put("countdown", countdown)
        if (repeat.type != RepeatType.None) {
            put("repeat", AiPromptBuilder.repeatText(repeat))
            put("repeatRule", repeat.toRuleJson())
            putJsonArray("pendingOccurrences") {
                AiPromptBuilder.pendingOccurrenceDates(this@toDetail, now).forEach { add(it.toString()) }
            }
            val completedDates = completedOccurrences.sortedBy { it.epochDay }.takeLast(MAX_OCCURRENCE_LINES)
            if (completedDates.isNotEmpty()) {
                putJsonArray("completedOccurrences") { completedDates.forEach { add(it.toString()) } }
            }
            // 漏期（错过未完成的期次）：产品口径「逾期 = 已过截止」，这些日期不叫逾期，
            // 仓库字段沿用历史名 overdueOccurrences
            val missedDates = overdueOccurrences.sortedBy { it.epochDay }.takeLast(MAX_OCCURRENCE_LINES)
            if (missedDates.isNotEmpty()) {
                putJsonArray("missedOccurrences") { missedDates.forEach { add(it.toString()) } }
            }
        }
    }

    /** 结构化重复规则：与 update_todos 的 `patch.repeat` 同一形状，便于直接改写 */
    private fun RepeatRule.toRuleJson(): JsonObject = buildJsonObject {
        put("type", type.name)
        if (type == RepeatType.Custom) {
            put("unit", unit.name)
            put("interval", interval)
            if (marks.isNotEmpty()) putJsonArray("marks") { marks.sorted().forEach { add(it) } }
        }
        put("endType", endType.name)
        endDate?.let { put("endDate", it.toString()) }
        endCount?.let { put("endCount", it) }
        if (checkIn) put("checkIn", true)
        put("merge", merge)
    }

    // ---------- 共用 ----------

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

    /** 完成日期是否落在 [from, to]（端点含）；重复待办任一分期完成日期命中即算——
     *  它们没有整条完成时刻（completedAtMillis 恒 null），按完成时刻搜永远搜不到 */
    private fun Todo.completedWithin(from: IsoDate?, to: IsoDate?): Boolean {
        if (from == null && to == null) return true
        val millis = completedAtMillis
        if (millis != null) {
            val date = IsoDate.today(millis)
            if ((from == null || date.epochDay >= from.epochDay) &&
                (to == null || date.epochDay <= to.epochDay)
            ) {
                return true
            }
        }
        if (repeat.type == RepeatType.None) return false
        return completedOccurrences.any { date ->
            (from == null || date.epochDay >= from.epochDay) &&
                (to == null || date.epochDay <= to.epochDay)
        }
    }

    /** 未完成在前 → 截止/首个日期升序 → 创建时间倒序 */
    private fun todoComparator(): Comparator<Todo> = compareBy<Todo> { it.completed }
        .thenBy { todo ->
            (todo.deadlineDate ?: todo.dates.minByOrNull { it.epochDay } ?: FAR_FUTURE).epochDay
        }
        .thenByDescending { it.createdAtMillis }

    /**
     * 完成相关查询：完成时刻倒序（没有完成时刻的旧数据垫底）→ 创建时间倒序。
     * 重复待办没有整条完成时刻，取最近完成分期的日期（日粒度换算成毫秒）作排序键——
     * 「最近完成了什么」的直觉与分期完成对齐。
     */
    private fun completedComparator(): Comparator<Todo> =
        compareByDescending<Todo> { todo ->
            todo.completedAtMillis
                ?: todo.completedOccurrences.maxByOrNull { it.epochDay }
                    ?.let { it.epochDay * MILLIS_PER_DAY }
                ?: Long.MIN_VALUE
        }.thenByDescending { it.createdAtMillis }

    /** 截止文本：随重复的按当前期折算（与列表卡一致，载体日期会误导），其余按存储截止 */
    private fun Todo.deadlineText(now: IsoDate): String? {
        val date = currentOccurrenceDeadline(now) ?: deadlineDate ?: return null
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
    private const val STATUS_INCOMPLETE = "incomplete"
    private const val STATUS_COMPLETED = "completed"
    private const val STATUS_ARCHIVED = "archived"
    private const val STATUS_DELETED = "deleted"
    private const val STATUS_ALL = "all"
    private val STATUS_VALUES = listOf(
        STATUS_ACTIVE,
        STATUS_INCOMPLETE,
        STATUS_COMPLETED,
        STATUS_ARCHIVED,
        STATUS_DELETED,
        STATUS_ALL,
    )
    private const val DEFAULT_SEARCH_LIMIT = 10
    private const val MAX_SEARCH_LIMIT = 30
    private const val MINUTES_PER_DAY = 24 * 60

    /** 重复待办按「最近完成分期」排序的日期→毫秒换算（日粒度，completedComparator 用） */
    private const val MILLIS_PER_DAY = 86_400_000L

    /**
     * all=true 一次返回完整集合的字符上限（比普通工具结果的 8K 宽得多）。
     *
     * 约合 600~800 条摘要；超过则显式返回 `limit_exceeded` 与总数，由用户缩小范围，
     * 而不是悄悄截断成“看起来完整”的列表。
     */
    internal const val MAX_SEARCH_RESULT_CHARS = 120_000

    /** get_day_plan 一次最多覆盖的天数（连续范围或离散日期）；超过则要求模型拆分 */
    internal const val MAX_DAY_PLAN_DAYS = 14

    /**
     * get_day_plan 的结果上限：多天模式每天含课程/待办/空闲时段，默认 8K 不够。
     * 40K 覆盖 14 天常见密度；超限时执行器按 `days` 数组截断并标记 `truncated`。
     */
    internal const val MAX_DAY_PLAN_RESULT_CHARS = 40_000

    /** 结果信封 `{ok,data}` 的余量，保证 executor 的最终字符串仍在上限内 */
    private const val RESULT_ENVELOPE_MARGIN = 64

    /** get_todo 的详情上限：备注/步骤/期次都截断，避免单条结果撑爆 8KB 预算 */
    private const val MAX_NOTE_CHARS = 800
    private const val MAX_STEP_LINES = 20
    private const val MAX_OCCURRENCE_LINES = 5
}
