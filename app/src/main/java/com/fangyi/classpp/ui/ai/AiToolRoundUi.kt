package com.fangyi.classpp.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatMessage
import com.fangyi.classpp.data.ai.AiChatRole
import com.fangyi.classpp.data.ai.AiToolCall
import com.fangyi.classpp.data.ai.AiToolRegistry
import com.fangyi.classpp.data.ai.AiToolResult
import com.fangyi.classpp.data.ai.AiToolRound
import com.fangyi.classpp.data.ai.AiToolRoundStatus
import com.fangyi.classpp.ui.motion.Expandable
import com.fangyi.classpp.ui.theme.CardSectionSpacing
import com.fangyi.classpp.ui.theme.ChatCardShape
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一条 assistant 消息内的工具轮次卡片（设计稿 §9.1）。
 *
 * 每个调用一张紧凑卡：工具名 + 参数摘要 + 状态；展开后展示结构化结果摘要，
 * **不展示原始 JSON**。业务卡片（新建待办 / 完成 / 归档 / 确认卡）仍由
 * [AiChatOverlay] 在气泡下方按原路径渲染，这里只负责查询过程。
 */
@Composable
internal fun AiChatToolRoundBlock(
    round: AiToolRound,
    modifier: Modifier = Modifier,
    /** 仅 Preview/调试用：直接以展开态渲染 */
    expandedByDefault: Boolean = false,
) {
    if (round.calls.isEmpty()) return
    Column(
        modifier = modifier,
        // 同一轮内的工具调用卡：与回复内其他卡片同节奏（10dp）
        verticalArrangement = Arrangement.spacedBy(CardSectionSpacing),
    ) {
        round.calls.forEach { call ->
            val result = round.results.firstOrNull { it.callId == call.id }
            AiChatToolCallCard(
                call = call,
                result = result,
                roundStatus = round.status,
                expandedByDefault = expandedByDefault,
            )
        }
    }
}

/** 循环因预算停止时的轻提示（设计稿 §9.3） */
@Composable
internal fun AiChatToolLoopCappedHint(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.ai_tool_loop_capped),
        style = ClassppTextTokens.secondaryTextSmall,
        color = MaterialTheme.classppColors.secondaryText,
        modifier = modifier.padding(horizontal = 2.dp),
    )
}

