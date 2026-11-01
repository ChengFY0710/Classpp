package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleValidator.MAX_SPAN
import com.fangyi.classpp.data.model.CourseColor
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.data.model.weeksFromSelection

/** 学期的教务课表原始数据（抓取与映射的边界，纯数据便于单测） */
internal data class XmuJwScheduleRaw(
    val semesterLabel: String,
    val slots: List<XmuJwSkjcDto>,
    val courses: List<XmuJwPkjgDto>,
    /** 教务判定的当前周次；假期等场景可能为空或越界，映射器自行钳制 */
    val currentZc: Int?,
    val fallbackName: String,
    /** 开学日推算基准日，默认今天；测试注入固定值 */
    val today: IsoDate = IsoDate.today(),
)

/**
 * 教务排课结果 → Classpp 课表（纯函数）。产物是一份 [Schedule]，再经 SharePayload
 * JSON 信封交给 `ScheduleRepository.importSchedule`——校验、名称去重、id 重铸全部复用
 * 既有导入路径，本对象只负责把两套模型对齐：
 *
 * - 作息 = 教务节次表按 PX 排序、剔除「中午」占位行后的列表位置（节次代码 DM → 位置），
 *   课程起止节次代码（KSJCDM/JSJCDM）对号入座；KSSJ/JSSJ 的 HHmm 整数转 `8:00` 形态；
 * - 周次 = ZCBH 位图（第 i 位 '1' = 第 i+1 周）→ 周号集合 → [weeksFromSelection]
 *   归并成区间并集（单双周自动成 ODD/EVEN 段）；
 * - **同一节课多行合并**：教务把一节课按「教师×教室」拆成多行下发（同课、同星期、
 *   同起止节次、同周次，仅教师/教室不同——教务网格把它们叠渲染在同一格，实测
 *   数据结构实验 4 位教师 4 行、AI 课同周次两间教室），合并成一条后再映射，
 *   否则这些行会在同格冲突校验里互撞，导入被误拒；
 * - Classpp 单卡跨度上限 [MAX_SPAN]（5 节）：超长课拆成连续块（周次相同，块间不重叠）；
 * - 课程数据有缺陷（空名/星期越界/节次未知/无周次）时**丢弃该行**而非整体失败，
 *   一门课都放不下才返回 null 交由 UI 报「未解析到课程」；
 * - 开学日：教务接口不下发，用「当前周次 + 今天」反推第 1 周周一（教务「第 N 周」与
 *   Classpp「第 N 周」同按日历周对齐）；缺省/越界时兜底今天所在周的周一，导入后可在
 *   课表设置里调整。
 */
internal object XmuJwScheduleMapper {

    /** 「我的课表」周次位图与教务学期周数的实际长度（30 位，超出即视为脏数据丢弃） */
    private const val MAX_BITMAP_WEEKS = 30

    fun toScheduleOrNull(raw: XmuJwScheduleRaw): Schedule? {
        // 作息先定：课程能否落格取决于它（节次代码 → 1-based 位置）
        val slots = buildSlots(raw.slots)
        if (slots.isEmpty()) return null
        val slotIndex = buildSlotIndex(raw.slots)

        var nextColorIndex = 0
        val colorByName = mutableMapOf<String, CourseColor>()
        val courses = mergeSameSessions(raw.courses).mapNotNull { row ->
            toEntriesOrNull(row, slotIndex) { name ->
                // 同名课共用一色，按首次出现顺序在 18 色里轮询
                colorByName.getOrPut(name) {
                    val colors = CourseColor.entries
                    colors[nextColorIndex++ % colors.size]
                }
            }
        }.flatten()
        if (courses.isEmpty()) return null

        val currentZc = raw.currentZc?.takeIf { it in 1..MAX_BITMAP_WEEKS }
        val termStart = termStartOf(raw.today, currentZc)
        val totalWeeks = maxOf(
            courses.maxOf { it.weeks.maxWeek() ?: 0 },
            currentZc ?: 0,
        ).coerceIn(1, MAX_BITMAP_WEEKS)
        return Schedule(
            id = newUuid(), // 导入时 decodeForImport 一律重铸，这里只为通过编码
            name = raw.semesterLabel.trim().ifBlank { raw.fallbackName },
            termStart = termStart,
            termEnd = termStart + (totalWeeks - 1) * 7 + 6, // 末周周日，R14 恒可满足
            daysPerWeek = if (courses.any { it.dayOfWeek > 5 }) 7 else 5,
            slots = slots,
            courses = courses,
        )
    }

    //region 课程 → CourseEntry

