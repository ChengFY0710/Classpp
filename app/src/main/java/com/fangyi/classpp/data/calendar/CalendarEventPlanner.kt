package com.fangyi.classpp.data.calendar

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind

/** 待办 → 系统日历事件 的中间草稿：纯数据，ContentValues 映射由 [SystemCalendar] 负责 */
data class CalendarEventDraft(
    val title: String,
    val description: String,
    val location: String,
    /** 事件所在的日历日期 */
    val date: IsoDate,
    /** 全天事件（日历侧 dtstart = 当日 UTC 零点、dtend = 次日零点、allDay = 1） */
    val allDay: Boolean,
    /** 当天分钟数；allDay = false 时必须有 */
    val startMinute: Int?,
    /** 当天分钟数；缺省与 [startMinute] 相同（零长事件兜底，正常不应发生） */
    val endMinute: Int?,
)

/**
 * 待办 → 日历事件草稿的纯函数规划：
 * - 多天日期**每天一个事件**（日期可不连续，无法用单个事件跨越中间天）；
 * - AllDay → 每天一个全天事件；
 * - Period → 每天一个时段事件（多天共用同一时段）；
 * - None → 空列表（调用方报 [com.fangyi.classpp.data.TodoError.CalendarNoTime]，UI 禁用入口）。
 */
object CalendarEventPlanner {

    fun drafts(todo: Todo): List<CalendarEventDraft> {
        if (todo.timeKind == TodoTimeKind.None) return emptyList()
        val allDay = todo.timeKind == TodoTimeKind.AllDay
        return todo.dates.map { date ->
            CalendarEventDraft(
                title = todo.name,
                description = describe(todo),
                location = todo.location,
                date = date,
                allDay = allDay,
                startMinute = if (!allDay) todo.startMinute else null,
                endMinute = if (!allDay) todo.endMinute else null,
            )
        }
    }

    /** 事件描述 = 备注 + 步骤清单（`- [ ]` / `- [x]` 行）；空段落不产出 */
    private fun describe(todo: Todo): String = buildList {
        if (todo.note.isNotBlank()) add(todo.note.trim())
        todo.steps.forEach { step ->
            add("${if (step.done) "- [x] " else "- [ ] "}${step.title}")
        }
    }.joinToString("\n")
}
