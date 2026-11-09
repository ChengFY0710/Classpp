package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.sync.AvatarImage
import com.fangyi.classpp.data.sync.CommitOutcome
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.PrepareOutcome
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SyncError
import com.fangyi.classpp.data.sync.MutableSyncPreferences
import com.fangyi.classpp.data.sync.SyncPlan
import com.fangyi.classpp.data.sync.SyncPlanItem
import com.fangyi.classpp.data.sync.SyncPreferences
import com.fangyi.classpp.data.sync.SyncReport
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.data.sync.SyncState
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.mapState
import com.fangyi.classpp.data.sync.media.AvatarCache
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 通用同步引擎（指导 §2.3/§2.4/§四 Phase 4）：新校适配的编排实现，与既有厦大编排
 * （冻结层）遵守同一组不变量，由同一份契约测试（`SyncInvariantContractTest`）约束。
 *
 * 不变量（与 `data.sync.SyncTypes` 的 KDoc 一一对应）：
 * 1. **单飞**：探活/同步/清空共用一把 [mutex]；探活 3 秒去抖（成功才刷时间戳，
 *    时钟回拨不卡死）；
 * 2. **预览-确认**：prepare 不写待办与映射，commit 才落库；但 prepare 会落盘连接与
 *    探活状态（uid、账号名、头像地址、上次探活时刻）——落盘失败不能报 Ready；
 * 3. **只增不改**：远端变化不回写；远端列表非空且已映射条目消失时，把对应本地活跃待办
 *    标记完成（可回滚：远端重新出现且完成时刻未变则恢复未完成；受
 *    [com.fangyi.classpp.data.sync.SyncPreferences.autoCompleteOnVanish] 开关控制，默认关）；
 *    远端列表为空、或本地已逾期时不做标记；回收站条目允许复活；
 * 4. **账号一致性**：uid 与已存映射不一致 → [SyncError.AccountChanged]；
 * 5. **entries 只存映射、哈希与同步状态**，不存标题原文；键 = [SyncCodec.remoteKey]（校内唯一）；
 * 6. **原子写**：tmp(fsync)→bak→rename，双坏隔离留证（[SchoolSyncStore]）；
 * 7. **退出清理按 [SyncCodec.isOwned] 过滤**，只删本校同步待办（活跃 + 归档 + 回收站）；
 * 8. **失败分类不兜底**：绝不把所有失败说成登录过期；
 * 9. **自动分组回填**：commit 与 markSynced 都给「`group == null` 的存量同步条目」补
 *    自动分组（用户手动分组不覆盖）；组键按校命名空间（`<id>:homework` 等）；
 * 10. **跨校隔离**：退出/换号只动本校数据；
 * 11. **会话认领 plan**：[SyncPlan] 是引擎持有的不透明句柄，跨引擎传入直接失败。
 *
 * 头像：可选传入 [avatar]（`filesDir/sync/<id>/avatar.img`）；不传则 `avatarImage` 恒为
 * null，UI 走姓名首字占位。头像失败只留日志，不影响同步结果。
 *
 * 构造：学校 provider 装配时注入 [SchoolSyncStore] 与 [SyncCodec]；测试注入 fake
 * [SessionApi] 与固定时钟。
 */
