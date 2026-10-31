package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** prepare/commit 的失败分类（指导 §5.5）——UI 按类选文案，绝不把所有失败说成「登录过期」 */
enum class XmuSyncError {
    /** 被重定向到身份域 / 返回登录页 HTML / 401、403 */
    SessionExpired,
    /** 5xx */
    ServiceBusy,
    /** 网络异常 */
    NetworkError,
    /** 页面加载或轮询超时 */
    Timeout,
    /** 200 但响应不是预期 JSON 结构（平台改版） */
    FormatChanged,
    /** profile 的 uid 与已存映射不一致（换账号了） */
    AccountChanged,
    /** 距上次成功探活不足 3 秒，去抖 */
    TooSoon,
}

/** 过滤+比对后的同步计划（prepare 阶段**零写入**，用户在预览里确认后才提交） */
data class XmuSyncPlan(
    val profile: XmuProfileDto,
    /** 本地（活跃+回收站）都没有的条目：预览里展示、可勾选取消 */
    val newItems: List<XmuPlanItem>,
    /** 在回收站里的条目：同步复活（不进预览勾选，直接恢复） */
    val restoreItems: List<XmuPlanItem>,
    /** 已在活跃列表但映射文件里缺条目的补缺记录（正常应是空集） */
    val entriesForActive: List<XmuSyncEntry>,
    /** 被过滤跳过的条目按原因计数 */
    val skipped: Map<SkipReason, Int>,
    /** 远端列表总条数（含被过滤项） */
    val totalRemote: Int,
    val fetchedAtMillis: Long,
)

data class XmuPlanItem(val dto: XmuTodoDto, val todo: Todo)

sealed interface XmuPrepareOutcome {
    data class Ready(val plan: XmuSyncPlan) : XmuPrepareOutcome
    data class Failed(val error: XmuSyncError) : XmuPrepareOutcome
}

data class XmuSyncReport(
    val imported: Int,
    val restored: Int,
    /** prepare 阶段过滤跳过的总数（锁定/过期/无效等） */
    val skippedFiltered: Int,
)

sealed interface XmuCommitOutcome {
    data class Done(val report: XmuSyncReport) : XmuCommitOutcome
    data class Failed(val error: TodoError) : XmuCommitOutcome
}

/**
 * 课程中心待办同步编排（指导 §5）：**只增不改**——远端内容变化本期不回写本地（P2 按
 * 哈希更新），远端消失不自动删/不自动勾完成；用户删除过的条目只要远端仍列出就允许复活。
 *
 * 并发纪律：探活/同步/清空共用一把 [mutex]，同一时刻只有一次会话；探活 3 秒去抖
 * （成功才刷新时间戳，失败立即可重试）。同步状态与 todos.json 完全独立文件（[XmuSyncStore]），
 * 损坏互不波及；entries 只存哈希不存标题原文。
 *
 * 构造：测试注入 fake [XmuTodoApi] 与固定时钟；应用内经 [com.fangyi.classpp.data.AppContainer] 装配。
 */
