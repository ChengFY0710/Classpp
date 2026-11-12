package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.ai.AiChatContext
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiClient
import com.fangyi.classpp.data.ai.AiDebugTrace
import com.fangyi.classpp.data.ai.AiImageStore
import com.fangyi.classpp.data.ai.AiTodoChangeWriter
import com.fangyi.classpp.data.ai.AiTodoPersister
import com.fangyi.classpp.data.ai.FileAiChatStore
import com.fangyi.classpp.data.ai.PrefsAiSettingsStore
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
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
import kotlinx.coroutines.Dispatchers
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
    /** AI 对话（BYOK）：日程页顶栏入口，见 data.ai 与 .mimocode/docs/AI功能计划.md */
    val aiChatRepository: AiChatRepository,
) {
    companion object {
        private val instanceMutex = Mutex()
        private var instance: AppContainer? = null

        /** 应用内进程级单例（首次调用完成各库磁盘载入；I/O 已由各仓库经 Dispatchers.IO 下沉） */
        suspend fun get(context: Context): AppContainer {
            instance?.let { return it }
            return instanceMutex.withLock {
                instance ?: create(context).also { instance = it }
            }
        }

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

        /** 构建并从磁盘载入各库（测试可用：传入应用上下文，仓库目录取 filesDir） */
        internal suspend fun create(context: Context): AppContainer {
            val appContext = context.applicationContext
            val dir = appContext.filesDir
            // AI 工具链路诊断（仅 debug 构建）：token/首字延迟/工具误用的观察口径
            AiDebugTrace.init(appContext)
            val todoRepository = TodoRepository.create(FileTodoStore(dir))
            val scheduleRepository = ScheduleRepository.create(FileScheduleStore(dir))
            val tagRepository = TagRepository.create(FileTagStore(dir))

            // 远端声明学校默认关闭：从未手动更新（无本地缓存）时不注册任何远端校。
            // 用户手动更新过的声明已落盘，冷启动**只读本地缓存**恢复远端校（不联网）；
            // 联网只发生在用户点击「更新学校列表」时（见 data/sync/remote）。
            val remoteProviders = withContext(Dispatchers.IO) {
                loadCachedRemoteProviders(appContext)
            }

            // 学校注册表：装配点唯一。这里只提供共享依赖与 provider 列表（新校注册 = 加一行）；
            // 厦大的全部装配逻辑在其 provider 内（搬移自本文件的历史实现，逻辑与注释保留）。
            // 活跃学校 id 落 school_prefs；切校成功后的闹钟重排经 onActiveSchoolChanged 接线。
            var dailySyncController: DailySyncController? = null
            val schools = SchoolRegistry(
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

            // 冷启动装配活跃学校（默认注册首项 / 上次切校的学校）：与改造前一致，
            // 容器返回即各库载入完成。装配失败时 registry 已把 activeSession 置 Failed，
            // 异常继续抛出（原路径同样会抛）。
            schools.session()

            val dailySync = SchoolDailySyncController(schools).also { dailySyncController = it }

            return AppContainer(
                scheduleRepository = scheduleRepository,
                todoRepository = todoRepository,
                tagRepository = tagRepository,
                schools = schools,
                // 每日同步实现：通用 scheduler/runner 接活跃学校声明的闹钟锚点
                dailySync = dailySync,
                aiChatRepository = AiChatRepository.create(
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
                    // 对话图片附件（相册/拍照导入的压缩副本），UI 借仓库的 imageStore 导入
                    imageStore = AiImageStore(File(appContext.filesDir, "ai_chat_images")),
                ),
            )
        }
    }
}
