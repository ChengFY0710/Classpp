package com.fangyi.classpp.data.model

import com.fangyi.classpp.data.CourseRef
import kotlinx.serialization.Serializable
import java.util.UUID

/** 存储文件与分享信封的格式版本；导入时拒绝更高版本 */
const val SCHEDULE_FORMAT_VERSION = 1

/** 调整节数生成默认作息时的课间时长（默认大课时长均为 100 分钟） */
private const val DEFAULT_BREAK_MINUTES = 30

/** 上课周的奇偶性；作用于单个 [WeekSegment] 区间 */
@Serializable
enum class Parity { ALL, ODD, EVEN }

/**
 * 一段连续上课周：`start..end`（含端点）内按 [parity] 取周。
 * 数值合法性（如不超学期总周数）由 ScheduleValidator 校验，此处不做 init 断言。
 */
@Serializable
data class WeekSegment(val start: Int, val end: Int, val parity: Parity = Parity.ALL) {

    fun contains(week: Int): Boolean = when {
        week !in start..end -> false
        parity == Parity.ALL -> true
        parity == Parity.ODD -> week % 2 == 1
        else -> week % 2 == 0
    }

    /** 两段的**周集合**是否相交：区间重叠且重叠区内存在能同时满足两侧奇偶的周 */
    fun intersects(other: WeekSegment): Boolean {
        val lo = maxOf(start, other.start)
        val hi = minOf(end, other.end)
        if (lo > hi) return false
        val required = when {
            parity == Parity.ALL -> other.parity
            other.parity == Parity.ALL -> parity
            parity == other.parity -> parity
            else -> return false // ODD vs EVEN 永不相交
        }
        return when (required) {
            Parity.ALL -> true
            Parity.ODD -> if (lo % 2 == 1) true else lo + 1 <= hi
            Parity.EVEN -> if (lo % 2 == 0) true else lo + 1 <= hi
        }
    }
}

/**
 * 一门课的上课周规律：多段区间**并集**（如 `1-8` 全选 + `10-16` 双周）。
 * 段间互相重叠无害（并集语义），不视为错误。
 */
@Serializable
data class WeekPattern(val segments: List<WeekSegment> = emptyList()) {

    /** 任一段命中即上课 */
    fun contains(week: Int): Boolean = segments.any { it.contains(week) }

    /** 两组周规律是否存在共同上课周 */
    fun intersects(other: WeekPattern): Boolean =
        segments.any { a -> other.segments.any { a.intersects(it) } }

    /** 最大周号；空 pattern（从未上课）返回 null */
    fun maxWeek(): Int? = segments.maxOfOrNull { it.end }

    companion object {
        /** `1..totalWeeks` 全周上课 */
        fun everyWeek(totalWeeks: Int): WeekPattern =
            WeekPattern(listOf(WeekSegment(1, totalWeeks)))
    }
}

/**
 * 一节大课的起止时间。**列表位置即节次编号**（第 1 个 = 第 1 节），不另存 id。
 * 文本约定见 [TimeText]。
 */
@Serializable
data class TimeSlotDef(val startTime: String, val endTime: String)

/** 课程卡片配色；条目名与 ui.schedule.CourseColor 完全一致，后续 UI 映射可直接 valueOf() */
@Serializable
enum class CourseColor { Blue, Green, Greentwo, Yellow, Orange, Purple, Teal, Pink }

/**
 * 一门课在课表中的一次占位。
 *
 * @param dayOfWeek 1 = 周一 … [Schedule.daysPerWeek]
 * @param startSlot 起始节次（1-based，指向 slots 列表位置）
 * @param span 连续占用的节数，1..5
 */
@Serializable
data class CourseEntry(
    val id: String,
    val name: String,
    val teacher: String,
    val location: String,
    val dayOfWeek: Int,
    val startSlot: Int,
    val span: Int = 1,
    val weeks: WeekPattern,
    val color: CourseColor,
) {
    val endSlot: Int get() = startSlot + span - 1

    fun toRef(): CourseRef = CourseRef(id, name, dayOfWeek, startSlot, span)
}

/**
 * 一份课表 = 学期 + 作息 + 课程 + 显示开关（每份课表各带一套设置）。
 * 合法性由 ScheduleValidator 全量校验；此模型只承载数据。
 */
