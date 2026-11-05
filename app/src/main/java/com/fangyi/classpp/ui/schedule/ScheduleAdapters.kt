package com.fangyi.classpp.ui.schedule

import androidx.compose.ui.graphics.Color
import com.fangyi.classpp.data.CourseOccurrence
import com.fangyi.classpp.data.model.CourseAdjustment
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.occurrencesForWeek
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
 *
 * [dayOfWeek]/[slotId]/[span] 默认取原条目；调课卡传入目标值，原值另存进
 * `adjustedFrom*` 供角标/详情显示。
 */
internal fun CourseEntry.toUiCourse(
    active: Boolean,
    dayOfWeek: Int = this.dayOfWeek,
    slotId: Int = startSlot,
    span: Int = this.span,
    adjustmentId: String? = null,
): Course = Course(
    name = name,
    teacher = teacher,
    location = location,
    dayOfWeek = dayOfWeek,
    slotId = slotId,
    color = CourseColor.valueOf(color.name),
    active = active,
    span = span,
    id = id,
    adjustmentId = adjustmentId,
    adjustedFromDayOfWeek = if (adjustmentId != null) this.dayOfWeek else null,
    adjustedFromSlot = if (adjustmentId != null) this.startSlot else null,
    adjustedFromSpan = if (adjustmentId != null) this.span else null,
)

/**
 * 课程呈现 → 渲染课程：跨节次课程只在起始格命中，故 [Course.slotId] 取有效起始节；
 * [CourseColor] 两枚举同名同值，经 name 直转；[CourseOccurrence.active] 随行作置灰标志。
 * 调课卡的有效位置取 [CourseOccurrence.dayOfWeek]/[startSlot]/[span]，原位置进 `adjustedFrom*`。
 */
internal fun CourseOccurrence.toUiCourse(): Course = course.toUiCourse(
    active = active,
    dayOfWeek = dayOfWeek,
    slotId = startSlot,
    span = span,
    adjustmentId = adjustment?.id,
)

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

/**
 * 课程标签配色表（课程名 → 课程卡配色）：激活课表按课程名去重。
 * 取与当前主题**相反**的课程卡配色：浅色主题用深色卡配色（马卡龙原色作标签文字对比
 * 不足），深色主题用浅色卡配色（深色卡配色在深底上过闷）。待办卡/浮层的课程标签胶囊、
 * 顶栏标签胶囊、分组浮层的标签行 icon 与 TagChoosingCard 的课程标签区共用此口径
 * （课表内查无同名课程时才由调用方回落主题 Primary）。
 */
internal fun Schedule.courseTagColors(darkTheme: Boolean): Map<String, Color> =
    courses.distinctBy { it.name }
        .associate { it.name to CourseColor.valueOf(it.color.name).barColor(dark = !darkTheme) }

/**
 * 编辑态草稿路径：与仓库路径共用同一套解析（编辑态一律显示全部课，不受显示开关影响）。
 * 调课与常规课一样先合成 occurrence 再解析，保证源课次当周消失、目标格出现调课卡。
 */
internal fun List<CourseEntry>.toWeekCards(
    week: Int,
    adjustments: List<CourseAdjustment> = emptyList(),
): List<Course> =
    occurrencesForWeek(week, adjustments)
        .map { it.toUiCourse() }
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
