package com.fangyi.classpp.data

import android.content.Context
import android.util.Base64
import com.fangyi.classpp.data.store.FileScheduleStore
import com.fangyi.classpp.data.store.FileTagStore
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.xmu.AndroidXmuCookieMirror
import com.fangyi.classpp.data.xmu.KeystoreXmuSecureStore
import com.fangyi.classpp.data.xmu.XmuAutoLoginManager
import com.fangyi.classpp.data.xmu.XmuDebugLog
import com.fangyi.classpp.data.xmu.XmuLoginClient
import com.fangyi.classpp.data.xmu.XmuNativeTodoApi
import com.fangyi.classpp.data.xmu.XmuOkHttpTransport
import com.fangyi.classpp.data.xmu.XmuPasswordCipher
import com.fangyi.classpp.data.xmu.XmuSessionCookieHolder
import com.fangyi.classpp.data.xmu.XmuSyncRepository
import com.fangyi.classpp.data.xmu.XmuSyncStore
import com.fangyi.classpp.data.xmu.XmuTodoApiRouter
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
    /** 方案 B 自动续期的状态与凭据（UI 的「自动续期」开关读写它） */
    val xmuAutoLoginManager: XmuAutoLoginManager,
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
            XmuDebugLog.init(context.applicationContext)
            val dir = context.filesDir
            val todoRepository = TodoRepository.create(FileTodoStore(dir))

            // 方案 B：原生 OAuth 登录 + 自动续期。传输层（OkHttp + IPv4 优先 DNS，
            // XMU 主机发布不可达 AAAA 记录，HttpURLConnection 会先试 v6 黑洞）与
            // cookie 持有者由登录客户端和原生取数共享；WebView 路径仍由 XmuWebFetcher 保持原样。
            val transport = XmuOkHttpTransport()
            val session = XmuSessionCookieHolder()
            val xmuAutoLoginManager = XmuAutoLoginManager(
                store = KeystoreXmuSecureStore(context.applicationContext),
                loginClient = XmuLoginClient(
                    transport = transport,
                    // minSdk 24 不能用 java.util.Base64；密码加密的编码器在此注入
                    cipher = XmuPasswordCipher(
                        base64Encoder = { Base64.encodeToString(it, Base64.NO_WRAP) },
                    ),
                ),
                nativeApi = XmuNativeTodoApi(transport, session),
                session = session,
                cookieMirror = AndroidXmuCookieMirror,
            ).also { it.bootstrap() }

            return AppContainer(
                scheduleRepository = ScheduleRepository.create(FileScheduleStore(dir)),
                todoRepository = todoRepository,
                tagRepository = TagRepository.create(FileTagStore(dir)),
                xmuSyncRepository = XmuSyncRepository(
                    store = XmuSyncStore(dir),
                    todoRepository = todoRepository,
                    // 自动续期关闭/暂停/不可用时原样委托无头 WebView（方案 A）
                    api = XmuTodoApiRouter(XmuWebFetcher(context.applicationContext), xmuAutoLoginManager),
                ).also { it.bootstrap() },
                xmuAutoLoginManager = xmuAutoLoginManager,
            )
        }
    }
}
