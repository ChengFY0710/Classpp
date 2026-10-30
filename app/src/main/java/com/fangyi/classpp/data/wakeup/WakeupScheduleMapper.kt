package com.fangyi.classpp.data.wakeup

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.ScheduleValidator.MAX_SLOTS
import com.fangyi.classpp.data.ScheduleValidator.MAX_SPAN
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOT_DURATION_MINUTES
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.MAX_SLOT_DURATION
import com.fangyi.classpp.data.model.MIN_SLOT_DURATION
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.SharePayload
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.data.model.defaultSlotsFor
import com.fangyi.classpp.data.model.newUuid

/** 一天最后一分钟数（23:59）：续生成的节不得越过，与 ScheduleModels 的约定一致 */
private const val LAST_MINUTE_OF_DAY = 1439

/**
 * Wakeup 载荷 → Classpp 课表（纯函数）。产物是一份 [Schedule]，再经 [SharePayload]
 * JSON 信封交给 `ScheduleRepository.importSchedule`——校验（周界/冲突/作息格式）、名称去重、
 * id 重铸全部复用既有导入路径，本类只负责把两套模型对齐：
 *
 * - 节次 = Wakeup 的 node（1-based），作息条目按 [TimeSlotDef] 列表位置对号入座；
 * - Classpp 单卡跨度上限 [MAX_SPAN]（5 节）：超长课拆成连续块（周次相同，块间不重叠，
 *   不会触发同格冲突校验）；
 * - 自定义时间课程（ownTime）按节次占位映射，自定义上下课时间不迁移（模型无此概念）；
 * - 课程数据有缺陷（空名/星期越界/无周次/节次放不下）时**丢弃该课**而非整体失败，
 *   一门课都放不下才返回 null 交由 UI 报「未解析到课程」。
 */
object WakeupScheduleMapper {

    /**
     * 映射为可直接导入的分享 JSON（`ShareCodec.decodeForImport` 可解析、可校验通过）。
     * @param shareText 用户粘贴的原始文案（提取「XXX的课表」作课表名）
     * @param fallbackName 提取不到名称时的本地化缺省名
     * @return 无任何可导入课程时返回 null
     */
    fun toShareJsonOrNull(
        payload: WakeupSharePayload,
        shareText: String,
        fallbackName: String,
    ): String? = toScheduleOrNull(payload, shareText, fallbackName)?.let { toShareJson(it) }

    /** 课表对象 → 分享 JSON（确认弹窗后落库用；导入时 id 由 decodeForImport 重铸） */
    fun toShareJson(schedule: Schedule): String =
        ScheduleJson.encodeShare(
            SharePayload(exportedAtMillis = System.currentTimeMillis(), schedule = schedule),
        )

    /** 映射为课表对象（供确认弹窗展示课程数/课表名；确认后再走 [toShareJsonOrNull] 同一套编码） */
    fun toScheduleOrNull(
        payload: WakeupSharePayload,
        shareText: String,
        fallbackName: String,
    ): Schedule? {
        val merged = WakeupCourseMerger.merge(payload.courses)
        // 作息先定：课程能否落格取决于它
        val maxEndSection = merged.maxOfOrNull { it.endSection } ?: 0
        val slots = buildSlots(payload, maxEndSection)

        var nextColorIndex = 0
        val colorByName = mutableMapOf<String, CourseColor>()
        val courses = merged.mapNotNull { course ->
            toEntryOrNull(course, slots.size, colorByName) { name ->
                // 同名课共用一色，按首次出现顺序在 18 色里轮询
                colorByName.getOrPut(name) {
                    val colors = CourseColor.entries
                    colors[nextColorIndex++ % colors.size]
                }
            }
        }.flatten() // 一门课可能拆成多块（跨度超上限），过滤后摊平
        if (courses.isEmpty()) return null

        val termStart = termStartOf(payload.config)
        val maxCourseWeek = courses.maxOf { it.weeks.maxWeek() ?: 0 }
        val totalWeeks = maxOf(payload.config.semesterTotalWeeks, maxCourseWeek, 1)
        return Schedule(
            id = newUuid(), // 导入时 decodeForImport 一律重铸，这里只为通过编码
            name = WakeupShareText.extractScheduleName(shareText)
                .ifBlank { fallbackName.ifBlank { "Wakeup" } },
            termStart = termStart,
            termEnd = termStart + (totalWeeks - 1) * 7 + 6, // 末周周日，R14 恒可满足
            daysPerWeek = if (courses.any { it.dayOfWeek > 5 }) 7 else 5,
            slots = slots,
            slotDurationMinutes = slotDurationOf(payload.config),
            courses = courses,
        )
    }

