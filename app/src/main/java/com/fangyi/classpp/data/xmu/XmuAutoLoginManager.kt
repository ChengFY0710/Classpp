package com.fangyi.classpp.data.xmu

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 自动续期策略（指导 §6.2）：
 * - [ENABLED]：用户显式开启且凭据存在，会话过期时允许自动登录；
 * - [BLOCKED]：自动登录失败一次即置位，凭据已删除，必须手动登录后重新开启；
 * - [USER_LOGGED_OUT]：用户主动退出 / 从未开启，等价于「自动登录关闭」。
 *
 * 只有 `ENABLED + 有凭据` 才允许自动登录；其余一律回退 WebView。
 */
enum class XmuAutoLoginPolicy {
    ENABLED,
    BLOCKED,
    USER_LOGGED_OUT,
}

/** 自动续期对 UI 暴露的状态（[available] = Keystore 可用） */
data class XmuAutoLoginState(
    val policy: XmuAutoLoginPolicy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
    val hasCredentials: Boolean = false,
    val available: Boolean = true,
)

/** 开启自动续期的失败分类（UI 按类给文案） */
enum class XmuAutoLoginError {
    NeedCaptcha,
    InvalidCredentials,
    StorageUnavailable,
    ServiceBusy,
    NetworkError,
    Timeout,
    FormatChanged,
    /** 登录成功但随后取数仍被判定为过期：链路异常，改用网页登录 */
    SessionNotEstablished,
}

sealed interface XmuEnableOutcome {
    data class Ready(val profileRaw: String, val todosRaw: String) : XmuEnableOutcome
    data class Failed(val error: XmuAutoLoginError) : XmuEnableOutcome
}

/** 登录成功后把 lnt cookie 镜像进 WebView 的 CookieManager（best-effort） */
internal fun interface XmuCookieMirror {
    fun mirror(cookies: Map<String, String>)
}

/**
 * 把原生登录拿到的 lnt cookie 写回 WebView cookie 罐：用户关闭自动续期后，
 * WebView 路径仍能复用当前会话，而不是立刻提示过期。
 */
internal object AndroidXmuCookieMirror : XmuCookieMirror {
    override fun mirror(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        try {
            val manager = CookieManager.getInstance()
            for ((name, value) in cookies) {
                manager.setCookie(
                    "https://${XmuWebViewPolicy.LNT_HOST}",
                    "$name=$value; Path=/; Secure; HttpOnly",
                )
            }
            manager.flush()
        } catch (_: Exception) {
            // 镜像失败只影响 WebView 复用；原生会话不受影响，不向用户报错
        }
    }
}

/**
 * 方案 B 的编排层（指导 §3.2/§6.2/§6.3）：
 * - **单飞**：探活、自动登录、开启/关闭共用一把 [mutex]，同一时刻只有一次登录；
 * - **失败即停**：验证码/密码错误/格式变化一次就 [XmuAutoLoginPolicy.BLOCKED] 并删凭据，
 *   绝不自动重试密码；
 * - **瞬时错误退避**：5xx/网络/超时保持 ENABLED，但 60 秒内不再打身份域；
 * - 只有 `ENABLED + 有凭据` 才会自动登录，其余情况由 [XmuTodoApiRouter] 走 WebView。
 *
 * 密码不常驻内存：每次自动登录前从 [XmuSecureStore] 读取，用完即弃。
 */