class SchoolSyncEngine<R : Any>(
    private val spec: SchoolSpec,
    private val codec: SyncCodec<R>,
    private val store: SchoolSyncStore,
    private val todoRepository: TodoRepository,
    private val api: SessionApi,
    private val groupNames: SchoolAutoGroupNames,
    private val clock: () -> Long = System::currentTimeMillis,
    private val avatar: AvatarCache? = null,
    private val syncPreferences: SyncPreferences = MutableSyncPreferences(),
    private val logger: (String) -> Unit = SyncDebugLog::d,
) : SyncService {

    init {
        require(codec.source == spec.id) {
            "codec source '${codec.source}' must equal school id '${spec.id}'"
        }
        require(groupNames.source == spec.id) {
            "auto group namespace '${groupNames.source}' must equal school id '${spec.id}'"
        }
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow(SchoolSyncFile())

    /** 完整状态文件（provider/测试诊断用；UI 只读 [state]） */
    val fileState: StateFlow<SchoolSyncFile> = _state.asStateFlow()

    override val state: StateFlow<SyncState> = _state.mapState { file ->
        SyncState(
            uid = file.uid,
            accountName = file.accountName,
            avatarUrl = file.avatarUrl,
            lastSyncAtMillis = file.lastSyncAtMillis,
            lastProbeAtMillis = file.lastProbeAtMillis,
        )
    }

    private val _avatarImage = MutableStateFlow<AvatarImage?>(null)
    override val avatarImage: StateFlow<AvatarImage?> = _avatarImage.asStateFlow()

    private val busyTracker = BusyTracker()
    override val busy: StateFlow<Boolean> = busyTracker.busy

    override val preferences: SyncPreferences get() = syncPreferences

    /** 进程内是否已从磁盘载入：provider 装配时调用一次（与 status/avatar 成对） */
    suspend fun bootstrap() {
        val (outcome, cachedAvatar) = withContext(Dispatchers.IO) {
            store.load() to avatar?.cachedImage()
        }
        val loaded = when (outcome) {
            is LoadOutcome.Loaded<SchoolSyncFile> -> outcome.file
            is LoadOutcome.Fresh<SchoolSyncFile> -> outcome.file
        }
        _state.value = loaded
        // 状态文件与本地图片必须成对：只认「已连接 + 记过头像地址」的缓存，避免退出登录
        // 残留的图片被下一个账号复用
        _avatarImage.value = cachedAvatar.takeIf {
            loaded.uid.isNotEmpty() && loaded.avatarUrl.isNotEmpty()
        }
    }

    // ---------- 同步 ----------

    override suspend fun prepareSync(force: Boolean): PrepareOutcome = busyTracker.track {
        mutex.withLock { prepareLocked(force) }
    }

    override suspend fun prepareFromLogin(raw: LoginRaw): PrepareOutcome = busyTracker.track {
        mutex.withLock { onSession(raw, clock()) }
    }

    override suspend fun commitSync(
        plan: SyncPlan,
        selectedNewIds: Set<String>,
    ): CommitOutcome = busyTracker.track {
        mutex.withLock { commitLocked(claim(plan), selectedNewIds) }
    }

    override suspend fun markSynced(plan: SyncPlan): Boolean = busyTracker.track {
        mutex.withLock { markSyncedLocked(claim(plan)) }
    }

    override suspend fun resetSyncData(deleteSyncedTodos: Boolean) {
        busyTracker.track {
            mutex.withLock {
                if (deleteSyncedTodos) {
                    // 已归档条目不在活跃流里（归档 = 隐藏），退出清理要一并扫到
                    val syncedActive =
                        (todoRepository.todos.value + todoRepository.archivedTodos.value)
                            .filter { codec.isOwned(it) }
                            .map { it.id }
                    if (syncedActive.isNotEmpty()) todoRepository.removeTodos(syncedActive)

                    // 软删已把活跃条目全部推进回收站：一次批量 purge 彻底删掉
                    val syncedTrash = todoRepository.deletedTodos.value
                        .filter { codec.isOwned(it) }
                        .map { it.id }
                    if (syncedTrash.isNotEmpty()) todoRepository.purgeTodos(syncedTrash)
                }
                withContext(Dispatchers.IO) {
                    store.delete()
                    // 头像属于账号数据：退出登录/换号一并清掉
                    avatar?.delete()
                }
                _avatarImage.value = null
                _state.value = SchoolSyncFile()
                logger("reset done deleteTodos=$deleteSyncedTodos")
            }
        }
    }

    // ---------- 内部：prepare ----------

    private suspend fun prepareLocked(force: Boolean): PrepareOutcome {
        val current = _state.value
        // 时间戳是 wall clock：系统时间回拨后差值可能为负，此时不能一直判「刚刚同步过」
        val elapsed = clock() - current.lastProbeAtMillis
        if (!force && current.lastProbeAtMillis > 0 &&
            elapsed >= 0 && elapsed < PROBE_DEBOUNCE_MS
        ) {
            return PrepareOutcome.Failed(SyncError.TooSoon)
        }
        return when (val result = api.fetchSession()) {
            is FetchOutcome.Ok -> onSession(result.raw, clock())
            is FetchOutcome.Failed -> PrepareOutcome.Failed(result.error.toSyncError())
        }
    }

    /** 会话原文 → 解析 → 账号一致性 → 比对出计划；成功时顺带落探活/账号信息与头像 */
    private suspend fun onSession(raw: LoginRaw, fetchedAt: Long): PrepareOutcome {
        val current = _state.value
        val profile = codec.parseProfile(raw.profileRaw)
            ?: return PrepareOutcome.Failed(classifyBody(raw.profileRaw))
        if (current.uid.isNotEmpty() && profile.uid != current.uid) {
            return PrepareOutcome.Failed(SyncError.AccountChanged)
        }
        val remotes = codec.parseTodos(raw.todosRaw)
            ?: return PrepareOutcome.Failed(classifyBody(raw.todosRaw))

        // 头像：与同步同一次会话里顺带抓（纯装饰，失败保留上一张/首字占位）
        val avatarUrl = refreshAvatar(profile.avatarUrl, current.avatarUrl)

        val next = current.copy(
            uid = profile.uid,
            accountName = profile.accountName,
            lastProbeAtMillis = fetchedAt,
            avatarUrl = avatarUrl,
        )
        // 账号/探活状态没落盘就不能返回 Ready：否则连接卡仍显示未连接、去抖基线也错位
        if (!persist(next)) return PrepareOutcome.Failed(SyncError.PersistFailed)
        return PrepareOutcome.Ready(buildPlan(remotes, fetchedAt))
    }

    /**
     * 抓一次头像（**不抛出、不阻断同步**）：地址没变且本地文件在 → 不发请求；否则下载并
     * 校验落盘。返回「当前本地图片对应的地址」——成功是新地址，失败/未装配沿用
     * [cachedUrl]，这样下次同步会重试，而状态文件始终与本地图片一致。
     */
    private suspend fun refreshAvatar(url: String?, cachedUrl: String): String {
        val cache = avatar ?: return cachedUrl
        if (url == null) {
            logger("avatar: absent")
            return cachedUrl
        }
        if (url == cachedUrl && withContext(Dispatchers.IO) { cache.cachedImage() } != null) {
            logger("avatar: cached")
            return cachedUrl
        }
        val saved = cache.store(url) ?: run {
            logger("avatar: failed")
            return cachedUrl
        }
        _avatarImage.value = saved
        logger("avatar: saved")
        return url
    }

    private fun buildPlan(remotes: List<R>, fetchedAt: Long): EngineSyncPlan<R> {
        val active = todoRepository.todos.value.associateBy { it.id }
        // 已归档条目不在活跃流里（归档 = 隐藏），比对时要算「已导入」——漏掉会把归档的
        // 同步待办当新增候选，用户确认导入后撞 DuplicateId 整批失败
        val archived = todoRepository.archivedTodos.value.associateBy { it.id }
        val trashed = todoRepository.deletedTodos.value.associateBy { it.id }
        val knownEntries = _state.value.entries.associateBy { it.remoteKey }
        // 原始远端键集合（含 locked/overdue 等稍后会被跳过的条目）：跳过不等于消失
        val presentKeys = remotes.mapTo(mutableSetOf()) { codec.remoteKey(it) }

        val newItems = mutableListOf<PlanItem<R>>()
        val restoreItems = mutableListOf<PlanItem<R>>()
        val vanishedItems = mutableListOf<VanishedItem>()
        val reactivatedItems = mutableListOf<ReactivatedItem>()
        val entriesForActive = mutableListOf<SchoolSyncEntry>()
        // 本次同步要更新的既有映射（清空/写入自动完成基线）
        val entryUpdates = mutableMapOf<String, SchoolSyncEntry>()
        val skipped = mutableMapOf<SkipReason, Int>()
        val groupBackfill = mutableMapOf<String, String>()

        for (remote in remotes.distinctBy { codec.remoteKey(it) }) {
            val rKey = codec.remoteKey(remote)
            // 映射里的 todoId 优先沿用（映射丢失时再按确定性 id 找回），避免升级/恢复后
            // 把已导入的待办当新增重复导入。先于 skip 判定：被过滤的远端条目仍可能对应
            // 本地已导入的待办，分组回填不能因为它这次被跳过就漏掉
            val knownTodoId = knownEntries[rKey]?.todoId
            val localId = sequenceOf(knownTodoId, codec.localId(rKey))
                .filterNotNull()
                .firstOrNull { it in active || it in archived || it in trashed }
                ?: knownTodoId
                ?: codec.localId(rKey)
            require(localId.startsWith("${codec.source}-")) {
                "codec localId must start with '${codec.source}-': '$localId'"
            }
            val liveTodo = active[localId] ?: archived[localId]
            val trashedTodo = trashed[localId]
            val existing = liveTodo ?: trashedTodo

            // 上次因消失被自动完成、本次又出现：只有完成时刻仍等于自动标记基线才回滚
            //（用户手动勾选/取消过完成态则尊重用户，只清基线）。归档/回收站条目不回滚。
            val known = knownEntries[rKey]
            if (known?.autoCompletedAtMillis != null) {
                // 基线属于映射里的 todoId；映射失效/旧 id 时不能拿确定性 id 的新条目顶替
                val activeTodo = active[known.todoId]
                if (activeTodo != null && activeTodo.completed &&
                    activeTodo.completedAtMillis == known.autoCompletedAtMillis &&
                    codec.isOwned(activeTodo) &&
                    activeTodo.repeat.type == RepeatType.None
                ) {
                    reactivatedItems += ReactivatedItem(activeTodo)
                }
                // 远端已重新出现：基线失效（回滚/用户手动改过都只清基线）
                entryUpdates[known.remoteKey] = known.copy(autoCompletedAtMillis = null)
            }

            // 存量同步待办按远端类型补自动分组：只补本地确实存在且仍未分组的，
            // 用户改过分组就尊重；远端新增候选不在这里回填（导入时按类型直接设置）
            if (existing != null && existing.group == null) {
                codec.autoGroupKey(remote)?.let { groupBackfill[localId] = it }
            }

            val reason = codec.skipReason(remote)
            if (reason != null) {
                skipped.merge(reason, 1, Int::plus)
                continue
            }
            val todo = codec.toTodo(remote, fetchedAt)
            if (todo == null) {
                // 契约要求「可导入 ⇔ skipReason == null 且 toTodo != null」；这里按无效条目
                // 计数并继续，不让单个坏样本中断整批同步
                skipped.merge(SkipReason.InvalidId, 1, Int::plus)
                logger("toTodo returned null without skipReason: $rKey")
                continue
            }

            when {
                liveTodo != null -> {
                    // 已导入（活跃或已归档）：跳过，映射缺档只补基线
                    if (rKey !in knownEntries) {
                        entriesForActive += SchoolSyncEntry(
                            remoteKey = rKey,
                            todoId = localId,
                            remoteHash = codec.remoteHash(remote),
                            localHash = localHash(liveTodo),
                        )
                    }
                }
                trashedTodo != null -> restoreItems += PlanItem(
                    remote = remote,
                    todo = trashedTodo,
                    courseName = trashedTodo.sourceDetail ?: "",
                )
                else -> newItems += PlanItem(
                    remote = remote,
                    todo = todo.copy(id = localId),
                    courseName = todo.sourceDetail ?: "",
                )
            }
        }

        // 远端列表非空才做消失判定：空列表可能是学期切换/接口异常，不能把全部待办误标完成。
        // 只认有映射的条目（entries），避免同步状态丢失后按 id 前缀误伤历史/旧口径条目。
        var overdueSkipped = 0
        if (remotes.isNotEmpty() && syncPreferences.autoCompleteOnVanish.value) {
            val seenTodoIds = mutableSetOf<String>()
            for (entry in knownEntries.values) {
                if (entry.remoteKey in presentKeys) continue
                // 映射重建/历史数据可能让多条 entry 指向同一待办：只处理一次
                if (!seenTodoIds.add(entry.todoId)) continue
                val todo = active[entry.todoId] ?: continue
                if (!codec.isOwned(todo)) continue
                // 已完成（含自动完成）跳过；用户手动取消后 completed=false，会再次进入候选。
                // 远端列表仍是完成判定的唯一依据：远端不返回就再次标记完成。
                if (todo.completed || todo.repeat.type != RepeatType.None) continue
                // 逾期条目不因远端消失而自动完成：远端停止返回更可能是过期清理，不是提交完成
                if (codec.isOverdue(todo, fetchedAt)) {
                    overdueSkipped++
                    continue
                }
                vanishedItems += VanishedItem(entry, todo)
            }
        }
        if (vanishedItems.isNotEmpty() || reactivatedItems.isNotEmpty() || overdueSkipped > 0) {
            logger(
                "sync diff present=${presentKeys.size} vanished=${vanishedItems.size} " +
                    "reactivated=${reactivatedItems.size} overdueSkipped=$overdueSkipped",
            )
        }

        return EngineSyncPlan(
            owner = this,
            plannedNewItems = newItems,
            restoreItems = restoreItems,
            plannedVanished = vanishedItems,
            plannedReactivated = reactivatedItems,
            entriesForActive = entriesForActive,
            entryUpdates = entryUpdates,
            skipped = skipped,
            totalRemote = remotes.size,
            groupBackfill = groupBackfill,
        )
    }

    /** 200 但解析失败 → 按响应体形态分类：JSON 是结构变了，HTML 是被踢到登录页 */
    private fun classifyBody(raw: String): SyncError =
        if (raw.trim().startsWith("{")) SyncError.FormatChanged else SyncError.SessionExpired

    // ---------- 内部：commit ----------

    private suspend fun commitLocked(
        plan: EngineSyncPlan<R>,
        selectedNewIds: Set<String>,
    ): CommitOutcome {
        val selected = plan.plannedNewItems.filter { it.todo.id in selectedNewIds }
        // 自动分组：勾选的新增条目 + 存量回填各自需要的组键；先把组建好并把存量未分组
        // 条目归位（含回收站——随后 restoreTodo 会原样保留刚补上的 group），再导入新增
        val groupKeys = buildSet {
            for (item in selected) codec.autoGroupKey(item.remote)?.let { add(it) }
            addAll(plan.groupBackfill.values)
        }
        val groups = resolveAutoGroups(groupKeys)
        val assignments = groupBackfillAssignments(groups.nameByKey, plan.groupBackfill)
        if (groups.ensure.isNotEmpty() || assignments.isNotEmpty()) {
            when (val r = todoRepository.applyGroupAssignments(groups.ensure, assignments)) {
                TodoOpResult.Ok -> Unit
                is TodoOpResult.Err -> return CommitOutcome.Failed(r.error)
            }
        }

        var restored = 0
        for (item in plan.restoreItems) {
            if (todoRepository.restoreTodo(item.todo.id) is TodoOpResult.Ok) restored++
        }

        var imported = 0
        if (selected.isNotEmpty()) {
            val prepared = selected.map { item ->
                val group = codec.autoGroupKey(item.remote)?.let { groups.nameByKey[it] }
                if (group == null) item.todo else item.todo.copy(group = group)
            }
            when (val r = todoRepository.addTodos(prepared)) {
                is TodoReadResult.Ok -> imported = r.value.size
                is TodoReadResult.Err -> return CommitOutcome.Failed(r.error)
            }
        }

        // 远端消失 → 完成；远端重新出现 → 恢复未完成（单次批量落库，无候选时不写盘）
        val completedAt = clock()
        val completion = when (
            val r = todoRepository.applySyncCompletion(
                completeIds = plan.plannedVanished.map { it.todo.id },
                reopenIds = plan.plannedReactivated.map { it.todo.id },
                completedAtMillis = completedAt,
            )
        ) {
            is TodoReadResult.Ok -> r.value
            is TodoReadResult.Err -> return CommitOutcome.Failed(r.error)
        }

        // entries 增量合并：新增/复活写新哈希基线，活跃条目只补缺（不洗掉既有基线）。
        // 键是 codec.remoteKey（校内唯一）：单键会让跨课程的映射互相覆盖
        val byRemote = _state.value.entries.associateBy { it.remoteKey }.toMutableMap()
        // 远端重新出现：清空自动完成基线（无论是否回滚，基线都已失效）
        for (update in plan.entryUpdates.values) byRemote[update.remoteKey] = update
        // 自动完成基线必须与本地 completedAtMillis 完全一致，供下次远端重新出现时判断能否回滚
        for (item in plan.plannedVanished) {
            byRemote[item.entry.remoteKey] = item.entry.copy(autoCompletedAtMillis = completedAt)
        }
        for (item in selected) {
            byRemote[codec.remoteKey(item.remote)] = entryOf(item.remote, item.todo)
        }
        for (item in plan.restoreItems) {
            byRemote[codec.remoteKey(item.remote)] = entryOf(item.remote, item.todo)
        }
        for (entry in plan.entriesForActive) byRemote.putIfAbsent(entry.remoteKey, entry)

        val next = _state.value.copy(
            entries = byRemote.values.toList(),
            lastSyncAtMillis = clock(),
        )
        if (!persist(next)) {
            // 映射没保存就不能报「已导入」；待办已入库，下次同步会按 entriesForActive 补写
            return CommitOutcome.Failed(TodoError.PersistFailed("sync.json save failed"))
        }
        return CommitOutcome.Done(
            SyncReport(
                imported = imported,
                restored = restored,
                skippedFiltered = plan.skipped.values.sum(),
                completed = completion.completed,
                reopened = completion.reopened,
            ),
        )
    }

    private suspend fun markSyncedLocked(plan: EngineSyncPlan<R>): Boolean {
        // 「无新增」同样要回填存量分组：每日自动同步/手动同步的已是最新分支都走这里
        if (!applyGroupBackfill(plan)) return false
        val byRemote = _state.value.entries.associateBy { it.remoteKey }.toMutableMap()
        // 远端重新出现但无需回滚（用户手动改过完成态）时，也要把基线清掉
        for (update in plan.entryUpdates.values) byRemote[update.remoteKey] = update
        for (entry in plan.entriesForActive) byRemote.putIfAbsent(entry.remoteKey, entry)
        return persist(
            _state.value.copy(
                entries = byRemote.values.toList(),
                lastSyncAtMillis = clock(),
            ),
        )
    }

    private fun entryOf(remote: R, todo: Todo): SchoolSyncEntry = SchoolSyncEntry(
        remoteKey = codec.remoteKey(remote),
        todoId = todo.id,
        remoteHash = codec.remoteHash(remote),
        localHash = localHash(todo),
    )

    /** 本地内容哈希：导入时对映射结果取哈希，作为「用户是否编辑过」的基线 */
    private fun localHash(todo: Todo): String {
        val text = listOf(
            todo.name,
            todo.deadlineDate?.toString() ?: "",
            todo.deadlineMinute?.toString() ?: "",
            todo.note,
        ).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 自动分组解析结果：[ensure] 需要落库的组；[nameByKey] 分组键 → 当前使用的组名 */
    private data class ResolvedAutoGroups(
        val ensure: List<TodoGroup>,
        val nameByKey: Map<String, String>,
    )

    /**
     * 解析本次同步需要的自动分组（只读，不落库）：
     * - 已存在同 [TodoGroup.autoKey] 的分组 → 直接用它的当前名称（用户重命名后继续沿用）；
     * - 没有同 autoKey 但有同名用户分组 → 收养（补 autoKey，不改名、不挪位置）；
     * - 都没有 → 用默认名新建；默认名被别的分组占用时追加「 2/3…」保证组名唯一；
     * - 顺序固定为「作业、测验」，与远端返回顺序无关。
     */
    private fun resolveAutoGroups(keys: Collection<String>): ResolvedAutoGroups {
        val desiredKeys = groupNames.orderedKeys.filter { it in keys }
        if (desiredKeys.isEmpty()) return ResolvedAutoGroups(emptyList(), emptyMap())
        val current = todoRepository.groups.value
        val usedNames = current.mapTo(mutableSetOf()) { it.name }
        val byKey = current.mapNotNull { group -> group.autoKey?.let { it to group } }.toMap()
        val resolved = LinkedHashMap<String, TodoGroup>()
        for (key in desiredKeys) {
            val existing = byKey[key]
            if (existing != null) {
                resolved[key] = existing
                continue
            }
            val preferred = groupNames.nameFor(key) ?: continue
            val adoptable = current.firstOrNull { it.name == preferred && it.autoKey == null }
            val group = when {
                adoptable != null -> adoptable.copy(autoKey = key)
                preferred !in usedNames -> TodoGroup(name = preferred, autoKey = key)
                else -> TodoGroup(name = uniqueGroupName(preferred, usedNames), autoKey = key)
            }
            usedNames += group.name
            resolved[key] = group
        }
        return ResolvedAutoGroups(resolved.values.toList(), resolved.mapValues { it.value.name })
    }

    /** 默认组名被占用时的兜底名：「作业 2」「作业 3」…（仅组名冲突的极端场景） */
    private fun uniqueGroupName(base: String, used: Set<String>): String {
        var index = 2
        while ("$base $index" in used) index++
        return "$base $index"
    }

    /** 存量回填的 todoId → 组名映射（分组键解析不到名称的忽略） */
    private fun groupBackfillAssignments(
        nameByKey: Map<String, String>,
        backfill: Map<String, String>,
    ): Map<String, String> = buildMap {
        for ((todoId, key) in backfill) {
            nameByKey[key]?.let { put(todoId, it) }
        }
    }

    /** 只回填存量未分组同步待办（活跃/归档/回收站）；失败返回 false 交调用方报错 */
    private suspend fun applyGroupBackfill(plan: EngineSyncPlan<R>): Boolean {
        if (plan.groupBackfill.isEmpty()) return true
        val groups = resolveAutoGroups(plan.groupBackfill.values)
        val assignments = groupBackfillAssignments(groups.nameByKey, plan.groupBackfill)
        if (groups.ensure.isEmpty() && assignments.isEmpty()) return true
        return when (todoRepository.applyGroupAssignments(groups.ensure, assignments)) {
            TodoOpResult.Ok -> true
            is TodoOpResult.Err -> false
        }
    }

    // ---------- 内部：持久化 / plan 认领 ----------

    private suspend fun persist(next: SchoolSyncFile): Boolean {
        val stamped = next.copy(formatVersion = SCHOOL_SYNC_FORMAT_VERSION)
        return when (val result = withContext(Dispatchers.IO) { store.save(stamped) }) {
            StoreResult.Ok -> {
                _state.value = stamped
                true
            }
            is StoreResult.Failed -> {
                logger("persist failed: ${result.detail}")
                false
            }
        }
    }

    /**
     * 会话认领计划（指导 §2.4 #11）：plan 是产出它的引擎持有的不透明句柄，跨 session/跨校
     * 传入是编程错误（不能静默按新校解释），直接失败而非返回误导性结果。
     */
    private fun claim(plan: SyncPlan): EngineSyncPlan<R> {
        require(plan is EngineSyncPlan<*> && plan.owner === this) {
            "sync plan was produced by another school session"
        }
        @Suppress("UNCHECKED_CAST")
        return plan as EngineSyncPlan<R>
    }

    private companion object {
        /** 探活/同步最小间隔（防连点重复请求；放到 3 秒，避免正常重试被拦） */
        const val PROBE_DEBOUNCE_MS = 3_000L
    }
}

/** 预览计划的一项：远端对象只供引擎内部分组/哈希使用，UI 只读 [todo]/[courseName] */
private data class PlanItem<R : Any>(val remote: R, val todo: Todo, val courseName: String)

/** 远端已消失的已映射条目：commit 时标记完成并写入自动完成基线 */
private data class VanishedItem(val entry: SchoolSyncEntry, val todo: Todo)

/** 远端重新出现的自动完成条目：commit 时恢复未完成（基线由 entryUpdates 清空） */
private data class ReactivatedItem(val todo: Todo)

/**
 * 引擎持有的计划句柄：UI 只读归一化属性；commit/markSynced 由 [owner] 认领。
 * `courseName` 仅作引擎内部分组键（UI 按 `todo.sourceDetail` 分组，与现状一致）。
 */
private class EngineSyncPlan<R : Any>(
    val owner: SchoolSyncEngine<R>,
    val plannedNewItems: List<PlanItem<R>>,
    val restoreItems: List<PlanItem<R>>,
    val plannedVanished: List<VanishedItem>,
    val plannedReactivated: List<ReactivatedItem>,
    val entriesForActive: List<SchoolSyncEntry>,
    /** 既有映射的更新（清空自动完成基线等）；commit 与 markSynced 都要合并 */
    val entryUpdates: Map<String, SchoolSyncEntry>,
    val skipped: Map<SkipReason, Int>,
    @Suppress("unused") val totalRemote: Int,
    val groupBackfill: Map<String, String>,
) : SyncPlan {

    override val newItems: List<SyncPlanItem> =
        plannedNewItems.map { SyncPlanItem(it.todo, it.courseName) }

    override val restoreCount: Int = restoreItems.size

    override val vanishedItems: List<SyncPlanItem> =
        plannedVanished.map { SyncPlanItem(it.todo, it.todo.sourceDetail.orEmpty()) }

    override val reactivatedItems: List<SyncPlanItem> =
        plannedReactivated.map { SyncPlanItem(it.todo, it.todo.sourceDetail.orEmpty()) }

    override val skippedTotal: Int = skipped.values.sum()

    override val deadlineUnparseableCount: Int =
        skipped[SkipReason.DeadlineUnparseable] ?: 0
}

/** 在途计数：单飞调用期间 [busy] 为 true（并发调用只在计数归零时复位） */
private class BusyTracker {
    private val active = AtomicInteger(0)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun <T> track(block: suspend () -> T): T {
        if (active.incrementAndGet() == 1) _busy.value = true
        try {
            return block()
        } finally {
            if (active.decrementAndGet() == 0) _busy.value = false
        }
    }
}

private fun FetchError.toSyncError(): SyncError = when (this) {
    FetchError.SessionExpired -> SyncError.SessionExpired
    FetchError.ServiceBusy -> SyncError.ServiceBusy
    FetchError.NetworkError -> SyncError.NetworkError
    FetchError.Timeout -> SyncError.Timeout
    FetchError.FormatChanged -> SyncError.FormatChanged
}
