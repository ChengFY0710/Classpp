package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeText

/**
 * 某天真正要上的一节课，时间已由 [Schedule.slots] 解析成「当天第几分钟」。
 *
 * 这是日程页 / 桌面小组件共用的纯数据口径：课次来源仍是 [Schedule.occurrencesOn]
 * （周次、单双周、调课、源课次消失、按起始节排序都已处理），这里只把节次换成可比较的分钟，
 * 跨节课程取**最后一节的结束时间**（与 DayScreen / NotificationPlanner 同口径）。
 */
data class DayAgendaEntry(
    val courseId: String,
    val name: String,
    val location: String,
    val color: CourseColor,
    /** 首节开始分钟（0..1439） */
    val startMinute: Int,
    /** 末节结束分钟（0..1439） */
    val endMinute: Int,
)

/**
 * [date] 当天的课程，按开始时间升序；节次时间缺失或非法的条目直接跳过
 * （宁可少一行，也不显示 0:00 这类假时间）。
 */
fun Schedule.agendaOn(date: IsoDate): List<DayAgendaEntry> =
    occurrencesOn(date).mapNotNull { occurrence ->
        val startSlot = slots.getOrNull(occurrence.startSlot - 1) ?: return@mapNotNull null
        val endSlot = slots.getOrNull(occurrence.endSlot - 1) ?: startSlot
        val startMinute = TimeText.parseMinutes(startSlot.startTime) ?: return@mapNotNull null
        val endMinute = TimeText.parseMinutes(endSlot.endTime) ?: return@mapNotNull null
        DayAgendaEntry(
            courseId = occurrence.course.id,
            name = occurrence.course.name,
            location = occurrence.course.location,
            color = occurrence.course.color,
            startMinute = startMinute,
            endMinute = endMinute,
        )
    }