    //region 课程 → CourseEntry

    private fun toEntryOrNull(
        course: WakeupCourse,
        slotCount: Int,
        colorByName: MutableMap<String, CourseColor>,
        assignColor: (String) -> CourseColor,
    ): List<CourseEntry>? {
        val name = course.name.trim()
        if (name.isBlank()) return null
        val day = course.day
        if (day !in 1..7) return null
        val start = course.startSection
        val end = course.endSection
        // 起止节次必须在作息范围内（Classpp 网格按 slots 列表位置对号）
        if (start < 1 || end < start || end > slotCount) return null
        val segments = weeksToSegments(course.weeks)
        if (segments.isEmpty()) return null
        val color = assignColor(name)

        // 单卡跨度上限：超长课拆成连续块（同周次，块间不重叠 → 不构成同格冲突）
        return buildList {
            var section = start
            while (section <= end) {
                val span = minOf(MAX_SPAN, end - section + 1)
                add(
                    CourseEntry(
                        id = newUuid(),
                        name = name,
                        teacher = course.teacher?.trim().orEmpty(),
                        location = course.position?.trim().orEmpty(),
                        dayOfWeek = day,
                        startSlot = section,
                        span = span,
                        weeks = WeekPattern(segments),
                        color = color,
                    ),
                )
                section += span
            }
        }
    }

    /**
     * 周次列表 → 周段：排序后按公差游程切分——步长 1 = 全周（ALL）、从奇数起步长 2 =
     * 单周（ODD）、从偶数起步长 2 = 双周（EVEN）；断续处切段（段间并集语义）。
     * 例：[1,2,3] → ALL 1..3；[1,3,5] → ODD 1..5；[1,2,4,5] → ALL 1..2 + ALL 4..5。
     */
    internal fun weeksToSegments(weeks: List<Int>): List<WeekSegment> {
        val sorted = weeks.filter { it > 0 }.distinct().sorted()
        if (sorted.isEmpty()) return emptyList()
        val segments = mutableListOf<WeekSegment>()
        var index = 0
        while (index < sorted.size) {
            val start = sorted[index]
            var step = 1
            var end = start
            if (index + 1 < sorted.size && sorted[index + 1] - start == 2) step = 2
            var cursor = index + 1
            while (cursor < sorted.size && sorted[cursor] - end == step) {
                end = sorted[cursor]
                cursor++
            }
            val parity = when {
                step == 1 -> Parity.ALL
                start % 2 == 1 -> Parity.ODD
                else -> Parity.EVEN
            }
            segments += WeekSegment(start, end, parity)
            index = cursor
        }
        return segments
    }

    //endregion

    //region 作息

