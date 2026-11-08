package com.fangyi.classpp.data.sync.engine

import android.content.Context
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.DailySyncToggle
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolSession
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SessionDeps
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.credential.AutoRenewManager
import com.fangyi.classpp.data.sync.credential.AutoRenewSessionRouter
import com.fangyi.classpp.data.sync.credential.LoginClient
import com.fangyi.classpp.data.sync.credential.LoginResult
import com.fangyi.classpp.data.sync.credential.LoginSession
import com.fangyi.classpp.data.sync.credential.SecureStore
import com.fangyi.classpp.data.sync.credential.StoredCredentials
import com.fangyi.classpp.data.sync.net.CookieMirror
import com.fangyi.classpp.data.sync.net.SessionCookieHolder
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 假学校 provider（测试工具，指导 §四 Phase 4）：完全用通用工具包装配——
 * [SchoolSyncEngine] + [AutoRenewManager] + 假登录客户端 + 假取数接口，不依赖任何真实
 * 学校代码。证明「一校一文件夹」适配路径可以不碰冻结实现走通。
 *
 * `createSession` 需要 Android [Context]（生产环境用于 prefs/SharedPreferences）；JVM
 * 单测不能构造 Context，因此测试走 [assemble]（与 `createSession` 同一装配函数）。
 */
internal class FakeSchoolProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SchoolProvider {

    override val spec: SchoolSpec = fakeSchoolSpec(
        id = "fakeu",
        capabilities = setOf(Capability.NATIVE_LOGIN, Capability.WEBVIEW_LOGIN),
    )

    val api = FakeSessionApi()
    val secureStore = InMemorySecureStore()
    val loginClient = ScriptedLoginClient()
    val mirror = RecordingCookieMirror()
    val toggle = InMemoryToggle()

    /** 装配一次完整会话（测试直接调用；生产路径 `createSession` 委托到这里） */
    suspend fun assemble(todoRepository: TodoRepository, filesDir: File): SchoolSession {
        val codec = FakeSyncCodec(source = spec.id) { nowMillis() }
        val holder = SessionCookieHolder()
        val autoRenew = AutoRenewManager(
            store = secureStore,
            loginClient = loginClient,
            sessionApi = api,
            cookieHolder = holder,
            cookieMirror = mirror,
            mirrorHost = WEB_VIEW.dataHost,
            nowMillis = nowMillis,
        ).also { it.bootstrap() }
        val engine = SchoolSyncEngine(
            spec = spec,
            codec = codec,
            store = SchoolSyncStore(filesDir, spec.id),
            todoRepository = todoRepository,
            api = AutoRenewSessionRouter(autoRenew, api),
            groupNames = SchoolAutoGroupNames(source = spec.id),
            clock = nowMillis,
        ).also { it.bootstrap() }
        return FakeSchoolSession(
            spec = spec,
            sync = engine,
            autoRenew = autoRenew,
            webViewLogin = WEB_VIEW,
            dailySync = toggle,
            webViewProfileValidator = { raw -> codec.parseProfile(raw) != null },
        )
    }

    override fun dailySyncToggle(context: Context): DailySyncToggle = toggle

    override suspend fun createSession(deps: SessionDeps): SchoolSession =
        assemble(deps.todoRepository, deps.filesDir)

    private class FakeSchoolSession(
        override val spec: SchoolSpec,
        override val sync: com.fangyi.classpp.data.sync.SyncService,
        override val autoRenew: AutoRenewManager,
        override val webViewLogin: WebViewLoginSpec,
        override val dailySync: DailySyncToggle,
        override val webViewProfileValidator: ((String) -> Boolean)?,
    ) : SchoolSession {
        override val scheduleImport: com.fangyi.classpp.data.sync.ScheduleImportService? = null
    }

    companion object {
        /** 合法的声明（注册表硬校验必须通过）；白名单包含认证域与数据域 */
        val WEB_VIEW = WebViewLoginSpec(
            entryUrl = "https://lms.example.test/user/index?version=v1",
            allowedHosts = setOf("lms.example.test", "ids.example.test"),
            dataHost = "lms.example.test",
            profilePath = "/api/profile",
            todosPath = "/api/todos",
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
        )
    }
}

/** 内存版安全存储（与生产 [com.fangyi.classpp.data.sync.credential.SchoolSecureStore] 同契约） */
internal class InMemorySecureStore : SecureStore {
    var policy: AutoRenewPolicy = AutoRenewPolicy.USER_LOGGED_OUT
    var credentials: StoredCredentials? = null
    var cookie: String? = null
    override var available: Boolean = true

    override fun loadPolicy(): AutoRenewPolicy = policy

    override fun savePolicy(policy: AutoRenewPolicy): Boolean {
        this.policy = policy
        return true
    }

    override fun loadCredentials(): StoredCredentials? = credentials

    override fun saveCredentials(credentials: StoredCredentials?): Boolean {
        this.credentials = credentials
        return true
    }

    override fun loadCookieHeader(): String? = cookie

    override fun saveCookieHeader(header: String?): Boolean {
        cookie = header
        return true
    }

    override fun clear(): Boolean {
        credentials = null
        cookie = null
        return true
    }
}

/** 脚本化登录客户端：默认接受任意账号密码 */
internal class ScriptedLoginClient : LoginClient {
    var result: LoginResult = LoginResult.Ok(
        LoginSession("session=S1; role=R1", mapOf("session" to "S1")),
    )
    var calls = 0
        private set

    override suspend fun login(username: String, password: String): LoginResult {
        calls++
        return result
    }
}

/** 记录镜像调用的 cookie 镜子 */
internal class RecordingCookieMirror : CookieMirror {
    var host: String? = null
    var cookies: Map<String, String> = emptyMap()

    override fun mirror(host: String, cookies: Map<String, String>) {
        this.host = host
        this.cookies = cookies
    }
}

/** 内存版每日开关 */
internal class InMemoryToggle : DailySyncToggle {
    private val _enabled = MutableStateFlow(false)
    override val enabled: StateFlow<Boolean> = _enabled

    override fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
    }
}
