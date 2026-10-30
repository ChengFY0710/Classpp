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

/** 内置「默认」时间方案的 id（UI 用 [DEFAULT_SLOTS] 构造，名称走本地化字符串） */
const val DEFAULT_TIME_SCHEME_ID = "default"

/**
 * 一套可复用的作息模板（「课程时间方案」）：节次时间表 + 每节课时长。
 * 保存后进入全局方案库（[ScheduleFile.timeSchemes]），选中时整套应用到课表
 * （[Schedule.slots] + [Schedule.slotDurationMinutes]）；方案本身不绑定某份课表。
 */
@Serializable
data class TimeScheme(
    val id: String,
    val name: String,
    val slots: List<TimeSlotDef>,
    val slotDurationMinutes: Int = DEFAULT_SLOT_DURATION_MINUTES,
)

/** 课程卡片配色；条目名与 ui.schedule.CourseColor 完全一致（颜色选集 18 色），后续 UI 映射可直接 valueOf() */
@Serializable
enum class CourseColor {
    Pink, Lilac, Purple, Blue, SkyBlue, SteelBlue, IceBlue, Aqua, Teal,
    Green, BrightGreen, Mint, Greentwo, Olive, Lemon, Yellow, Orange, Coral,
}

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

/** 合并相邻两小节的间隙上限（分钟）：超过则不配对，避免把午休前后撮成大节 */
const val SLOT_MERGE_MAX_GAP_MINUTES = 30

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
 * 把节数调整为 [count]（设置页「总节数」滚轮）：
 *
 * - 小于现有 → 前缀裁剪（保留用户已改时间）；等于现有 → 原样返回；
 * - 大于现有 → 三级补足，越靠前越少改动用户已排好的作息：
 *   1. **末尾追加**：新节 = 上一节结束 + [DEFAULT_BREAK_MINUTES] 课间，时长 [durationMinutes]，
 *      能放下则现有各节时间全部不动（与旧行为一致）；
 *   2. **剩余窗口分配**：追加越界时，新增节塞进「末节结束 ~ 23:59」，课间取
 *      `(剩余分钟 − 新增节数 × 时长) / 新增节数`（整除，可为 0 = 各节首尾相接），
 *      现有各节仍不动；
 *   3. **全天重排**：剩余窗口连「新增节数 × 时长」都放不下时，保留**第 1 节开始时间**，
 *      以 [durationMinutes] 为每节时长、全天课间统一压缩重排（课间上限 30 分钟，
 *      不为了填满 23:59 而拉长）。本级会改写现有各节的起止时间（午休等不规则课间
 *      被抹平），是"当天尽量排下"的最后手段。
 *
 * 只有第 3 级也放不下——即 `count × durationMinutes` 超过「第 1 节开始 ~ 23:59」——
 * 才返回 null（调用方提示后放弃调整，不产生部分生效的中间态）。
 */
fun slotsWithCount(
    slots: List<TimeSlotDef>,
    count: Int,
    durationMinutes: Int,
): List<TimeSlotDef>? {
    require(count >= 1) { "slot count must be positive: $count" }
    if (count == slots.size) return slots
    if (count < slots.size) return slots.take(count)

    // 第 1 级：末尾追加（课间 30 分钟），能放下就不改任何现有节
    var current = slots
    var appended = true
    while (current.size < count) {
        val next = appendSlot(current, durationMinutes)
        if (next == null) {
            appended = false
            break
        }
        current = next
    }
    if (appended) return current

    // 第 2 级：新增节塞进「末节结束 ~ 23:59」，课间按剩余时间均分（可压到 0）
    val lastEnd = slots.lastOrNull()?.let { TimeText.parseMinutes(it.endTime) }
    val newCount = count - slots.size
    if (lastEnd != null) {
        val remaining = LAST_MINUTE_OF_DAY - lastEnd
        val needed = newCount * durationMinutes
        if (needed <= remaining) {
            val gap = (remaining - needed) / newCount
            return slots + (0 until newCount).map { i ->
                val start = lastEnd + (i + 1) * gap + i * durationMinutes
                TimeSlotDef(TimeText.format(start), TimeText.format(start + durationMinutes))
            }
        }
    }

    // 第 3 级：全天重排——首节开始与每节时长不变，课间统一压缩（上限 30 分钟）
    val firstStart = slots.firstOrNull()?.let { TimeText.parseMinutes(it.startTime) } ?: return null
    val available = LAST_MINUTE_OF_DAY - firstStart
    val required = count * durationMinutes
    if (required > available) return null
    val gap = minOf(DEFAULT_BREAK_MINUTES, (available - required) / (count - 1))
    return (0 until count).map { i ->
        val start = firstStart + i * (durationMinutes + gap)
        TimeSlotDef(TimeText.format(start), TimeText.format(start + durationMinutes))
    }
}

/**
 * 「合并相邻两小节为一大节」的预览与门控结果（纯计算，不写库）。
 *
 * [canMerge] 同时要求：至少有一对可合并、至少有一门课程真正占用 ≥2 节、所有课程都能精确
 * 对齐、所有课程 span ≥ 2——后两条保证合并后每门课的起止时间不变，不会出现课程时间被
 * 悄悄改写；[hasMultiSlotCourse] 则保证合并确实有课程可重映射——空课表 / 全是单节课时
 * 合并只是把节表缩小一半，没有意义（产品规则：没有课程占用 ≥2 节时按钮置灰）。
 */
