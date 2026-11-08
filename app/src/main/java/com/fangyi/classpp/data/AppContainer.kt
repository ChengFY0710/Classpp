package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.ai.AiChatContext
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiClient
import com.fangyi.classpp.data.ai.AiImageStore
import com.fangyi.classpp.data.ai.AiTodoPersister
import com.fangyi.classpp.data.ai.FileAiChatStore
import com.fangyi.classpp.data.ai.PrefsAiSettingsStore
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.sync.DailySyncController
import com.fangyi.classpp.data.sync.SchoolRegistry
import com.fangyi.classpp.data.sync.SessionDeps
import com.fangyi.classpp.data.xmu.XmuDebugLog
import com.fangyi.classpp.schools.xmu.XmuDailySyncController
import com.fangyi.classpp.schools.xmu.XmuSchoolProvider
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

        /** 构建并从磁盘载入各库（测试可用：传入应用上下文，仓库目录取 filesDir） */
        internal suspend fun create(context: Context): AppContainer {
            val appContext = context.applicationContext
            XmuDebugLog.init(appContext)
            val dir = appContext.filesDir
            val todoRepository = TodoRepository.create(FileTodoStore(dir))
            val scheduleRepository = ScheduleRepository.create(FileScheduleStore(dir))
            val tagRepository = TagRepository.create(FileTagStore(dir))

            // 学校注册表：装配点唯一。这里只提供共享依赖与 provider 列表（新校注册 = 加一行）；
            // 厦大的全部装配逻辑在其 provider 内（搬移自本文件的历史实现，逻辑与注释保留）。
            val schools = SchoolRegistry(
                providers = listOf(XmuSchoolProvider()),
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
            )

            // 冷启动装配活跃学校（默认注册首项 = 厦大）：与改造前一致，容器返回即各库载入完成。
            // 装配失败时 registry 已把 activeSession 置 Failed，异常继续抛出（原路径同样会抛）。
            schools.session()

            return AppContainer(
                scheduleRepository = scheduleRepository,
                todoRepository = todoRepository,
                tagRepository = tagRepository,
                schools = schools,
                // 每日同步实现：当前由厦大薄壳委托冻结的三件套（Phase 3 迁入 data/sync 后替换）
                dailySync = XmuDailySyncController,
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
                            groups = todoRepository.groups.value.map { it.name },
                            tags = tagRepository.tags.value.map { it.name },
                        )
                    },
                    // 回复里的待办直接落库（无需确认）：与手建同路径，走全量校验与单次提交
                    persister = AiTodoPersister { todos ->
                        todoRepository.addTodos(todos) is TodoReadResult.Ok
                    },
                    // 对话图片附件（相册/拍照导入的压缩副本），UI 借仓库的 imageStore 导入
                    imageStore = AiImageStore(File(appContext.filesDir, "ai_chat_images")),
                ),
            )
        }
    }
}
