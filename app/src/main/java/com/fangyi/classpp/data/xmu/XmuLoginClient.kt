package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.ScheduleJson
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 原生 OAuth 登录的结果分类（指导 §3.2 / §5.5） */
internal sealed interface XmuLoginOutcome {
    /** 登录成功：lnt 域 cookie 与可直接用于业务请求的 Cookie 头 */
    data class Ok(val cookies: Map<String, String>, val cookieHeader: String) : XmuLoginOutcome

    /** 需要验证码：必须回退可见 WebView，绝不尝试提交密码 */
    data object NeedCaptcha : XmuLoginOutcome

    /** 学号或密码错误 / 登录页重新渲染：调用方置 BLOCKED，不重试 */
    data object InvalidCredentials : XmuLoginOutcome

    data object ServiceBusy : XmuLoginOutcome
    data object NetworkError : XmuLoginOutcome
    data object Timeout : XmuLoginOutcome

    /** 平台登录链路改版（缺 execution/salt/token/session，或跳到未知域） */
    data object FormatChanged : XmuLoginOutcome
}

/**
 * 方案 B：原生 OAuth 登录（指导 §3.2）。
 *
 * 链路：Keycloak 授权端点 → 302 到 ids.xmu.edu.cn 登录页 → 解析 execution /
 * pwdEncryptSalt → checkNeedCaptcha → 加密密码 POST → 跟随 CAS 回调取 code →
 * 换 access_token → `POST /api/login?login=access_token` 拿 lnt cookie。
 *
 * 纪律：
 * - **一次 login() 最多提交一次密码**，任何失败都不在内部重试；重试策略由
 *   [XmuAutoLoginManager] 统一裁决（失败即 BLOCKED）；
 * - 重定向手动跟随，只放行 https + 学校域名；c-mobile 回调只解析 code，不请求；
 * - 不写任何日志（学号、密码、cookie、URL 参数都不落日志）；
 * - 传输层异常按 Timeout / NetworkError 分类，5xx 单独返回 [XmuLoginOutcome.ServiceBusy]。
 *
 * 测试通过注入 [XmuHttpTransport] 脚本化整条重定向链，不需要真实网络。
 */
