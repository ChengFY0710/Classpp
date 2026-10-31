package com.fangyi.classpp.data

import com.fangyi.classpp.data.export.TodoShareCodec
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.REPEAT_END_COUNT_DEFAULT
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.markRange
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.isDeleted
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.store.TodoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 最近删除的保留天数：从删除时刻起满 30 天自动彻底删除（见 [TodoRepository.purgeExpiredDeleted]） */
const val TRASH_RETENTION_DAYS = 30

/** 回收站保留期的毫秒长度 */
private const val TRASH_RETENTION_MILLIS = TRASH_RETENTION_DAYS * 86_400_000L

/**
 * 待办仓库：内存真源（StateFlow）+ 磁盘持久化（[TodoStore]），与 ScheduleRepository 同构。
 * 同时持有**待办分组**（[TodoFile.groups]）：与待办同文件、同 mutex 原子写入，
 * 删除分组经 [setGroups] 级联把该组待办置回未分组；重命名分组经 [setGroups] 的 renames
 * 映射让该组待办的归属同一次提交跟随到新名。
 *
 * **软删除（最近删除）**：删除统一是软删——[removeTodo] / [removeTodos] 只写
 * [Todo.deletedAtMillis]，条目仍留在 todos.json；读侧按标记分成两个流：
 * [todos] 只发活跃待办（通知、日历、课程详情等既有消费方天然看不到回收站），
 * [deletedTodos] 只发回收站条目。恢复走 [restoreTodo]（清标记重回活跃流），
 * 彻底删除走 [purgeTodo]（才真正从文件移除）。回收站条目另有保留期：删除满
 * [TRASH_RETENTION_DAYS] 天由 [purgeExpiredDeleted] 自动彻底删除（装载时跑一次，
 * 最近删除页打开时再跑一次）。
 *
 * - 读：StateFlow 直接 collect，无 I/O；
 * - 写：Mutex 串行化，流程 = 归一化 → 校验 → `store.save` → 成功才发布到 StateFlow
 *   （落盘先行，失败即内存零变更，天然无回滚问题）。
 *
 * 构造：测试用 [create] 注入 fake store / 时钟；应用内经 [AppContainer] 装配（进程级单例）。
 */
