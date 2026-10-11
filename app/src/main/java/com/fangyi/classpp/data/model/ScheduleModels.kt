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
    // 课程备注（课程详情浮层可编辑，关闭浮层时落盘）；默认空串，旧文件缺该字段照常
    // 反序列化，旧版本 app 读新文件由 ignoreUnknownKeys 忽略，无版本号变更
    val note: String = "",
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
    /**
     * 每节课时长（分钟）：设置页「每节课时间」。改某节**开始时间**时结束按它自动推算，
     * 与下一节冲突则后续节级联顺延（见 [slotsWithAdjustedStart]）；改结束时间不联动。
     * 「加节」生成新节同样用它（见 [appendSlot]）。
     */
    val slotDurationMinutes: Int = 100,
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

/** 一天最后一分钟数（23:59）；追加节与级联顺延的结束不得越过 */
private const val LAST_MINUTE_OF_DAY = 1439

/** 每节课时长的缺省值（分钟）：与 [DEFAULT_SLOTS] 各节一致，新表与旧数据（无该字段）的初值 */
const val DEFAULT_SLOT_DURATION_MINUTES = 100

/** 每节课时长的可选范围（分钟）：设置页「每节课时间」滚轮的边界 */
const val MIN_SLOT_DURATION = 10
const val MAX_SLOT_DURATION = 200

/**
 * 保留现有 [slots] 并在末尾追加一节：新节开始 = 上一节结束 + [DEFAULT_BREAK_MINUTES]，
 * 时长 [durationMinutes]（缺省与 [DEFAULT_SLOTS] 各节一致）。空表、末节时间文本非法、
 * 或新节结束晚于 23:59 时返回 null（节数上限由调用方按 ScheduleValidator.MAX_SLOTS 把控）。
 */
fun appendSlot(
    slots: List<TimeSlotDef>,
    durationMinutes: Int = DEFAULT_SLOT_DURATION_MINUTES,
): List<TimeSlotDef>? {
    val lastEnd = slots.lastOrNull()?.let { TimeText.parseMinutes(it.endTime) } ?: return null
    val start = lastEnd + DEFAULT_BREAK_MINUTES
    val end = start + durationMinutes
    if (end > LAST_MINUTE_OF_DAY) return null
    return slots + TimeSlotDef(TimeText.format(start), TimeText.format(end))
}

/**
 * 把第 [index] 节的**开始时间**改为 [startMinutes]，结束时间按 [durationMinutes] 自动推算
 * （开始 + 时长）；若新结束越过下一节的开始，下一节开始顺延为该结束、结束同样按时长重推，
 * 级联到某节不再冲突为止——未冲突的后续节保持原样。任一时间越过当天末尾（23:59）返回
 * null（当天放不下，调用方提示后放弃本次修改，不产生部分生效的中间态）。
 * 与**上一节**的冲突（开始早于其结束）不在此处理：结束时间是用户自留的，交由仓库校验拒绝。
 */
fun slotsWithAdjustedStart(
    slots: List<TimeSlotDef>,
    index: Int,
    startMinutes: Int,
    durationMinutes: Int,
): List<TimeSlotDef>? {
    require(index in slots.indices) { "slot index out of range: $index" }
    val result = slots.toMutableList()
    var start = startMinutes
    var i = index
    while (true) {
        val end = start + durationMinutes
        if (end > LAST_MINUTE_OF_DAY) return null
        result[i] = result[i].copy(
            startTime = TimeText.format(start),
            endTime = TimeText.format(end),
        )
        val next = i + 1
        if (next == result.size) return result
        val nextStart = TimeText.parseMinutes(result[next].startTime) ?: return result
        if (nextStart >= end) return result
        // 冲突：下一节开始顶到本节新结束，整节顺延后继续看它的下一节
        start = end
        i = next
    }
}

/**
 * 把节数调整为 [count]（设置页「总节数」滚轮）：小于现有 → 前缀裁剪（保留用户已改时间）；
 * 大于现有 → 循环 [appendSlot] 在末尾生成（课间 30 分钟、时长 [durationMinutes]）。
 * 当天放不下目标节数时返回 null（调用方提示后放弃，不提交截断结果）。
 */
fun slotsWithCount(
    slots: List<TimeSlotDef>,
    count: Int,
    durationMinutes: Int,
): List<TimeSlotDef>? {
    require(count >= 1) { "slot count must be positive: $count" }
    if (count == slots.size) return slots
    if (count < slots.size) return slots.take(count)
    var current = slots
    while (current.size < count) {
        current = appendSlot(current, durationMinutes) ?: return null
    }
    return current
}

/** 新 UUID（课表 id / 课程 id 统一来源） */
fun newUuid(): String = UUID.randomUUID().toString()
