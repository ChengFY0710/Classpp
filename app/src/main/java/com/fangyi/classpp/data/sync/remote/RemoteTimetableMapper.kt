package com.fangyi.classpp.data.sync.remote

import com.fangyi.classpp.data.ScheduleJson
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
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 远端声明式课表映射：把页内固定脚本回传的原文（原始 slots/courses 数组 + 学期/周次）
 * 按 [DeclarationTimetable] 的字段路径映射成 `Schedule`。
 *
 * 规则与编入式适配保持同一组不变量（便于人工适配互相参照）：
 * - 节次按声明顺序/排序键生成位置表；课程起止可以是节次代码（CODE）或 1-based 位置（INDEX）；
 * - 周次只支持位图（第 i 位 = 第 i+1 周，上限 30），经 `weeksFromSelection` 归并；
 * - 同课拆行按「代码/名称/星期/起止/周次」合并教师与教室；超过 5 节的课拆成连续块；
 * - 按课程名首次出现顺序轮询配色；无法映射的行单独丢弃，全空才报 `NoCourses`；
 * - 开学日：CURRENT_WEEK 由「当前周 + 今天」反推（兜底今天所在周一）；DATE 按声明
 *   pattern/zone 解析（失败同样兜底）；`termEnd` 取末周周日，`totalWeeks` 取课程/当前周最大。
 */
internal object RemoteTimetableMapper {

    private const val MAX_WEEKS = 30

    @Serializable
    private data class Payload(
        val ok: Boolean = false,
        val error: String? = null,
        val semesterCode: String? = null,
        val semesterLabel: String? = null,
        val currentWeek: String? = null,
        val termStart: String? = null,
        val slots: List<JsonObject> = emptyList(),
        val courses: List<JsonObject> = emptyList(),
    )

    fun toImportOutcome(
        raw: String,
        timetable: DeclarationTimetable,
        fallbackName: String,
        today: IsoDate = IsoDate.today(),
    ): ImportOutcome {
        val payload = try {
            ScheduleJson.decodeStorage<Payload>(raw)
        } catch (_: Exception) {
            null
        } ?: return ImportOutcome.Failed(ImportError.FormatChanged)

        if (!payload.ok) {
            return ImportOutcome.Failed(payload.error.toImportError())
        }
        if (payload.semesterCode.isNullOrBlank()) {
            return ImportOutcome.Failed(ImportError.FormatChanged)
        }

        val slotTable = if (timetable.fixedSlots.isNotEmpty()) {
            fixedSlotTable(timetable.fixedSlots)
                ?: return ImportOutcome.Failed(ImportError.FormatChanged)
        } else {
            val mapping = timetable.slots
                ?: return ImportOutcome.Failed(ImportError.FormatChanged)
            buildSlotTable(payload.slots, mapping)
        }
        if (slotTable.slots.isEmpty()) return ImportOutcome.NoCourses

        val rowFields = timetable.courses
        val rawCourses = payload.courses.mapNotNull { row -> rawCourse(row, rowFields) }
        val rows = if (rowFields.mergeSameSessions) mergeSameSessions(rawCourses) else rawCourses
        val currentWeek = payload.currentWeek?.trim()?.toIntOrNull()
            ?.takeIf { it in 1..MAX_WEEKS }
        val courses = mapCourses(rows, rowFields, slotTable)
        if (courses.isEmpty()) return ImportOutcome.NoCourses

        val termStart = when (timetable.term.source) {
            DeclarationTimetable.TERM_DATE ->
                parseDate(payload.termStart, timetable.term.pattern, timetable.term.zone)
                    ?: today.mondayOfWeek()
            else -> today.mondayOfWeek() - ((currentWeek ?: 1) - 1) * 7
        }
        val totalWeeks = maxOf(
            courses.maxOf { it.weeks.maxWeek() ?: 0 },
            currentWeek ?: 0,
        ).coerceIn(1, MAX_WEEKS)
        return ImportOutcome.Ok(
            Schedule(
                id = newUuid(),
                name = payload.semesterLabel?.trim().orEmpty().ifBlank { fallbackName },
                termStart = termStart,
                termEnd = termStart.mondayOfWeek() + (totalWeeks - 1) * 7 + 6,
                daysPerWeek = if (courses.any { it.dayOfWeek > 5 }) 7 else 5,
                slots = slotTable.slots,
                courses = courses,
            ),
        )
    }

