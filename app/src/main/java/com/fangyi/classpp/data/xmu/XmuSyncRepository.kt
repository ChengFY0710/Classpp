package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
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
    /** xmu-sync.json 落盘失败（磁盘满/权限等）：映射没保存，不能当成功 */
    PersistFailed,
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
    /**
     * 存量同步待办的分组回填：本地 todoId → 自动分组键（[XmuTodoMapper.AUTO_GROUP_KEY_HOMEWORK]
     * 等）。只收当前 `group == null` 的条目——用户已手动分组的保持原样；远端 type 未知的也不收。
     */
    val groupBackfill: Map<String, String> = emptyMap(),
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
 * 唯一例外是本地分组元数据：按远端 type 自动维护「作业」「测验」分组，并给仍未分组的
 * 存量同步待办补分组（不碰标题/截止等远端字段，也不覆盖用户手动选择的组）。
 *
 * 并发纪律：探活/同步/清空共用一把 [mutex]，同一时刻只有一次会话；探活 3 秒去抖
 * （成功才刷新时间戳，失败立即可重试）。同步状态与 todos.json 完全独立文件（[XmuSyncStore]），
 * 损坏互不波及；entries 只存哈希不存标题原文。
 *
 * 头像：与同步同一次会话里顺带抓（[XmuAvatarExtractor] 取地址 → [XmuAvatarLoader] 下载 →
 * [XmuAvatarStore] 落盘），地址没变且文件在就跳过。失败只留日志、不影响同步结果，
 * UI 通过 [avatarImage] 观察本地图片。
 *
 * 构造：测试注入 fake [XmuTodoApi] 与固定时钟；应用内经 [com.fangyi.classpp.data.AppContainer] 装配。
 */
