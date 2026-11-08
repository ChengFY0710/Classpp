package com.fangyi.classpp.data.sync.credential

import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.AutoRenewService
import com.fangyi.classpp.data.sync.AutoRenewState
import com.fangyi.classpp.data.sync.EnableOutcome
import com.fangyi.classpp.data.sync.LoginError
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.engine.FetchError
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.engine.SessionApi
import com.fangyi.classpp.data.sync.net.CookieMirror
import com.fangyi.classpp.data.sync.net.SessionCookieHolder
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 通用自动续期编排（指导 §2.3/§四 Phase 4）：新校「原生登录 + 保存凭据」方案的复用骨架，
 * 与既有厦大实现同纪律：
 * - **单飞**：探活、自动登录、开启/关闭共用一把 [mutex]，同一时刻只有一次登录；
 * - **失败即停**：验证码/密码错误/格式变化一次就 [AutoRenewPolicy.BLOCKED] 并删凭据，
 *   绝不自动重试密码；
 * - **瞬时错误退避**：5xx/网络/超时保持 ENABLED，但 60 秒内不再打身份域；
 * - 只有 `ENABLED + 有凭据` 才会自动登录，其余情况由 [AutoRenewSessionRouter] 走
 *   WebView 兜底；
 * - 密码不常驻内存：每次自动登录前从 [SecureStore] 读取，用完即弃。
 *
 * 学校提供两件东西：[LoginClient]（账号密码 → 会话凭据）与 [SessionApi]（会话 → 两段
 * 取数原文）。本类只做编排，不认学校协议。
 */