class XmuSyncRepository internal constructor(
    private val store: XmuSyncStore,
    /** 待办库本体（复活/批量导入走它；测试与 UI 也读它观察同步结果） */
    val todoRepository: TodoRepository,
    private val api: XmuTodoApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(XmuSyncFile())
    val state: StateFlow<XmuSyncFile> = _state.asStateFlow()

    /** 进程内是否已从磁盘载入（AppContainer 装配时调用一次） */
    suspend fun bootstrap() {
        val outcome = withContext(Dispatchers.IO) { store.load() }
        _state.value = when (outcome) {
            is LoadOutcome.Loaded<XmuSyncFile> -> outcome.file
            is LoadOutcome.Fresh<XmuSyncFile> -> outcome.file
        }
    }

    // ---------- 同步 ----------

    /**
     * 拉取远端并生成同步计划（拉取+解析+比对，无写入）：已连接用户点「立即同步」走这条。
     * 比对口径：活跃已存在 → 跳过；在回收站 → 复活；都没有 → 新增候选。
     */
    suspend fun prepareSync(force: Boolean = false): XmuPrepareOutcome = mutex.withLock {
        val current = _state.value
        if (!force && current.lastProbeAtMillis > 0 &&
            nowMillis() - current.lastProbeAtMillis < PROBE_DEBOUNCE_MS
        ) {
            return@withLock XmuPrepareOutcome.Failed(XmuSyncError.TooSoon)
        }
        when (val result = api.fetchSession()) {
            XmuFetchResult.SessionExpired -> XmuPrepareOutcome.Failed(XmuSyncError.SessionExpired)
            XmuFetchResult.ServiceBusy -> XmuPrepareOutcome.Failed(XmuSyncError.ServiceBusy)
            XmuFetchResult.NetworkError -> XmuPrepareOutcome.Failed(XmuSyncError.NetworkError)
            XmuFetchResult.Timeout -> XmuPrepareOutcome.Failed(XmuSyncError.Timeout)
            is XmuFetchResult.Ok -> onSession(result.profileRaw, result.todosRaw, nowMillis())
        }
    }

    /**
     * 可见登录浮层拿到两个响应原文后的入口：不走 fetch、不去抖（登录本身就是用户动作）。
     * 检出 uid 与已存映射不一致时返回 [XmuSyncError.AccountChanged]，由 UI 弹确认决定
     * 是否清空旧记录（防两个账号的待办混在一起）。
     */
    suspend fun prepareFromLogin(profileRaw: String, todosRaw: String): XmuPrepareOutcome =
        mutex.withLock { onSession(profileRaw, todosRaw, nowMillis()) }

    /** 提交同步计划：复活回收站条目 + 批量导入勾选的新增项 + 更新映射文件 */
    suspend fun commitSync(plan: XmuSyncPlan, selectedNewIds: Set<String>): XmuCommitOutcome =
        mutex.withLock {
            var restored = 0
            for (item in plan.restoreItems) {
                if (todoRepository.restoreTodo(item.todo.id) is TodoOpResult.Ok) restored++
            }

            val selected = plan.newItems.filter { it.todo.id in selectedNewIds }
            var imported = 0
            if (selected.isNotEmpty()) {
                when (val r = todoRepository.addTodos(selected.map { it.todo })) {
                    is TodoReadResult.Ok -> imported = r.value.size
                    is TodoReadResult.Err -> return@withLock XmuCommitOutcome.Failed(r.error)
                }
            }

            // entries 增量合并：新增/复活写新哈希基线，活跃条目只补缺（不洗掉既有基线，
            // P2 的「用户是否编辑过」判定靠它）
            val byRemote = _state.value.entries.associateBy { it.remoteId }.toMutableMap()
            for (item in selected) {
                byRemote[item.dto.id] = XmuSyncEntry(
                    remoteId = item.dto.id,
                    courseId = item.dto.courseId,
                    todoId = item.todo.id,
                    remoteHash = XmuTodoMapper.remoteHash(item.dto),
                    localHash = XmuTodoMapper.localHash(item.todo),
                )
            }
            for (item in plan.restoreItems) {
                byRemote[item.dto.id] = XmuSyncEntry(
                    remoteId = item.dto.id,
                    courseId = item.dto.courseId,
                    todoId = item.todo.id,
                    remoteHash = XmuTodoMapper.remoteHash(item.dto),
                    localHash = XmuTodoMapper.localHash(item.todo),
                )
            }
            for (entry in plan.entriesForActive) byRemote.putIfAbsent(entry.remoteId, entry)

            persist(
                _state.value.copy(
                    entries = byRemote.values.toList(),
                    lastSyncAtMillis = nowMillis(),
                ),
            )
            XmuCommitOutcome.Done(
                XmuSyncReport(
                    imported = imported,
                    restored = restored,
                    skippedFiltered = plan.skipped.values.sum(),
                ),
            )
        }

    /**
     * 清空同步状态（退出登录与账号切换共用）：删 xmu-sync.json 三件套并复位内存态；
     * [deleteSyncedTodos] 时把 `xmu-` 前缀的本地待办一并移除（活跃软删后随回收站条目
     * 一起彻底删除）。cookie 清理由 UI 层做（CookieManager.removeAllCookies）。
     */
    suspend fun resetSyncData(deleteSyncedTodos: Boolean) = mutex.withLock {
        if (deleteSyncedTodos) {
            val syncedActive = todoRepository.todos.value
                .filter { XmuTodoMapper.isSyncedLocalId(it.id) }
                .map { it.id }
            val syncedAll = todoRepository.todos.value + todoRepository.deletedTodos.value
            if (syncedActive.isNotEmpty()) todoRepository.removeTodos(syncedActive)
            // 软删后全部进回收站，逐个彻底删除（仓库没有批量 purge）
            for (todo in syncedAll) {
                if (XmuTodoMapper.isSyncedLocalId(todo.id)) todoRepository.purgeTodo(todo.id)
            }
        }
        withContext(Dispatchers.IO) { store.delete() }
        _state.value = XmuSyncFile()
    }

    // ---------- 内部 ----------

    /** 会话原文 → 解析 → 账号一致性 → 比对出计划；成功时顺带落探活/账号信息 */
    private suspend fun onSession(profileRaw: String, todosRaw: String, fetchedAt: Long): XmuPrepareOutcome {
        val current = _state.value
        val profile = parseProfile(profileRaw)
        if (profile == null) {
            return XmuPrepareOutcome.Failed(classifyBody(profileRaw))
        }
        if (current.uid.isNotEmpty() && profile.id.toString() != current.uid) {
            return XmuPrepareOutcome.Failed(XmuSyncError.AccountChanged)
        }
        val listDto = parseTodos(todosRaw) ?: return XmuPrepareOutcome.Failed(classifyBody(todosRaw))

        persist(
            current.copy(
                uid = profile.id.toString(),
                accountName = profile.name,
                lastProbeAtMillis = fetchedAt,
            ),
        )
        return XmuPrepareOutcome.Ready(buildPlan(profile, listDto, fetchedAt))
    }

    private fun buildPlan(
        profile: XmuProfileDto,
        listDto: XmuTodoListDto,
        fetchedAt: Long,
    ): XmuSyncPlan {
        val active = todoRepository.todos.value.associateBy { it.id }
        val trashed = todoRepository.deletedTodos.value.associateBy { it.id }
        val knownEntries = _state.value.entries.mapTo(mutableSetOf()) { it.remoteId }

        val newItems = mutableListOf<XmuPlanItem>()
        val restoreItems = mutableListOf<XmuPlanItem>()
        val entriesForActive = mutableListOf<XmuSyncEntry>()
        val skipped = mutableMapOf<SkipReason, Int>()

        for (dto in listDto.todoList.distinctBy { it.id }) {
            when (val outcome = XmuTodoMapper.map(dto, fetchedAt)) {
                is XmuMapOutcome.Skipped -> skipped.merge(outcome.reason, 1, Int::plus)
                is XmuMapOutcome.Mapped -> {
                    val localId = outcome.todo.id
                    val activeTodo = active[localId]
                    val trashedTodo = trashed[localId]
                    when {
                        activeTodo != null -> {
                            // 已导入：跳过（P2 再按哈希做远端变化更新）；映射缺档只补基线
                            if (dto.id !in knownEntries) {
                                entriesForActive += XmuSyncEntry(
                                    remoteId = dto.id,
                                    courseId = dto.courseId,
                                    todoId = localId,
                                    remoteHash = XmuTodoMapper.remoteHash(dto),
                                    localHash = XmuTodoMapper.localHash(activeTodo),
                                )
                            }
                        }
                        trashedTodo != null -> restoreItems += XmuPlanItem(dto, trashedTodo)
                        else -> newItems += XmuPlanItem(dto, outcome.todo)
                    }
                }
            }
        }
        return XmuSyncPlan(
            profile = profile,
            newItems = newItems,
            restoreItems = restoreItems,
            entriesForActive = entriesForActive,
            skipped = skipped,
            totalRemote = listDto.todoList.size,
            fetchedAtMillis = fetchedAt,
        )
    }

    private fun parseProfile(raw: String): XmuProfileDto? = try {
        ScheduleJson.decodeStorage<XmuProfileDto>(raw).takeIf { it.id > 0 }
    } catch (_: Exception) {
        null
    }

    private fun parseTodos(raw: String): XmuTodoListDto? = try {
        ScheduleJson.decodeStorage<XmuTodoListDto>(raw)
    } catch (_: Exception) {
        null
    }

    /** 200 但解析失败 → 按响应体形态分类：JSON 是结构变了（格式变更），HTML 是被踢到登录页 */
    private fun classifyBody(raw: String): XmuSyncError =
        if (raw.trim().startsWith("{")) XmuSyncError.FormatChanged else XmuSyncError.SessionExpired

    private suspend fun persist(next: XmuSyncFile): Boolean {
        val result = withContext(Dispatchers.IO) { store.save(next) }
        return when (result) {
            StoreResult.Ok -> {
                _state.value = next
                true
            }
            is StoreResult.Failed -> false
        }
    }

    private companion object {
        /** 探活/同步最小间隔（防连点重复请求；放到 3 秒，避免正常重试被拦） */
        const val PROBE_DEBOUNCE_MS = 3_000L
    }
}
