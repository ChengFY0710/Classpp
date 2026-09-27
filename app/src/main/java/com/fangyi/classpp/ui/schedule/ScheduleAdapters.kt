package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.CourseOccurrence
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TimeSlotDef
import java.util.Calendar
import java.util.Date

/**
 * 数据层模型 → UI 渲染模型的适配层（happy-rocket 计划《后续 UI 接入路径》固化）。
 *
 * 本批视图能力为"仅换数据源"：网格保持 5 列单格渲染，
 * span>1 的课程只在起始格出现（续格由 findAt 自然留空）。
 */

/**
 * 课程呈现 → 渲染课程：跨节次课程本批只落起始格，故 [Course.slotId] 取 startSlot；
 * [CourseColor] 两枚举同名同值，经 name 直转；[CourseOccurrence.active] 随行作置灰标志。
 */
internal fun CourseOccurrence.toUiCourse(): Course = Course(
    name = course.name,
    teacher = course.teacher,
    location = course.location,
    dayOfWeek = course.dayOfWeek,
    slotId = course.startSlot,
    color = CourseColor.valueOf(course.color.name),
    active = active,
)

/** 节次定义列表 → 渲染节次：数据层列表位置即节次编号，转成 1-based id（与网格 key 一致） */
internal fun List<TimeSlotDef>.toUiSlots(): List<TimeSlot> =
    mapIndexed { index, def -> TimeSlot(index + 1, def.startTime, def.endTime) }

/**
 * 数据层日期 → `java.util.Date`：按 yyyy-MM-dd 构造**本地日历正午**。
 * 正午取值使 isSameDay/dayOfMonth 不受时区偏移与夏令时切换影响（Date 仅作日期语义使用）。
 */
internal fun IsoDate.toUiDate(): Date {
    val parts = toString().split('-')
    return Calendar.getInstance().apply {
        set(
            parts[0].toInt(),
            parts[1].toInt() - 1,
            parts[2].toInt(),
            12, 0, 0,
        )
        set(Calendar.MILLISECOND, 0)
    }.time
}

/** DatePicker 互转：Material3 selectedDateMillis 语义为 UTC 零点毫秒，与 epochDay 纯整数天等价 */
internal fun IsoDate.toPickerMillis(): Long = epochDay * 86_400_000L

/** UTC 零点毫秒 → 数据层日期（floorDiv 保证负值方向正确） */
internal fun Long.toIsoDate(): IsoDate = IsoDate(Math.floorDiv(this, 86_400_000L))
