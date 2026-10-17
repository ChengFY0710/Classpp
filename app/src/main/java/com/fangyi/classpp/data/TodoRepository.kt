package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.export.TodoShareCodec
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.RepeatUnit
import com.fangyi.classpp.data.model.markRange
import com.fangyi.classpp.data.model.occurrencesBetween
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoStep
import com.fangyi.classpp.data.model.newUuid
import com.fangyi.classpp.data.store.FileTodoStore
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

/**
 * 待办仓库：内存真源（StateFlow）+ 磁盘持久化（[TodoStore]），与 ScheduleRepository 同构。
 * 同时持有**待办分组**（[TodoFile.groups]）：与待办同文件、同 mutex 原子写入，
 * 删除分组经 [setGroups] 级联把该组待办置回未分组。
 *
 * - 读：StateFlow 直接 collect，无 I/O；
 * - 写：Mutex 串行化，流程 = 归一化 → 校验 → `store.save` → 成功才发布到 StateFlow
 *   （落盘先行，失败即内存零变更，天然无回滚问题）。
 *
 * 构造：测试用 [create] 注入 fake store / 时钟；应用内用 [get] 进程级单例。
 */
class TodoRepository internal constructor(
    private val store: TodoStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    // canonical in-memory file state（mutex 守护下写入；读走 StateFlow）
    private val fileState = MutableStateFlow(TodoFile())

    private val _todos = MutableStateFlow<List<Todo>>(emptyList())
    val todos: StateFlow<List<Todo>> = _todos.asStateFlow()

    // 分组列表（顺序即显示序，置顶项构成连续前缀）：与 todos 同文件、同 commit 原子写入
    private val _groups = MutableStateFlow<List<TodoGroup>>(emptyList())
    val groups: StateFlow<List<TodoGroup>> = _groups.asStateFlow()

    private val _loadState = MutableStateFlow(LoadState.Ready)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    // ---------- 生命周期 ----------

    /** 从磁盘载入（阻塞 I/O，经 Dispatchers.IO） */
    private suspend fun bootstrap() {
        val outcome = withContext(Dispatchers.IO) { store.load() }
        _loadState.value = when (outcome.issue) {
            LoadIssue.None -> LoadState.Ready
            LoadIssue.RestoredFromBackup -> LoadState.RestoredFromBackup
            LoadIssue.ResetAfterCorruption -> LoadState.ResetAfterCorruption
        }
        publish(
            when (outcome) {
                is LoadOutcome.Loaded<TodoFile> -> outcome.file
                is LoadOutcome.Fresh<TodoFile> -> outcome.file
            },
        )
    }

    private fun publish(file: TodoFile) {
        fileState.value = file
        _todos.value = file.todos
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

    private fun todoOrNull(id: String): Todo? =
        fileState.value.todos.firstOrNull { it.id == id }

    /**
     * 写入前归一化：名称/标签/步骤标题 trim、标签去空、日期 distinct + 排序；
     * 完成不变式：completed=true 时 completedAtMillis 缺省补当前，false 时清空；
     * 倒计时不变式：无截止日期时开关回 false（清掉截止日期后不留悬挂的开启态）；
     * 分组引用完整性：group trim、空串与引用不存在的组一律清回 null（未分组）——
     * 由此读侧只需判 `group == null`，导入的带组名待办同此规则落为未分组；
     * 重复规则消洗见 [normalizeRepeat]、逾期落账见 [sweepOverdue]。
     * 在 mutex 内调用，读 [fileState] 的分组表安全。
     */
    private fun normalize(todo: Todo): Todo {
        val groupNames = fileState.value.groups.mapTo(mutableSetOf()) { it.name }
        val (overdueCount, sweptThrough) = sweepOverdue(todo)
        return todo.copy(
            name = todo.name.trim(),
            dates = todo.dates.distinct().sortedBy { it.epochDay },
            repeat = normalizeRepeat(todo.repeat),
            completedCount = todo.completedCount.coerceAtLeast(0),
            overdueCount = overdueCount,
            overdueSweptThrough = sweptThrough,
            tags = todo.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            steps = todo.steps.map { it.copy(title = it.title.trim()) },
            completedAtMillis = if (todo.completed) todo.completedAtMillis ?: nowMillis() else null,
            countdown = todo.countdown && todo.deadlineDate != null,
            group = todo.group?.trim()?.takeIf { it.isNotEmpty() && it in groupNames },
        )
    }

    /**
     * 重复待办的逾期落账（写库路径顺带结算，水位保证每个错过的周期只计一次）：
     * 未完成的重复待办把水位之后、今天之前错过的应完成日期（[RepeatRule.occurrencesBetween]
     * 展开）折入逾期计数，水位推进到昨天——当天的应完成日期还不算错过（与 [Todo.isOverdue]
     * 同为日期粒度）。已完成跳过：完成即视为赶上，完成期间不累计（勾选完成时的拖欠折入与
     * 水位推进见 [setCompleted]）。返回 (落账后的逾期次数, 结算水位)，非重复待办原样透传。
     * 在 mutex 内调用（nowMillis 时钟一致）。
     */
    private fun sweepOverdue(todo: Todo): Pair<Int, Long> {
        val passthrough = todo.overdueCount.coerceAtLeast(0) to todo.overdueSweptThrough
        if (todo.completed || todo.repeat.type == RepeatType.None) return passthrough
        val anchor = todo.dates.minByOrNull { it.epochDay } ?: return passthrough
        val today = IsoDate.today(nowMillis())
        val swept = todo.overdueSweptThrough
        val from = if (swept < 0) anchor else IsoDate(swept + 1)
        val missed = todo.repeat.occurrencesBetween(anchor, from, today).size
        return (todo.overdueCount.coerceAtLeast(0) + missed) to (today.epochDay - 1)
    }

    /**
     * 重复规则消洗（导入的外来数据可能越界）：预设档位不带自定义参数——unit/interval/marks
     * 重置为默认值，**类型原样保留**；自定义档位间隔夹紧 1..99、方格标记按单位合法域剔除
     * （天的规则无方格语义，标记恒清空）。
     */
    private fun normalizeRepeat(rule: RepeatRule): RepeatRule =
        when (rule.type) {
            RepeatType.Custom -> rule.copy(
                interval = rule.interval.coerceIn(1, 99),
                marks = rule.marks.filterTo(mutableSetOf()) { it in rule.unit.markRange },
            )
            // 预设档位（无/每天/每周/每月/每年）：只留类型，自定义参数一律归默认
            else -> rule.copy(unit = RepeatUnit.Week, interval = 1, marks = emptySet())
        }

    /**
     * 分组整表替换：新建（追加）、置顶/重排（调用方重排后写回）、删除（滤除）共用这一个入口。
     *
     * 归一化：name trim、空白丢弃、按名去重（保留首个），并做置顶连续前缀稳定划分
     * （`filter { pinned } + filter { !pinned }`，维持 TodoGroup/GroupCard 契约）。
     * 级联：本次被移除的组，其待办的 group 一并清回 null——与分组表**同一次 commit 原子落盘**，
     * 不会出现悬挂引用。失败内存零变更（同其余写 API）。
     */
    suspend fun setGroups(newGroups: List<TodoGroup>): TodoOpResult = mutex.withLock {
        val normalized = normalizeGroups(newGroups)
        val kept = normalized.mapTo(mutableSetOf()) { it.name }
        val current = fileState.value
        // 级联 = 对每条待办做一次分组引用归一化（trim + 成员检查，同 normalize 的 group 规则）
        val cascaded = current.todos.map { todo ->
            val group = todo.group?.trim()?.takeIf { it in kept }
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

    /** 按 id 全量替换（编辑表单提交走这条）；归一化 + 校验后落盘，失败内存零变更 */
    suspend fun updateTodo(todo: Todo): TodoOpResult = mutex.withLock {
        if (todoOrNull(todo.id) == null) return@withLock TodoOpResult.Err(TodoError.NotFound(todo.id))
        val prepared = normalize(todo)
        val errors = TodoValidator.validate(prepared)
        if (errors.isNotEmpty()) return@withLock TodoOpResult.Err(errors.first())
        commit(fileState.value.copy(todos = fileState.value.todos.map { if (it.id == prepared.id) prepared else it }))
    }

    suspend fun removeTodo(id: String): TodoOpResult = mutex.withLock {
        if (todoOrNull(id) == null) return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        commit(fileState.value.copy(todos = fileState.value.todos.filterNot { it.id == id }))
    }

    /** 批量删除；未知 id 宽容忽略（批量语义，同 removeCourses） */
    suspend fun removeTodos(ids: List<String>): TodoOpResult = mutex.withLock {
        val drop = ids.toSet()
        commit(fileState.value.copy(todos = fileState.value.todos.filterNot { it.id in drop }))
    }

    /**
     * 切换完成状态并维护 completedAtMillis 与重复统计；其余字段不动。
     * 完成次数只在 false→true 时 +1（取消完成不回退——历史事实不抹去）。勾选完成时先把
     * 水位前拖欠的错过周期折入逾期（拖欠不因补完成而消失），再把结算水位推进到昨天——
     * 完成期间不累计逾期，取消完成后从这里恢复累计（[sweepOverdue] 跳过完成态）。
     */
    suspend fun setCompleted(id: String, completed: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val prepared = if (completed && !target.completed) {
            val today = IsoDate.today(nowMillis())
            val (foldedOverdue, _) = sweepOverdue(target)
            normalize(
                target.copy(
                    completed = true,
                    completedCount = target.completedCount + 1,
                    overdueCount = foldedOverdue,
                    overdueSweptThrough = today.epochDay - 1,
                ),
            )
        } else {
            normalize(target.copy(completed = completed))
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

    // ---------- 分享 ----------

    /** 导出全部待办为 pretty JSON 文本（分享载荷），供系统分享面板发送 */
    suspend fun exportTodos(): TodoReadResult<String> {
        val current = fileState.value
        return TodoReadResult.Ok(TodoShareCodec.encode(current.todos, nowMillis()))
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

        private val instanceMutex = Mutex()
        private var instance: TodoRepository? = null

        /** 应用内进程级单例（首次调用完成磁盘载入） */
        suspend fun get(context: Context): TodoRepository {
            instance?.let { return it }
            return instanceMutex.withLock {
                instance ?: create(FileTodoStore(context.filesDir)).also { instance = it }
            }
        }
    }
}