class AutoRenewManager(
    private val store: SecureStore,
    private val loginClient: LoginClient,
    private val sessionApi: SessionApi,
    private val cookieHolder: SessionCookieHolder,
    private val cookieMirror: CookieMirror = CookieMirror { _, _ -> },
    /** 镜像 cookie 的业务域（学校业务数据域，如课程平台主机） */
    private val mirrorHost: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = SyncDebugLog::d,
) : AutoRenewService {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(AutoRenewState())
    override val state: StateFlow<AutoRenewState> = _state.asStateFlow()

    private val busyTracker = BusyTracker()
    override val busy: StateFlow<Boolean> = busyTracker.busy

    /** 瞬时失败后的退避截止时刻（wall clock）与上次错误 */
    private var backoffUntilMillis = 0L
    private var backoffError: FetchError = FetchError.NetworkError

    /** 从磁盘恢复策略/凭据/cookie（provider 装配时调用一次） */
    suspend fun bootstrap() {
        val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
        logger(
            "bootstrap policy=${snapshot.policy} creds=${snapshot.credentials != null} " +
                "cookie=${!snapshot.cookie.isNullOrBlank()} available=${store.available}",
        )
        if (!store.available) {
            _state.value = AutoRenewState(available = false)
            return
        }

        // ENABLED 但没有凭据（换机/Keystore 失效）：不能假装还能自动登录，暂停并要求重开
        if (snapshot.policy == AutoRenewPolicy.ENABLED && snapshot.credentials == null) {
            persistBlocked("bootstrap-normalize")
            return
        }
        // BLOCKED / USER_LOGGED_OUT 语义就是「没有保存密码」：残留密文一并清掉
        val staleSecrets = snapshot.policy != AutoRenewPolicy.ENABLED &&
            snapshot.credentials != null
        if (staleSecrets) {
            withContext(Dispatchers.IO) {
                store.saveCredentials(null)
                store.saveCookieHeader(null)
            }
        }

        // 原生会话 cookie 只在开启态才有意义：关闭/暂停态的清空密文不能继续驻留进程内
        cookieHolder.header = snapshot.cookie.takeIf {
            snapshot.policy == AutoRenewPolicy.ENABLED
        }
        _state.value = AutoRenewState(
            policy = snapshot.policy,
            hasCredentials = snapshot.policy == AutoRenewPolicy.ENABLED &&
                snapshot.credentials != null,
            available = true,
        )
    }

    /**
     * 用户显式开启：用输入的凭据原生登录一次，成功即保存并完成首次取数。
     * 任何认证失败都置 BLOCKED 并删除凭据——重新开启必须再次输入密码。
     */
    override suspend fun enable(username: String, password: String): EnableOutcome =
        busyTracker.track { mutex.withLock { enableLocked(username, password) } }

    /** 关闭自动续期并删除已保存密码；不动 WebView cookie（保留当前连接） */
    override suspend fun disable() {
        busyTracker.track {
            mutex.withLock {
                logger("autoLogin disable")
                cookieHolder.header = null
                backoffUntilMillis = 0
                withContext(Dispatchers.IO) {
                    store.clear()
                    store.savePolicy(AutoRenewPolicy.USER_LOGGED_OUT)
                }
                _state.value = AutoRenewState(
                    policy = AutoRenewPolicy.USER_LOGGED_OUT,
                    hasCredentials = false,
                    available = store.available,
                )
            }
        }
    }

    /**
     * 手动同步时的原生会话入口（仅由 [AutoRenewSessionRouter] 在 ENABLED+有凭据时调用）：
     * 有 cookie 先探活；过期才登录一次；登录成功后重新取数。
     */
    suspend fun fetchSession(): FetchOutcome = mutex.withLock {
        val current = _state.value
        logger(
            "fetch begin policy=${current.policy} creds=${current.hasCredentials} " +
                "cookie=${!cookieHolder.header.isNullOrBlank()}",
        )
        if (!current.available ||
            current.policy != AutoRenewPolicy.ENABLED ||
            !current.hasCredentials
        ) {
            return@withLock FetchOutcome.Failed(FetchError.SessionExpired)
        }
        if (isBackingOff()) {
            logger("fetch backoff hit -> ${backoffError.name}")
            return@withLock FetchOutcome.Failed(backoffError)
        }

        // 没有持久化 cookie（首次/被清）时省掉一次必然失败的探活
        val probe = if (cookieHolder.header.isNullOrBlank()) {
            FetchOutcome.Failed(FetchError.SessionExpired)
        } else {
            sessionApi.fetchSession()
        }
        when (probe) {
            is FetchOutcome.Ok -> return@withLock probe
            is FetchOutcome.Failed -> when (probe.error) {
                FetchError.SessionExpired -> Unit
                // 5xx/网络/超时/格式变化：会话是否过期未知，绝不能盲目提交密码；
                // 同时进入退避，连点「立即同步」不会反复打身份域
                else -> {
                    backoff(probe.error)
                    return@withLock probe
                }
            }
        }
        loginAndFetch()
    }

    /**
     * 已存凭据的只读访问（教务站等第二套系统用同一套账号密码登录）：不改任何策略、
     * 会话或 cookie——业务会话登录态只归 [enable]/[disable]/自动续期管。
     */
    suspend fun peekCredentials(): StoredCredentials? =
        withContext(Dispatchers.IO) { store.loadCredentials() }

    // ---------- 内部 ----------

    private suspend fun enableLocked(username: String, password: String): EnableOutcome {
        logger("enable begin available=${store.available} policy=${_state.value.policy}")
        if (!store.available) return EnableOutcome.Failed(LoginError.StorageUnavailable)
        val trimmedUser = username.trim()
        if (trimmedUser.isEmpty() || password.isEmpty()) {
            return EnableOutcome.Failed(LoginError.InvalidCredentials)
        }
        backoffUntilMillis = 0

        return when (val login = loginClient.login(trimmedUser, password)) {
            is LoginResult.Ok -> onLoginForEnable(trimmedUser, password, login.session)
            LoginResult.NeedCaptcha -> {
                persistBlocked("enable-captcha")
                EnableOutcome.Failed(LoginError.NeedCaptcha)
            }
            LoginResult.InvalidCredentials -> {
                persistBlocked("enable-invalid-credentials")
                EnableOutcome.Failed(LoginError.InvalidCredentials)
            }
            LoginResult.FormatChanged -> {
                persistBlocked("enable-format-changed")
                EnableOutcome.Failed(LoginError.FormatChanged)
            }
            LoginResult.ServiceBusy -> EnableOutcome.Failed(LoginError.ServiceBusy)
            LoginResult.NetworkError -> EnableOutcome.Failed(LoginError.NetworkError)
            LoginResult.Timeout -> EnableOutcome.Failed(LoginError.Timeout)
        }
    }

    private suspend fun onLoginForEnable(
        username: String,
        password: String,
        login: LoginSession,
    ): EnableOutcome {
        logger("enable login ok cookies=${login.cookies.size}")
        val saved = withContext(Dispatchers.IO) {
            store.saveCredentials(StoredCredentials(username, password)) &&
                store.saveCookieHeader(login.cookieHeader) &&
                store.savePolicy(AutoRenewPolicy.ENABLED)
        }
        logger("enable credentials saved=$saved")
        if (!saved) {
            withContext(Dispatchers.IO) {
                store.clear()
                store.savePolicy(AutoRenewPolicy.USER_LOGGED_OUT)
            }
            _state.value = AutoRenewState(
                policy = AutoRenewPolicy.USER_LOGGED_OUT,
                hasCredentials = false,
                available = store.available,
            )
            return EnableOutcome.Failed(LoginError.StorageUnavailable)
        }

        cookieHolder.header = login.cookieHeader
        _state.value = AutoRenewState(
            policy = AutoRenewPolicy.ENABLED,
            hasCredentials = true,
            available = true,
        )
        cookieMirror.mirror(mirrorHost, login.cookies)

        return when (val fetched = sessionApi.fetchSession()) {
            is FetchOutcome.Ok -> {
                logger("enable outcome=Ready")
                EnableOutcome.Ready(fetched.raw)
            }
            is FetchOutcome.Failed -> when (fetched.error) {
                FetchError.SessionExpired -> {
                    persistBlocked("enable-fetch-expired")
                    EnableOutcome.Failed(LoginError.SessionNotEstablished)
                }
                FetchError.ServiceBusy -> EnableOutcome.Failed(LoginError.ServiceBusy)
                FetchError.NetworkError -> EnableOutcome.Failed(LoginError.NetworkError)
                FetchError.Timeout -> EnableOutcome.Failed(LoginError.Timeout)
                FetchError.FormatChanged -> EnableOutcome.Failed(LoginError.FormatChanged)
            }
        }
    }

    private suspend fun loginAndFetch(): FetchOutcome {
        val credentials = withContext(Dispatchers.IO) { store.loadCredentials() }
        if (credentials == null) {
            // 凭据读不出（Keystore 失效）：不能继续尝试，按暂停处理
            persistBlocked("relogin-credentials-unreadable")
            return FetchOutcome.Failed(FetchError.SessionExpired)
        }

        return when (val login = loginClient.login(credentials.username, credentials.password)) {
            is LoginResult.Ok -> onLoginForRenew(login.session)
            LoginResult.NeedCaptcha, LoginResult.InvalidCredentials -> {
                persistBlocked("relogin-auth-failed")
                FetchOutcome.Failed(FetchError.SessionExpired)
            }
            LoginResult.FormatChanged -> {
                persistBlocked("relogin-format-changed")
                FetchOutcome.Failed(FetchError.FormatChanged)
            }
            LoginResult.ServiceBusy -> backoffAndFail(FetchError.ServiceBusy)
            LoginResult.NetworkError -> backoffAndFail(FetchError.NetworkError)
            LoginResult.Timeout -> backoffAndFail(FetchError.Timeout)
        }
    }

    private suspend fun onLoginForRenew(login: LoginSession): FetchOutcome {
        logger("autoLogin relogin ok cookies=${login.cookies.size}")
        // 先落 cookie 再取数：即便进程被杀，下次仍能直接探活
        withContext(Dispatchers.IO) { store.saveCookieHeader(login.cookieHeader) }
        cookieHolder.header = login.cookieHeader
        cookieMirror.mirror(mirrorHost, login.cookies)
        return when (val fetched = sessionApi.fetchSession()) {
            is FetchOutcome.Ok -> {
                backoffUntilMillis = 0
                logger("autoLogin relogin+fetch ok")
                fetched
            }
            is FetchOutcome.Failed -> when (fetched.error) {
                // 刚登录仍过期：链路变了，停止自动尝试，避免反复打身份域
                FetchError.SessionExpired -> {
                    persistBlocked("relogin-fetch-expired")
                    fetched
                }
                else -> fetched
            }
        }
    }

    /** 认证失败一次：BLOCKED + 删除凭据/cookie，绝不重试 */
    private suspend fun persistBlocked(reason: String) {
        logger("autoLogin block reason=$reason")
        cookieHolder.header = null
        backoffUntilMillis = 0
        withContext(Dispatchers.IO) {
            store.clear()
            store.savePolicy(AutoRenewPolicy.BLOCKED)
        }
        _state.value = AutoRenewState(
            policy = AutoRenewPolicy.BLOCKED,
            hasCredentials = false,
            available = store.available,
        )
    }

    private fun backoff(error: FetchError) {
        backoffUntilMillis = nowMillis() + AUTO_LOGIN_BACKOFF_MS
        backoffError = error
    }

    private fun backoffAndFail(error: FetchError): FetchOutcome {
        backoff(error)
        return FetchOutcome.Failed(error)
    }

    /**
     * 退避判定：wall clock 回拨后 `backoffUntil - now` 会远大于退避时长，
     * 此时必须视为已过期，否则用户会被「一直在退避」卡住。
     */
    private fun isBackingOff(): Boolean {
        val remaining = backoffUntilMillis - nowMillis()
        return remaining > 0 && remaining <= AUTO_LOGIN_BACKOFF_MS
    }

    private fun loadSnapshot(): SecureSnapshot = SecureSnapshot(
        policy = store.loadPolicy(),
        credentials = store.loadCredentials(),
        cookie = store.loadCookieHeader(),
    )

    private data class SecureSnapshot(
        val policy: AutoRenewPolicy,
        val credentials: StoredCredentials?,
        val cookie: String?,
    )

    private companion object {
        /** 瞬时失败后的最小重试间隔（与既有实现一致：探活/登录最小间隔 60 秒） */
        const val AUTO_LOGIN_BACKOFF_MS = 60_000L
    }
}

/**
 * 原生优先、WebView 兜底的路由（指导 §2.3）：
 * 自动续期开启且凭据可用时走原生 HTTP 会话，否则原样委托 [fallback]。
 *
 * 判断只看 [AutoRenewManager.state] 的快照，不做任何网络探测——路由本身不能引入
 * 额外请求，也不能因为自动续期不可用而阻断原有 WebView 路径。
 */
class AutoRenewSessionRouter(
    private val autoRenew: AutoRenewManager,
    private val fallback: SessionApi,
) : SessionApi {

    override suspend fun fetchSession(): FetchOutcome {
        val state = autoRenew.state.value
        return if (state.available &&
            state.policy == AutoRenewPolicy.ENABLED &&
            state.hasCredentials
        ) {
            autoRenew.fetchSession()
        } else {
            fallback.fetchSession()
        }
    }
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
