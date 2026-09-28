package com.fangyi.classpp.ui.schedule

import com.fangyi.classpp.data.CourseOccurrence
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TimeSlotDef
import java.util.Calendar
import java.util.Date

/**
 * 数据层模型 → UI 渲染模型的适配层（happy-rocket 计划《后续 UI 接入路径》固化）。
 *
 * 本批视图能力为"仅换数据源"：网格保持 5 列布局，
 * span>1 的课程在起始格命中（续格由 isContinuationAt 抑制，卡片由叠加层跨行绘制）。
 */

/**
 * 课程条目 → 渲染课程：[active] = 本周是否上课，false 时卡片按规格置灰。
 * 编辑态的草稿直接喂 [CourseEntry]，与仓库路径共用同一个映射。
 */
internal fun CourseEntry.toUiCourse(active: Boolean): Course = Course(
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    slotId = startSlot,
    color = CourseColor.valueOf(color.name),
    active = active,
    span = span,
    id = id,
)

/**
 * 课程呈现 → 渲染课程：跨节次课程只在起始格命中，故 [Course.slotId] 取 startSlot；
 * [CourseColor] 两枚举同名同值，经 name 直转；[CourseOccurrence.active] 随行作置灰标志。
 */
internal fun CourseOccurrence.toUiCourse(): Course = course.toUiCourse(active)

/**
 * 仓库路径：一周的全部课程呈现 → 每周渲染卡（同格只留当周那张，并附交替课色条）。
 *
 * [showInactive] = 课表的「显示本周不上的课」开关。**先解析再过滤**：关掉开关时非本周的
 * 交替课不画卡，但当周卡底部的交替色条仍在（它表示"这格还有别的课"，与开关无关）；
 * 过滤规则与过滤前一致——只剔除本周不上且没有当周主卡的卡。
 */
internal fun List<CourseOccurrence>.toWeekCards(showInactive: Boolean): List<Course> =
    map { it.toUiCourse() }
        .resolveWeekCards()
        .filter { showInactive || it.active }

/** 编辑态草稿路径：与仓库路径共用同一套解析（编辑态一律显示全部课，不受显示开关影响） */
internal fun List<CourseEntry>.toWeekCards(week: Int): List<Course> =
    map { it.toUiCourse(active = it.weeks.contains(week)) }
        .resolveWeekCards()

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
