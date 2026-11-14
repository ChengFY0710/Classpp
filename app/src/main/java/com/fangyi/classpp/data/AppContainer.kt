package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.ai.AiChatContext
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiClient
import com.fangyi.classpp.data.ai.AiDebugTrace
import com.fangyi.classpp.data.ai.AiImageStore
import com.fangyi.classpp.data.ai.AiOrganizerWriteOutcome
import com.fangyi.classpp.data.ai.AiTodoChangeWriter
import com.fangyi.classpp.data.ai.AiTodoOrganizerWriter
import com.fangyi.classpp.data.ai.AiTodoPersister
import com.fangyi.classpp.data.ai.FileAiChatStore
import com.fangyi.classpp.data.ai.PrefsAiSettingsStore
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.sync.DailySyncController
import com.fangyi.classpp.data.sync.PrefsActiveSchoolStore
import com.fangyi.classpp.data.sync.SchoolDailySyncController
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolRegistry
import com.fangyi.classpp.data.sync.SessionDeps
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.remote.SchoolRemoteCatalog
import com.fangyi.classpp.data.sync.remote.SchoolRemoteConfig
import com.fangyi.classpp.data.sync.remote.toRemoteProviders
import com.fangyi.classpp.schools.xmu.XmuSchoolProvider
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 进程级依赖容器：各仓库的唯一装配点（原各自 companion 的手动单例——多份
 * instanceMutex/instance/get——收敛为一条路径，装载时机与语义不变：首次调用完成
 * 磁盘载入，之后进程内恒定同一实例）。
 *
 * 学校装配自 Phase 1 起收敛到 [schools]（[SchoolRegistry]）：厦大的 provider 把
 * 冻结的 `data/xmu` 组件组装成通用会话；UI 只读 [SchoolRegistry.activeSession]，不再持有
 * 任何学校内部对象（Phase 2 删除了迁移期的 `xmu*` 引用别名与三层穿透参数）。
 *
 * 测试不走这里——各仓库的 [create] 注入 fake store，容器只是应用侧的装配胶水。
 */
