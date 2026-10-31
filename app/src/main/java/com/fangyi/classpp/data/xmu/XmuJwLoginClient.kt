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

/**
 * 教务系统（jw.xmu.edu.cn，金智/EMP 架构）的原生登录。
 *
 * 链路：GET 受保护的课表业务端点 → 未登录被 302 到 ids.xmu.edu.cn CAS 登录页
 * （service 指回该端点）→ 解析 execution / pwdEncryptSalt → checkNeedCaptcha →
 * 加密密码 POST → 跟随 ticket 重定向回该端点 → 收集 jw 域 cookie。
 * **入口不用根路径**：`/` 只 302 到校内门户页（不强制登录），拿不到 CAS 往返；
 * 受保护端点会让服务器自己给出 service，登录后回落同端点即建立会话。
 * 与 [XmuLoginClient]（课程中心链路）共用 CAS 身份域与密码加密，但不共享登录态：
 * 教务会话只对 jw 域有效。
 *
 * 纪律（同 [XmuLoginClient]）：
 * - **一次 login() 最多提交一次密码**，任何失败都不在内部重试；失败由
 *   [XmuJwScheduleImporter] 报给 UI，绝不触碰课程中心的登录态与凭据策略；
 * - 重定向手动跟随，只放行 https + 白名单学校域；
 * - 不写任何日志（学号、密码、cookie、URL 参数都不落日志）；
 * - 登录页按**页面内容**识别（正文含 execution + salt），不赌具体路径。
 *
 * 测试通过注入 [XmuHttpTransport] 脚本化整条重定向链，不需要真实网络。
 */
