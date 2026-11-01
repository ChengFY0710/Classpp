package com.fangyi.classpp.data.xmu

import java.io.IOException
import kotlinx.coroutines.delay

internal const val XMU_TEST_LOGIN_HTML =
    """<html><head><script>var pwdEncryptSalt = "0123456789abcdef";</script></head>
       <body><input type="hidden" name="execution" value="e1s1"/>
       <input type="hidden" name="lt" id="lt" value="lt-token"/>
       <input type="hidden" id="dllt" name="dllt" value="generalLogin"/></body></html>"""

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

internal const val XMU_JW_TEST_LOGIN_REDIRECT =
    "https://ids.xmu.edu.cn/authserver/login?service=https%3A%2F%2Fjw.xmu.edu.cn%2Fgsapp%2Fsys%2Fwdkbapp%2Fmodules%2Fxskcb%2Fkfdxnxqcx.do"
internal const val XMU_JW_TEST_TICKET_URL =
    "https://jw.xmu.edu.cn/gsapp/sys/wdkbapp/*default/index.do?ticket=ST-1"
internal const val XMU_JW_TEST_HOME_HTML = "<html>jw home</html>"

/** 以下接口样本取自真实教务「我的课表」抓包（精简字段，结构与线上响应一致） */
internal const val XMU_JW_TEST_SEMESTERS = """
{"datas":{"kfdxnxqcx":{"rows":[
  {"XNXQDM":"20261","XNXQDM_DISPLAY":"2026-2027学年 秋季学期","KBKFRQ":"2026-09-17 10:00:00","BY1":null},
  {"XNXQDM":"20252","XNXQDM_DISPLAY":"2025-2026学年 春季学期","KBKFRQ":"2025-12-31 19:55:00","BY1":null}
]}}}
"""

internal const val XMU_JW_TEST_SKJC = """
{"success":true,"data":[
  {"DM":"1","MC":"第1节","PX":1,"KSSJ":800,"JSSJ":845,"JCFADM":"01"},
  {"DM":"2","MC":"第2节","PX":2,"KSSJ":855,"JSSJ":940,"JCFADM":"01"},
  {"DM":"3","MC":"第3节","PX":3,"KSSJ":1010,"JSSJ":1055,"JCFADM":"01"},
  {"DM":"4","MC":"第4节","PX":4,"KSSJ":1105,"JSSJ":1150,"JCFADM":"01"},
  {"DM":"12","MC":"中午","PX":12,"KSSJ":1200,"JSSJ":1400,"JCFADM":"01"},
  {"DM":"5","MC":"第5节","PX":5,"KSSJ":1430,"JSSJ":1515,"JCFADM":"01"}
]}
"""

internal const val XMU_JW_TEST_ZCXX = """{"currentZc":"8","success":true,"zcList":[{"ZC":1},{"ZC":2}]}"""

internal const val XMU_JW_TEST_PKJG = """
{"success":true,"pkjgList":[
  {"KCMC":"大学物理B（下）","KCDM":"130090010055","JSXM":"李开航","JASMC":"学武楼（1号楼）A202",
   "XQ":2,"KSJCDM":3,"JSJCDM":4,"ZCBH":"111111111111111100000000000000","ZCMC":"1-16周","KBBZ":null,"WID":"W1"},
  {"KCMC":"大学物理实验","KCDM":"130090010085","JSXM":"程唤龄","JASMC":"学武楼（1号楼）C201",
   "XQ":5,"KSJCDM":1,"JSJCDM":4,"ZCBH":"110000000000000000000000000000","ZCMC":"1-2周","KBBZ":"备注A","WID":"W2"}
],"ttbList":[]}
"""

/**
 * 脚本化教务站（jw）登录链路 + 课表接口的假服务器：
 * - 未登录：入口与业务接口一律 302 到身份域 CAS（与真实教务一致）；
 * - 登录成功后置 [sessionValid]，后续 jw 请求按路径分发样本响应并种 JW_SESSION cookie。
 */
internal class FakeXmuJwServer {
    var sessionValid = false
    var needCaptcha = false
    var invalidCredentials = false
    var networkDown = false

    /** 各端点返回体；置 null 让该端点 404（测格式变化） */
    var semestersBody: String? = XMU_JW_TEST_SEMESTERS
    var skjcBody: String? = XMU_JW_TEST_SKJC
    var zcxxBody: String? = XMU_JW_TEST_ZCXX
    var pkjgBody: String? = XMU_JW_TEST_PKJG

    var loginPosts = 0
        private set
    var loginForm: String? = null
        private set

    val requests: List<XmuHttpRequest> get() = transport.requests

    val transport = FakeXmuTransport { request ->
        if (networkDown) throw IOException("network down")
        when {
            request.url.startsWith("https://ids.xmu.edu.cn/authserver/checkNeedCaptcha") ->
                XmuHttpResponse(200, body = """{"isNeed":$needCaptcha}""")

            request.url.startsWith("https://ids.xmu.edu.cn/authserver/login") &&
                request.method == "POST" -> {
                loginPosts++
                loginForm = request.formBody
                if (invalidCredentials) {
                    XmuHttpResponse(200, body = XMU_TEST_LOGIN_HTML)
                } else {
                    sessionValid = true
                    XmuHttpResponse(302, location = XMU_JW_TEST_TICKET_URL)
                }
            }

            request.url.startsWith("https://ids.xmu.edu.cn/authserver/login") ->
                XmuHttpResponse(200, body = XMU_TEST_LOGIN_HTML)

            request.url.startsWith("https://jw.xmu.edu.cn/") -> when {
                // 带 ticket 回落：CAS 过滤器校验后建立会话（种 JW_SESSION）
                request.url.contains("ticket=") ->
                    XmuHttpResponse(
                        200,
                        body = XMU_JW_TEST_HOME_HTML,
                        setCookies = listOf("JW_SESSION=S; Path=/; HttpOnly"),
                    )
                // 未登录的业务请求（含登录入口/应用入口）一律 302 到 CAS
                !sessionValid -> XmuHttpResponse(302, location = XMU_JW_TEST_LOGIN_REDIRECT)
                // 课表应用入口：会话有效时打开应用完成初始化
                request.url.contains("appShow") ->
                    XmuHttpResponse(200, body = XMU_JW_TEST_HOME_HTML)
                request.url.endsWith("kfdxnxqcx.do") ->
                    XmuHttpResponse(200, body = requireNotNull(semestersBody))
                request.url.endsWith("queryXsskjc.do") ->
                    XmuHttpResponse(200, body = requireNotNull(skjcBody))
                request.url.endsWith("getZcxx.do") ->
                    XmuHttpResponse(200, body = requireNotNull(zcxxBody))
                request.url.endsWith("queryXspkjg.do") ->
                    XmuHttpResponse(200, body = requireNotNull(pkjgBody))
                else -> XmuHttpResponse(404)
            }

            else -> error("unexpected ${request.method} ${request.url}")
        }
    }
}