class AppContainer internal constructor(
    val scheduleRepository: ScheduleRepository,
    val todoRepository: TodoRepository,
    val tagRepository: TagRepository,
    /** 学校注册表：多校适配的唯一装配点与活跃会话入口 */
    val schools: SchoolRegistry,
    /** 每日同步的调度/补漏入口（校无关契约；学校适配层提供实现） */
    val dailySync: DailySyncController,
    /** AI 仓库工厂：容器发布时不读 ai_chat.json，首次 ensure/后台预热时才装配 */
    private val aiChatFactory: suspend () -> AiChatRepository,
) {
    /** 进程级 AI 预热域：不随宿主页面卸载取消（同 AiChatRepository 自有 scope 的理由） */
    private val aiChatScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val aiChatMutex = Mutex()

    /** 快路径读值；写入在 [aiChatMutex] 内，@Volatile 保证跨线程可见 */
    @Volatile
    private var aiChat: AiChatRepository? = null
    private val _aiChatRepository = MutableStateFlow<AiChatRepository?>(null)

    /** 预热循环已启动：幂等闸门，Activity 重建重复调用不再叠加退避协程 */
    private val aiChatWarmUpStarted = AtomicBoolean(false)

    /**
     * AI 仓库就绪态：null = 尚未装配（UI 显示加载态）。
     * 容器就绪后再由 [warmUpAiChat] 后台预热；首次打开 AI 浮层/设置页时也能直接读到。
     */
    val aiChatRepository: StateFlow<AiChatRepository?> = _aiChatRepository.asStateFlow()

    /** 单飞装配 AI 仓库；失败不缓存，下次调用可重试 */
    suspend fun ensureAiChatRepository(): AiChatRepository {
        aiChat?.let { return it }
        return aiChatMutex.withLock {
            aiChat ?: aiChatFactory().also { created ->
                aiChat = created
                _aiChatRepository.value = created
            }
        }
    }

    /**
     * 容器发布后的后台预热：延迟 [AI_WARMUP_DELAY_MILLIS] 让首帧/tab 预热先落定，
     * 再在 Default 上装配 AI 历史。
     *
     * 失败按 2s→4s→…→[AI_WARMUP_MAX_RETRY_DELAY_MILLIS] 上限退避**长期重试**：装配失败
     * 多半是启动瞬间的 IO/Keystore 竞争，放弃一次就再没有恢复路径——AI 入口会永久停在
     * 加载态，而修复它需要的「AI 设置」页本身也依赖该仓库。重试只在进程存活时进行，
     * 后台被系统冻结后自然停摆，不产生常驻唤醒。单飞装配 + [aiChatWarmUpStarted] 幂等，
     * Activity 重建重复调用不会叠加退避协程。
     */
    fun warmUpAiChat() {
        if (!aiChatWarmUpStarted.compareAndSet(false, true)) return
        aiChatScope.launch {
            var attempt = 0
            while (true) {
                try {
                    delay(aiChatWarmUpDelay(attempt))
                    ensureAiChatRepository()
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    attempt++
                }
            }
        }
    }

    /** 首次 300ms 让位首帧，之后 2s 起步翻倍，封顶 30s */
    private fun aiChatWarmUpDelay(attempt: Int): Long = if (attempt == 0) {
        AI_WARMUP_DELAY_MILLIS
    } else {
        (AI_WARMUP_RETRY_DELAY_MILLIS shl (attempt - 1).coerceAtMost(4))
            .coerceAtMost(AI_WARMUP_MAX_RETRY_DELAY_MILLIS)
    }

    companion object {
        private val instanceMutex = Mutex()

        @Volatile
        private var instance: AppContainer? = null

        /** AI 后台预热延迟：避开冷启动首帧与 tab 预热的同帧 IO/CPU 争抢 */
        private const val AI_WARMUP_DELAY_MILLIS = 300L

        /** 预热失败后的退避：2s 起步逐次翻倍，封顶 30s（长期重试，不放弃） */
        private const val AI_WARMUP_RETRY_DELAY_MILLIS = 2_000L
        private const val AI_WARMUP_MAX_RETRY_DELAY_MILLIS = 30_000L

        /** 应用内进程级单例（首次调用完成各库磁盘载入；I/O 已由各仓库经 Dispatchers.IO 下沉） */
        suspend fun get(context: Context): AppContainer {
            instance?.let { return it }
            return instanceMutex.withLock {
                instance ?: create(context).also { instance = it }
            }
        }

        /**
         * 已装配容器（可能为 null）：通知排期等后台路径据此复用内存态，
         * 未装配时退回直接读轻量 JSON，不触发完整容器冷启动。
         */
        fun peek(): AppContainer? = instance

        /**
         * 系统事件（开机 / 时间·时区·日期变化）只重排每日闹钟的轻量入口：
         * 只读 `school_prefs` 的活跃学校与各校开关，不载入数据仓库、不装配完整会话
         * （指导 §2.7）。到点执行仍走 [get] 的完整容器。
         */
        fun scheduleDailySyncInBackground(context: Context) {
            val appContext = context.applicationContext
            // 轻量路径同样并入本地缓存的远端校：活跃校可能是手动更新后切过去的远端校，
            // 闹钟必须按它重排。只读缓存，不联网、不装配会话。
            val registry = SchoolRegistry(
                providers = mergedProviders(loadCachedRemoteProviders(appContext)),
                // 轻量路径不应装配会话：真被调用即接线错误，直接失败暴露
                assembler = { error("background daily sync must not assemble a session") },
                toggleOf = { provider -> provider.dailySyncToggle(appContext) },
                activeStore = PrefsActiveSchoolStore(appContext),
            )
            SchoolDailySyncController(registry).scheduleNext(appContext)
        }

        /** 学校注册列表：新校接入 = 在此加一行（指导 §2.5 装配点唯一） */
        private fun schoolProviders(): List<SchoolProvider> = listOf(XmuSchoolProvider())

        /** 编译期学校在前（默认活跃校不变），远端声明按 id 去重追加 */
        private fun mergedProviders(remote: List<SchoolProvider>): List<SchoolProvider> {
            val base = schoolProviders()
            val known = base.mapTo(HashSet()) { it.spec.id }
            return base + remote
                .distinctBy { it.spec.id }
                .filter { it.spec.id !in known }
        }

        /**
         * 本地缓存的远端声明 → provider：**只读磁盘、不联网**。用户手动更新过的声明
         * 已由 [SchoolRemoteCatalog] 落盘，冷启动据此恢复远端校；从未手动更新则返回空
         * （远端学校默认关闭）。坏声明单条跳过，不拖垮冷启动。
         */
        private fun loadCachedRemoteProviders(context: Context): List<SchoolProvider> =
            runCatching {
                SchoolRemoteCatalog(cacheDir = SchoolRemoteConfig.cacheDir(context))
                    .loadCached()
                    .toRemoteProviders()
            }.getOrDefault(emptyList())

        /**
         * 构建并从磁盘载入各库（测试可用：传入应用上下文，仓库目录取 filesDir）。
         *
         * 三个整库 + 远端校缓存互不依赖，并行载入：容器就绪时间 = 最慢单链路而非各链路之和。
         * 学校会话与 AI 历史**不**在这里装配：前者由宿主在容器发布后后台预热，后者经
         * [ensureAiChatRepository]/[warmUpAiChat] 惰性装配——通知 receiver 等后台路径
         * 不再为一条提醒解析 AI 全量历史或解密 Keystore。
         */
        internal suspend fun create(context: Context): AppContainer = coroutineScope {
            val appContext = context.applicationContext
            val dir = appContext.filesDir
            // AI 工具链路诊断（仅 debug 构建）：token/首字延迟/工具误用的观察口径
            AiDebugTrace.init(appContext)

            // 显式指定 IO：create() 的调用方在 Main（Compose effect / receiver），不指定会
            // 让每个 async 在首个挂起点前占用主线程
            val todoDeferred = async(Dispatchers.IO) { TodoRepository.create(FileTodoStore(dir)) }
            val scheduleDeferred = async(Dispatchers.IO) {
                ScheduleRepository.create(FileScheduleStore(dir))
            }
            val tagDeferred = async(Dispatchers.IO) { TagRepository.create(FileTagStore(dir)) }
            // 远端声明学校默认关闭：从未手动更新（无本地缓存）时不注册任何远端校。
            // 用户手动更新过的声明已落盘，冷启动**只读本地缓存**恢复远端校（不联网）；
            // 联网只发生在用户点击「更新学校列表」时（见 data/sync/remote）。
            val remoteProvidersDeferred = async(Dispatchers.IO) {
                loadCachedRemoteProviders(appContext)
            }

            val todoRepository = todoDeferred.await()
            val scheduleRepository = scheduleDeferred.await()
            val tagRepository = tagDeferred.await()
            val remoteProviders = remoteProvidersDeferred.await()

            // 学校注册表：装配点唯一。这里只提供共享依赖与 provider 列表（新校注册 = 加一行）；
            // 厦大的全部装配逻辑在其 provider 内（搬移自本文件的历史实现，逻辑与注释保留）。
            // 活跃学校 id 落 school_prefs；切校成功后的闹钟重排经 onActiveSchoolChanged 接线。
            // 会话装配不再 eager：容器先发布，宿主/ScheduleScreen 读 activeSession 的
            // Loading/Ready/Failed 槽位，后台预热与每日同步补漏共享同一单飞装配。
            var dailySyncController: DailySyncController? = null
            // 注册表 init 会读 school_prefs（活跃校 id）：下沉 IO，避免容器返回前
            // 在 Main 上做一次 SharedPreferences 冷读
            val schools = withContext(Dispatchers.IO) {
                SchoolRegistry(
                    providers = mergedProviders(remoteProviders),
                    assembler = { provider ->
                        provider.createSession(
                            SessionDeps(
                                appContext = appContext,
                                filesDir = dir,
                                todoRepository = todoRepository,
                                scheduleRepository = scheduleRepository,
                            ),
                        )
                    },
                    toggleOf = { provider -> provider.dailySyncToggle(appContext) },
                    activeStore = PrefsActiveSchoolStore(appContext),
                    onActiveSchoolChanged = {
                        // 切校后闹钟必须按新校开关重排，否则会停留在旧校状态
                        dailySyncController?.scheduleNext(appContext)
                    },
                )
            }

            val dailySync = SchoolDailySyncController(schools).also { dailySyncController = it }

            AppContainer(
                scheduleRepository = scheduleRepository,
                todoRepository = todoRepository,
                tagRepository = tagRepository,
                schools = schools,
                // 每日同步实现：通用 scheduler/runner 接活跃学校声明的闹钟锚点
                dailySync = dailySync,
                aiChatFactory = {
                    AiChatRepository.create(
                        store = FileAiChatStore(dir),
                        settings = PrefsAiSettingsStore(appContext),
                        client = AiClient(),
                        contextProvider = {
                            val activeSchedule = scheduleRepository.activeSchedule.value
                            AiChatContext(
                                schedule = activeSchedule,
                                currentWeek = activeSchedule?.let {
                                    scheduleRepository.currentWeek(it.id)
                                },
                                todos = todoRepository.todos.value,
                                archivedTodos = todoRepository.archivedTodos.value,
                                deletedTodos = todoRepository.deletedTodos.value,
                                groups = todoRepository.groups.value.map { it.name },
                                tags = tagRepository.tags.value.map { it.name },
                            )
                        },
                        // 回复里的待办直接落库（无需确认）：与手建同路径，走全量校验与单次提交
                        persister = AiTodoPersister { todos ->
                            todoRepository.addTodos(todos) is TodoReadResult.Ok
                        },
                        // 修改/删除待办：用户点确认后才会调用，走仓库既有归一化/校验/软删除路径
                        changeWriter = object : AiTodoChangeWriter {
                            override suspend fun update(todo: Todo): Boolean =
                                todoRepository.updateTodo(todo) is TodoOpResult.Ok

                            override suspend fun deleteAll(ids: List<String>): Boolean =
                                todoRepository.removeTodos(ids) is TodoOpResult.Ok

                            override suspend fun setCompleted(id: String, completed: Boolean): Boolean =
                                todoRepository.setCompleted(id, completed) is TodoOpResult.Ok

                            override suspend fun setOccurrenceCompleted(
                                id: String,
                                date: IsoDate,
                                completed: Boolean,
                            ): Boolean =
                                todoRepository.setOccurrenceCompleted(id, date, completed) is TodoOpResult.Ok

                            override suspend fun archive(id: String): Boolean =
                                todoRepository.archiveTodo(id) is TodoOpResult.Ok

                            override suspend fun unarchive(id: String): Boolean =
                                todoRepository.unarchiveTodo(id) is TodoOpResult.Ok

                            override suspend fun restore(id: String): Boolean =
                                todoRepository.restoreTodo(id) is TodoOpResult.Ok
                        },
                        // 新建分组/标签（AI 立即落库）：分组在仓库 mutex 内原子追加，同名幂等
                        organizerWriter = object : AiTodoOrganizerWriter {
                            override suspend fun ensureGroup(name: String): AiOrganizerWriteOutcome {
                                val prepared = name.trim()
                                if (prepared.isEmpty()) return AiOrganizerWriteOutcome.Failed
                                val existed = todoRepository.groups.value.any { it.name == prepared }
                                val result = todoRepository.applyGroupAssignments(
                                    ensureGroups = listOf(TodoGroup(prepared)),
                                    assignments = emptyMap(),
                                )
                                return when {
                                    result is TodoOpResult.Err -> AiOrganizerWriteOutcome.Failed
                                    existed -> AiOrganizerWriteOutcome.Existing
                                    else -> AiOrganizerWriteOutcome.Created
                                }
                            }

                            override suspend fun ensureTag(name: String): AiOrganizerWriteOutcome {
                                val prepared = name.trim()
                                if (prepared.isEmpty()) return AiOrganizerWriteOutcome.Failed
                                return when (val result = tagRepository.addTag(prepared)) {
                                    TagOpResult.Ok -> AiOrganizerWriteOutcome.Created
                                    is TagOpResult.Err -> when (result.error) {
                                        is TagError.DuplicateName -> AiOrganizerWriteOutcome.Existing
                                        else -> AiOrganizerWriteOutcome.Failed
                                    }
                                }
                            }
                        },
                        // 对话图片附件（相册/拍照导入的压缩副本），UI 借仓库的 imageStore 导入
                        imageStore = AiImageStore(File(appContext.filesDir, "ai_chat_images")),
                    )
                },
            )
        }
    }
}