data class SlotMergePlan(
    val newSlots: List<TimeSlotDef>,
    val newCourses: List<CourseEntry>,
    val mergedPairCount: Int,
    val unalignedCourses: List<CourseEntry>,
    val singleSlotCourses: List<CourseEntry>,
    val newDurationMinutes: Int,
    /**
     * 是否存在至少一门「实际占用 ≥2 节」的课程（起止节次都落在当前节表内）。
     * 越界的隐藏课（5 天课表里的周末课且节次已越界）不参与：它们本就不会被重映射。
     */
    val hasMultiSlotCourse: Boolean,
) {
    val canMerge: Boolean
        get() = mergedPairCount > 0 &&
            hasMultiSlotCourse &&
            unalignedCourses.isEmpty() &&
            singleSlotCourses.isEmpty()
}

/**
 * 按位置把相邻两小节两两配对成一大节：
 *
 * - 仅当两节间隙落在 `0..` [SLOT_MERGE_MAX_GAP_MINUTES] 内才合并（如 45+10 的小节表）；
 *   间隙超标的对（如午休前后）保留原样，避免撮出数小时的大节。时长不参与配对判断，
 *   合并节的起止 = 首节开始 ~ 次节结束，完全实算；
 * - 课程映射以「起点/终点时间不变」为准：起点落在合并对的次节、或终点落在首节时
 *   无法精确对齐，计入 [SlotMergePlan.unalignedCourses] 阻断合并；
 * - span < 2 的课程一律计入 [SlotMergePlan.singleSlotCourses] 阻断（产品规则：所有
 *   课程都占用 ≥2 节时才允许合并）；空课表或全是单节课时没有课程可重映射，合并只会
 *   把节表缩小一半，同样阻断（[SlotMergePlan.hasMultiSlotCourse]）；
 * - 落在当前每周天数之外、且索引已越界的隐藏课（5 天课表里的周末课）原样保留、
 *   不参与阻断：校验器本就跳过它们，保留旧映射不会让可见数据变错；
 * - 新节表的「每节课时间」取各节实际时长的众数（并列取较大），它仅作后续改开始时间
 *   级联顺延的推算依据，不写死 45/100。
 *
 * 函数无副作用，产物整表由调用方（仓库）提交前再走一遍 `ScheduleValidator`。
 */
fun previewSlotMerge(
    slots: List<TimeSlotDef>,
    courses: List<CourseEntry>,
    daysPerWeek: Int,
): SlotMergePlan {
    val newSlots = mutableListOf<TimeSlotDef>()
    // 旧节索引（0-based）→ 新节索引
    val indexMap = IntArray(slots.size)
    var mergedPairs = 0
    var i = 0
    while (i < slots.size) {
        val current = slots[i]
        val next = slots.getOrNull(i + 1)
        val gap = if (next != null) {
            val end = TimeText.parseMinutes(current.endTime)
            val nextStart = TimeText.parseMinutes(next.startTime)
            if (end != null && nextStart != null) nextStart - end else null
        } else {
            null
        }
        if (next != null && gap != null && gap in 0..SLOT_MERGE_MAX_GAP_MINUTES) {
            indexMap[i] = newSlots.size
            indexMap[i + 1] = newSlots.size
            newSlots += TimeSlotDef(current.startTime, next.endTime)
            mergedPairs++
            i += 2
        } else {
            indexMap[i] = newSlots.size
            newSlots += current
            i += 1
        }
    }

    val newCourses = mutableListOf<CourseEntry>()
    val unaligned = mutableListOf<CourseEntry>()
    val singleSlot = mutableListOf<CourseEntry>()
    var hasMultiSlotCourse = false
    for (course in courses) {
        val oldStart = course.startSlot - 1
        val oldEnd = course.endSlot - 1
        if (oldStart !in slots.indices || oldEnd !in slots.indices) {
            // 可见课越界属于非法草稿，阻断；隐藏课的旧映射本就越界，原样保留
            if (course.dayOfWeek <= daysPerWeek) unaligned += course else newCourses += course
            continue
        }
        if (course.span < 2) singleSlot += course else hasMultiSlotCourse = true
        val newStart = indexMap[oldStart]
        val newEnd = indexMap[oldEnd]
        val aligned = newSlots[newStart].startTime == slots[oldStart].startTime &&
            newSlots[newEnd].endTime == slots[oldEnd].endTime
        if (aligned) {
            newCourses += course.copy(
                startSlot = newStart + 1,
                span = newEnd - newStart + 1,
            )
        } else {
            unaligned += course
        }
    }

    val durations = newSlots.mapNotNull { slot ->
        val start = TimeText.parseMinutes(slot.startTime)
        val end = TimeText.parseMinutes(slot.endTime)
        if (start != null && end != null && end > start) end - start else null
    }
    val newDuration = durations
        .groupingBy { it }
        .eachCount()
        .maxWithOrNull(compareBy({ it.value }, { it.key }))
        ?.key
        ?: DEFAULT_SLOT_DURATION_MINUTES

    return SlotMergePlan(
        newSlots = newSlots,
        newCourses = newCourses,
        mergedPairCount = mergedPairs,
        unalignedCourses = unaligned,
        singleSlotCourses = singleSlot,
        newDurationMinutes = newDuration,
        hasMultiSlotCourse = hasMultiSlotCourse,
    )
}

/** 新 UUID（课表 id / 课程 id 统一来源） */
fun newUuid(): String = UUID.randomUUID().toString()