class XmuAutoLoginManager internal constructor(
    private val store: XmuSecureStore,
    private val loginClient: XmuLoginClient,
    private val nativeApi: XmuNativeTodoApi,
    private val session: XmuSessionCookieHolder,
    private val cookieMirror: XmuCookieMirror = XmuCookieMirror {},
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(XmuAutoLoginState())
    val state: StateFlow<XmuAutoLoginState> = _state.asStateFlow()

    /** 瞬时失败后的退避截止时刻（wall clock）与上次错误 */
    private var backoffUntilMillis = 0L
    private var backoffResult: XmuFetchResult = XmuFetchResult.NetworkError

    /** 从磁盘恢复策略/凭据/cookie（AppContainer 装配时调用一次） */
    suspend fun bootstrap() {
        val snapshot = withContext(Dispatchers.IO) { loadSnapshot() }
        XmuDebugLog.d(
            "bootstrap policy=${snapshot.policy} creds=${snapshot.credentials != null} " +
                "cookie=${!snapshot.cookie.isNullOrBlank()} available=${store.available}",
        )
        if (!store.available) {
            _state.value = XmuAutoLoginState(available = false)
            return
        }

        // ENABLED 但没有凭据（换机/Keystore 失效）：不能假装还能自动登录，暂停并要求重开
        if (snapshot.policy == XmuAutoLoginPolicy.ENABLED && snapshot.credentials == null) {
            persistBlocked("bootstrap-normalize")
            return
        }
        // BLOCKED / USER_LOGGED_OUT 语义就是「没有保存密码」：残留密文一并清掉
        if (snapshot.policy != XmuAutoLoginPolicy.ENABLED && snapshot.credentials != null) {
            withContext(Dispatchers.IO) {
                store.saveCredentials(null)
                store.saveCookieHeader(null)
            }
        }

        session.header = snapshot.cookie
        _state.value = XmuAutoLoginState(
            policy = snapshot.policy,
            hasCredentials = snapshot.policy == XmuAutoLoginPolicy.ENABLED &&
                snapshot.credentials != null,
            available = true,
        )
    }

    /**
     * 用户显式开启：用输入的凭据原生登录一次，成功即保存并完成首次取数。
     * 任何认证失败都置 BLOCKED 并删除凭据——重新开启必须再次输入密码。
     */
    suspend fun enable(username: String, password: String): XmuEnableOutcome = mutex.withLock {
        XmuDebugLog.d("enable begin available=${store.available} policy=${_state.value.policy}")
        if (!store.available) {
            return@withLock XmuEnableOutcome.Failed(XmuAutoLoginError.StorageUnavailable)
        }
        val trimmedUser = username.trim()
        if (trimmedUser.isEmpty() || password.isEmpty()) {
            return@withLock XmuEnableOutcome.Failed(XmuAutoLoginError.InvalidCredentials)
        }
        backoffUntilMillis = 0

        when (val login = loginClient.login(trimmedUser, password)) {
            is XmuLoginOutcome.Ok -> {
                XmuDebugLog.d("enable login ok cookies=${login.cookies.size}")
                val saved = withContext(Dispatchers.IO) {
                    store.saveCredentials(XmuCredentials(trimmedUser, password)) &&
                        store.saveCookieHeader(login.cookieHeader) &&
                        store.savePolicy(XmuAutoLoginPolicy.ENABLED)
                }
                XmuDebugLog.d("enable credentials saved=$saved")
                if (!saved) {
                    withContext(Dispatchers.IO) {
                        store.clear()
                        store.savePolicy(XmuAutoLoginPolicy.USER_LOGGED_OUT)
                    }
                    _state.value = XmuAutoLoginState(
                        policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
                        hasCredentials = false,
                        available = store.available,
                    )
                    return@withLock XmuEnableOutcome.Failed(XmuAutoLoginError.StorageUnavailable)
                }

                session.header = login.cookieHeader
                _state.value = XmuAutoLoginState(
                    policy = XmuAutoLoginPolicy.ENABLED,
                    hasCredentials = true,
                    available = true,
                )
                cookieMirror.mirror(login.cookies)

                when (val fetched = nativeApi.fetchSession()) {
                    is XmuFetchResult.Ok -> {
                        XmuDebugLog.d("enable outcome=Ready")
                        XmuEnableOutcome.Ready(fetched.profileRaw, fetched.todosRaw)
                    }
                    XmuFetchResult.SessionExpired -> {
                        persistBlocked("enable-fetch-expired")
                        XmuEnableOutcome.Failed(XmuAutoLoginError.SessionNotEstablished)
                    }
                    XmuFetchResult.ServiceBusy ->
                        XmuEnableOutcome.Failed(XmuAutoLoginError.ServiceBusy)
                    XmuFetchResult.NetworkError ->
                        XmuEnableOutcome.Failed(XmuAutoLoginError.NetworkError)
                    XmuFetchResult.Timeout ->
                        XmuEnableOutcome.Failed(XmuAutoLoginError.Timeout)
                    XmuFetchResult.FormatChanged ->
                        XmuEnableOutcome.Failed(XmuAutoLoginError.FormatChanged)
                }
            }
            XmuLoginOutcome.NeedCaptcha -> {
                persistBlocked("enable-captcha")
                XmuEnableOutcome.Failed(XmuAutoLoginError.NeedCaptcha)
            }
            XmuLoginOutcome.InvalidCredentials -> {
                persistBlocked("enable-invalid-credentials")
                XmuEnableOutcome.Failed(XmuAutoLoginError.InvalidCredentials)
            }
            XmuLoginOutcome.FormatChanged -> {
                persistBlocked("enable-format-changed")
                XmuEnableOutcome.Failed(XmuAutoLoginError.FormatChanged)
            }
            XmuLoginOutcome.ServiceBusy ->
                XmuEnableOutcome.Failed(XmuAutoLoginError.ServiceBusy)
            XmuLoginOutcome.NetworkError ->
                XmuEnableOutcome.Failed(XmuAutoLoginError.NetworkError)
            XmuLoginOutcome.Timeout ->
                XmuEnableOutcome.Failed(XmuAutoLoginError.Timeout)
        }
    }

    /**
     * 手动同步时的原生会话入口（仅由 [XmuTodoApiRouter] 在 ENABLED+有凭据时调用）：
     * 有 cookie 先探活；过期才登录一次；登录成功后重新取数。
     */
    suspend fun fetchSession(): XmuFetchResult = mutex.withLock {
        val current = _state.value
        XmuDebugLog.d(
            "fetch begin policy=${current.policy} creds=${current.hasCredentials} " +
                "cookie=${!session.header.isNullOrBlank()}",
        )
        if (!current.available ||
            current.policy != XmuAutoLoginPolicy.ENABLED ||
            !current.hasCredentials
        ) {
            return@withLock XmuFetchResult.SessionExpired
        }
        if (isBackingOff()) {
            XmuDebugLog.d("fetch backoff hit -> ${backoffResult::class.simpleName}")
            return@withLock backoffResult
        }

        // 没有持久化 cookie（首次/被清）时省掉一次必然失败的探活
        val probe = if (session.header.isNullOrBlank()) {
            XmuFetchResult.SessionExpired
        } else {
            nativeApi.fetchSession()
        }
        XmuDebugLog.d("fetch probe outcome=${probe::class.simpleName}")
        when (probe) {
            is XmuFetchResult.Ok -> return@withLock probe
            XmuFetchResult.SessionExpired -> Unit
            // 5xx/网络/超时/格式变化：会话是否过期未知，绝不能盲目提交密码；
            // 同时进入退避，连点「立即同步」不会反复打课程中心
            else -> return@withLock backoff(probe)
        }
        loginAndFetch()
    }

    /** 关闭自动续期并删除已保存密码；不动 WebView cookie（保留当前连接） */
    suspend fun disable() = mutex.withLock {
        XmuDebugLog.d("autoLogin disable")
        session.header = null
        backoffUntilMillis = 0
        withContext(Dispatchers.IO) {
            store.clear()
            store.savePolicy(XmuAutoLoginPolicy.USER_LOGGED_OUT)
        }
        _state.value = XmuAutoLoginState(
            policy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
            hasCredentials = false,
            available = store.available,
        )
    }

    // ---------- 内部 ----------

    private suspend fun loginAndFetch(): XmuFetchResult {
        val credentials = withContext(Dispatchers.IO) { store.loadCredentials() }
        if (credentials == null) {
            // 凭据读不出（Keystore 失效）：不能继续尝试，按暂停处理
            persistBlocked("relogin-credentials-unreadable")
            return XmuFetchResult.SessionExpired
        }

        return when (val login = loginClient.login(credentials.username, credentials.password)) {
            is XmuLoginOutcome.Ok -> {
                XmuDebugLog.d("autoLogin relogin ok cookies=${login.cookies.size}")
                withContext(Dispatchers.IO) { store.saveCookieHeader(login.cookieHeader) }
                session.header = login.cookieHeader
                cookieMirror.mirror(login.cookies)
                when (val fetched = nativeApi.fetchSession()) {
                    is XmuFetchResult.Ok -> {
                        backoffUntilMillis = 0
                        XmuDebugLog.d("autoLogin relogin+fetch ok")
                        fetched
                    }
                    // 刚登录仍过期：链路变了，停止自动尝试，避免反复打身份域
                    XmuFetchResult.SessionExpired -> {
                        persistBlocked("relogin-fetch-expired")
                        XmuFetchResult.SessionExpired
                    }
                    else -> fetched
                }
            }
            XmuLoginOutcome.NeedCaptcha, XmuLoginOutcome.InvalidCredentials -> {
                persistBlocked("relogin-auth-failed")
                XmuFetchResult.SessionExpired
            }
            XmuLoginOutcome.FormatChanged -> {
                persistBlocked("relogin-format-changed")
                XmuFetchResult.FormatChanged
            }
            XmuLoginOutcome.ServiceBusy -> backoff(XmuFetchResult.ServiceBusy)
            XmuLoginOutcome.NetworkError -> backoff(XmuFetchResult.NetworkError)
            XmuLoginOutcome.Timeout -> backoff(XmuFetchResult.Timeout)
        }
    }

    /** 认证失败一次：BLOCKED + 删除凭据/cookie，绝不重试 */
    private suspend fun persistBlocked(reason: String) {
        XmuDebugLog.d("autoLogin block reason=$reason")
        session.header = null
        backoffUntilMillis = 0
        withContext(Dispatchers.IO) {
            store.clear()
            store.savePolicy(XmuAutoLoginPolicy.BLOCKED)
        }
        _state.value = XmuAutoLoginState(
            policy = XmuAutoLoginPolicy.BLOCKED,
            hasCredentials = false,
            available = store.available,
        )
    }

    private fun backoff(result: XmuFetchResult): XmuFetchResult {
        backoffUntilMillis = nowMillis() + AUTO_LOGIN_BACKOFF_MS
        backoffResult = result
        return result
    }

    /**
     * 退避判定：wall clock 回拨后 `backoffUntil - now` 会远大于退避时长，
     * 此时必须视为已过期，否则用户会被「一直在退避」卡住。
     */
    private fun isBackingOff(): Boolean {
        val remaining = backoffUntilMillis - nowMillis()
        return remaining > 0 && remaining <= AUTO_LOGIN_BACKOFF_MS
    }

    private fun loadSnapshot(): XmuSecureSnapshot = XmuSecureSnapshot(
        policy = store.loadPolicy(),
        credentials = store.loadCredentials(),
        cookie = store.loadCookieHeader(),
    )

    private data class XmuSecureSnapshot(
        val policy: XmuAutoLoginPolicy,
        val credentials: XmuCredentials?,
        val cookie: String?,
    )

    private companion object {
        /** 瞬时失败后的最小重试间隔（指导 §6.3：探活/登录最小间隔 60 秒） */
        const val AUTO_LOGIN_BACKOFF_MS = 60_000L
    }
}
