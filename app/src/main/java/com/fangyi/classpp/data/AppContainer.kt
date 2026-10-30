package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.xmu.XmuSyncRepository
import com.fangyi.classpp.data.xmu.XmuSyncStore
import com.fangyi.classpp.data.xmu.XmuWebFetcher
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级依赖容器：各仓库的唯一装配点（原各自 companion 的手动单例——多份
 * instanceMutex/instance/get——收敛为一条路径，装载时机与语义不变：首次调用完成
 * 磁盘载入，之后进程内恒定同一实例）。
 *
 * 测试不走这里——各仓库的 [create] 注入 fake store，容器只是应用侧的装配胶水。
 */
class AppContainer internal constructor(
    val scheduleRepository: ScheduleRepository,
    val todoRepository: TodoRepository,
    val tagRepository: TagRepository,
    val xmuSyncRepository: XmuSyncRepository,
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
            val dir = context.filesDir
            val todoRepository = TodoRepository.create(FileTodoStore(dir))
            return AppContainer(
                scheduleRepository = ScheduleRepository.create(FileScheduleStore(dir)),
                todoRepository = todoRepository,
                tagRepository = TagRepository.create(FileTagStore(dir)),
                xmuSyncRepository = XmuSyncRepository(
                    store = XmuSyncStore(dir),
                    todoRepository = todoRepository,
                    api = XmuWebFetcher(context.applicationContext),
                ).also { it.bootstrap() },
            )
        }
    }
}