    /**
     * 作息表：脚本回传的 timeSlots 按节次编号排序；时间经 [TimeText] 归一为 `8:00` 形态
     * （容忍 `08:00` / `8:00:00`）。任一条时间非法则整体弃用（节次编号 = 列表位置，
     * 中途缺位会错位），改用默认作息；课程末节超出时按「上节结束 + 课间 + 每节时长」续生成。
     *
     * 尾部裁剪：Wakeup 对学校未配置的节次会下发**互相重叠的占位作息**（如 12-20 节全挤在
     * 21:00-23:30 里），而 Classpp 校验要求各节时间有序不重叠。课程实际用到的节（1..maxEnd）
     * 一律保留（不能移位），其后的节只在「开始不早于上一节结束」时保留——真实的额外节次
     * 留在网格里，占位垃圾自此截断。
     */
    private fun buildSlots(payload: WakeupSharePayload, maxEndSection: Int): List<TimeSlotDef> {
        val raw = payload.timeSlots.sortedBy { it.slotNumber }
        val provided = raw.map { slot ->
            val start = normalizeTime(slot.startTime)
            val end = normalizeTime(slot.endTime)
            if (slot.slotNumber <= 0 || start == null || end == null) null else TimeSlotDef(start, end)
        }
        var slots = if (provided.any { it == null }) emptyList() else provided.filterNotNull()

        val target = maxEndSection.coerceAtMost(MAX_SLOTS)
        if (slots.size < target) {
            // 空表从默认作息起步（defaultSlotsFor 对大节数会生成 1 分钟的节，钳到 29）
            if (slots.isEmpty()) {
                slots = defaultSlotsFor(target.coerceAtMost(MAX_SLOTS - 1))
            }
            val breakMinutes = payload.config.defaultBreakDuration.takeIf { it in 0..120 } ?: 30
            val durationMinutes = slotDurationOf(payload.config)
            while (slots.size < target && slots.size < MAX_SLOTS) {
                val lastEnd = TimeText.parseMinutes(slots.last().endTime) ?: break
                val start = lastEnd + breakMinutes
                val end = start + durationMinutes
                if (end > LAST_MINUTE_OF_DAY) break
                slots = slots + TimeSlotDef(TimeText.format(start), TimeText.format(end))
            }
        }
        return trimTrailingSlots(slots, target)
    }

    /** 课程用到的节全保留；其后遇到「开始早于上一节结束」的占位节即截断 */
    private fun trimTrailingSlots(slots: List<TimeSlotDef>, usedEnd: Int): List<TimeSlotDef> {
        if (slots.size <= usedEnd) return slots
        val kept = mutableListOf<TimeSlotDef>()
        var prevEnd: Int? = null
        for ((index, slot) in slots.withIndex()) {
            val start = TimeText.parseMinutes(slot.startTime)
            val end = TimeText.parseMinutes(slot.endTime)
            if (start == null || end == null) break
            if (index >= usedEnd) {
                val previous = prevEnd
                if (previous != null && previous > start) break
            }
            kept += slot
            prevEnd = end
        }
        return kept
    }

    private fun normalizeTime(text: String?): String? {
        var value = text?.trim().orEmpty()
        // 容忍带秒形态（8:00:00 → 8:00）
        val withSeconds = Regex("^(\\d{1,2}:\\d{2}):(\\d{2})$").find(value)
        if (withSeconds != null) value = withSeconds.groupValues[1]
        return TimeText.normalize(value)
    }

    private fun slotDurationOf(config: WakeupCourseConfig): Int =
        config.defaultClassDuration
            .takeIf { it in MIN_SLOT_DURATION..MAX_SLOT_DURATION }
            ?: DEFAULT_SLOT_DURATION_MINUTES

    //endregion

    /** 开学日：脚本配置优先（宽松解析 yyyy-MM-dd / yyyy-M-d），缺省今天所在日历周的周一 */
    private fun termStartOf(config: WakeupCourseConfig): IsoDate {
        config.semesterStartDate?.let { text ->
            parseLenientIso(text)?.let { return it }
        }
        return IsoDate.today().mondayOfWeek()
    }

    private fun parseLenientIso(text: String): IsoDate? {
        IsoDate.parseOrNull(text.trim())?.let { return it }
        val parts = Regex("(\\d{4})\\D+(\\d{1,2})\\D+(\\d{1,2})").find(text) ?: return null
        val (year, month, day) = parts.destructured
        return try {
            IsoDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