@Serializable
data class Schedule(
    val id: String,
    val name: String,
    /**
     * 学期开始日：可为任意星期几，第 1 周 = 它所在的日历周（周一~周日），
     * 开学日之前的几天属于第 1 周但尚未开学。
     */
    val termStart: IsoDate,
    /** 学期结束日：最后一周的最后一个教学日，**可落在周中**（如某学期只上到周三） */
    val termEnd: IsoDate,
    /** 每周上课天数：5（周一~五）或 7（周一~日） */
    val daysPerWeek: Int = 5,
    val slots: List<TimeSlotDef> = DEFAULT_SLOTS,
    val courses: List<CourseEntry> = emptyList(),
    /** 本周不上的课是否显示（显示时 UI 置灰）——课表级全局开关 */
    val showInactiveCourses: Boolean = true,
) {
    /**
     * 学期总周数 = termEnd 落在第几个日历周（周一~周日）：
     * 两端各取所在周的周一，相隔几整周即几周。开学日是任意星期几时同样成立——
     * 第 1 周是它所在的日历周（可能只含开学日之后几天）；termEnd 可停在周中，
     * 末周同理只数到它所在的那一周为止。
     */
    val totalWeeks: Int get() = ((termEnd.mondayOfWeek() - termStart.mondayOfWeek()).toInt() / 7) + 1

    /** 每天大课节数（= slots.size） */
    val slotCount: Int get() = slots.size

    /** [course] 是否在第 [week] 周上课 */
    fun isWeekActive(course: CourseEntry, week: Int): Boolean = course.weeks.contains(week)
}

/** 默认 5 节大课（时长均 100 分钟），与现有 UI mock 一致 */
val DEFAULT_SLOTS: List<TimeSlotDef> = listOf(
    TimeSlotDef("8:00", "9:40"),
    TimeSlotDef("10:10", "11:50"),
    TimeSlotDef("14:30", "16:10"),
    TimeSlotDef("16:40", "18:20"),
    TimeSlotDef("19:10", "20:50"),
)

/**
 * 用户调整每天节数时的默认作息生成：
 * N ≤ 5 取 [DEFAULT_SLOTS] 前 N 条；N > 5 按**等时长**在 8:00–22:30 窗口内均匀重新生成
 * （课间 30 分钟，永不越过 23:59）。纯启发式，调用方（未来 UI）可再让用户逐节修改。
 */
fun defaultSlotsFor(count: Int): List<TimeSlotDef> {
    if (count <= 0) return emptyList()
    if (count <= DEFAULT_SLOTS.size) return DEFAULT_SLOTS.take(count)
    val windowStart = 8 * 60
    val windowEnd = 22 * 60 + 30
    val breaks = DEFAULT_BREAK_MINUTES * (count - 1)
    val duration = (windowEnd - windowStart - breaks) / count
    require(duration >= 1) { "slot count too large: $count" }
    return (0 until count).map { i ->
        val start = windowStart + i * (duration + DEFAULT_BREAK_MINUTES)
        TimeSlotDef(TimeText.format(start), TimeText.format(start + duration))
    }
}

/** 一天最后一分钟数（23:59）；追加节的结束不得越过 */
private const val LAST_MINUTE_OF_DAY = 1439

/** 追加节时长（分钟），与 [DEFAULT_SLOTS] 各节一致 */
private const val APPEND_SLOT_MINUTES = 100

/**
 * 保留现有 [slots] 并在末尾追加一节：新节开始 = 上一节结束 + [DEFAULT_BREAK_MINUTES]，
 * 时长 [APPEND_SLOT_MINUTES]。空表、末节时间文本非法、或新节结束晚于 23:59 时返回 null
 * （调用方据此禁用「＋」；节数上限由调用方按 ScheduleValidator.MAX_SLOTS 把控）。
 */
fun appendSlot(slots: List<TimeSlotDef>): List<TimeSlotDef>? {
    val lastEnd = slots.lastOrNull()?.let { TimeText.parseMinutes(it.endTime) } ?: return null
    val start = lastEnd + DEFAULT_BREAK_MINUTES
    val end = start + APPEND_SLOT_MINUTES
    if (end > LAST_MINUTE_OF_DAY) return null
    return slots + TimeSlotDef(TimeText.format(start), TimeText.format(end))
}

/** 新 UUID（课表 id / 课程 id 统一来源） */
fun newUuid(): String = UUID.randomUUID().toString()