    // ---------- 作息 ----------

    private data class SlotTable(
        val slots: List<TimeSlotDef>,
        val codeIndex: Map<String, Int>,
    )

    private data class SlotCandidate(
        val code: String,
        val order: Int?,
        val slot: TimeSlotDef,
    )

    private fun buildSlotTable(
        rows: List<JsonObject>,
        mapping: DeclarationSlotMapping,
    ): SlotTable {
        val candidates = rows.mapNotNull { row ->
            val name = mapping.fields.name
                ?.let { DeclarationPath.stringAt(row, it) }
                .orEmpty()
            if (mapping.excludeNameContains?.let { name.contains(it) } == true) {
                return@mapNotNull null
            }
            val start = parseTime(
                DeclarationPath.stringAt(row, mapping.fields.start),
                mapping.timeFormat,
            ) ?: return@mapNotNull null
            val end = parseTime(
                DeclarationPath.stringAt(row, mapping.fields.end),
                mapping.timeFormat,
            ) ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            SlotCandidate(
                code = DeclarationPath.stringAt(row, mapping.fields.code)?.trim().orEmpty(),
                order = mapping.fields.order?.let { DeclarationPath.intAt(row, it) },
                slot = TimeSlotDef(TimeText.format(start), TimeText.format(end)),
            )
        }.sortedBy { it.order ?: Int.MAX_VALUE }

        val seenCodes = mutableSetOf<String>()
        val slots = mutableListOf<TimeSlotDef>()
        val codeIndex = mutableMapOf<String, Int>()
        for (candidate in candidates) {
            if (candidate.code.isNotBlank() && !seenCodes.add(candidate.code)) continue
            slots += candidate.slot
            if (candidate.code.isNotBlank()) codeIndex[candidate.code] = slots.size
        }
        return SlotTable(slots, codeIndex)
    }

    private fun fixedSlotTable(rows: List<DeclarationFixedSlot>): SlotTable? {
        val slots = rows.map { row ->
            val start = TimeText.normalize(row.start)?.let(TimeText::parseMinutes) ?: return null
            val end = TimeText.normalize(row.end)?.let(TimeText::parseMinutes) ?: return null
            if (end <= start) return null
            TimeSlotDef(TimeText.format(start), TimeText.format(end))
        }
        return SlotTable(slots, emptyMap())
    }