internal class XmuLoginClient(
    private val transport: XmuHttpTransport,
    private val cipher: XmuPasswordCipher,
) {

    suspend fun login(username: String, password: String): XmuLoginOutcome {
        val outcome = try {
            withTimeout(LOGIN_TIMEOUT_MS) { performLogin(username, password) }
        } catch (_: TimeoutCancellationException) {
            // withTimeout 的取消是「本次登录超时」，不是调用方取消
            XmuLoginOutcome.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            XmuLoginOutcome.Timeout
        } catch (_: IOException) {
            XmuLoginOutcome.NetworkError
        } catch (_: Exception) {
            XmuLoginOutcome.FormatChanged
        }
        XmuDebugLog.d("login outcome=${outcome::class.simpleName}")
        return outcome
    }

    private suspend fun performLogin(username: String, password: String): XmuLoginOutcome {
        val jar = XmuCookieJar()

        // 1. 授权端点：跟随到 ids 登录页（或已有 SSO 会话时直接拿到回调 code）
        val first = follow(
            jar = jar,
            startUrl = AUTH_URL,
            headers = mapOf("Accept" to HTML_ACCEPT),
        )
        val loginPage = when (first) {
            is Hop.LoginPage -> first
            is Hop.Callback -> return exchangeCode(first.code, jar)
            Hop.ServiceBusy -> return XmuLoginOutcome.ServiceBusy
            is Hop.Page, Hop.FormatChanged -> return XmuLoginOutcome.FormatChanged
        }

        val execution = parseExecution(loginPage.html) ?: return XmuLoginOutcome.FormatChanged
        val salt = parseSalt(loginPage.html) ?: return XmuLoginOutcome.FormatChanged
        XmuDebugLog.d("login stage=loginPage host=${hostOf(loginPage.url)} parsed=ok")

        // 2. 验证码探测：需要验证码就到此为止，不提交密码
        val captcha = checkCaptcha(jar, username, loginPage.url)
        XmuDebugLog.d("login stage=captcha result=$captcha")
        when (captcha) {
            CaptchaCheck.Needed -> return XmuLoginOutcome.NeedCaptcha
            CaptchaCheck.Busy -> return XmuLoginOutcome.ServiceBusy
            CaptchaCheck.Format -> return XmuLoginOutcome.FormatChanged
            CaptchaCheck.Clear -> Unit
        }

        // 3. 加密密码并提交登录表单（保留登录页自己的 service 参数）；
        //    表单字段与校方登录页的隐藏域一一对应（lt 为空值也带上）
        val encrypted = cipher.encrypt(password, salt) ?: return XmuLoginOutcome.FormatChanged
        val body = form(
            "username" to username,
            "password" to encrypted,
            "captcha" to "",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to "",
            "execution" to execution,
        )
        val posted = follow(
            jar = jar,
            startUrl = loginPage.url,
            method = "POST",
            formBody = body,
            headers = mapOf(
                "Accept" to HTML_ACCEPT,
                "Referer" to loginPage.url,
                "Origin" to "https://$IDS_HOST",
            ),
        )
        XmuDebugLog.d("login stage=loginPost result=${posted::class.simpleName}")
        return when (posted) {
            is Hop.Callback -> exchangeCode(posted.code, jar)
            // 重新渲染登录页 = 密码错误（也可能是新一次验证码要求）：一次即停
            is Hop.LoginPage -> XmuLoginOutcome.InvalidCredentials
            Hop.ServiceBusy -> XmuLoginOutcome.ServiceBusy
            is Hop.Page ->
                if (hostOf(posted.url) == IDS_HOST) {
                    XmuLoginOutcome.InvalidCredentials
                } else {
                    XmuLoginOutcome.FormatChanged
                }
            Hop.FormatChanged -> XmuLoginOutcome.FormatChanged
        }
    }

    private suspend fun checkCaptcha(
        jar: XmuCookieJar,
        username: String,
        referer: String,
    ): CaptchaCheck {
        val response = transport.execute(
            XmuHttpRequest(
                method = "POST",
                url = CAPTCHA_URL,
                headers = buildMap {
                    put("Accept", "application/json, text/javascript, */*; q=0.01")
                    put("X-Requested-With", "XMLHttpRequest")
                    put("Referer", referer)
                    jar.headerFor(CAPTCHA_URL)?.let { put("Cookie", it) }
                },
                formBody = form("username" to username),
            ),
        )
        return when {
            response.status in 500..599 -> CaptchaCheck.Busy
            response.status !in 200..299 -> CaptchaCheck.Format
            else -> when (decodeOrNull<XmuCaptchaDto>(response.body)?.isNeed) {
                true -> CaptchaCheck.Needed
                false -> CaptchaCheck.Clear
                null -> CaptchaCheck.Format
            }
        }
    }

    /** code → access_token → lnt 会话 cookie */
    private suspend fun exchangeCode(code: String, jar: XmuCookieJar): XmuLoginOutcome {
        val tokenResponse = transport.execute(
            XmuHttpRequest(
                method = "POST",
                url = TOKEN_URL,
                headers = buildMap {
                    put("Accept", "application/json")
                    jar.headerFor(TOKEN_URL)?.let { put("Cookie", it) }
                },
                formBody = form(
                    "grant_type" to "authorization_code",
                    "client_id" to CLIENT_ID,
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "scope" to "openid",
                ),
            ),
        )
        if (tokenResponse.status in 500..599) return XmuLoginOutcome.ServiceBusy
        if (tokenResponse.status !in 200..299) return XmuLoginOutcome.FormatChanged
        XmuDebugLog.d("login stage=token status=${tokenResponse.status}")
        val accessToken = decodeOrNull<XmuTokenDto>(tokenResponse.body)
            ?.accessToken
            ?.takeIf { it.isNotBlank() }
            ?: return XmuLoginOutcome.FormatChanged

        val requestBody = ScheduleJson.encodeStorage(
            XmuAccessTokenRequest(accessToken = accessToken, orgId = ORG_ID),
        )
        var url = LNT_TOKEN_LOGIN_URL
        var method = "POST"
        var json: String? = requestBody
        // lnt 换 cookie 偶尔会 302 一次（到 lnt 首页），最多跟 3 跳并收集 Set-Cookie
        repeat(LNT_LOGIN_REDIRECTS) {
            val response = transport.execute(
                XmuHttpRequest(
                    method = method,
                    url = url,
                    headers = buildMap {
                        put("Accept", "application/json")
                        jar.headerFor(url)?.let { put("Cookie", it) }
                    },
                    jsonBody = json,
                ),
            )
            jar.store(url, response.setCookies)
            XmuDebugLog.d("login stage=lntLogin status=${response.status} cookies=${response.setCookies.size}")
            when {
                response.status in 500..599 -> return XmuLoginOutcome.ServiceBusy
                response.status in 300..399 -> {
                    val next = response.location?.let { resolve(url, it) }
                        ?: return XmuLoginOutcome.FormatChanged
                    if (hostOf(next) != XmuWebViewPolicy.LNT_HOST) {
                        return XmuLoginOutcome.FormatChanged
                    }
                    url = next
                    method = "GET"
                    json = null
                }
                response.status in 200..299 -> return finishLogin(jar)
                else -> return XmuLoginOutcome.FormatChanged
            }
        }
        return XmuLoginOutcome.FormatChanged
    }

    private fun finishLogin(jar: XmuCookieJar): XmuLoginOutcome {
        // 不硬性检查 cookie 名（lnt 的会话 cookie 名属于平台细节）：拿到 lnt 域 cookie
        // 且换登录返回 2xx 即算成功，有效性由随后的 /api/profile 探活判定
        val cookies = jar.lntCookies()
        if (cookies.isEmpty()) return XmuLoginOutcome.FormatChanged
        return XmuLoginOutcome.Ok(
            cookies = cookies,
            cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        )
    }

    // ---------- 重定向与 cookie ----------

    private sealed interface Hop {
        data class LoginPage(val url: String, val html: String) : Hop
        data class Callback(val code: String) : Hop
        data class Page(val url: String, val response: XmuHttpResponse) : Hop
        data object ServiceBusy : Hop
        data object FormatChanged : Hop
    }

    private suspend fun follow(
        jar: XmuCookieJar,
        startUrl: String,
        method: String = "GET",
        formBody: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Hop {
        var url = startUrl
        var currentMethod = method
        var currentForm = formBody
        var currentHeaders = headers

        repeat(MAX_REDIRECTS) {
            // 回调页只在 Location 里解析 code，绝不真的请求 c-mobile
            if (isCallback(url)) {
                val code = queryParam(url, "code") ?: return Hop.FormatChanged
                return Hop.Callback(code)
            }
            if (!isAllowed(url)) return Hop.FormatChanged

            val cookie = jar.headerFor(url)
            val requestHeaders = if (cookie == null || currentHeaders.containsKey("Cookie")) {
                currentHeaders
            } else {
                currentHeaders + ("Cookie" to cookie)
            }
            val response = transport.execute(
                XmuHttpRequest(
                    method = currentMethod,
                    url = url,
                    headers = requestHeaders,
                    formBody = currentForm,
                ),
            )
            jar.store(url, response.setCookies)
            XmuDebugLog.d("login hop $currentMethod ${hopLabel(url)} -> ${response.status}")

            when {
                response.status in 500..599 -> return Hop.ServiceBusy
                response.status in 300..399 -> {
                    val location = response.location ?: return Hop.FormatChanged
                    val next = resolve(url, location) ?: return Hop.FormatChanged
                    if (isCallback(next)) {
                        val code = queryParam(next, "code") ?: return Hop.FormatChanged
                        return Hop.Callback(code)
                    }
                    if (!isAllowed(next)) return Hop.FormatChanged
                    // 307/308 保留方法与请求体；CAS 链路实际只出现 302/303
                    if (response.status != 307 && response.status != 308) {
                        currentMethod = "GET"
                        currentForm = null
                        currentHeaders = emptyMap()
                    }
                    url = next
                }
                response.status in 200..299 -> {
                    return if (isLoginPage(url)) {
                        Hop.LoginPage(url, response.body)
                    } else {
                        Hop.Page(url, response)
                    }
                }
                else -> return Hop.FormatChanged
            }
        }
        return Hop.FormatChanged
    }

    private enum class CaptchaCheck { Clear, Needed, Busy, Format }

    // ---------- 页面解析 ----------

    private fun parseExecution(html: String): String? =
        EXECUTION_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?.let(::unescapeHtml)
            ?.takeIf { it.isNotBlank() }

    private fun parseSalt(html: String): String? =
        SALT_PATTERNS.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?.let(::unescapeHtml)
            ?.takeIf { it.isNotBlank() }

    /** 登录页是 UTF-8；少量属性值可能被 HTML 实体转义 */
    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    // ---------- URL / 表单工具 ----------

    private fun hostOf(url: String): String? = try {
        URL(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** host + path（不含查询串：service/ticket/code 等参数不进日志） */
    private fun hopLabel(url: String): String {
        val host = hostOf(url) ?: "?"
        val path = pathOf(url) ?: ""
        return "$host$path"
    }

    private fun pathOf(url: String): String? = try {
        URL(url).path
    } catch (_: Exception) {
        null
    }

    private fun isLoginPage(url: String): Boolean =
        hostOf(url) == IDS_HOST && pathOf(url)?.startsWith("/authserver/login") == true

    private fun isCallback(url: String): Boolean =
        hostOf(url) == C_MOBILE_HOST && pathOf(url) == CALLBACK_PATH

    private fun isAllowed(url: String): Boolean = try {
        val parsed = URL(url)
        parsed.protocol.equals("https", ignoreCase = true) && hostOf(url) in ALLOWED_HOSTS
    } catch (_: Exception) {
        false
    }

    private fun resolve(base: String, location: String): String? = try {
        URL(URL(base), location).toString()
    } catch (_: Exception) {
        null
    }

    private fun queryParam(url: String, name: String): String? {
        val query = try {
            URL(url).query
        } catch (_: Exception) {
            null
        } ?: return null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            val key = if (eq < 0) pair else pair.substring(0, eq)
            if (key != name) continue
            val raw = if (eq < 0) "" else pair.substring(eq + 1)
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        return null
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private inline fun <reified T> decodeOrNull(text: String): T? = try {
        ScheduleJson.decodeStorage<T>(text)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val CLIENT_ID = "TronClassH5"
        const val REDIRECT_URI =
            "https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true"
        const val AUTH_URL =
            "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/auth" +
                "?client_id=TronClassH5" +
                "&redirect_uri=https%3A%2F%2Fc-mobile.xmu.edu.cn%2Fidentity-web-login-callback%3F_h5%3Dtrue" +
                "&response_type=code&scope=openid"
        const val TOKEN_URL =
            "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/token"
        const val CAPTCHA_URL = "https://ids.xmu.edu.cn/authserver/checkNeedCaptcha.htl"
        const val LNT_TOKEN_LOGIN_URL = "https://lnt.xmu.edu.cn/api/login?login=access_token"
        const val IDS_HOST = "ids.xmu.edu.cn"
        const val C_MOBILE_HOST = "c-mobile.xmu.edu.cn"
        const val CALLBACK_PATH = "/identity-web-login-callback"
        const val ORG_ID = 1
        const val MAX_REDIRECTS = 12
        const val LNT_LOGIN_REDIRECTS = 3
        const val LOGIN_TIMEOUT_MS = 60_000L
        const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        val ALLOWED_HOSTS = setOf(
            XmuWebViewPolicy.LNT_HOST,
            IDS_HOST,
            "c-identity.xmu.edu.cn",
            "pass.xmu.edu.cn",
        )

        val EXECUTION_PATTERNS = listOf(
            Regex(
                """name\s*=\s*["']execution["'][^>]*?value\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """value\s*=\s*["']([^"']+)["'][^>]*?name\s*=\s*["']execution["']""",
                RegexOption.IGNORE_CASE,
            ),
        )

        val SALT_PATTERNS = listOf(
            Regex("""pwdEncryptSalt\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex(
                """id\s*=\s*["']pwdEncryptSalt["'][^>]*?value\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """value\s*=\s*["']([^"']+)["'][^>]*?id\s*=\s*["']pwdEncryptSalt["']""",
                RegexOption.IGNORE_CASE,
            ),
        )
    }
}

@Serializable
private data class XmuCaptchaDto(
    @SerialName("isNeed") val isNeed: Boolean? = null,
)

@Serializable
private data class XmuTokenDto(
    @SerialName("access_token") val accessToken: String? = null,
)

@Serializable
private data class XmuAccessTokenRequest(
    @SerialName("access_token") val accessToken: String,
    @SerialName("org_id") val orgId: Int = 1,
)