class TodoRepository internal constructor(
    private val store: TodoStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    // canonical in-memory file state（mutex 守护下写入；读走 StateFlow）
    private val fileState = MutableStateFlow(TodoFile())

    // 活跃待办（最近删除之外的全部条目）：通知/日历/课程详情等既有消费方只读这一份
    private val _todos = MutableStateFlow<List<Todo>>(emptyList())
    val todos: StateFlow<List<Todo>> = _todos.asStateFlow()

    // 最近删除（deletedAtMillis 非 null）：仅待办页回收站读取；按删除时刻倒序由 UI 排布
    private val _deletedTodos = MutableStateFlow<List<Todo>>(emptyList())
    val deletedTodos: StateFlow<List<Todo>> = _deletedTodos.asStateFlow()

    // 分组列表（顺序即显示序，置顶项构成连续前缀）：与 todos 同文件、同 commit 原子写入
    private val _groups = MutableStateFlow<List<TodoGroup>>(emptyList())
    val groups: StateFlow<List<TodoGroup>> = _groups.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.Ready)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    // ---------- 生命周期 ----------

    /** 从磁盘载入（阻塞 I/O，经 Dispatchers.IO）；装载时顺带清理超过保留期的回收站条目 */
    private suspend fun bootstrap() {
        val outcome = withContext(Dispatchers.IO) { store.load() }
        _loadState.value = when (outcome.issue) {
            LoadIssue.None -> LoadState.Ready
            LoadIssue.RestoredFromBackup -> LoadState.RestoredFromBackup
            LoadIssue.ResetAfterCorruption -> LoadState.ResetAfterCorruption
        }
        val loaded = when (outcome) {
            is LoadOutcome.Loaded<TodoFile> -> outcome.file
            is LoadOutcome.Fresh<TodoFile> -> outcome.file
        }
        val expiredIds = loaded.expiredDeletedIds(nowMillis())
        if (expiredIds.isEmpty()) {
            publish(loaded)
            return
        }
        // 过期条目不再进内存也不写回磁盘：内存立即隐藏（保留期是硬约束），落盘失败
        // 只影响下次装载时再清一遍——与写路径「落盘先行」相反是有意的例外
        val purged = loaded.copy(todos = loaded.todos.filterNot { it.id in expiredIds })
        publish(purged)
        withContext(Dispatchers.IO) { store.save(purged) }
    }

    /** 回收站里已满保留期（[TRASH_RETENTION_DAYS] 天）的条目 id 集 */
    private fun TodoFile.expiredDeletedIds(now: Long): Set<String> =
        todos.asSequence()
            .filter { todo ->
                val deletedAt = todo.deletedAtMillis
                deletedAt != null && now - deletedAt >= TRASH_RETENTION_MILLIS
            }
            .mapTo(mutableSetOf()) { it.id }

    private fun publish(file: TodoFile) {
        fileState.value = file
        // 单文件双流：活跃/回收站按软删标记分流，写入路径无需在两条列表间搬运条目
        _todos.value = file.todos.filterNot { it.isDeleted }
        _deletedTodos.value = file.todos.filter { it.isDeleted }
        _groups.value = file.groups
    }

    /** 落盘成功才发布内存态 */
    private suspend fun commit(next: TodoFile): TodoOpResult {
        val result = withContext(Dispatchers.IO) { store.save(next) }
        return when (result) {
            StoreResult.Ok -> {
                publish(next)
                TodoOpResult.Ok
            }
            is StoreResult.Failed -> TodoOpResult.Err(TodoError.PersistFailed(result.detail))
        }
    }

    /** 活跃待办按 id 反查（已进回收站的条目对常规写路径不可见，返回 null） */
    private fun todoOrNull(id: String): Todo? =
        fileState.value.todos.firstOrNull { it.id == id && !it.isDeleted }

    /** 回收站条目按 id 反查（恢复/彻底删除专用） */
    private fun deletedTodoOrNull(id: String): Todo? =
        fileState.value.todos.firstOrNull { it.id == id && it.isDeleted }

    /**
     * 写入前归一化：名称/标签/步骤标题 trim、标签去空、日期 distinct + 排序；
     * 完成不变式：completed=true 时 completedAtMillis 缺省补当前，false 时清空；
     * 倒计时不变式：无截止日期时开关回 false（清掉截止日期后不留悬挂的开启态）；
     * 截止随重复不变式：无截止日期或无重复规则时开关回 false（同理不留悬挂开启态），
     * 提前天数非负夹紧；
     * 分组引用完整性：group trim、空串与引用不存在的组一律清回 null（未分组）——
     * 由此读侧只需判 `group == null`，导入的带组名待办同此规则落为未分组；
     * 重复规则消洗见 [normalizeRepeat]、分期集合与迁移见本函数体、逾期归账见 [sweepOverdue]。
     * 在 mutex 内调用，读 [fileState] 的分组表安全。
     */
    private fun normalize(todo: Todo): Todo {
        val groupNames = fileState.value.groups.mapTo(mutableSetOf()) { it.name }
        // 迁移：重复待办不再整条完成——旧的整条完成态折算为「把既有日期计入分期完成集合」，
        // completed 复位后由完成不变式清掉 completedAtMillis
        val migrated = if (todo.repeat.type != RepeatType.None && todo.completed) {
            todo.copy(completed = false, completedOccurrences = todo.completedOccurrences + todo.dates)
        } else {
            todo
        }
        return migrated.copy(
            name = migrated.name.trim(),
            dates = migrated.dates.distinct().sortedBy { it.epochDay },
            repeat = normalizeRepeat(migrated.repeat),
            completedOccurrences = migrated.completedOccurrences.distinct().sortedBy { it.epochDay },
            overdueOccurrences = sweepOverdue(migrated, migrated.dates.minByOrNull { it.epochDay }),
            tags = migrated.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            steps = migrated.steps.map { it.copy(title = it.title.trim()) },
            completedAtMillis = if (migrated.completed) migrated.completedAtMillis ?: nowMillis() else null,
            countdown = migrated.countdown && migrated.deadlineDate != null,
            deadlineBeforeRepeat = migrated.deadlineBeforeRepeat &&
                migrated.deadlineDate != null && migrated.repeat.type != RepeatType.None,
            deadlineBeforeDays = migrated.deadlineBeforeDays.coerceAtLeast(0),
            group = migrated.group?.trim()?.takeIf { it.isNotEmpty() && it in groupNames },
        )
    }

    /**
     * 重复待办的逾期归账（写库路径顺带结算，按集合去重）：应完成日期在今天之前、既不在完成
     * 集合也不在逾期集合的分期收进逾期集合——取消完成不会重复计账（该期早已在集合里），
     * 迟到的补完成也不撤销拖欠（勾选完成时先归账后入集，见 [setOccurrenceCompleted]）。
     * 当天的应完成日期还不算错过（与 [Todo.isOverdue] 同为日期粒度）；整条完成的待办不参与
     * 分期统计。在 mutex 内调用（nowMillis 时钟一致）。
     */
    private fun sweepOverdue(todo: Todo, anchor: IsoDate?): List<IsoDate> {
        val settled = todo.overdueOccurrences
        if (todo.completed || todo.repeat.type == RepeatType.None || anchor == null) return settled
        val known = todo.overdueOccurrences.toSet() + todo.completedOccurrences.toSet()
        val missed = todo.repeat.occurrencesBetween(anchor, anchor, IsoDate.today(nowMillis()))
            .filter { it !in known }
        if (missed.isEmpty()) return settled
        return (settled + missed).distinct().sortedBy { it.epochDay }
    }

    /**
     * 重复规则消洗（导入的外来数据可能越界）：预设档位不带自定义参数——unit/interval/marks
     * 重置为默认值，**类型原样保留**；自定义档位间隔夹紧 1..99、方格标记按单位合法域剔除
     * （天的规则无方格语义，标记恒清空）。结束条件只随重复存在：无重复强制归「永不」，
     * 日期档缺日期回退「永不」，次数档缺次数兜底默认值、越界夹紧 1..99。显示层的
     * [RepeatRule.merge] 不参与周期语义，原样保留。
     */
    private fun normalizeRepeat(rule: RepeatRule): RepeatRule {
        val endType = when {
            rule.type == RepeatType.None -> RepeatEndType.Never
            rule.endType == RepeatEndType.Date && rule.endDate != null -> RepeatEndType.Date
            rule.endType == RepeatEndType.Count -> RepeatEndType.Count
            else -> RepeatEndType.Never
        }
        val base = when (rule.type) {
            RepeatType.Custom -> rule.copy(
                interval = rule.interval.coerceIn(1, 99),
                marks = rule.marks.filterTo(mutableSetOf()) { it in rule.unit.markRange },
            )
            // 预设档位（无/每天/每周/每月/每年）：只留类型，自定义参数一律归默认
            else -> rule.copy(unit = RepeatUnit.Week, interval = 1, marks = emptySet())
        }
        return base.copy(
            endType = endType,
            endDate = if (endType == RepeatEndType.Date) rule.endDate else null,
            endCount = if (endType == RepeatEndType.Count) {
                (rule.endCount ?: REPEAT_END_COUNT_DEFAULT).coerceIn(1, 99)
            } else {
                null
            },
        )
    }

    /**
     * 分组整表替换：新建（追加）、置顶/重排（调用方重排后写回）、删除（滤除）、重命名共用这一个入口。
     *
     * 归一化：name trim、空白丢弃、按名去重（保留首个），并做置顶连续前缀稳定划分
     * （`filter { pinned } + filter { !pinned }`，维持 TodoGroup/GroupCard 契约）。
     * 重命名跟随：[renames]（本次的原名 → 新名映射）在级联**之前**应用到待办的 group——
     * 改名后的新名已在 [newGroups] 里，引用因此同一次 commit 原子跟随，不会因旧名不在
     * 新表而被误级联成未分组（改名丢归属）；映射外的引用照常走级联规则。
     * 级联：本次被移除的组（含被删组），其待办的 group 一并清回 null——与分组表**同一次
     * commit 原子落盘**，不会出现悬挂引用。失败内存零变更（同其余写 API）。
     */
    suspend fun setGroups(
        newGroups: List<TodoGroup>,
        renames: Map<String, String> = emptyMap(),
    ): TodoOpResult = mutex.withLock {
        val normalized = normalizeGroups(newGroups)
        val kept = normalized.mapTo(mutableSetOf()) { it.name }
        val current = fileState.value
        // 重命名跟随 + 级联 = 对每条待办做一次分组引用归一化（trim + 重命名映射 + 成员检查，
        // 同 normalize 的 group 规则）
        val cascaded = current.todos.map { todo ->
            val group = todo.group?.trim()
                ?.let { renames[it] ?: it }
                ?.takeIf { it in kept }
            if (group != todo.group) todo.copy(group = group) else todo
        }
        commit(current.copy(groups = normalized, todos = cascaded))
    }

    /** 分组归一化：trim → 空白丢弃 → 按名去重 → 置顶连续前缀稳定划分（见 [setGroups]） */
    private fun normalizeGroups(groups: List<TodoGroup>): List<TodoGroup> {
        val cleaned = groups
            .map { it.copy(name = it.name.trim()) }
            .filter { it.name.isNotEmpty() }
            .distinctBy { it.name }
        return cleaned.filter { it.pinned } + cleaned.filter { !it.pinned }
    }

    // ---------- 待办 CRUD ----------

    /** 新增待办：id 空则生成、createdAt 缺省（0）补当前时刻；归一化 + 校验后追加 */
    suspend fun addTodo(todo: Todo): TodoReadResult<String> = mutex.withLock {
        val prepared = normalize(
            todo.copy(
                id = todo.id.ifBlank { newUuid() },
                createdAtMillis = if (todo.createdAtMillis == 0L) nowMillis() else todo.createdAtMillis,
            ),
        )
        val errors = TodoValidator.validate(prepared)
        if (errors.isNotEmpty()) return@withLock TodoReadResult.Err(errors.first())
        if (fileState.value.todos.any { it.id == prepared.id }) {
            return@withLock TodoReadResult.Err(TodoError.DuplicateId(prepared.id))
        }
        when (val r = commit(fileState.value.copy(todos = fileState.value.todos + prepared))) {
            TodoOpResult.Ok -> TodoReadResult.Ok(prepared.id)
            is TodoOpResult.Err -> TodoReadResult.Err(r.error)
        }
    }

    /**
     * 批量新增（课程中心等外部同步导入走这条）：id/createdAt 补齐 → 全量归一化 → 逐条
     * 校验 → 查重 → **单次 commit**（导入 N 条只落盘一次，区别于逐条 addTodo）。
     * 任意一条非法或 id 与现有待办重复则整体失败、零落盘——同步层应先过滤非法项与
     * 已存在项（active + 回收站）再调用。
     */
    suspend fun addTodos(todos: List<Todo>): TodoReadResult<List<String>> = mutex.withLock {
        if (todos.isEmpty()) return@withLock TodoReadResult.Ok(emptyList())
        val prepared = todos.map {
            normalize(
                it.copy(
                    id = it.id.ifBlank { newUuid() },
                    createdAtMillis = if (it.createdAtMillis == 0L) nowMillis() else it.createdAtMillis,
                ),
            )
        }
        prepared.forEach { todo ->
            val errors = TodoValidator.validate(todo)
            if (errors.isNotEmpty()) return@withLock TodoReadResult.Err(errors.first())
        }
        val existing = fileState.value.todos.mapTo(mutableSetOf()) { it.id }
        prepared.firstOrNull { it.id in existing }?.let {
            return@withLock TodoReadResult.Err(TodoError.DuplicateId(it.id))
        }
        when (val r = commit(fileState.value.copy(todos = fileState.value.todos + prepared))) {
            TodoOpResult.Ok -> TodoReadResult.Ok(prepared.map { it.id })
            is TodoOpResult.Err -> TodoReadResult.Err(r.error)
        }
    }

    /** 按 id 全量替换（编辑表单提交走这条）；归一化 + 校验后落盘，失败内存零变更 */
    suspend fun updateTodo(todo: Todo): TodoOpResult = mutex.withLock {
        if (todoOrNull(todo.id) == null) return@withLock TodoOpResult.Err(TodoError.NotFound(todo.id))
        val prepared = normalize(todo)
        val errors = TodoValidator.validate(prepared)
        if (errors.isNotEmpty()) return@withLock TodoOpResult.Err(errors.first())
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == prepared.id) prepared else it }))
    }

    /**
     * 删除待办 = 移入最近删除（软删）：只写 [Todo.deletedAtMillis]，条目留在 todos.json。
     * 活跃列表即时移除、回收站流即时出现；已删除或不存在返回 NotFound。
     */
    suspend fun removeTodo(id: String): TodoOpResult = mutex.withLock {
        if (todoOrNull(id) == null) return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val deletedAt = nowMillis()
        commit(
            fileState.value.copy(
                todos = fileState.value.todos.map {
                    if (it.id == id) it.copy(deletedAtMillis = deletedAt) else it
                },
            ),
        )
    }

    /** 批量软删除；未知 id 宽容忽略（批量语义，同 removeCourses），已在回收站的不重复打标 */
    suspend fun removeTodos(ids: List<String>): TodoOpResult = mutex.withLock {
        val drop = ids.toSet()
        val deletedAt = nowMillis()
        commit(
            fileState.value.copy(
                todos = fileState.value.todos.map {
                    if (it.id in drop && !it.isDeleted) it.copy(deletedAtMillis = deletedAt) else it
                },
            ),
        )
    }

    /**
     * 从最近删除恢复：清 [Todo.deletedAtMillis] 后重回活跃列表。
     * 原分组 / 置顶 / 完成态一并保留；分组若在此期间被删，[normalize] 的引用完整性会
     * 把 group 兜底回未分组。不在回收站（不存在或仍活跃）返回 NotFound。
     */
    suspend fun restoreTodo(id: String): TodoOpResult = mutex.withLock {
        val target = deletedTodoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val restored = normalize(target.copy(deletedAtMillis = null))
        val errors = TodoValidator.validate(restored)
        if (errors.isNotEmpty()) return@withLock TodoOpResult.Err(errors.first())
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == id) restored else it }))
    }

    /**
     * 彻底删除（仅最近删除页的侧滑动作走这条）：才真正从 todos.json 移除、不可恢复。
     * 只允许删回收站里的条目——活跃条目误调返回 NotFound，避免绕过软删直接毁数据。
     */
    suspend fun purgeTodo(id: String): TodoOpResult = mutex.withLock {
        if (deletedTodoOrNull(id) == null) return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        commit(fileState.value.copy(todos = fileState.value.todos.filterNot { it.id == id }))
    }

    /**
     * 批量彻底删除（仅回收站条目）：一次 commit 完成，未知/活跃 id 宽容忽略。
     * 与 [purgeTodo] 同语义，供外部同步的「退出并删除」等批量场景使用——
     * 逐条 purge 会每条全量写一次 todos.json，条目多时落盘次数与中间态都不可接受。
     */
    suspend fun purgeTodos(ids: Collection<String>): TodoOpResult = mutex.withLock {
        val drop = ids.toSet()
        val remaining = fileState.value.todos.filterNot { it.id in drop && it.isDeleted }
        if (remaining.size == fileState.value.todos.size) return@withLock TodoOpResult.Ok
        commit(fileState.value.copy(todos = remaining))
    }

    /**
     * 清理最近删除里已满保留期（[TRASH_RETENTION_DAYS] 天）的条目：真正从 todos.json 移除，
     * 不可恢复。装载时由 [bootstrap] 自动跑一次；最近删除页打开时再跑一次，补上应用装载后
     * 新到期的条目（应用长时间驻留时不重新装载也能按时清掉）。没有到期条目时不写盘。
     */
    suspend fun purgeExpiredDeleted(): TodoOpResult = mutex.withLock {
        val expiredIds = fileState.value.expiredDeletedIds(nowMillis())
        if (expiredIds.isEmpty()) return@withLock TodoOpResult.Ok
        commit(fileState.value.copy(todos = fileState.value.todos.filterNot { it.id in expiredIds }))
    }

    /**
     * 切换完成状态并维护 completedAtMillis；其余字段不动。
     * 重复待办的列表勾选不走这里（经 [setOccurrenceCompleted] 逐期结算）；若被误用，
     * 整条完成态由归一化迁移折算成把既有日期计入分期完成集合。
     */
    suspend fun setCompleted(id: String, completed: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val updated = normalize(target.copy(completed = completed))
        val errors = TodoValidator.validate(updated)
        if (errors.isNotEmpty()) return@withLock TodoOpResult.Err(errors.first())
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == id) updated else it }))
    }

    /**
     * 重复待办的分期勾选（列表里分期卡片的完成/取消完成）：向 [Todo.completedOccurrences]
     * 增删该期日期。勾选完成先跑一次逾期归账（用旧集合——迟到的补完成仍计拖欠），再入集；
     * 取消完成直接出集（该期若已逾期早已归账，集合语义天然不会重复计账）。
     * 待办不存在返回 NotFound；重复增删（无变化）直接 Ok。
     */
    suspend fun setOccurrenceCompleted(id: String, date: IsoDate, completed: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val current = target.completedOccurrences
        val prepared = if (completed) {
            if (date in current) return@withLock TodoOpResult.Ok
            normalize(
                target.copy(
                    completedOccurrences = (current + date).distinct().sortedBy { it.epochDay },
                    overdueOccurrences = sweepOverdue(target, target.dates.minByOrNull { it.epochDay }),
                ),
            )
        } else {
            if (date !in current) return@withLock TodoOpResult.Ok
            normalize(target.copy(completedOccurrences = current.filter { it != date }))
        }
        val errors = TodoValidator.validate(prepared)
        if (errors.isNotEmpty()) return@withLock TodoOpResult.Err(errors.first())
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == id) prepared else it }))
    }

    /** 切换置顶状态；其余字段不动（置顶条目归列表「已置顶」组，移动语义不与其余组重复） */
    suspend fun setPinned(id: String, pinned: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val updated = normalize(target.copy(pinned = pinned))
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == id) updated else it }))
    }

    /**
     * 开关倒计时（待办列表卡在截止时间左侧显示剩余天数）；其余字段不动。
     * 无截止日期时开启会被 [normalize] 的倒计时不变式归一化回 false（调用侧本就不该发这种请求）。
     */
    suspend fun setCountdown(id: String, enabled: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val updated = normalize(target.copy(countdown = enabled))
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == id) updated else it }))
    }

    /** 勾选/取消一条步骤；步骤不存在返回 NotFound */
    suspend fun setStepDone(todoId: String, stepId: String, done: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(todoId) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(todoId))
        if (target.steps.none { it.id == stepId }) {
            return@withLock TodoOpResult.Err(TodoError.NotFound(stepId))
        }
        val updated = target.copy(steps = target.steps.map { if (it.id == stepId) it.copy(done = done) else it })
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == todoId) updated else it }))
    }

    /**
     * 追加一条步骤（标题 trim 后追加，id 现铸，同 [TodoShareCodec] 的步骤 id 规则）；
     * 标题 trim 后为空返回 [TodoError.StepInvalid]（同校验器的 BlankTitle 规则），
     * 待办不存在返回 NotFound。
     */
    suspend fun addStep(todoId: String, title: String): TodoOpResult = mutex.withLock {
        val target = todoOrNull(todoId) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(todoId))
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            return@withLock TodoOpResult.Err(
                TodoError.StepInvalid(target.steps.size, TodoStepReason.BlankTitle),
            )
        }
        val updated = target.copy(steps = target.steps + TodoStep(id = newUuid(), title = trimmed))
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == todoId) updated else it }))
    }

    /** 删除一条步骤（侧滑删除走这条）；待办或步骤不存在返回 NotFound */
    suspend fun removeStep(todoId: String, stepId: String): TodoOpResult = mutex.withLock {
        val target = todoOrNull(todoId) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(todoId))
        if (target.steps.none { it.id == stepId }) {
            return@withLock TodoOpResult.Err(TodoError.NotFound(stepId))
        }
        val updated = target.copy(steps = target.steps.filterNot { it.id == stepId })
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == todoId) updated else it }))
    }

    // ---------- 标签批量回写 ----------

    /**
     * 标签重命名的待办侧回写：把**全部**待办（含回收站条目，恢复后引用才不悬空）tags 里的
     * [from] 替换为 [to]。与 [TagRepository.renameTag] 配对使用（标签库先落、待办随后回写，
     * 两个文件无法同一次提交原子落盘）；调用方保证 [to] trim 非空且不与其它标签重名，空名
     * 宽容为无操作（批量语义，同 [removeTodos] 对未知 id 的处理）。
     *
     * 不逐条 [normalize]：tags 每次写入已归一化（trim/去空/去重），替换值为合法标签名后仍
     * 归一化，distinct 兜住「待办同时挂 from 与 to」时替换后叠加重复的情况；绕开 normalize
     * 也避免顺带触发重复待办的逾期归账等无关结算。
     */
    suspend fun renameTagInTodos(from: String, to: String): TodoOpResult = mutex.withLock {
        val prepared = to.trim()
        if (prepared.isEmpty() || from == prepared) return@withLock TodoOpResult.Ok
        commit(
            fileState.value.copy(
                todos = fileState.value.todos.map { todo ->
                    if (from !in todo.tags) {
                        todo
                    } else {
                        todo.copy(tags = todo.tags.map { if (it == from) prepared else it }.distinct())
                    }
                },
            ),
        )
    }

    /**
     * 删除标签的待办侧回写：从**全部**待办（含回收站条目）tags 里摘除 [names]——只解绑
     * 标签，待办本身保留。与 [TagRepository.setTags]（整表滤除）配对使用（顺序同
     * [renameTagInTodos]）；未知标签宽容为无操作（批量语义）。
     */
    suspend fun removeTagsFromTodos(names: Collection<String>): TodoOpResult = mutex.withLock {
        val drop = names.toSet()
        commit(
            fileState.value.copy(
                todos = fileState.value.todos.map { todo ->
                    if (todo.tags.none { it in drop }) {
                        todo
                    } else {
                        todo.copy(tags = todo.tags.filterNot { it in drop })
                    }
                },
            ),
        )
    }

    // ---------- 分享 ----------

    /** 导出全部**活跃**待办为 pretty JSON 文本（分享载荷）；最近删除不随分享导出 */
    suspend fun exportTodos(): TodoReadResult<String> {
        val current = fileState.value
        val live = current.todos.filterNot { it.isDeleted }
        return TodoReadResult.Ok(TodoShareCodec.encode(live, nowMillis()))
    }

    /**
     * 导入分享 JSON 并**追加**为本地待办：解析 → 版本/kind 闸门 → id 全量重铸
     * （todo 与 step，保证与本机零冲突）→ 逐条校验 → 全部追加。任一条非法整体拒绝。
     * 重名允许（名称不是唯一键）；createdAt 缺失（0）补当前时刻。
     */
    suspend fun importTodos(json: String): TodoReadResult<Int> = mutex.withLock {
        val imported = when (val r = TodoShareCodec.decodeForImport(json)) {
            is TodoReadResult.Err -> return@withLock TodoReadResult.Err(r.error)
            is TodoReadResult.Ok -> r.value
        }
        val prepared = imported.map { todo ->
            normalize(
                if (todo.createdAtMillis == 0L) todo.copy(createdAtMillis = nowMillis()) else todo,
            )
        }
        when (val r = commit(fileState.value.copy(todos = fileState.value.todos + prepared))) {
            TodoOpResult.Ok -> TodoReadResult.Ok(prepared.size)
            is TodoOpResult.Err -> TodoReadResult.Err(r.error)
        }
    }

    companion object {
        /** 构建并从磁盘载入（阻塞 I/O 经 Dispatchers.IO） */
        suspend fun create(
            store: TodoStore,
            nowMillis: () -> Long = System::currentTimeMillis,
        ): TodoRepository = TodoRepository(store, nowMillis).also { it.bootstrap() }
    }
}