class XmuSyncRepository internal constructor(
    private val store: XmuSyncStore,
    /** 待办库本体（复活/批量导入走它；测试与 UI 也读它观察同步结果） */
    val todoRepository: TodoRepository,
    private val api: XmuTodoApi,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 头像缓存（null = 不抓头像：单测与未装配场景保持原行为） */
    private val avatarStore: XmuAvatarStore? = null,
    /** 头像下载（null = 不抓头像）；失败由实现返回 null，这里只当作「这次没抓到」 */
    private val avatarLoader: XmuAvatarLoader? = null,
    /** 自动分组首次创建时的默认名（AppContainer 按当前语言注入；测试回退中文） */
    private val autoGroupNames: XmuAutoGroupNames = XmuAutoGroupNames(),
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(XmuSyncFile())
    val state: StateFlow<XmuSyncFile> = _state.asStateFlow()

    private val _avatarImage = MutableStateFlow<XmuAvatarImage?>(null)

    /** 已缓存的头像（null = 没有头像）：交给 Coil 当本地模型，随下载/退出登录更新 */
    val avatarImage: StateFlow<XmuAvatarImage?> = _avatarImage.asStateFlow()

    /** 进程内是否已从磁盘载入（AppContainer 装配时调用一次） */
    suspend fun bootstrap() {
        val (outcome, cachedAvatar) = withContext(Dispatchers.IO) {
            store.load() to avatarStore?.cachedImage()
        }
        val loaded = when (outcome) {
            is LoadOutcome.Loaded<XmuSyncFile> -> outcome.file
            is LoadOutcome.Fresh<XmuSyncFile> -> outcome.file
        }
        _state.value = loaded
        // 状态文件与本地图片必须成对：只认「已连接 + 记过头像地址」的缓存，避免退出登录
        // 残留的图片被下一个账号复用
        _avatarImage.value = cachedAvatar.takeIf {
            loaded.uid.isNotEmpty() && loaded.avatarUrl.isNotEmpty()
        }
    }

    // ---------- 同步 ----------

    /**
     * 拉取远端并生成同步计划（拉取+解析+比对，无写入）：已连接用户点「立即同步」走这条。
     * 比对口径：活跃已存在 → 跳过；在回收站 → 复活；都没有 → 新增候选。
     */
    suspend fun prepareSync(force: Boolean = false): XmuPrepareOutcome = mutex.withLock {
        val current = _state.value
        // 时间戳是 wall clock：系统时间回拨后差值可能为负，此时不能一直判「刚刚同步过」
        val elapsed = nowMillis() - current.lastProbeAtMillis
        if (!force && current.lastProbeAtMillis > 0 &&
            elapsed >= 0 && elapsed < PROBE_DEBOUNCE_MS
        ) {
            return@withLock XmuPrepareOutcome.Failed(XmuSyncError.TooSoon)
        }
        when (val result = api.fetchSession()) {
            XmuFetchResult.SessionExpired -> XmuPrepareOutcome.Failed(XmuSyncError.SessionExpired)
            XmuFetchResult.ServiceBusy -> XmuPrepareOutcome.Failed(XmuSyncError.ServiceBusy)
            XmuFetchResult.NetworkError -> XmuPrepareOutcome.Failed(XmuSyncError.NetworkError)
            XmuFetchResult.Timeout -> XmuPrepareOutcome.Failed(XmuSyncError.Timeout)
            // 原生登录链路已确认结构变化（WebView 路径的正常/异常响应不会走到这里）
            XmuFetchResult.FormatChanged -> XmuPrepareOutcome.Failed(XmuSyncError.FormatChanged)
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
            val selected = plan.newItems.filter { it.todo.id in selectedNewIds }
            // 自动分组：勾选的新增条目 + 存量回填各自需要的组键；先把组建好并把存量未分组
            // 条目归位（含回收站——随后 restoreTodo 会原样保留刚补上的 group），再导入新增
            val groupKeys = buildSet {
                for (item in selected) XmuTodoMapper.autoGroupKey(item.dto.type)?.let(::add)
                addAll(plan.groupBackfill.values)
            }
            val groups = resolveAutoGroups(groupKeys)
            val assignments = groupBackfillAssignments(groups.nameByKey, plan.groupBackfill)
            if (groups.ensure.isNotEmpty() || assignments.isNotEmpty()) {
                when (val r = todoRepository.applyGroupAssignments(groups.ensure, assignments)) {
                    TodoOpResult.Ok -> Unit
                    is TodoOpResult.Err -> return@withLock XmuCommitOutcome.Failed(r.error)
                }
            }

            var restored = 0
            for (item in plan.restoreItems) {
                if (todoRepository.restoreTodo(item.todo.id) is TodoOpResult.Ok) restored++
            }

            var imported = 0
            if (selected.isNotEmpty()) {
                val prepared = selected.map { item ->
                    val group = XmuTodoMapper.autoGroupKey(item.dto.type)
                        ?.let { groups.nameByKey[it] }
                    if (group == null) item.todo else item.todo.copy(group = group)
                }
                when (val r = todoRepository.addTodos(prepared)) {
                    is TodoReadResult.Ok -> imported = r.value.size
                    is TodoReadResult.Err -> return@withLock XmuCommitOutcome.Failed(r.error)
                }
            }

            // entries 增量合并：新增/复活写新哈希基线，活跃条目只补缺（不洗掉既有基线，
            // P2 的「用户是否编辑过」判定靠它）。键是 remoteKey（课程+activity）：
            // remoteId 单键会让同 activity id 跨课程的映射互相覆盖
            val byRemote = _state.value.entries.associateBy { XmuTodoMapper.remoteKey(it) }.toMutableMap()
            for (item in selected) {
                byRemote[XmuTodoMapper.remoteKey(item.dto)] = XmuSyncEntry(
                    remoteId = item.dto.id,
                    courseId = item.dto.courseId,
                    todoId = item.todo.id,
                    remoteHash = XmuTodoMapper.remoteHash(item.dto),
                    localHash = XmuTodoMapper.localHash(item.todo),
                )
            }
            for (item in plan.restoreItems) {
                byRemote[XmuTodoMapper.remoteKey(item.dto)] = XmuSyncEntry(
                    remoteId = item.dto.id,
                    courseId = item.dto.courseId,
                    todoId = item.todo.id,
                    remoteHash = XmuTodoMapper.remoteHash(item.dto),
                    localHash = XmuTodoMapper.localHash(item.todo),
                )
            }
            for (entry in plan.entriesForActive) byRemote.putIfAbsent(XmuTodoMapper.remoteKey(entry), entry)

            val next = _state.value.copy(
                entries = byRemote.values.toList(),
                lastSyncAtMillis = nowMillis(),
            )
            if (!persist(next)) {
                // 映射没保存就不能报「已导入」；待办已入库，下次同步会按 entriesForActive 补写
                return@withLock XmuCommitOutcome.Failed(
                    TodoError.PersistFailed("xmu-sync.json save failed"),
                )
            }
            XmuCommitOutcome.Done(
                XmuSyncReport(
                    imported = imported,
                    restored = restored,
                    skippedFiltered = plan.skipped.values.sum(),
                ),
            )
        }

    /**
     * 无新增/无恢复时仅补写映射基线（[XmuSyncPlan.entriesForActive]）与 lastSyncAt，
     * 不产生任何待办：备份恢复（todos.json 保留、同步状态未保留）后重新登录靠它重建
     * 「上次同步」；返回 false 表示状态文件落盘失败。
     */
    suspend fun markSynced(plan: XmuSyncPlan): Boolean = mutex.withLock {
        // 「无新增」同样要回填存量分组：每日自动同步/手动同步的已是最新分支都走这里
        if (!applyGroupBackfill(plan)) return@withLock false
        val byRemote = _state.value.entries.associateBy { XmuTodoMapper.remoteKey(it) }.toMutableMap()
        for (entry in plan.entriesForActive) byRemote.putIfAbsent(XmuTodoMapper.remoteKey(entry), entry)
        persist(
            _state.value.copy(
                entries = byRemote.values.toList(),
                lastSyncAtMillis = nowMillis(),
            ),
        )
    }

    /**
     * 清空同步状态（退出登录与账号切换共用）：删 filesDir/xmu/ 下的状态文件与损坏隔离
     * 文件并复位内存态；[deleteSyncedTodos] 时把课程中心同步来的本地待办一并移除（活跃与
     * 已归档条目软删后随回收站条目一起彻底删除）。cookie 清理由 UI 层做
     * （CookieManager.removeAllCookies）。
     */
    suspend fun resetSyncData(deleteSyncedTodos: Boolean) = mutex.withLock {
        if (deleteSyncedTodos) {
            // 已归档条目不在活跃流里（归档 = 隐藏），退出清理要一并扫到，否则归档的同步待办残留
            val syncedActive = (todoRepository.todos.value + todoRepository.archivedTodos.value)
                .filter { it.isXmuSynced() }
                .map { it.id }
            if (syncedActive.isNotEmpty()) todoRepository.removeTodos(syncedActive)

            // 软删已把活跃条目全部推进回收站：一次批量 purge 彻底删掉，避免逐条全量落盘
            val syncedTrash = todoRepository.deletedTodos.value
                .filter { it.isXmuSynced() }
                .map { it.id }
            if (syncedTrash.isNotEmpty()) todoRepository.purgeTodos(syncedTrash)
        }
        withContext(Dispatchers.IO) {
            store.delete()
            // 头像属于账号数据：退出登录/换号一并清掉，避免下一个账号看到上一张脸
            avatarStore?.delete()
        }
        _avatarImage.value = null
        _state.value = XmuSyncFile()
    }

    // ---------- 内部 ----------

    /**
     * 是否课程中心同步条目：来源标识优先，再兜底 `xmu-` 前缀 id（v1 老数据、或被改过
     * source 的条目）。两处口径必须一致——分享导入会清掉 source，不能再靠它单独判定。
     */
    private fun Todo.isXmuSynced(): Boolean =
        source == XmuTodoMapper.SOURCE_XMU || XmuTodoMapper.isSyncedLocalId(id)

    /** 会话原文 → 解析 → 账号一致性 → 比对出计划；成功时顺带落探活/账号信息与头像 */
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

        // 头像：与同步同一次会话里顺带抓（纯装饰，失败保留上一张/首字占位）
        val avatarUrl = refreshAvatar(XmuAvatarExtractor.extract(profileRaw), current.avatarUrl)

        val next = current.copy(
            uid = profile.id.toString(),
            accountName = profile.name,
            lastProbeAtMillis = fetchedAt,
            avatarUrl = avatarUrl,
        )
        // 账号/探活状态没落盘就不能返回 Ready：否则连接卡仍显示未连接、去抖基线也错位
        if (!persist(next)) return XmuPrepareOutcome.Failed(XmuSyncError.PersistFailed)
        return XmuPrepareOutcome.Ready(buildPlan(profile, listDto, fetchedAt))
    }

    /**
     * 抓一次头像（**不抛出、不阻断同步**）：地址没变且本地文件在 → 不发请求；否则下载并
     * 校验落盘。返回「当前本地图片对应的地址」——成功是新地址，失败/未装配沿用 [cachedUrl]，
     * 这样下次同步会重试，而状态文件始终与本地图片一致。
     */
    private suspend fun refreshAvatar(url: String?, cachedUrl: String): String {
        val store = avatarStore ?: return cachedUrl
        val loader = avatarLoader ?: return cachedUrl
        if (url == null) {
            XmuDebugLog.d("avatar: absent")
            return cachedUrl
        }
        if (url == cachedUrl && withContext(Dispatchers.IO) { store.cachedImage() } != null) {
            XmuDebugLog.d("avatar: cached")
            return cachedUrl
        }
        val bytes = loader.load(url) ?: run {
            XmuDebugLog.d("avatar: failed")
            return cachedUrl
        }
        val saved = withContext(Dispatchers.IO) { store.save(bytes) }
        if (saved == null) {
            XmuDebugLog.d("avatar: save failed")
            return cachedUrl
        }
        XmuDebugLog.d("avatar: saved ${bytes.size}B")
        _avatarImage.value = saved
        return url
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
        val desiredKeys = XmuTodoMapper.AUTO_GROUP_KEY_ORDER.filter { it in keys }
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
            val preferred = autoGroupNames.nameFor(key) ?: continue
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
    private suspend fun applyGroupBackfill(plan: XmuSyncPlan): Boolean {
        if (plan.groupBackfill.isEmpty()) return true
        val groups = resolveAutoGroups(plan.groupBackfill.values)
        val assignments = groupBackfillAssignments(groups.nameByKey, plan.groupBackfill)
        if (groups.ensure.isEmpty() && assignments.isEmpty()) return true
        return when (todoRepository.applyGroupAssignments(groups.ensure, assignments)) {
            TodoOpResult.Ok -> true
            is TodoOpResult.Err -> false
        }
    }

    private fun buildPlan(
        profile: XmuProfileDto,
        listDto: XmuTodoListDto,
        fetchedAt: Long,
    ): XmuSyncPlan {
        val active = todoRepository.todos.value.associateBy { it.id }
        // 已归档条目不在活跃流里（归档 = 隐藏），比对时要算「已导入」——漏掉会把归档的
        // 同步待办当新增候选，用户确认导入后撞 DuplicateId 整批失败
        val archived = todoRepository.archivedTodos.value.associateBy { it.id }
        val trashed = todoRepository.deletedTodos.value.associateBy { it.id }
        val knownEntries = _state.value.entries.associateBy { XmuTodoMapper.remoteKey(it) }

        val newItems = mutableListOf<XmuPlanItem>()
        val restoreItems = mutableListOf<XmuPlanItem>()
        val entriesForActive = mutableListOf<XmuSyncEntry>()
        val skipped = mutableMapOf<SkipReason, Int>()
        val groupBackfill = mutableMapOf<String, String>()

        for (dto in listDto.todoList.distinctBy { XmuTodoMapper.remoteKey(it) }) {
            val rKey = XmuTodoMapper.remoteKey(dto)
            // 本地 id 迁移：映射里的 todoId（v1 不含 courseId）优先沿用；映射丢失时再按
            // v1 口径找回旧条目，避免升级/恢复后把已导入的待办当新增重复导入。
            // 先于 map() 解析：已逾期/已锁定等被过滤的远端条目仍可能对应本地已导入的
            // 待办，分组回填不能因为它这次被跳过就漏掉
            val knownTodoId = knownEntries[rKey]?.todoId
            val localId = sequenceOf(
                knownTodoId,
                XmuTodoMapper.localId(dto),
                XmuTodoMapper.legacyLocalId(dto),
            ).filterNotNull().firstOrNull { it in active || it in archived || it in trashed }
                ?: knownTodoId
                ?: XmuTodoMapper.localId(dto)
            // 已导入（活跃或已归档——归档只是隐藏，不改变导入事实）：跳过
            // （P2 再按哈希做远端变化更新）；映射缺档只补基线
            val liveTodo = active[localId] ?: archived[localId]
            val trashedTodo = trashed[localId]
            // 存量同步待办按远端 type 补自动分组：只补本地确实存在且仍未分组的，
            // 用户改过分组就尊重；远端新增候选不在这里回填（导入时按 type 直接设置）
            val existing = liveTodo ?: trashedTodo
            if (existing != null && existing.group == null) {
                XmuTodoMapper.autoGroupKey(dto.type)?.let { groupBackfill[localId] = it }
            }

            when (val outcome = XmuTodoMapper.map(dto, fetchedAt)) {
                is XmuMapOutcome.Skipped -> skipped.merge(outcome.reason, 1, Int::plus)
                is XmuMapOutcome.Mapped -> when {
                    liveTodo != null -> {
                        if (rKey !in knownEntries) {
                            entriesForActive += XmuSyncEntry(
                                remoteId = dto.id,
                                courseId = dto.courseId,
                                todoId = localId,
                                remoteHash = XmuTodoMapper.remoteHash(dto),
                                localHash = XmuTodoMapper.localHash(liveTodo),
                            )
                        }
                    }
                    trashedTodo != null -> restoreItems += XmuPlanItem(dto, trashedTodo)
                    else -> newItems += XmuPlanItem(dto, outcome.todo.copy(id = localId))
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
            groupBackfill = groupBackfill,
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
        // 载入的 v1 文件在首次落盘时升到当前版本号（字段语义不变，只是 id 口径标记）
        val stamped = next.copy(formatVersion = XMU_SYNC_FORMAT_VERSION)
        val result = withContext(Dispatchers.IO) { store.save(stamped) }
        return when (result) {
            StoreResult.Ok -> {
                _state.value = stamped
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
