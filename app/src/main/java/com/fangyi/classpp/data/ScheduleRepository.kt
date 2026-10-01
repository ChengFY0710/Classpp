package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.CourseEntry
import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.ScheduleFile
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.TimeText
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.ScheduleStore
import com.fangyi.classpp.data.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 某门课在某周课表中的一次呈现 */
data class CourseOccurrence(
    val course: CourseEntry,
    /** 该周是否真上课（false = 本周不上，UI 按 showInactiveCourses 决定灰显或隐藏） */
    val active: Boolean,
)

/**
 * 课表仓库：内存真源（StateFlow）+ 磁盘持久化（[ScheduleStore]）。
 *
 * - 读：StateFlow 直接 collect，无 I/O；
 * - 写：Mutex 串行化，流程 = 校验 → `store.save` → 成功才发布到 StateFlow
 *   （落盘先行，失败即内存零变更，天然无回滚问题）；
 * - 设置变更若波及已有课程：整体拒绝返回 [ScheduleError.CoursesOutOfRange]，绝不静默删课。
 *
 * 构造：测试用 [create] 注入 fake store / FakeClock；应用内用 [get] 进程级单例。
 */
class ScheduleRepository internal constructor(
    private val store: ScheduleStore,
    private val clock: Clock = Clock.system,
) {
    private val mutex = Mutex()

    // canonical in-memory file state（mutex 守护下写入；读走 StateFlow）
    private val fileState = MutableStateFlow(ScheduleFile())

    private val _schedules = MutableStateFlow<List<Schedule>>(emptyList())
    val schedules: StateFlow<List<Schedule>> = _schedules.asStateFlow()

    private val _activeScheduleId = MutableStateFlow<String?>(null)
    val activeScheduleId: StateFlow<String?> = _activeScheduleId.asStateFlow()

    private val _activeSchedule = MutableStateFlow<Schedule?>(null)
    val activeSchedule: StateFlow<Schedule?> = _activeSchedule.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.Ready)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    // ---------- 生命周期 ----------

    /** 从磁盘载入并修复悬挂的 activeScheduleId（阻塞 I/O，经 Dispatchers.IO） */
    private suspend fun bootstrap() {
        val outcome = withContext(Dispatchers.IO) { store.load() }
        _loadState.value = when (outcome.issue) {
            LoadIssue.None -> LoadState.Ready
            LoadIssue.RestoredFromBackup -> LoadState.RestoredFromBackup
            LoadIssue.ResetAfterCorruption -> LoadState.ResetAfterCorruption
        }
        val file = when (outcome) {
            is LoadOutcome.Loaded -> outcome.file
            is LoadOutcome.Fresh -> outcome.file
        }
        val activeExists = file.activeScheduleId != null &&
            file.schedules.any { it.id == file.activeScheduleId }
        publish(
            if (file.activeScheduleId != null && !activeExists) {
                file.copy(activeScheduleId = file.schedules.firstOrNull()?.id)
            } else {
                file
            },
        )
    }

    private fun publish(file: ScheduleFile) {
        fileState.value = file
        _schedules.value = file.schedules
        _activeScheduleId.value = file.activeScheduleId
        _activeSchedule.value = file.schedules.firstOrNull { it.id == file.activeScheduleId }
    }

    /** 落盘成功才发布内存态 */
    private suspend fun commit(next: ScheduleFile): OpResult {
        val result = withContext(Dispatchers.IO) { store.save(next) }
        return when (result) {
            StoreResult.Ok -> {
                publish(next)
                OpResult.Ok
            }
            is StoreResult.Failed -> OpResult.Err(ScheduleError.PersistFailed(result.detail))
        }
    }

    private fun scheduleOrNull(id: String): Schedule? =
        fileState.value.schedules.firstOrNull { it.id == id }

    // ---------- 课表 CRUD ----------

    /** 新建课表；无激活课表时自动设为激活。Ok 的值为新课表 id */
    suspend fun createSchedule(
        name: String,
        termStart: IsoDate,
        termEnd: IsoDate,
        daysPerWeek: Int = 5,
        slots: List<TimeSlotDef> = DEFAULT_SLOTS,
    ): ReadResult<String> = mutex.withLock {
        val id = newUuid()
        val schedule = Schedule(
            id = id,
            name = name,
            termStart = termStart,
            termEnd = termEnd,
            daysPerWeek = daysPerWeek,
            slots = normalizeSlots(slots),
        )
        val errors = ScheduleValidator.validate(schedule)
        if (errors.isNotEmpty()) return@withLock ReadResult.Err(errors.first())

        val current = fileState.value
        val next = current.copy(
            schedules = current.schedules + schedule,
            activeScheduleId = current.activeScheduleId ?: id,
        )
        when (val r = commit(next)) {
            OpResult.Ok -> ReadResult.Ok(id)
            is OpResult.Err -> ReadResult.Err(r.error)
        }
    }

    suspend fun renameSchedule(id: String, name: String): OpResult = mutex.withLock {
        val current = fileState.value
        val target = scheduleOrNull(id) ?: return@withLock OpResult.Err(ScheduleError.NotFound(id))
        val updated = target.copy(name = name)
        val errors = ScheduleValidator.validate(updated)
        if (errors.isNotEmpty()) return@withLock OpResult.Err(errors.first())
        commit(current.replace(target, updated))
    }

    /** 删除课表；删激活项则落到剩余第一份，删空则为 null。文件保留 */
    suspend fun deleteSchedule(id: String): OpResult = mutex.withLock {
        val current = fileState.value
        if (current.schedules.none { it.id == id }) {
            return@withLock OpResult.Err(ScheduleError.NotFound(id))
        }
        val remaining = current.schedules.filterNot { it.id == id }
        val newActive = if (current.activeScheduleId == id) remaining.firstOrNull()?.id
        else current.activeScheduleId
        commit(current.copy(schedules = remaining, activeScheduleId = newActive))
    }

    suspend fun setActiveSchedule(id: String): OpResult = mutex.withLock {
        if (scheduleOrNull(id) == null) return@withLock OpResult.Err(ScheduleError.NotFound(id))
        commit(fileState.value.copy(activeScheduleId = id))
    }

    // ---------- 设置变更（波及课程则整体拒绝） ----------

    suspend fun setTerm(id: String, start: IsoDate, end: IsoDate): OpResult = mutex.withLock {
        val target = scheduleOrNull(id) ?: return@withLock OpResult.Err(ScheduleError.NotFound(id))
        val updated = target.copy(termStart = start, termEnd = end)
        val errors = ScheduleValidator.validate(updated)
        if (errors.isNotEmpty()) return@withLock OpResult.Err(rejection(updated, errors))
        commit(fileState.value.replace(target, updated))
    }

    /**
     * 切换每周天数。termEnd 允许停在周中，但必须落在新天数的列范围内，故先只收不放地把
     * 结束日收进"该周最后一个教学日"（7→5 时周日 → 同周周五；5→7 时保持不动），再整体校验。
     *
     * 被拒的两种情形都归并成 [ScheduleError.CoursesOutOfRange]（见 [rejection]），**绝不静默删课**：
     *  - 周六/周日仍有课 → [ScheduleError.DayOutOfWeek]：5 天视图没有这两列；
     *  - 末周被收掉后越界的课 → [ScheduleError.WeeksBeyondTerm]：结束日提前了，有些课没了落点。
     */
    suspend fun setDaysPerWeek(id: String, days: Int): OpResult = mutex.withLock {
        val target = scheduleOrNull(id) ?: return@withLock OpResult.Err(ScheduleError.NotFound(id))
        // 只收不放：结束日已在范围内时保持用户选的那一天不动
        val clampedEnd = target.termEnd.lastTeachingDayOnOrBefore(days)
        val updated = target.copy(daysPerWeek = days, termEnd = clampedEnd)
        val errors = ScheduleValidator.validate(updated)
        if (errors.isNotEmpty()) return@withLock OpResult.Err(rejection(updated, errors))
        commit(fileState.value.replace(target, updated))
    }

    suspend fun setSlots(id: String, slots: List<TimeSlotDef>): OpResult = mutex.withLock {
        val target = scheduleOrNull(id) ?: return@withLock OpResult.Err(ScheduleError.NotFound(id))
        val updated = target.copy(slots = normalizeSlots(slots))
        val errors = ScheduleValidator.validate(updated)
        if (errors.isNotEmpty()) return@withLock OpResult.Err(rejection(updated, errors))
        commit(fileState.value.replace(target, updated))
    }

    suspend fun setShowInactiveCourses(id: String, show: Boolean): OpResult = mutex.withLock {
        val target = scheduleOrNull(id) ?: return@withLock OpResult.Err(ScheduleError.NotFound(id))
        commit(fileState.value.replace(target, target.copy(showInactiveCourses = show)))
    }

    /**
     * 校验失败的归类：设置本身非法 → 返回首个设置错误；
     * 仅课程受影响 → [ScheduleError.CoursesOutOfRange] 携带受影响课程引用（零变更）。
     */
    private fun rejection(target: Schedule, errors: List<ScheduleError>): ScheduleError {
        val settingsErrors = ScheduleValidator.validateSettingsOnly(target)
        if (settingsErrors.isNotEmpty()) return settingsErrors.first()

        val affectedIds = buildSet {
            for (e in errors) {
                when (e) {
                    is ScheduleError.CourseFieldInvalid -> add(e.courseId)
                    is ScheduleError.WeekSegmentInvalid -> add(e.courseId)
                    is ScheduleError.WeeksBeyondTerm -> add(e.courseId)
                    // 切天数时周六/周日的课没了列、末周被收掉后越界的课：都归到具体课程上
                    is ScheduleError.DayOutOfWeek -> add(e.courseId)
                    is ScheduleError.TermEndInvalid -> add(e.courseId)
                    is ScheduleError.GridConflict -> {
                        add(e.a.id); add(e.b.id)
                    }
                    else -> Unit
                }
            }
        }
        val affected = target.courses.filter { it.id in affectedIds }.map { it.toRef() }
        return if (affected.isEmpty()) errors.first()
        else ScheduleError.CoursesOutOfRange(affected)
    }

    // ---------- 课程 CRUD ----------

    /** 新增或按 id 覆盖课程；id 为空则由仓库生成。提交前做全量校验（含同格周数重叠） */
    suspend fun upsertCourse(scheduleId: String, course: CourseEntry): OpResult = mutex.withLock {
        val target = scheduleOrNull(scheduleId)
            ?: return@withLock OpResult.Err(ScheduleError.NotFound(scheduleId))
        val withId = if (course.id.isBlank()) course.copy(id = newUuid()) else course
        val courses = if (target.courses.any { it.id == withId.id }) {
            target.courses.map { if (it.id == withId.id) withId else it }
        } else {
            target.courses + withId
        }
        val updated = target.copy(courses = courses)
        val errors = ScheduleValidator.validateCourses(updated)
        if (errors.isNotEmpty()) return@withLock OpResult.Err(errors.first())
        commit(fileState.value.replace(target, updated))
    }

    suspend fun removeCourse(scheduleId: String, courseId: String): OpResult = mutex.withLock {
        val target = scheduleOrNull(scheduleId)
            ?: return@withLock OpResult.Err(ScheduleError.NotFound(scheduleId))
        if (target.courses.none { it.id == courseId }) {
            return@withLock OpResult.Err(ScheduleError.NotFound(courseId))
        }
        commit(
            fileState.value.replace(
                target,
                target.copy(courses = target.courses.filterNot { it.id == courseId }),
            ),
        )
    }

    /** 批量删除；未知 id 宽容忽略（批量语义） */
    suspend fun removeCourses(scheduleId: String, courseIds: List<String>): OpResult = mutex.withLock {
        val target = scheduleOrNull(scheduleId)
            ?: return@withLock OpResult.Err(ScheduleError.NotFound(scheduleId))
        val drop = courseIds.toSet()
        commit(fileState.value.replace(target, target.copy(courses = target.courses.filterNot { it.id in drop })))
    }

    /**
     * 整份替换课程列表（编辑态保存走这条）：单次校验 + 单次提交，草稿要么整体生效要么原样不动。
     *
     * 不能用逐条 [upsertCourse]/[removeCourse] 拼出同样的结果——每条都拿"当时的仓库"做全量校验，
     * 中间态一旦非法就整轮失败，而合法的草稿经常要经过非法中间态：例如把原课周数缩成单周、
     * 同格再加一门双周的交替课，先写新课那一刻原课还占着全部周数，于是报出一场最终并不存在的冲突。
     *
     * id 由调用方保证（草稿条目都带 id）。与其它 mutator 一致：失败时内存态零变更。
     */
    suspend fun replaceCourses(scheduleId: String, courses: List<CourseEntry>): OpResult = mutex.withLock {
        val target = scheduleOrNull(scheduleId)
            ?: return@withLock OpResult.Err(ScheduleError.NotFound(scheduleId))
        val updated = target.copy(courses = courses)
        val errors = ScheduleValidator.validateCourses(updated)
        if (errors.isNotEmpty()) return@withLock OpResult.Err(errors.first())
        commit(fileState.value.replace(target, updated))
    }

    // ---------- 按周查询（替代 mock 的 findAt/datesForWeek） ----------

    /** 第 [week] 周全部课程及活跃标记；课表不存在或周越界返回空（宽松读，不抛错） */
    fun coursesForWeek(scheduleId: String, week: Int): List<CourseOccurrence> {
        val s = scheduleOrNull(scheduleId) ?: return emptyList()
        if (week !in 1..s.totalWeeks) return emptyList()
        return s.courses.map { CourseOccurrence(it, active = it.weeks.contains(week)) }
    }

    /** 占据 (day, slot) 格子的课程；span 覆盖该节即命中，含本周不活跃的（灰显候选） */
    fun courseAt(scheduleId: String, week: Int, day: Int, slot: Int): CourseOccurrence? =
        coursesForWeek(scheduleId, week).firstOrNull {
            it.course.dayOfWeek == day && slot in it.course.startSlot..it.course.endSlot
        }

    /**
     * 按课表开关过滤后的可见课程：
     * `showInactiveCourses=false` 时剔除本周不上的课；true 则全部返回（UI 置灰 #cbcbcb/#737a83）。
     */
    fun visibleCoursesForWeek(scheduleId: String, week: Int): List<CourseOccurrence> {
        val s = scheduleOrNull(scheduleId) ?: return emptyList()
        val all = coursesForWeek(scheduleId, week)
        return if (s.showInactiveCourses) all else all.filter { it.active }
    }

    /** 第 [week] 周各上课日日期（5 天模式 5 个、7 天模式 7 个）；越界返回空 */
    fun datesForWeek(scheduleId: String, week: Int): List<IsoDate> {
        val s = scheduleOrNull(scheduleId) ?: return emptyList()
        if (week !in 1..s.totalWeeks) return emptyList()
        val base = s.termStart + (week - 1) * 7
        return (0 until s.daysPerWeek).map { base + it }
    }

    // ---------- 当前周 ----------

    /** [today] 相对学期位置；课表不存在返回 null。不钳制、不抛错 */
    fun termPosition(scheduleId: String, today: IsoDate = clock.today()): TermPosition? {
        val s = scheduleOrNull(scheduleId) ?: return null
        val diff = today - s.termStart
        return when {
            diff < 0 -> TermPosition.BeforeTerm
            diff > s.termEnd - s.termStart -> TermPosition.AfterTerm
            else -> TermPosition.InTerm((diff / 7).toInt() + 1)
        }
    }

    /** 学期内的当前周号；学期外或课表不存在为 null */
    fun currentWeek(scheduleId: String): Int? =
        (termPosition(scheduleId) as? TermPosition.InTerm)?.week

    // ---------- 分享 ----------

    /** 导出为 pretty JSON 文本（分享载荷），供系统分享面板发送 */
    suspend fun exportSchedule(id: String): ReadResult<String> {
        val s = scheduleOrNull(id) ?: return ReadResult.Err(ScheduleError.NotFound(id))
        return ReadResult.Ok(ShareCodec.encode(s, System.currentTimeMillis()))
    }

    /**
     * 导入分享 JSON 并保存为**新**课表：解析 → 版本/kind 闸门 → id 全量重铸 → 全量校验 →
     * 名称去重后缀 → 落盘。不切换激活课表（不劫持当前视图）。
     */
    suspend fun importSchedule(json: String): ReadResult<String> = mutex.withLock {
        val imported = when (val r = ShareCodec.decodeForImport(json)) {
            is ReadResult.Err -> return@withLock ReadResult.Err(r.error)
            is ReadResult.Ok -> r.value
        }
        val current = fileState.value
        val name = dedupName(imported.name, current.schedules.map { it.name }.toSet())
        val saved = imported.copy(name = name)
        val next = current.copy(schedules = current.schedules + saved)
        when (val r = commit(next)) {
            OpResult.Ok -> ReadResult.Ok(saved.id)
            is OpResult.Err -> ReadResult.Err(r.error)
        }
    }

    private fun dedupName(name: String, existing: Set<String>): String {
        if (name !in existing) return name
        var candidate = "$name (导入)"
        var i = 2
        while (candidate in existing) {
            candidate = "$name (导入 $i)"
            i++
        }
        return candidate
    }

    // ---------- 工具 ----------

    /** 时间归一化：`08:00` → `8:00`；非法文本原样保留（交由校验报错） */
    private fun normalizeSlots(slots: List<TimeSlotDef>): List<TimeSlotDef> = slots.map {
        TimeSlotDef(
            startTime = TimeText.normalize(it.startTime) ?: it.startTime,
            endTime = TimeText.normalize(it.endTime) ?: it.endTime,
        )
    }

    private fun ScheduleFile.replace(old: Schedule, new: Schedule): ScheduleFile =
        copy(schedules = schedules.map { if (it.id == old.id) new else it })

    companion object {
        /** 构建并从磁盘载入（阻塞 I/O 经 Dispatchers.IO） */
        suspend fun create(
            store: ScheduleStore,
            clock: Clock = Clock.system,
        ): ScheduleRepository = ScheduleRepository(store, clock).also { it.bootstrap() }

        private val instanceMutex = Mutex()
        private var instance: ScheduleRepository? = null

        /** 应用内进程级单例（首次调用完成磁盘载入） */
        suspend fun get(context: Context): ScheduleRepository {
            instance?.let { return it }
            return instanceMutex.withLock {
                instance ?: create(FileScheduleStore(context.filesDir)).also { instance = it }
            }
        }
    }
}