    private fun parseTime(raw: String?, format: String): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return when (format) {
            DeclarationTimetable.TIME_HHMM -> {
                val value = text.toIntOrNull() ?: return null
                val hour = value / 100
                val minute = value % 100
                if (hour !in 0..23 || minute !in 0..59) null else hour * 60 + minute
            }
            DeclarationTimetable.TIME_HMM ->
                TimeText.normalize(text)?.let(TimeText::parseMinutes)
            DeclarationTimetable.TIME_MINUTES ->
                text.toIntOrNull()?.takeIf { it in 0..1439 }
            else -> null
        }
    }

    // ---------- 课程 ----------

    private data class RawCourse(
        val code: String,
        val name: String,
        val teacher: String,
        val location: String,
        val day: Int,
        val startRef: String,
        val endRef: String,
        val weeksRaw: String,
        val note: String,
    )

    private fun rawCourse(row: JsonObject, mapping: DeclarationCourseMapping): RawCourse? {
        val fields = mapping.fields
        val name = DeclarationPath.stringAt(row, fields.name)?.trim().orEmpty()
        if (name.isBlank()) return null
        val day = DeclarationPath.intAt(row, fields.day) ?: return null
        if (day !in 1..7) return null
        return RawCourse(
            code = fields.code?.let { DeclarationPath.stringAt(row, it)?.trim().orEmpty() }.orEmpty(),
            name = name,
            teacher = fields.teacher?.let { DeclarationPath.stringAt(row, it)?.trim().orEmpty() }
                .orEmpty(),
            location = fields.location?.let { DeclarationPath.stringAt(row, it)?.trim().orEmpty() }
                .orEmpty(),
            day = day,
            startRef = DeclarationPath.stringAt(row, fields.startSlot)?.trim().orEmpty(),
            endRef = DeclarationPath.stringAt(row, fields.endSlot)?.trim().orEmpty(),
            weeksRaw = DeclarationPath.stringAt(row, fields.weeks)?.trim().orEmpty(),
            note = fields.note?.let { DeclarationPath.stringAt(row, it)?.trim().orEmpty() }
                .orEmpty(),
        ).takeIf { it.startRef.isNotBlank() && it.endRef.isNotBlank() }
    }

    private fun mergeSameSessions(rows: List<RawCourse>): List<RawCourse> =
        rows.groupBy {
            listOf(it.code, it.name, it.day, it.startRef, it.endRef, it.weeksRaw)
        }.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                group.first().copy(
                    teacher = group.asSequence()
                        .mapNotNull { it.teacher.takeIf(String::isNotBlank) }
                        .flatMap(::splitNames)
                        .distinct()
                        .joinToString("、"),
                    location = group.asSequence()
                        .mapNotNull { it.location.takeIf(String::isNotBlank) }
                        .distinct()
                        .joinToString("；"),
                    note = group.mapNotNull { it.note.takeIf(String::isNotBlank) }.firstOrNull()
                        .orEmpty(),
                )
            }
        }

    private fun splitNames(text: String): List<String> =
        text.split(',', '，', '、', ';', '；', '/')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun mapCourses(
        rows: List<RawCourse>,
        mapping: DeclarationCourseMapping,
        slotTable: SlotTable,
    ): List<CourseEntry> {
        var nextColor = 0
        val colorByName = mutableMapOf<String, CourseColor>()
        return rows.flatMap { row ->
            val start = resolveSlot(row.startRef, mapping.slotRef, slotTable) ?: return@flatMap emptyList()
            val end = resolveSlot(row.endRef, mapping.slotRef, slotTable) ?: return@flatMap emptyList()
            if (end < start) return@flatMap emptyList()
            val weeks = weeksFromBitmap(row.weeksRaw) ?: return@flatMap emptyList()
            val color = colorByName.getOrPut(row.name) {
                val colors = CourseColor.entries
                colors[nextColor++ % colors.size]
            }
            buildList {
                var section = start
                while (section <= end) {
                    val span = minOf(MAX_SPAN, end - section + 1)
                    add(
                        CourseEntry(
                            id = newUuid(),
                            name = row.name,
                            teacher = row.teacher,
                            location = row.location,
                            dayOfWeek = row.day,
                            startSlot = section,
                            span = span,
                            weeks = weeks,
                            color = color,
                            note = row.note,
                        ),
                    )
                    section += span
                }
            }
        }
    }

    private fun resolveSlot(ref: String, slotRef: String, table: SlotTable): Int? =
        when (slotRef) {
            DeclarationTimetable.SLOT_INDEX ->
                ref.toIntOrNull()?.takeIf { it in 1..table.slots.size }
            else -> table.codeIndex[ref]
        }

    private fun weeksFromBitmap(bitmap: String): WeekPattern? {
        if (bitmap.isBlank()) return null
        val weeks = buildList {
            for (index in bitmap.indices) {
                if (bitmap[index] == '1' && index < MAX_WEEKS) add(index + 1)
            }
        }
        return if (weeks.isEmpty()) null else weeksFromSelection(weeks)
    }

    // ---------- 学期 ----------

    private fun parseDate(raw: String?, pattern: String?, zone: String?): IsoDate? {
        if (raw.isNullOrBlank() || pattern.isNullOrBlank()) return null
        val text = raw.trim()
        val zoneId = TimeZone.getTimeZone(
            zone?.takeIf { it.isNotBlank() } ?: "Asia/Shanghai",
        )
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            isLenient = false
            timeZone = zoneId
        }
        val position = ParsePosition(0)
        val parsed = format.parse(text, position) ?: return null
        if (position.index != text.length) return null
        val calendar = Calendar.getInstance(zoneId).apply { timeInMillis = parsed.time }
        return runCatching {
            IsoDate.of(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }.getOrNull()
    }

    private fun String?.toImportError(): ImportError = when (this) {
        "session" -> ImportError.SessionExpired
        "network" -> ImportError.NetworkError
        "busy" -> ImportError.ServiceBusy
        else -> ImportError.FormatChanged
    }
}
