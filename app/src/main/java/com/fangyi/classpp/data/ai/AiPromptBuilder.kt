package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoUrgency
import com.fangyi.classpp.data.model.isArchived
import com.fangyi.classpp.data.model.isDeleted

/**
 * 上下文注入与 system prompt 构建（纯函数，重点单测对象）。
 *
 * 只吃数据快照不吃仓库——调用方（AiChatRepository）从各仓库 StateFlow 取活跃值传入，
 * 时钟由 [build] 的 now 参数注入（同 Clock 注入惯例），测试零 Android 依赖。
 *
 * **红线**：注入内容只有课表、作息、待办、分组、标签——教务凭据、Cookie、API Key
 * 在类型上就进不了构建路径。
 */
object AiPromptBuilder {

    /** 注入的待办行数上限（token 预算控制；未完成、非删除、非归档里取最近的） */
    const val MAX_TODO_LINES = 30

    private val WEEKDAY_ZH = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val URGENCY_ZH = mapOf(
        TodoUrgency.Critical to "非常急",
        TodoUrgency.High to "很急",
        TodoUrgency.Medium to "急",
        TodoUrgency.Low to "不急",
    )

    fun build(
        now: IsoDate,
        schedule: Schedule?,
        currentWeek: Int?,
        todos: List<Todo>,
        groups: List<String>,
        tags: List<String>,
    ): String = buildString {
        appendLine("你是课表应用 Classpp 内置的学习助手。用简洁中文回答，默认给出可执行的建议。")
        appendLine("以下为用户的当前上下文（可能与提问相关）：")
        append("[日期] 今天是 ${now} ${WEEKDAY_ZH[now.isoDayOfWeek() - 1]}")
        if (currentWeek != null) append("，学期第 $currentWeek 周")
        appendLine()

        append("[课表] ")
        if (schedule == null) {
            appendLine("（未设置课表）")
        } else {
            appendLine()
            for (course in schedule.courses) {
                courseLine(schedule, course)?.let { appendLine(it) }
            }
        }

        append("[作息] ")
        if (schedule == null || schedule.slots.isEmpty()) {
            appendLine("（未设置）")
        } else {
            appendLine(
                schedule.slots.mapIndexed { i, slot ->
                    "第${i + 1}节 ${slot.startTime}-${slot.endTime}"
                }.joinToString("；"),
            )
        }

        append("[待办] ")
        val active = todos.asSequence()
            .filterNot { it.isDeleted || it.isArchived || it.completed }
            .sortedBy { it.sortKey() }
            .take(MAX_TODO_LINES)
            .toList()
        if (active.isEmpty()) {
            appendLine("（暂无未完成待办）")
        } else {
            appendLine()
            for (todo in active) appendLine(todoLine(todo))
        }

        appendLine("[可选分组] ${groups.ifEmpty { listOf("（无）") }.joinToString("、")}")
        appendLine("[可选标签] ${tags.ifEmpty { listOf("（无）") }.joinToString("、")}")
        appendLine()
        appendLine("当用户想添加待办时，输出一个 ```json 代码块，格式（字段都可省略）：")
        appendLine(
            """{"todos":[{"name":"...","dates":["yyyy-MM-dd"],"startTime":"HH:mm",""" +
                """"endTime":"HH:mm","deadlineDate":"yyyy-MM-dd","deadlineTime":"HH:mm",""" +
                """"urgency":"Critical|High|Medium|Low","tags":["..."],"group":"...",""" +
                """"note":"...","steps":["..."],"location":"..."}]}""",
        )
        appendLine("规则：")
        appendLine("- 日期从今天的日期与星期推算（含“下周三”这类相对说法），不要编造具体日期；")
        appendLine("- group/tag 只用【可选分组】【可选标签】里出现的值，没有匹配就省略该字段；")
        appendLine("- 截止的日期（deadlineDate）与时刻（deadlineTime）要么都给，要么都不给；")
        appendLine("- 用户表达含糊时先在回答里问清，再给草稿；")
        appendLine("- 其余情况正常对话，不要输出 JSON 代码块。")
    }.trimEnd() + "\n"

    // ---------- 课表行 ----------

    /**
     * 一门课一行：`周一 第3-4节 14:30-16:10 高等数学 @教学楼A-301 周1-16（教师：张三）`。
     *
     * `dayOfWeek` 越界（损坏/手工编辑的数据，ScheduleValidator 按 hidden 容忍保留）
     * 返回 null 跳过该行——裸下标会让每次发送都崩在数组越界。
     */
    private fun courseLine(schedule: Schedule, course: CourseEntry): String? {
        val weekday = WEEKDAY_ZH.getOrNull(course.dayOfWeek - 1) ?: return null
        val slotStart = schedule.slots.getOrNull(course.startSlot - 1)
        val slotEnd = schedule.slots.getOrNull(course.endSlot - 1)
        val slotRange = if (course.span == 1) {
            "第${course.startSlot}节"
        } else {
            "第${course.startSlot}-${course.endSlot}节"
        }
        val time = if (slotStart != null && slotEnd != null) {
            " ${slotStart.startTime}-${slotEnd.endTime}"
        } else ""
        val teacher = course.teacher.takeIf { it.isNotBlank() }?.let { "（教师：$it）" } ?: ""
        val location = course.location.takeIf { it.isNotBlank() }?.let { " @$it" } ?: ""
        return "$weekday $slotRange$time ${course.name}" +
            "$location ${weeksText(course)}$teacher"
    }

    /** 周数区间文本：`周1-16`、`周1-8,10-16双`（单双周逐段标注）；空 pattern → `周-` */
    private fun weeksText(course: CourseEntry): String {
        if (course.weeks.segments.isEmpty()) return "周-"
        val segments = course.weeks.segments.joinToString(",") { seg ->
            val range = if (seg.start == seg.end) "${seg.start}" else "${seg.start}-${seg.end}"
            range + when (seg.parity) {
                Parity.ODD -> "单"
                Parity.EVEN -> "双"
                Parity.ALL -> ""
            }
        }
        return "周$segments"
    }

    // ---------- 待办行 ----------

    /** 排序键：截止 > 首个待办日期 > 长整型上限（无日期垫底） */
    private fun Todo.sortKey(): Long =
        deadlineDate?.epochDay ?: dates.minByOrNull { it.epochDay }?.epochDay ?: Long.MAX_VALUE

    /** 一条待办一行：`名称｜截止 2026-11-05 18:00｜急｜组：学习｜标签：期末、实验` */
    private fun todoLine(todo: Todo): String = buildString {
        append(todo.name.ifBlank { "（未命名）" })
        val deadline = todo.deadlineDate?.let { date ->
            val minute = todo.deadlineMinute
            if (minute != null) "截止 ${date} ${minute / 60}:${(minute % 60).toString().padStart(2, '0')}" else "截止 $date"
        }
        if (deadline != null) append("｜$deadline")
        if (todo.dates.isNotEmpty()) append("｜日期 ${todo.dates.joinToString("、")}")
        URGENCY_ZH[todo.urgency]?.let { append("｜$it") }
        todo.group?.let { append("｜组：$it") }
        if (todo.tags.isNotEmpty()) append("｜标签：${todo.tags.joinToString("、")}")
        if (todo.steps.isNotEmpty()) {
            append("｜步骤：${todo.steps.joinToString("、") { it.title }}")
        }
    }
}
