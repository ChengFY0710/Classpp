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
 * 链路：直接 GET 身份域 CAS 登录页（service 指向教务课表业务端点；该 URL 取自教务站
 * 未登录 302 的 Location，不依赖教务站当次的重定向行为）→ 解析 execution /
 * pwdEncryptSalt → checkNeedCaptcha → 加密密码 POST → 跟随 ticket 重定向回教务端点
 * → 收集 jw 域 cookie。**入口不用教务根路径**：`/` 只 302 到校内门户页（不强制登录）。
 * 与 [XmuLoginClient]（课程中心链路）共用 CAS 身份域与密码加密，但不共享登录态：
 * 教务会话只对 jw 域有效。
 *
 * 纪律（同 [XmuLoginClient]）：
 * - **一次 login() 最多提交一次密码**，任何失败都不在内部重试；失败由
 *   [XmuJwScheduleImporter] 报给 UI，绝不触碰课程中心的登录态与凭据策略；
 * - 重定向手动跟随，只放行 https + 学校域（*.xmu.edu.cn）；
 * - **密码只提交给 CAS 主机**（ids / pass）：登录页即便出现在别处也按改版处理；
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

        // 1. 入口直达 CAS 登录页（service 采用教务嵌套登录入口，见 ENTRY_URL 注释）
        val first = follow(jar, ENTRY_URL)
        val loginPage = when (first) {
            is Hop.LoginPage -> first
            Hop.ServiceBusy -> return XmuLoginOutcome.ServiceBusy
            is Hop.Page, Hop.FormatChanged, is Hop.Callback -> return XmuLoginOutcome.FormatChanged
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

        // 3. 加密密码并提交登录表单：字段与教务登录页一致（lt/dllt 取自页面，
        //    rmShown=1 为校方页面默认提交项）。密码只交给 CAS 主机
        if (hostOf(loginPage.url) !in CAS_HOSTS) return XmuLoginOutcome.FormatChanged
        val encrypted = cipher.encrypt(password, salt) ?: return XmuLoginOutcome.FormatChanged
        val body = form(
            "username" to username,
            "password" to encrypted,
            "lt" to parseInputValue(loginPage.html, "lt"),
            "dllt" to parseInputValue(loginPage.html, "dllt"),
            "execution" to execution,
            "_eventId" to "submit",
            "rmShown" to "1",
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

        // 4. CAS 成功后经 ticket 重定向回教务站；重新渲染登录页 = 密码错误（一次即停）。
        //    个别节点会把落地页判 403，但 ticket 校验已下发会话 cookie：只要 jw 域已有
        //    会话类 cookie，就继续走应用入口，由随后的业务请求最终判定有效性
        return when (posted) {
            is Hop.Page ->
                if (hostOf(posted.url) == JW_HOST) {
                    finishWithAppEntry(jar)
                } else {
                    XmuLoginOutcome.FormatChanged
                }
            is Hop.LoginPage -> XmuLoginOutcome.InvalidCredentials
            Hop.ServiceBusy -> XmuLoginOutcome.ServiceBusy
            Hop.FormatChanged ->
                if (hasJwSessionCookie(jar)) {
                    finishWithAppEntry(jar)
                } else {
                    XmuLoginOutcome.FormatChanged
                }
            is Hop.Callback -> XmuLoginOutcome.FormatChanged
        }
    }

    /**
     * 打开课表应用入口（appShow）。教务为每个应用分配独立的 appShow 入口：仅有统一
     * 会话不够，必须先打开应用完成应用级会话初始化，否则课表接口会被重定向回登录页。
     * 与参考实现（democard/xmu_assistant）的 appId 一致（即 wdkbapp 的应用 id）。
     */
    private suspend fun finishWithAppEntry(jar: XmuCookieJar): XmuLoginOutcome {
        when (val appShow = follow(jar, APP_SHOW_URL)) {
            Hop.ServiceBusy -> return XmuLoginOutcome.ServiceBusy
            // 应用入口仍回登录页 = 会话未落地（如密码错误/风控中间态），不返回无效会话
            is Hop.LoginPage -> return XmuLoginOutcome.FormatChanged
            is Hop.Page, Hop.FormatChanged, is Hop.Callback -> Unit
        }
        return finishLogin(jar)
    }

    /** jw 域是否已出现会话类 cookie（GS_ 前缀或 JSESSIONID；route 只是负载均衡路由） */
    private fun hasJwSessionCookie(jar: XmuCookieJar): Boolean =
        jar.cookiesFor(JW_HOST).keys.any { name ->
            name.startsWith("GS_", ignoreCase = true) || name.equals("JSESSIONID", ignoreCase = true)
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
        // 跳转链上的来源页：随 302 更新（与浏览器导航的 Referer 语义一致），
        // 供 CAS 回落教务站等链路做同源/来源校验
        var referer: String? = null

        repeat(MAX_REDIRECTS) {
            if (!isAllowed(url)) return Hop.FormatChanged

            val cookie = jar.headerFor(url)
            // var 在 lambda 内无法智能转型：先取局部 val 再拼请求头
            val refererForHop = referer
            val requestHeaders = buildMap {
                putAll(currentHeaders)
                if (!containsKey("Accept")) put("Accept", HTML_ACCEPT)
                if (!containsKey("Referer") && refererForHop != null) put("Referer", refererForHop)
                if (!containsKey("Cookie") && cookie != null) put("Cookie", cookie)
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
            XmuDebugLog.d(
                "jwLogin hop $currentMethod ${hopLabel(url)} -> ${response.status}" +
                    " cookieSent=${cookie != null}" +
                    " setCookies=[${cookieNames(response.setCookies)}]",
            )

            when {
                response.status in 500..599 -> return Hop.ServiceBusy
                response.status in 300..399 -> {
                    val location = response.location ?: return Hop.FormatChanged
                    val next = resolve(url, location) ?: return Hop.FormatChanged
                    XmuDebugLog.d("jwLogin redirect -> ${hopLabel(next)}${queryParamNames(next)}")
                    if (!isAllowed(next)) return Hop.FormatChanged
                    // 307/308 保留方法与请求体；CAS 链路实际只出现 302/303
                    if (response.status != 307 && response.status != 308) {
                        currentMethod = "GET"
                        currentForm = null
                        currentHeaders = emptyMap()
                    }
                    referer = url
                    url = next
                }
                // 本部署的 CAS 对失败登录返回 401 + 重渲染登录页（含验证码错误提示）：
                // 正文仍是登录页 → 按登录失败处理，而不是链路改版
                response.status == 401 || response.status == 403 -> {
                    val execution = parseExecution(response.body)
                    val salt = parseSalt(response.body)
                    return if (execution != null && salt != null) {
                        Hop.LoginPage(url, response.body)
                    } else {
                        XmuDebugLog.d(
                            "jwLogin denied ${hopLabel(url)} status=${response.status}" +
                                failureHint(response.body),
                        )
                        Hop.FormatChanged
                    }
                }
                response.status in 200..299 -> {
                    val execution = parseExecution(response.body)
                    val salt = parseSalt(response.body)
                    return if (execution != null && salt != null) {
                        Hop.LoginPage(url, response.body)
                    } else {
                        XmuDebugLog.d(
                            "jwLogin page ${hopLabel(url)} kind=${pageKind(response.body)}",
                        )
                        Hop.Page(url, response)
                    }
                }
                else -> {
                    XmuDebugLog.d(
                        "jwLogin unexpected ${hopLabel(url)} status=${response.status}" +
                            failureHint(response.body),
                    )
                    return Hop.FormatChanged
                }
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

    /** 解析隐藏输入值（lt/dllt 等；找不到返回空串，与校方页面默认提交一致） */
    private fun parseInputValue(html: String, name: String): String {
        val patterns = listOf(
            Regex(
                """name\s*=\s*["']$name["'][^>]*?value\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """value\s*=\s*["']([^"']*)["'][^>]*?name\s*=\s*["']$name["']""",
                RegexOption.IGNORE_CASE,
            ),
            Regex(
                """id\s*=\s*["']$name["'][^>]*?value\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ),
        )
        for (pattern in patterns) {
            pattern.find(html)?.groupValues?.getOrNull(1)?.let { return unescapeHtml(it) }
        }
        return ""
    }

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

    /** 跳转目标的查询参数**名**（值可能是 ticket/service，不落日志） */
    private fun queryParamNames(url: String): String {
        val query = try {
            URL(url).query
        } catch (_: Exception) {
            null
        } ?: return ""
        val names = query.split('&')
            .map { it.substringBefore('=') }
            .filter { it.isNotEmpty() }
        return if (names.isEmpty()) "" else "?${names.joinToString("&")}"
    }

    /** Set-Cookie 的 cookie 名（值不落日志），用于确认会话/路由 cookie 是否下发 */
    private fun cookieNames(setCookies: List<String>): String =
        setCookies.mapNotNull { raw ->
            raw.substringBefore('=').trim().takeIf { it.isNotEmpty() }
        }.joinToString(",").ifEmpty { "-" }

    /**
     * 4xx/异常页只记正文长度与命中的第一个错误关键词，不记正文（错误页可能回显
     * 请求内容）；用于区分 WAF 拦截 / 权限拒绝 / 登录页改版。
     */
    private fun failureHint(body: String): String {
        if (body.isBlank()) return " len=0"
        val markers = listOf(
            "验证码", "密码", "非法", "无权", "权限", "禁止", "登录", "过期",
            "ticket", "sso", "forbidden", "denied", "error",
        )
        val hit = markers.firstOrNull { body.contains(it, ignoreCase = true) }
        return " len=${body.length} hint=${hit ?: "-"}"
    }

    /** 200 页面分类：app = 教务应用页；other = 其他页面（长度供对照） */
    private fun pageKind(body: String): String = when {
        body.contains("WIS_EMAP_SERV") || body.contains("wdkbapp") || body.contains("xskcb") ->
            "app(${body.length})"
        body.contains("pwdEncryptSalt") || body.contains("authserver") ->
            "login(${body.length})"
        else -> "other(${body.length})"
    }

    private fun isAllowed(url: String): Boolean = try {
        val parsed = URL(url)
        val host = hostOf(url)
        parsed.protocol.equals("https", ignoreCase = true) && host != null &&
            (host == SCHOOL_DOMAIN || host.endsWith(".$SCHOOL_DOMAIN"))
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
         * 教务「我的课表」应用页：登录后应用入口与接口调用的 Referer 基准
         * （与参考实现一致，页面导航/接口同源语义）。
         */
        const val APP_INDEX_URL =
            "https://$JW_HOST/gsapp/sys/wdkbapp/*default/index.do"

        /** 接口请求的 Referer（与参考实现一致：门户首页，教务接口按此判定来源） */
        const val API_REFERER = "https://$JW_HOST/new/index.html"

        private const val IDS_HOST = "ids.xmu.edu.cn"
        private const val SCHOOL_DOMAIN = "xmu.edu.cn"

        /**
         * 登录入口 = CAS 登录页，service 采用教务的**嵌套登录入口**
         * `jw.xmu.edu.cn/login?service=https://jw.xmu.edu.cn/new/index.html`
         * （与参考实现 democard/xmu_assistant 完全一致）：CAS 认证后先落到教务的
         * /login 端点建立 jw 会话，再跳门户页；只把应用页当 service 建立不了会话。
         */
        private const val ENTRY_URL =
            "https://$IDS_HOST/authserver/login?type=userNameLogin&service=" +
                "https://$JW_HOST/login?service=https://$JW_HOST/new/index.html"

        /** wdkbapp 的应用 id（界面加载的 getAppConfig/wdkbapp-<id> 同值） */
        private const val SCHEDULE_APP_ID = "4979568947762216"

        /**
         * 课表应用入口：教务为每个应用分配独立的 appShow 会话初始化入口。
         * 仅有统一会话不够，必须先打开应用，课表接口才会接受请求。
         */
        private const val APP_SHOW_URL = "https://$JW_HOST/appShow?appId=$SCHEDULE_APP_ID"

        /** 允许提交密码的 CAS 主机（登录页识别按内容，但凭据绝不交给别的域） */
        private val CAS_HOSTS = setOf(IDS_HOST, "pass.xmu.edu.cn")

        private const val MAX_REDIRECTS = 16
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
