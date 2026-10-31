package com.fangyi.classpp.data.xmu

import java.io.IOException
import kotlinx.coroutines.delay

internal const val XMU_TEST_LOGIN_HTML =
    """<html><head><script>var pwdEncryptSalt = "0123456789abcdef";</script></head>
       <body><input type="hidden" name="execution" value="e1s1"/></body></html>"""

internal const val XMU_TEST_PROFILE = """{"id":9001,"user_no":"20260001","name":"张三"}"""
internal const val XMU_TEST_TODOS = """{"todo_list":[]}"""
internal const val XMU_TEST_CALLBACK =
    "https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true&code=CODE"

/** 记录全部请求的假传输；handler 可脚本化任意链路 */
internal class FakeXmuTransport(
    private val handler: suspend (XmuHttpRequest) -> XmuHttpResponse,
) : XmuHttpTransport {
    val requests = mutableListOf<XmuHttpRequest>()

    override suspend fun execute(request: XmuHttpRequest): XmuHttpResponse {
        requests += request
        return handler(request)
    }
}

/** 内存版安全存储；saveFailed 模拟 Keystore/写盘失败 */
internal class FakeXmuSecureStore(
    var policy: XmuAutoLoginPolicy = XmuAutoLoginPolicy.USER_LOGGED_OUT,
    var credentials: XmuCredentials? = null,
    var cookie: String? = null,
    override var available: Boolean = true,
) : XmuSecureStore {
    var clearCount = 0
        private set
    var saveFailed = false

    override fun loadPolicy(): XmuAutoLoginPolicy = policy

    override fun savePolicy(policy: XmuAutoLoginPolicy): Boolean {
        if (saveFailed) return false
        this.policy = policy
        return true
    }

    override fun loadCredentials(): XmuCredentials? = credentials

    override fun saveCredentials(credentials: XmuCredentials?): Boolean {
        if (saveFailed) return false
        this.credentials = credentials
        return true
    }

    override fun loadCookieHeader(): String? = cookie

    override fun saveCookieHeader(header: String?): Boolean {
        if (saveFailed) return false
        cookie = header
        return true
    }

    override fun clear(): Boolean {
        credentials = null
        cookie = null
        clearCount++
        return true
    }
}

/** 脚本化校方登录链路 + lnt 业务接口的假服务器 */
internal class FakeXmuServer {
    var sessionValid = false
    var needCaptcha = false
    var invalidCredentials = false
    var networkDown = false
    var missingToken = false
    var missingSession = false

    /** 登录 POST 成功、但随后业务接口仍过期（模拟链路异常） */
    var loginSucceedsButSessionStaysInvalid = false

    /** 并发测试用：登录 POST 的耗时 */
    var loginDelayMillis = 0L

    var loginPosts = 0
        private set
    var totalRequests = 0
        private set

    /** 最近一次登录表单原文（解码密码载荷用） */
    var loginForm: String? = null
        private set

    /** 全部已收到的请求（断言链路形态用） */
    val requests: List<XmuHttpRequest> get() = transport.requests

    val transport = FakeXmuTransport { request ->
        totalRequests++
        if (networkDown) throw IOException("network down")
        when {
            request.url.contains("/protocol/openid-connect/auth") ->
                XmuHttpResponse(
                    302,
                    location = "https://ids.xmu.edu.cn/authserver/login?service=x",
                )

            request.url.startsWith("https://ids.xmu.edu.cn/authserver/checkNeedCaptcha.htl") ->
                XmuHttpResponse(200, body = """{"isNeed":$needCaptcha}""")

            request.url.startsWith("https://ids.xmu.edu.cn/authserver/login") &&
                request.method == "POST" -> {
                loginPosts++
                loginForm = request.formBody
                if (loginDelayMillis > 0) delay(loginDelayMillis)
                if (invalidCredentials) {
                    XmuHttpResponse(200, body = XMU_TEST_LOGIN_HTML)
                } else {
                    sessionValid = true
                    XmuHttpResponse(302, location = XMU_TEST_CALLBACK)
                }
            }

            request.url.startsWith("https://ids.xmu.edu.cn/authserver/login") ->
                XmuHttpResponse(200, body = XMU_TEST_LOGIN_HTML)

            request.url.contains("/protocol/openid-connect/token") ->
                XmuHttpResponse(
                    200,
                    body = if (missingToken) "{}" else """{"access_token":"AT"}""",
                )

            request.url.startsWith("https://lnt.xmu.edu.cn/api/login") -> {
                if (loginSucceedsButSessionStaysInvalid) sessionValid = false
                XmuHttpResponse(
                    200,
                    setCookies = if (missingSession) {
                        emptyList()
                    } else {
                        listOf(
                            "session=S1; Path=/; HttpOnly",
                            "role_token=R1; Path=/",
                        )
                    },
                )
            }

            request.url == "https://lnt.xmu.edu.cn/api/profile" ->
                if (sessionValid) {
                    XmuHttpResponse(200, body = XMU_TEST_PROFILE)
                } else {
                    XmuHttpResponse(302, location = "https://c-identity.xmu.edu.cn/auth")
                }

            request.url == "https://lnt.xmu.edu.cn/api/todos" ->
                if (sessionValid) {
                    XmuHttpResponse(200, body = XMU_TEST_TODOS)
                } else {
                    XmuHttpResponse(302, location = "https://c-identity.xmu.edu.cn/auth")
                }

            else -> error("unexpected ${request.method} ${request.url}")
        }
    }
}

/** JVM 侧 Base64 实现（生产用 android.util.Base64） */
internal fun jvmCipher(): XmuPasswordCipher = XmuPasswordCipher(
    base64Encoder = { java.util.Base64.getEncoder().encodeToString(it) },
)

internal class XmuTestHarness(
    policy: XmuAutoLoginPolicy = XmuAutoLoginPolicy.ENABLED,
    credentials: XmuCredentials? = XmuCredentials("20260001", "secret"),
    cookie: String? = "session=OLD",
    sessionValid: Boolean = false,
) {
    var now = 1_000_000L

    val store = FakeXmuSecureStore(policy, credentials, cookie)
    val server = FakeXmuServer().apply { this.sessionValid = sessionValid }
    val session = XmuSessionCookieHolder()

    val manager = XmuAutoLoginManager(
        store = store,
        loginClient = XmuLoginClient(server.transport, jvmCipher()),
        nativeApi = XmuNativeTodoApi(server.transport, session),
        session = session,
        cookieMirror = XmuCookieMirror {},
        nowMillis = { now },
    )
}