    /**
     * 同一节课的拆行合并：教务按「教师×教室」把一节课下发成多行——同课、同星期、
     * 同起止节次、同周次，仅教师/教室不同（教务网格把它们叠渲染在同一格）。按这五个
     * 维度并成一行：教师取拆分去重后的并集、教室去重拼接、备注取首个非空——否则这些
     * 行周次全等，映射成多条 CourseEntry 后会在同格冲突校验里互撞（实测数据结构实验
     * 4 位教师 4 行、AI 课同周次两间教室，导入被误拒）。
     */
    private fun mergeSameSessions(rows: List<XmuJwPkjgDto>): List<XmuJwPkjgDto> =
        rows.groupBy {
            listOf(
                it.courseCode, it.courseName,
                it.weekday, it.startSlotCode, it.endSlotCode, it.weekBitmap,
            )
        }.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                group.first().copy(
                    teacher = group.asSequence()
                        .mapNotNull { it.teacher }
                        .flatMap(::splitNames)
                        .distinct()
                        .joinToString("、")
                        .ifEmpty { null },
                    location = group.mapNotNull { it.location?.takeIf(String::isNotBlank) }
                        .distinct()
                        .joinToString("；")
                        .ifEmpty { null },
                    note = group.mapNotNull { it.note?.takeIf(String::isNotBlank) }.firstOrNull(),
                )
            }
        }

    /** 教师字段可能是「李杰,齐洁」这类合写：按常见分隔符拆成名单 */
    private fun splitNames(text: String): List<String> =
        text.split(',', '，', '、', ';', '；', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun toEntriesOrNull(
        row: XmuJwPkjgDto,
        slotIndex: Map<Int, Int>,
        assignColor: (String) -> CourseColor,
    ): List<CourseEntry>? {
        val name = row.courseName?.trim().orEmpty()
        if (name.isBlank()) return null
        val day = row.weekday ?: return null
        if (day !in 1..7) return null
        val startCode = row.startSlotCode ?: return null
        val endCode = row.endSlotCode ?: return null
        // 起止节次代码必须都能对到作息位置（引用「中午」等非课节 → 丢弃）
        val start = slotIndex[startCode] ?: return null
        val end = slotIndex[endCode] ?: return null
        if (end < start) return null
        val weeks = weeksFromBitmap(row.weekBitmap) ?: return null
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
                        teacher = row.teacher?.trim().orEmpty(),
                        location = row.location?.trim().orEmpty(),
                        dayOfWeek = day,
                        startSlot = section,
                        span = span,
                        weeks = weeks,
                        color = color,
                        note = row.note?.trim().orEmpty(),
                    ),
                )
                section += span
            }
        }
    }

    /** ZCBH 位图（第 i 位 '1' = 第 i+1 周）→ 周段；缺失/无任何命中返回 null */
    private fun weeksFromBitmap(bitmap: String?): WeekPattern? {
        if (bitmap.isNullOrBlank()) return null
        val weeks = buildList {
            for (index in bitmap.indices) {
                if (bitmap[index] == '1' && index < MAX_BITMAP_WEEKS) add(index + 1)
            }
        }
        if (weeks.isEmpty()) return null
        return weeksFromSelection(weeks)
    }

    //endregion

    //region 作息

    /**
     * 可排课节次（剔除「中午」占位行、按 PX 排序）→ 「节次代码 → 作息条目」表，
     * 列表位置即节次编号。时间非法/重复代码的行整体跳过（正常教务数据不出现；
     * 位置表与作息列表同源于这张表，保证对号一致）。
     */
    private fun slotTable(rows: List<XmuJwSkjcDto>): List<Pair<Int, TimeSlotDef>> {
        val seen = mutableSetOf<Int>()
        val table = mutableListOf<Pair<Int, TimeSlotDef>>()
        for (row in orderedRows(rows)) {
            val code = row.code?.toIntOrNull() ?: continue
            if (!seen.add(code)) continue
            val start = row.startTime?.let(::hhmmToMinutes) ?: continue
            val end = row.endTime?.let(::hhmmToMinutes) ?: continue
            if (end <= start) continue
            table += code to TimeSlotDef(TimeText.format(start), TimeText.format(end))
        }
        return table
    }

    internal fun buildSlots(rows: List<XmuJwSkjcDto>): List<TimeSlotDef> =
        slotTable(rows).map { it.second }

    /** 节次代码 → 1-based 位置（与 [buildSlots] 同源于 [slotTable]，保证对号一致） */
    private fun buildSlotIndex(rows: List<XmuJwSkjcDto>): Map<Int, Int> {
        val index = mutableMapOf<Int, Int>()
        slotTable(rows).forEachIndexed { position, (code, _) -> index[code] = position + 1 }
        return index
    }

    private fun orderedRows(rows: List<XmuJwSkjcDto>): List<XmuJwSkjcDto> = rows
        .filter { !(it.name?.contains("中午") ?: false) }
        .sortedBy { it.order ?: Int.MAX_VALUE }

    /** HHmm 整数（800 = 8:00）→ 当日分钟数；非法值返回 null */
    private fun hhmmToMinutes(value: Int): Int? {
        val hour = value / 100
        val minute = value % 100
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    //endregion

    /** 开学日：第 1 周周一 = 今天所在周一 −（当前周次 − 1）×7；周次不可用时兜底今天所在周一 */
    private fun termStartOf(today: IsoDate, currentZc: Int?): IsoDate =
        today.mondayOfWeek() - ((currentZc ?: 1) - 1) * 7
}