internal class XmuJwLoginClient(
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
        XmuDebugLog.d("jwLogin outcome=${outcome::class.simpleName}")
        return outcome
    }

    private suspend fun performLogin(username: String, password: String): XmuLoginOutcome {
        val jar = XmuCookieJar()

        // 1. 教务站入口：未登录会被逐跳 302 到身份域 CAS 登录页
        val first = follow(jar, ENTRY_URL)
        val loginPage = when (first) {
            is Hop.LoginPage -> first
            // 入口直接 200 落在教务站（无 CAS 往返）：拿不到会话，按改版处理
            is Hop.Page, Hop.FormatChanged -> return XmuLoginOutcome.FormatChanged
            Hop.ServiceBusy -> return XmuLoginOutcome.ServiceBusy
            is Hop.Callback -> return XmuLoginOutcome.FormatChanged
        }
        val execution = parseExecution(loginPage.html) ?: return XmuLoginOutcome.FormatChanged
        val salt = parseSalt(loginPage.html) ?: return XmuLoginOutcome.FormatChanged
        XmuDebugLog.d("jwLogin stage=loginPage host=${hostOf(loginPage.url)} parsed=ok")

        // 2. 验证码探测：需要验证码就到此为止，不提交密码
        val captcha = checkCaptcha(jar, username, loginPage.url)
        XmuDebugLog.d("jwLogin stage=captcha result=$captcha")
        when (captcha) {
            CaptchaCheck.Needed -> return XmuLoginOutcome.NeedCaptcha
            CaptchaCheck.Busy -> return XmuLoginOutcome.ServiceBusy
            CaptchaCheck.Format -> return XmuLoginOutcome.FormatChanged
            CaptchaCheck.Clear -> Unit
        }

        // 3. 加密密码并提交登录表单（保留登录页自己的 service 参数）
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
                "Origin" to "https://${hostOf(loginPage.url) ?: IDS_HOST}",
            ),
        )
        XmuDebugLog.d("jwLogin stage=loginPost result=${posted::class.simpleName}")

        // 4. CAS 成功后经 ticket 重定向回教务站；重新渲染登录页 = 密码错误（一次即停）
        return when (posted) {
            is Hop.Page ->
                if (hostOf(posted.url) == JW_HOST) {
                    finishLogin(jar)
                } else {
                    XmuLoginOutcome.FormatChanged
                }
            is Hop.LoginPage -> XmuLoginOutcome.InvalidCredentials
            Hop.ServiceBusy -> XmuLoginOutcome.ServiceBusy
            is Hop.Callback, Hop.FormatChanged -> XmuLoginOutcome.FormatChanged
        }
    }

    private fun finishLogin(jar: XmuCookieJar): XmuLoginOutcome {
        // 不硬性检查 cookie 名（教务站会话 cookie 名属于平台细节）：拿到 jw 域 cookie
        // 即算成功，有效性由随后的业务请求判定
        val cookies = jar.cookiesFor(JW_HOST)
        if (cookies.isEmpty()) return XmuLoginOutcome.FormatChanged
        return XmuLoginOutcome.Ok(
            cookies = cookies,
            cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
        )
    }

    // ---------- 重定向与页面识别 ----------

    private sealed interface Hop {
        /** 正文含 execution + salt 的 CAS 登录页（不赌具体 URL） */
        data class LoginPage(val url: String, val html: String) : Hop

        data class Page(val url: String, val response: XmuHttpResponse) : Hop

        /** 教务链路没有 code 回调页，出现即改版 */
        data object Callback : Hop

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
            XmuDebugLog.d("jwLogin hop $currentMethod ${hopLabel(url)} -> ${response.status}")

            when {
                response.status in 500..599 -> return Hop.ServiceBusy
                response.status in 300..399 -> {
                    val location = response.location ?: return Hop.FormatChanged
                    val next = resolve(url, location) ?: return Hop.FormatChanged
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
                    val execution = parseExecution(response.body)
                    val salt = parseSalt(response.body)
                    return if (execution != null && salt != null) {
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

    /** 验证码探测端点跟随登录页所在主机（CAS 部署域属于平台细节） */
    private suspend fun checkCaptcha(
        jar: XmuCookieJar,
        username: String,
        referer: String,
    ): CaptchaCheck {
        val captchaUrl = "https://${hostOf(referer) ?: IDS_HOST}/authserver/checkNeedCaptcha.htl"
        val response = transport.execute(
            XmuHttpRequest(
                method = "POST",
                url = captchaUrl,
                headers = buildMap {
                    put("Accept", "application/json, text/javascript, */*; q=0.01")
                    put("X-Requested-With", "XMLHttpRequest")
                    put("Referer", referer)
                    jar.headerFor(captchaUrl)?.let { put("Cookie", it) }
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

    // ---------- 页面解析（与 XmuLoginClient 同一套 CAS 表单约定） ----------

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

    /** host + path（不含查询串：service/ticket 等参数不进日志） */
    private fun hopLabel(url: String): String {
        val host = hostOf(url) ?: "?"
        val path = try {
            URL(url).path.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return "$host$path"
    }

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

    companion object {
        const val JW_HOST = "jw.xmu.edu.cn"

        /**
         * 登录入口 = 受保护的课表业务端点：未登录请求它会被 302 到 CAS（service 指回本
         * 端点），登录成功后带 ticket 回落同端点，CAS 过滤器就地校验并建立 jw 会话。
         * 根路径 `/` 不强制登录（只跳门户页），不能作入口。
         */
        const val ENTRY_URL = "https://$JW_HOST/gsapp/sys/wdkbapp/modules/xskcb/kfdxnxqcx.do"

        private const val IDS_HOST = "ids.xmu.edu.cn"
        private const val MAX_REDIRECTS = 12
        private const val LOGIN_TIMEOUT_MS = 60_000L
        private const val HTML_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

        private val ALLOWED_HOSTS = setOf(JW_HOST, IDS_HOST, "pass.xmu.edu.cn")

        private val EXECUTION_PATTERNS = listOf(
            Regex(
                """name\s*=\s*["']execution["'][^>]*?value\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """value\s*=\s*["']([^"']+)["'][^>]*?name\s*=\s*["']execution["']""",
                RegexOption.IGNORE_CASE,
            ),
        )

        private val SALT_PATTERNS = listOf(
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
