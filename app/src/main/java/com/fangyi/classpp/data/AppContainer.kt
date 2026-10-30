package com.fangyi.classpp.data

import android.content.Context
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.FileTodoStore
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级依赖容器：三个仓库的唯一装配点（原各自 companion 的手动单例——三份
 * instanceMutex/instance/get——收敛为一条路径，装载时机与语义不变：首次调用完成
 * 磁盘载入，之后进程内恒定同一实例）。
 *
 * 测试不走这里——各仓库的 [create] 注入 fake store，容器只是应用侧的装配胶水。
 */
class AppContainer internal constructor(
    val scheduleRepository: ScheduleRepository,
    val todoRepository: TodoRepository,
    val tagRepository: TagRepository,
) {
    companion object {
        private val instanceMutex = Mutex()
        private var instance: AppContainer? = null

        /** 应用内进程级单例（首次调用完成三库磁盘载入；I/O 已由各仓库经 Dispatchers.IO 下沉） */
        suspend fun get(context: Context): AppContainer {
            instance?.let { return it }
            return instanceMutex.withLock {
                instance ?: create(context.filesDir).also { instance = it }
            }
        }

        /** 构建并从磁盘载入三库（测试可用：传入临时目录） */
        internal suspend fun create(dir: File): AppContainer = AppContainer(
            scheduleRepository = ScheduleRepository.create(FileScheduleStore(dir)),
            todoRepository = TodoRepository.create(FileTodoStore(dir)),
            tagRepository = TagRepository.create(FileTagStore(dir)),
        )
    }
}
