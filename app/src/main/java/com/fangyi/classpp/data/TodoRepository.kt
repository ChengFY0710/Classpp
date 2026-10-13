package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.export.TodoShareCodec
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoFile
import com.fangyi.classpp.data.model.TodoGroup
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
     * 分组引用完整性：group trim、空串与引用不存在的组一律清回 null（未分组）——
     * 由此读侧只需判 `group == null`，导入的带组名待办同此规则落为未分组。
     * 在 mutex 内调用，读 [fileState] 的分组表安全。
     */
    private fun normalize(todo: Todo): Todo {
        val groupNames = fileState.value.groups.mapTo(mutableSetOf()) { it.name }
        return todo.copy(
            name = todo.name.trim(),
            dates = todo.dates.distinct().sortedBy { it.epochDay },
            tags = todo.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            steps = todo.steps.map { it.copy(title = it.title.trim()) },
            completedAtMillis = if (todo.completed) todo.completedAtMillis ?: nowMillis() else null,
            group = todo.group?.trim()?.takeIf { it.isNotEmpty() && it in groupNames },
        )
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

    /** 切换完成状态并维护 completedAtMillis；其余字段不动 */
    suspend fun setCompleted(id: String, completed: Boolean): TodoOpResult = mutex.withLock {
        val target = todoOrNull(id) ?: return@withLock TodoOpResult.Err(TodoError.NotFound(id))
        val updated = normalize(target.copy(completed = completed))
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