@Composable
private fun AiChatToolCallCard(
    call: AiToolCall,
    result: AiToolResult?,
    roundStatus: AiToolRoundStatus,
    expandedByDefault: Boolean = false,
) {
    val status = toolStatus(result, roundStatus)
    var expanded by rememberSaveable { mutableStateOf(expandedByDefault) }
    // 折叠态只留一行描述：参数与结果合并，重复的数量信息只保留结果侧
    val description = toolDescription(call, result)
    val statusColor = when (status) {
        ToolStatus.Failed -> MaterialTheme.colorScheme.error
        ToolStatus.Completed -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.classppColors.secondaryText
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = ChatCardShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(toolIconRes(call.name)),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    // Row 基础间距 8dp，额外补 6dp，让 icon ↔ 文字列到 14dp
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolDisplayName(call.name),
                        style = MaterialTheme.classppTextStyles.fieldLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (description.isNotBlank()) {
                        // 标题↔描述间距与 SettingsCard 行内文字块一致
                        Spacer(Modifier.size(3.dp))
                        Text(
                            text = description,
                            style = ClassppTextTokens.settingsDescription.copy(
                                color = MaterialTheme.classppColors.secondaryText,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (status == ToolStatus.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.classppColors.secondaryText,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(statusColor, CircleShape),
                    )
                }
                Text(
                    text = toolStatusText(status),
                    style = ClassppTextTokens.secondaryTextSmall,
                    color = statusColor,
                )
                Icon(
                    painter = painterResource(
                        if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
                    ),
                    contentDescription = stringResource(
                        if (expanded) R.string.ai_tool_collapse else R.string.ai_tool_expand,
                    ),
                    tint = MaterialTheme.classppColors.secondaryText,
                    // 与思考卡右侧展开箭头保持同尺寸
                    modifier = Modifier.size(18.dp),
                )
            }
            Expandable(expanded = expanded) {
                // 明细在展开区内部求值：收起动画期间内容保持原样，高度才能从完整高度
                // 连续收缩；若在外部按 expanded 清空，内容会先塌成空态，看起来像瞬间关闭
                val details = toolResultDetails(call.name, result)
                Column(
                    modifier = Modifier.padding(bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (details.isEmpty()) {
                        Text(
                            text = stringResource(R.string.ai_tool_result_empty),
                            style = ClassppTextTokens.secondaryTextSmall,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                    } else {
                        // 不设行数上限：用单个 Text 承载全部明细（每行一个换行），
                        // 避免上千条结果时创建上千个 Text 组合项。
                        Text(
                            text = details.joinToString("\n"),
                            style = ClassppTextTokens.secondaryTextSmall,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                    }
                }
            }
        }
    }
}

/** 工具卡左侧图标：查询类用搜索，新建待办/分组/标签各用其专属图标，其余写操作用撰写 */
private fun toolIconRes(name: String): Int = when (name) {
    AiToolRegistry.GET_CURRENT_TIME,
    AiToolRegistry.GET_DAY_PLAN,
    AiToolRegistry.SEARCH_TODOS,
    AiToolRegistry.GET_WEEK_SCHEDULE,
    AiToolRegistry.GET_TODO,
    -> R.drawable.ic_search
    AiToolRegistry.CREATE_TODOS -> R.drawable.ic_note_add_regular
    AiToolRegistry.CREATE_TODO_GROUPS -> R.drawable.ic_folder_add
    AiToolRegistry.CREATE_TODO_TAGS -> R.drawable.ic_tag_add
    else -> R.drawable.ic_compose
}

/** 写操作：参数里的条目数与结果里的数量重复，折叠态只留结果侧 */
private val AiToolWriteNames = setOf(
    AiToolRegistry.CREATE_TODOS,
    AiToolRegistry.UPDATE_TODOS,
    AiToolRegistry.DELETE_TODOS,
    AiToolRegistry.COMPLETE_TODOS,
    AiToolRegistry.ARCHIVE_TODOS,
    AiToolRegistry.CREATE_TODO_GROUPS,
    AiToolRegistry.CREATE_TODO_TAGS,
)

/**
 * 折叠态的单行描述：查询类合并「查了什么 · 结果概况」，写操作只留结果数。
 * 结果已包含同一信息时不重复（周视图的周数、多天查询的天数、写操作的条目数）；
 * 失败时只显示错误原因，不再带参数。
 */
@Composable
private fun toolDescription(call: AiToolCall, result: AiToolResult?): String {
    val summary = toolResultSummary(call.name, result)
    if (result?.isError == true) return summary
    val params = toolParamSummary(call)
    val resultCoversParams = when (call.name) {
        // 结果里已有实际周数，参数里的周数 / 偏移不再重复
        AiToolRegistry.GET_WEEK_SCHEDULE -> true
        // 多天查询结果自身带天数，避免「日期 3 天 · 3 天」
        AiToolRegistry.GET_DAY_PLAN ->
            parseObject(call.argumentsJson)?.containsKey("dates") == true
        in AiToolWriteNames -> true
        else -> false
    }
    return when {
        summary.isBlank() -> params
        params.isBlank() || resultCoversParams -> summary
        else -> "$params · $summary"
    }
}

@Composable
private fun toolDisplayName(name: String): String = when (name) {
    AiToolRegistry.GET_CURRENT_TIME -> stringResource(R.string.ai_tool_name_get_current_time)
    AiToolRegistry.GET_DAY_PLAN -> stringResource(R.string.ai_tool_name_get_day_plan)
    AiToolRegistry.SEARCH_TODOS -> stringResource(R.string.ai_tool_name_search_todos)
    AiToolRegistry.GET_WEEK_SCHEDULE -> stringResource(R.string.ai_tool_name_get_week_schedule)
    AiToolRegistry.GET_TODO -> stringResource(R.string.ai_tool_name_get_todo)
    AiToolRegistry.CREATE_TODOS -> stringResource(R.string.ai_tool_name_create_todos)
    AiToolRegistry.UPDATE_TODOS -> stringResource(R.string.ai_tool_name_update_todos)
    AiToolRegistry.DELETE_TODOS -> stringResource(R.string.ai_tool_name_delete_todos)
    AiToolRegistry.COMPLETE_TODOS -> stringResource(R.string.ai_tool_name_complete_todos)
    AiToolRegistry.ARCHIVE_TODOS -> stringResource(R.string.ai_tool_name_archive_todos)
    AiToolRegistry.CREATE_TODO_GROUPS -> stringResource(R.string.ai_tool_name_create_todo_groups)
    AiToolRegistry.CREATE_TODO_TAGS -> stringResource(R.string.ai_tool_name_create_todo_tags)
    else -> name
}

@Composable
private fun toolParamSummary(call: AiToolCall): String {
    val args = parseObject(call.argumentsJson) ?: return ""
    return when (call.name) {
        AiToolRegistry.GET_DAY_PLAN -> {
            val dates = args["dates"] as? JsonArray
            val from = args["from"].textOrNull()
            val to = args["to"].textOrNull()
            val date = args["date"].textOrNull()
            when {
                dates != null -> stringResource(R.string.ai_tool_arg_days, dates.size)
                !from.isNullOrBlank() && !to.isNullOrBlank() ->
                    stringResource(R.string.ai_tool_arg_date_range, from, to)
                !date.isNullOrBlank() -> stringResource(R.string.ai_tool_arg_date, date)
                else -> ""
            }
        }
        AiToolRegistry.GET_WEEK_SCHEDULE -> {
            val week = args["week"].textOrNull()?.toIntOrNull()
            val offset = args["weekOffset"].textOrNull()?.toIntOrNull()
            when {
                args["overview"].textOrNull() == "true" -> stringResource(R.string.ai_tool_arg_overview)
                week != null -> stringResource(R.string.ai_tool_arg_week, week)
                offset != null -> stringResource(R.string.ai_tool_arg_week_offset, offset)
                else -> ""
            }
        }
        AiToolRegistry.SEARCH_TODOS -> {
            // 只留「查什么」：分页 / 全部等执行细节不属于卡片需要展示的信息
            listOfNotNull(
                args["query"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                    stringResource(R.string.ai_tool_arg_query, it)
                },
                args["status"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                    stringResource(R.string.ai_tool_arg_status, it)
                },
            ).joinToString(" · ")
        }
        AiToolRegistry.CREATE_TODOS -> itemsSummary(args, "todos")
        AiToolRegistry.UPDATE_TODOS -> itemsSummary(args, "updates")
        AiToolRegistry.DELETE_TODOS -> itemsSummary(args, "deletes")
        AiToolRegistry.COMPLETE_TODOS -> itemsSummary(args, "completes", "reopens")
        AiToolRegistry.ARCHIVE_TODOS -> itemsSummary(args, "archives", "unarchives", "restores")
        AiToolRegistry.CREATE_TODO_GROUPS -> organizerNamesSummary(args, "groups")
        AiToolRegistry.CREATE_TODO_TAGS -> organizerNamesSummary(args, "tags")
        else -> ""
    }
}

/** 写工具的参数摘要：动作数组的元素数（单个对象按 1 条算） */
@Composable
private fun itemsSummary(args: JsonObject, vararg keys: String): String {
    val count = keys.sumOf { key ->
        when (val element = args[key]) {
            is JsonArray -> element.size
            is JsonObject -> 1
            else -> 0
        }
    }
    return if (count == 0) "" else stringResource(R.string.ai_tool_arg_items, count)
}

/** 新建分组/标签的参数摘要：名字数组的元素数 */
@Composable
private fun organizerNamesSummary(args: JsonObject, key: String): String {
    val count = (args[key] as? JsonArray)?.size ?: 0
    return if (count == 0) "" else stringResource(R.string.ai_tool_arg_organizers, count)
}

/** 折叠态摘要：只给数量与关键统计，避免长文本占据气泡 */
@Composable
private fun toolResultSummary(name: String, result: AiToolResult?): String {
    if (result == null) return ""
    val obj = parseObject(result.contentJson) ?: return ""
    if (obj["ok"].textOrNull() == "false") {
        return (obj["error"] as? JsonObject)?.get("message").textOrNull().orEmpty()
    }
    val data = obj["data"] as? JsonObject ?: return ""
    return when (name) {
        AiToolRegistry.GET_CURRENT_TIME -> currentTimeText(data)
        AiToolRegistry.GET_DAY_PLAN -> {
            val parts = mutableListOf<String>()
            val days = data["days"] as? JsonArray
            if (days != null) {
                // 多天模式：按天聚合课程/待办/空闲数量；为 0 的类别不占版面
                parts += stringResource(R.string.ai_tool_result_days, days.size)
                val courses = days.sumOf { day ->
                    ((day as? JsonObject)?.get("courses") as? JsonArray)?.size ?: 0
                }
                val todos = days.sumOf { day ->
                    ((day as? JsonObject)?.get("todos") as? JsonArray)?.size ?: 0
                }
                val freeSlots = days.sumOf { day ->
                    ((day as? JsonObject)?.get("freeSlots") as? JsonArray)?.size ?: 0
                }
                if (courses > 0) parts += stringResource(R.string.ai_tool_result_courses, courses)
                if (todos > 0) parts += stringResource(R.string.ai_tool_result_todos, todos)
                if (freeSlots > 0) {
                    parts += stringResource(R.string.ai_tool_result_free_slots, freeSlots)
                }
                if (courses + todos + freeSlots == 0) {
                    parts += stringResource(R.string.ai_tool_result_empty)
                }
                if (data["truncated"].textOrNull() == "true") {
                    parts += stringResource(R.string.ai_tool_result_truncated)
                }
            } else {
                val courses = (data["courses"] as? JsonArray)?.size
                val todos = (data["todos"] as? JsonArray)?.size
                val freeSlots = (data["freeSlots"] as? JsonArray)?.size
                if (courses != null && courses > 0) {
                    parts += stringResource(R.string.ai_tool_result_courses, courses)
                }
                if (todos != null && todos > 0) {
                    parts += stringResource(R.string.ai_tool_result_todos, todos)
                }
                if (freeSlots != null && freeSlots > 0) {
                    parts += stringResource(R.string.ai_tool_result_free_slots, freeSlots)
                }
                if (listOfNotNull(courses, todos, freeSlots).let { it.isNotEmpty() && it.sum() == 0 }) {
                    parts += stringResource(R.string.ai_tool_result_empty)
                }
            }
            parts.joinToString(" · ")
        }
        AiToolRegistry.SEARCH_TODOS -> {
            val total = data["total"].textOrNull()?.toIntOrNull() ?: 0
            val text = stringResource(R.string.ai_tool_result_todos, total)
            if (data["truncated"].textOrNull() == "true") {
                "$text · ${stringResource(R.string.ai_tool_result_truncated)}"
            } else {
                text
            }
        }
        AiToolRegistry.GET_WEEK_SCHEDULE -> {
            val parts = mutableListOf<String>()
            if (data["overview"].textOrNull() == "true") {
                data["totalWeeks"].textOrNull()?.toIntOrNull()?.let {
                    parts += stringResource(R.string.ai_tool_result_total_weeks, it)
                }
            } else {
                data["week"].textOrNull()?.toIntOrNull()?.let {
                    parts += stringResource(R.string.ai_tool_result_week, it)
                }
                (data["days"] as? JsonArray)?.size?.let {
                    parts += stringResource(R.string.ai_tool_result_days, it)
                }
            }
            parts.joinToString(" · ")
        }
        AiToolRegistry.GET_TODO -> {
            val name = data["name"].textOrNull().orEmpty()
            val status = todoStatusText(data)
            listOf(name, status).filter { it.isNotBlank() }.joinToString(" · ")
        }
        AiToolRegistry.CREATE_TODOS -> {
            val count = data["count"].textOrNull()?.toIntOrNull()
                ?: (data["created"] as? JsonArray)?.size
                ?: 0
            val text = stringResource(R.string.ai_tool_result_created, count)
            if (data["dropped"].textOrNull() == "true") {
                "$text · ${stringResource(R.string.ai_tool_result_skipped)}"
            } else {
                text
            }
        }
        AiToolRegistry.UPDATE_TODOS, AiToolRegistry.DELETE_TODOS -> {
            val count = data["count"].textOrNull()?.toIntOrNull()
                ?: (data["items"] as? JsonArray)?.size
                ?: 0
            stringResource(R.string.ai_tool_result_pending, count)
        }
        AiToolRegistry.COMPLETE_TODOS, AiToolRegistry.ARCHIVE_TODOS -> {
            val count = data["count"].textOrNull()?.toIntOrNull()
                ?: (data["applied"] as? JsonArray)?.size
                ?: 0
            stringResource(R.string.ai_tool_result_applied, count)
        }
        AiToolRegistry.CREATE_TODO_GROUPS, AiToolRegistry.CREATE_TODO_TAGS -> {
            val created = data["count"].textOrNull()?.toIntOrNull()
                ?: (data["created"] as? JsonArray)?.size
                ?: 0
            val existing = (data["existing"] as? JsonArray)?.size ?: 0
            val text = stringResource(R.string.ai_tool_result_organizers_created, created)
            if (existing > 0) {
                "$text · ${stringResource(R.string.ai_tool_result_organizers_existing, existing)}"
            } else {
                text
            }
        }
        else -> ""
    }
}

/** 展开态明细：按已知结构渲染列表/键值，不展示原始 JSON；最多 [MAX_DETAIL_LINES] 行，超出追加省略提示 */
@Composable
private fun toolResultDetails(name: String, result: AiToolResult?): List<String> {
    if (result == null) return emptyList()
    val obj = parseObject(result.contentJson) ?: return emptyList()
    if (obj["ok"].textOrNull() == "false") {
        return listOfNotNull((obj["error"] as? JsonObject)?.get("message").textOrNull())
    }
    val data = obj["data"] as? JsonObject ?: return emptyList()
    val lines = mutableListOf<String>()
    when (name) {
        // 展开后就是这次查询得到的“当前时间”，一屏即可读完
        AiToolRegistry.GET_CURRENT_TIME -> {
            currentTimeText(data).takeIf { it.isNotBlank() }?.let { lines += it }
        }
        AiToolRegistry.GET_DAY_PLAN -> {
            val days = data["days"] as? JsonArray
            if (days != null) {
                // 多天模式：每天先给日期头，再列当天课程/待办/空闲
                days.forEach { element ->
                    val day = element as? JsonObject ?: return@forEach
                    val weekday = day["weekday"].textOrNull().orEmpty()
                    val date = day["date"].textOrNull().orEmpty()
                    lines += listOf(weekday, date)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    appendDayPlanDay(day, lines, appendEmpty = true)
                }
            } else {
                appendDayPlanDay(data, lines, appendEmpty = false)
            }
        }
        AiToolRegistry.SEARCH_TODOS -> {
            (data["items"] as? JsonArray)?.forEach { element ->
                lines += todoLine(element as? JsonObject)
            }
        }
        AiToolRegistry.GET_WEEK_SCHEDULE -> {
            if (data["overview"].textOrNull() == "true") {
                val start = data["termStart"].textOrNull().orEmpty()
                val end = data["termEnd"].textOrNull().orEmpty()
                if (start.isNotBlank() && end.isNotBlank()) {
                    lines += stringResource(R.string.ai_tool_detail_term, start, end)
                }
            } else {
                (data["days"] as? JsonArray)?.forEach { element ->
                    val day = element as? JsonObject ?: return@forEach
                    val weekday = day["weekday"].textOrNull().orEmpty()
                    val date = day["date"].textOrNull().orEmpty()
                    val courses = (day["courses"] as? JsonArray)?.size ?: 0
                    lines += "$weekday $date · ${stringResource(R.string.ai_tool_result_courses, courses)}"
                }
            }
        }
        AiToolRegistry.GET_TODO -> {
            data["deadline"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                lines += stringResource(R.string.ai_tool_detail_deadline, it)
            }
            data["time"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                lines += stringResource(R.string.ai_tool_detail_time, it)
            }
            data["group"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                lines += stringResource(R.string.ai_tool_detail_group, it)
            }
            (data["tags"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { tags ->
                lines += stringResource(
                    R.string.ai_tool_detail_tags,
                    tags.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString("、"),
                )
            }
            data["repeat"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                lines += stringResource(R.string.ai_tool_detail_repeat, it)
            }
            data["note"].textOrNull()?.takeIf { it.isNotBlank() }?.let {
                lines += stringResource(R.string.ai_tool_detail_note, it)
            }
            (data["steps"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { steps ->
                lines += stringResource(
                    R.string.ai_tool_detail_steps,
                    steps.mapNotNull { step ->
                        (step as? JsonObject)?.get("title").textOrNull()
                    }.joinToString("、"),
                )
            }
        }
        AiToolRegistry.CREATE_TODOS -> {
            (data["created"] as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                lines += stringResource(
                    R.string.ai_tool_detail_created,
                    item["name"].textOrNull().orEmpty(),
                )
            }
        }
        AiToolRegistry.UPDATE_TODOS, AiToolRegistry.DELETE_TODOS -> {
            (data["items"] as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val label = if (item["kind"].textOrNull() == "delete") {
                    R.string.ai_tool_detail_deleted
                } else {
                    R.string.ai_tool_detail_updated
                }
                lines += stringResource(label, item["name"].textOrNull().orEmpty())
            }
        }
        AiToolRegistry.COMPLETE_TODOS -> {
            (data["applied"] as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val label = if (item["completed"].textOrNull() == "false") {
                    R.string.ai_todo_completion_action_reopened
                } else {
                    R.string.ai_todo_completion_action_done
                }
                lines += stringResource(label, item["name"].textOrNull().orEmpty())
            }
        }
        AiToolRegistry.ARCHIVE_TODOS -> {
            (data["applied"] as? JsonArray)?.forEach { element ->
                val item = element as? JsonObject ?: return@forEach
                val label = when (item["action"].textOrNull()) {
                    "unarchive" -> R.string.ai_todo_lifecycle_unarchive
                    "restore" -> R.string.ai_todo_lifecycle_restore
                    else -> R.string.ai_todo_lifecycle_archive
                }
                lines += stringResource(label, item["name"].textOrNull().orEmpty())
            }
        }
        AiToolRegistry.CREATE_TODO_GROUPS, AiToolRegistry.CREATE_TODO_TAGS -> {
            (data["created"] as? JsonArray)?.forEach { element ->
                lines += stringResource(
                    R.string.ai_tool_detail_created,
                    element.textOrNull().orEmpty(),
                )
            }
            (data["existing"] as? JsonArray)?.forEach { element ->
                lines += stringResource(
                    R.string.ai_tool_detail_organizer_existing,
                    element.textOrNull().orEmpty(),
                )
            }
            (data["failed"] as? JsonArray)?.forEach { element ->
                lines += stringResource(
                    R.string.ai_tool_detail_organizer_failed,
                    element.textOrNull().orEmpty(),
                )
            }
        }
    }
    if (lines.size > MAX_DETAIL_LINES) {
        return lines.take(MAX_DETAIL_LINES) +
            stringResource(R.string.ai_tool_detail_truncated, MAX_DETAIL_LINES)
    }
    return lines
}

/** get_day_plan 单日明细：课程/待办/空闲依次追加；多天模式下当天无内容补「（无）」 */
@Composable
private fun appendDayPlanDay(
    day: JsonObject,
    lines: MutableList<String>,
    appendEmpty: Boolean,
) {
    val before = lines.size
    (day["courses"] as? JsonArray)?.forEach { element ->
        val course = element as? JsonObject ?: return@forEach
        val title = course["name"].textOrNull().orEmpty()
        val start = course["startTime"].textOrNull().orEmpty()
        val end = course["endTime"].textOrNull().orEmpty()
        val location = course["location"].textOrNull()
        lines += buildString {
            if (start.isNotBlank() || end.isNotBlank()) append("$start-$end ")
            append(title)
            if (!location.isNullOrBlank()) append(" @$location")
        }
    }
    (day["todos"] as? JsonArray)?.forEach { element ->
        lines += todoLine(element as? JsonObject)
    }
    (day["freeSlots"] as? JsonArray)?.forEach { element ->
        val slot = element as? JsonObject ?: return@forEach
        val start = slot["start"].textOrNull().orEmpty()
        val end = slot["end"].textOrNull().orEmpty()
        lines += stringResource(R.string.ai_tool_detail_free_slot, start, end)
    }
    if (appendEmpty && lines.size == before) {
        lines += stringResource(R.string.ai_tool_detail_empty)
    }
}

@Composable
private fun todoLine(todo: JsonObject?): String {
    if (todo == null) return stringResource(R.string.ai_tool_result_empty)
    val name = todo["name"].textOrNull().orEmpty()
    // compact 结果（all 模式装不下完整字段时的降级）只带 date/completedAt
    val deadline = todo["deadline"].textOrNull()
        ?: todo["date"].textOrNull()
        ?: todo["completedAt"].textOrNull()
    val completed = todo["completed"].textOrNull() == "true"
    return buildString {
        if (completed) append("✓ ")
        append(name)
        if (!deadline.isNullOrBlank()) append(" · $deadline")
    }
}

/** get_todo 的状态文案：归档/回收站优先，其次完成态 */
@Composable
private fun todoStatusText(data: JsonObject): String = when {
    data["status"].textOrNull() == "deleted" -> stringResource(R.string.ai_tool_todo_status_deleted)
    data["status"].textOrNull() == "archived" -> stringResource(R.string.ai_tool_todo_status_archived)
    data["completed"].textOrNull() == "true" -> stringResource(R.string.ai_tool_todo_status_completed)
    else -> stringResource(R.string.ai_tool_todo_status_active)
}

/** get_current_time 的结果文本：星期 + 日期时间，摘要与展开明细共用 */
private fun currentTimeText(data: JsonObject): String {
    val dateTime = data["dateTime"].textOrNull()
        ?: listOfNotNull(data["date"].textOrNull(), data["time"].textOrNull())
            .joinToString(" ")
    return listOfNotNull(
        data["weekday"].textOrNull(),
        dateTime.takeIf { it.isNotBlank() },
    ).joinToString(" ")
}

private enum class ToolStatus { Running, Completed, Failed, Cancelled }

private fun toolStatus(result: AiToolResult?, roundStatus: AiToolRoundStatus): ToolStatus = when {
    result != null -> if (result.isError) ToolStatus.Failed else ToolStatus.Completed
    roundStatus == AiToolRoundStatus.Cancelled -> ToolStatus.Cancelled
    roundStatus == AiToolRoundStatus.Failed -> ToolStatus.Failed
    else -> ToolStatus.Running
}

@Composable
private fun toolStatusText(status: ToolStatus): String = when (status) {
    ToolStatus.Running -> stringResource(R.string.ai_tool_status_running)
    ToolStatus.Completed -> stringResource(R.string.ai_tool_status_completed)
    ToolStatus.Failed -> stringResource(R.string.ai_tool_status_failed)
    ToolStatus.Cancelled -> stringResource(R.string.ai_tool_status_cancelled)
}

private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.content

private fun parseObject(raw: String): JsonObject? = try {
    kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
} catch (_: Exception) {
    null
}

/** 展开态最多显示的明细行数，超出后只留省略提示 */
private const val MAX_DETAIL_LINES = 100

// ---------- Preview ----------

@Preview(name = "工具-运行中", showBackground = true, widthDp = 360)
@Composable
private fun ToolRunningPreview() {
    MaterialTheme {
        AiChatToolRoundBlock(
            round = AiToolRound(
                text = "我先查一下。",
                calls = listOf(
                    AiToolCall(
                        id = "call_1",
                        name = AiToolRegistry.GET_DAY_PLAN,
                        argumentsJson = """{"date":"2026-11-18"}""",
                    ),
                ),
                status = AiToolRoundStatus.Running,
            ),
        )
    }
}

@Preview(name = "工具-成功展开", showBackground = true, widthDp = 360)
@Composable
private fun ToolCompletedPreview() {
    MaterialTheme {
        AiChatToolRoundBlock(
            round = AiToolRound(
                calls = listOf(
                    AiToolCall(
                        id = "call_1",
                        name = AiToolRegistry.GET_DAY_PLAN,
                        argumentsJson = """{"date":"2026-11-18"}""",
                    ),
                ),
                results = listOf(
                    AiToolResult(
                        callId = "call_1",
                        name = AiToolRegistry.GET_DAY_PLAN,
                        contentJson = """{"ok":true,"data":{"date":"2026-11-18","weekday":"周三",""" +
                            """"week":12,"courses":[{"name":"高等数学","startTime":"14:30","endTime":"16:10",""" +
                            """"location":"教学楼A-301"}],"todos":[{"id":"t1","name":"交实验报告",""" +
                            """"completed":false,"deadline":"2026-11-18 18:00"}],""" +
                            """"freeSlots":[{"start":"08:00","end":"14:30","minutes":390}]}}""",
                    ),
                ),
                status = AiToolRoundStatus.Completed,
            ),
            expandedByDefault = true,
        )
    }
}

@Preview(name = "工具-多天查询", showBackground = true, widthDp = 360)
@Composable
private fun ToolMultiDayPreview() {
    MaterialTheme {
        AiChatToolRoundBlock(
            round = AiToolRound(
                calls = listOf(
                    AiToolCall(
                        id = "call_1",
                        name = AiToolRegistry.GET_DAY_PLAN,
                        argumentsJson = """{"dates":["2026-11-04","2026-12-14","2027-02-03"]}""",
                    ),
                ),
                results = listOf(
                    AiToolResult(
                        callId = "call_1",
                        name = AiToolRegistry.GET_DAY_PLAN,
                        contentJson = """{"ok":true,"data":{"days":[
                            {"date":"2026-11-04","weekday":"周三","week":12,"courses":[{"name":"高等数学","startTime":"14:30","endTime":"16:10","location":"教学楼A-301"}],"todos":[{"id":"t1","name":"交实验报告","completed":false,"deadline":"2026-11-04 18:00"}],"freeSlots":[{"start":"08:00","end":"14:30","minutes":390}]},
                            {"date":"2026-12-14","weekday":"周一","week":18,"courses":[],"todos":[],"freeSlots":[{"start":"08:00","end":"16:10","minutes":490}]},
                            {"date":"2027-02-03","weekday":"周三","week":25,"courses":[],"todos":[],"freeSlots":[]}
                        ]}}""",
                    ),
                ),
                status = AiToolRoundStatus.Completed,
            ),
            expandedByDefault = true,
        )
    }
}

@Preview(name = "工具-失败", showBackground = true, widthDp = 360)
@Composable
private fun ToolFailedPreview() {
    MaterialTheme {
        AiChatToolRoundBlock(
            round = AiToolRound(
                calls = listOf(
                    AiToolCall(
                        id = "call_1",
                        name = AiToolRegistry.SEARCH_TODOS,
                        argumentsJson = """{"status":"archived","limit":10}""",
                    ),
                ),
                results = listOf(
                    AiToolResult(
                        callId = "call_1",
                        name = AiToolRegistry.SEARCH_TODOS,
                        contentJson = """{"ok":false,"error":{"code":"invalid_arguments",""" +
                            """"message":"status 只能是 active/completed/archived/deleted/all"}}""",
                        isError = true,
                    ),
                ),
                status = AiToolRoundStatus.Failed,
            ),
        )
    }
}

@Preview(name = "工具-多轮", showBackground = true, widthDp = 360)
@Composable
private fun ToolMultiRoundPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AiChatToolRoundBlock(
                round = AiToolRound(
                    calls = listOf(
                        AiToolCall(
                            id = "call_1",
                            name = AiToolRegistry.GET_WEEK_SCHEDULE,
                            argumentsJson = """{"weekOffset":1}""",
                        ),
                    ),
                    results = listOf(
                        AiToolResult(
                            callId = "call_1",
                            name = AiToolRegistry.GET_WEEK_SCHEDULE,
                            contentJson = """{"ok":true,"data":{"week":13,"days":[""" +
                                """{"date":"2026-11-23","weekday":"周一","courses":[]}]}}""",
                        ),
                    ),
                    status = AiToolRoundStatus.Completed,
                ),
            )
            AiChatToolRoundBlock(
                round = AiToolRound(
                    calls = listOf(
                        AiToolCall(
                            id = "call_2",
                            name = AiToolRegistry.SEARCH_TODOS,
                            argumentsJson = """{"query":"报告"}""",
                        ),
                    ),
                    results = listOf(
                        AiToolResult(
                            callId = "call_2",
                            name = AiToolRegistry.SEARCH_TODOS,
                            contentJson = """{"ok":true,"data":{"total":1,"truncated":false,""" +
                                """"items":[{"id":"t1","name":"交实验报告","completed":false}]}}""",
                        ),
                    ),
                    status = AiToolRoundStatus.Completed,
                ),
            )
            AiChatToolLoopCappedHint()
        }
    }
}

@Preview(name = "工具-写操作待确认", showBackground = true, widthDp = 360)
@Composable
private fun ToolWritePendingPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 可逆写：自动执行，业务卡片在气泡下方提供撤销
            AiChatToolRoundBlock(
                round = AiToolRound(
                    calls = listOf(
                        AiToolCall(
                            id = "call_1",
                            name = AiToolRegistry.CREATE_TODOS,
                            argumentsJson = """{"todos":[{"name":"交实验报告"}]}""",
                        ),
                    ),
                    results = listOf(
                        AiToolResult(
                            callId = "call_1",
                            name = AiToolRegistry.CREATE_TODOS,
                            contentJson = """{"ok":true,"data":{"count":1,"created":[{"id":"t1",""" +
                                """"name":"交实验报告"}],"message":"已创建，可在消息下方撤销"}}""",
                        ),
                    ),
                    status = AiToolRoundStatus.Completed,
                ),
            )
            // 破坏性写：只生成待确认记录，确认卡仍由气泡下方渲染
            AiChatToolRoundBlock(
                round = AiToolRound(
                    calls = listOf(
                        AiToolCall(
                            id = "call_2",
                            name = AiToolRegistry.UPDATE_TODOS,
                            argumentsJson = """{"updates":[{"id":"t1","patch":{"urgency":"High"}}]}""",
                        ),
                    ),
                    results = listOf(
                        AiToolResult(
                            callId = "call_2",
                            name = AiToolRegistry.UPDATE_TODOS,
                            contentJson = """{"ok":true,"data":{"status":"pending_user_confirmation",""" +
                                """"count":1,"items":[{"id":"t1","name":"交实验报告","kind":"update"}],""" +
                                """"message":"已在消息下方生成确认卡，等待用户点击「应用变更」"}}""",
                        ),
                    ),
                    status = AiToolRoundStatus.Completed,
                ),
            )
        }
    }
}

@Preview(name = "工具-待办详情", showBackground = true, widthDp = 360)
@Composable
private fun ToolTodoDetailPreview() {
    MaterialTheme {
        AiChatToolRoundBlock(
            round = AiToolRound(
                calls = listOf(
                    AiToolCall(
                        id = "call_1",
                        name = AiToolRegistry.GET_TODO,
                        argumentsJson = """{"id":"5b0f7c2a-1e44-4a2f-9d19-8c4e2b7a1f30"}""",
                    ),
                ),
                results = listOf(
                    AiToolResult(
                        callId = "call_1",
                        name = AiToolRegistry.GET_TODO,
                        contentJson = """{"ok":true,"data":{"id":"5b0f7c2a-1e44-4a2f-9d19-8c4e2b7a1f30",""" +
                            """"name":"交实验报告","status":"active","completed":false,""" +
                            """"deadline":"2026-11-18 18:00","time":"14:00-16:00","group":"学习",""" +
                            """"tags":["物理"],"note":"实验楼 3 层提交","repeat":"每周（周1、3）",""" +
                            """"steps":[{"title":"查资料","done":true},{"title":"写正文","done":false}]}}""",
                    ),
                ),
                status = AiToolRoundStatus.Completed,
            ),
            expandedByDefault = true,
        )
    }
}

@Preview(name = "工具-空态", showBackground = true, widthDp = 360)
@Composable
private fun ToolEmptyPreview() {
    MaterialTheme {
        AiChatToolRoundBlock(
            round = AiToolRound(
                calls = listOf(
                    AiToolCall(
                        id = "call_1",
                        name = AiToolRegistry.SEARCH_TODOS,
                        argumentsJson = "{}",
                    ),
                ),
                results = listOf(
                    AiToolResult(
                        callId = "call_1",
                        name = AiToolRegistry.SEARCH_TODOS,
                        contentJson = """{"ok":true,"data":{"total":0,"truncated":false,"items":[]}}""",
                    ),
                ),
                status = AiToolRoundStatus.Completed,
            ),
        )
    }
}

/** 气泡内交错渲染的回归预览：工具轮文本 → 工具卡 → 最终正文 */
@Preview(name = "工具轮次气泡", showBackground = true, widthDp = 360)
@Composable
private fun ToolRoundBubblePreview() {
    MaterialTheme {
        AiChatMessageBubble(
            message = AiChatMessage(
                role = AiChatRole.Assistant,
                content = "我先查一下。\n那天下午有空。",
                toolRounds = listOf(
                    AiToolRound(
                        text = "我先查一下。",
                        calls = listOf(
                            AiToolCall(
                                id = "call_1",
                                name = AiToolRegistry.GET_DAY_PLAN,
                                argumentsJson = """{"date":"2026-11-18"}""",
                            ),
                        ),
                        results = listOf(
                            AiToolResult(
                                callId = "call_1",
                                name = AiToolRegistry.GET_DAY_PLAN,
                                contentJson = """{"ok":true,"data":{"date":"2026-11-18","weekday":"周三",""" +
                                    """"week":12,"courses":[],"todos":[],"freeSlots":[""" +
                                    """{"start":"08:00","end":"16:10","minutes":490}]}}""",
                            ),
                        ),
                        status = AiToolRoundStatus.Completed,
                    ),
                ),
            ),
        )
    }
}
