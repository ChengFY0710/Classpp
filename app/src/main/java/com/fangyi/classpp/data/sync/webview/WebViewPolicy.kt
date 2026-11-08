package com.fangyi.classpp.data.sync.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import java.net.URI
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 通用 WebView 登录的加固与声明校验（指导 §2.3/§四 Phase 4）。
 *
 * **加固项锁死在本文件**，[WebViewLoginSpec] 只能声明入口 URL/白名单/端点/UA，
 * 无法关闭任何加固：
 * - 仅 https、域名白名单内才放行导航；SSL 错误一律 cancel；
 * - 禁 file/content 访问、禁混合内容；
 * - **登录页（身份域）不注入任何脚本**，页内 fetch 只在 `dataHost` 页面上下文执行；
 * - 不用 addJavascriptInterface——结果写页面变量，原生侧 evaluateJavascript 轮询读取。
 *
 * 白名单校验（注册表装配后硬校验）：仅 https、默认 443 端口、禁 userinfo、禁 IP 字面量/
 * 通配符、`dataHost ∈ allowedHosts`；不同学校的 `allowedHosts` 不得重叠（共用 CAS/
 * 认证域会让 cookie 互串，属配置错误）。
 */
object WebViewPolicy {

    private const val TAG = "SyncWebView"

    /** 主机名：必须含至少一个点、小写字母/数字/连字符，拒绝 IP 字面量与通配符 */
    private val HOST_PATTERN = Regex(
        "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$",
    )

    /** 页内 fetch 路径的安全子集：必须是 `/` 开头，禁止引号/反斜杠/空白（防 JS 注入） */
    private val PATH_PATTERN = Regex("^/[A-Za-z0-9._~/?=&%+:-]*$")

    private val IPV4_LITERAL = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

    /**
     * 校验声明，返回全部问题（空 = 合法）。注册表在会话装配后硬校验，任何问题都拒绝装配。
     * 纯函数，JVM 单测直接覆盖。
     */
    fun validate(spec: WebViewLoginSpec): List<String> {
        val issues = mutableListOf<String>()
        issues += validateHosts(spec.allowedHosts)
        when {
            spec.dataHost !in spec.allowedHosts -> issues += "dataHost must be in allowedHosts"
            !HOST_PATTERN.matches(spec.dataHost) -> issues += "invalid dataHost: '${spec.dataHost}'"
        }
        if (!PATH_PATTERN.matches(spec.profilePath)) {
            issues += "invalid profilePath: '${spec.profilePath}'"
        }
        if (!PATH_PATTERN.matches(spec.todosPath)) {
            issues += "invalid todosPath: '${spec.todosPath}'"
        }
        issues += validateUserAgent(spec.userAgent)
        issues += validateUrl("entryUrl", spec.entryUrl, spec.allowedHosts)
        return issues
    }

    /**
     * 校验教务课表页声明（与 [validate] 同一套主机/入口/UA 规则；没有取数端点）。
     * 注册表在会话装配后硬校验，任何问题都拒绝装配。
     */
    fun validateTimetable(spec: WebViewTimetableSpec): List<String> {
        val issues = mutableListOf<String>()
        issues += validateHosts(spec.allowedHosts)
        issues += validateUserAgent(spec.userAgent)
        issues += validateUrl("entryUrl", spec.entryUrl, spec.allowedHosts)
        return issues
    }

    /** 两个学校的白名单是否重叠（共用认证域会让 cookie 互串，属配置错误） */
    fun overlappingHosts(first: WebViewLoginSpec, second: WebViewLoginSpec): Set<String> =
        first.allowedHosts intersect second.allowedHosts

    /** 默认登录态判定：profile 正文是 JSON 对象（学校可注入更严格的解析口径） */
    fun defaultProfileValidator(raw: String): Boolean = try {
        ScheduleJson.storage.parseToJsonElement(raw) is JsonObject
    } catch (_: Exception) {
        false
    }

    // ---------- 运行时判定 ----------

    fun isAllowedHost(spec: WebViewLoginSpec, host: String?): Boolean =
        isHostAllowed(host, spec.allowedHosts)

    private fun isHostAllowed(host: String?, allowedHosts: Set<String>): Boolean =
        host != null && host in allowedHosts

    fun isDataHost(spec: WebViewLoginSpec, url: String?): Boolean =
        url != null && hostOf(url) == spec.dataHost

    /** 停在身份域登录页（会话已过期、等用户输入）——无头模式据此把超时归类为「登录过期」 */
    fun isIdentityHost(spec: WebViewLoginSpec, url: String?): Boolean {
        if (url == null) return false
        val host = hostOf(url) ?: return false
        return host != spec.dataHost && host in spec.allowedHosts
    }

    // ---------- WebView 装配 ----------

    /** 统一加固：JS/DOM 允许（学校页面需要），文件与混合内容全禁；多窗口开启 */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION") // 两个 file 访问开关新 API 已默认关闭，这里显式置 false 兜底老设备
    fun harden(webView: WebView, userAgent: String?) {
        with(webView.settings) {
            userAgent?.takeIf { it.isNotBlank() }?.let { userAgentString = it }
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // target=_blank / window.open 走 onCreateWindow 统一在当前 WebView 打开
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }
        webView.setBackgroundColor(android.graphics.Color.WHITE)
    }

    /**
     * 白名单客户端：非白名单导航一律吞掉（含外链/协议跳转），SSL 错误不 proceed；
     * 主文档加载失败经 [onLoadError] 通知 UI（可见登录浮层显示错误态 + 重试，
     * 无头取数保持静默等待超时归类）。
     */
    fun createClient(
        spec: WebViewLoginSpec,
        onDataPageFinished: () -> Unit,
        onLoadError: () -> Unit = {},
        onPageStarted: (String?) -> Unit = {},
    ): WebViewClient = whitelistClient(
        allowedHosts = spec.allowedHosts,
        onPageFinished = { url -> if (isDataHost(spec, url)) onDataPageFinished() },
        onLoadError = onLoadError,
        onPageStarted = onPageStarted,
    )

    /**
     * 教务课表页的白名单客户端：只放行声明域，不注入任何脚本、不挂钩数据页回调；
     * 主文档失败经 [onLoadError] 交给 UI 显示错误态 + 重试。
     */
    fun createTimetableClient(
        spec: WebViewTimetableSpec,
        onLoadError: () -> Unit = {},
        onPageStarted: (String?) -> Unit = {},
    ): WebViewClient = whitelistClient(
        allowedHosts = spec.allowedHosts,
        onPageFinished = {},
        onLoadError = onLoadError,
        onPageStarted = onPageStarted,
    )

    private fun whitelistClient(
        allowedHosts: Set<String>,
        onPageFinished: (String?) -> Unit,
        onLoadError: () -> Unit,
        onPageStarted: (String?) -> Unit,
    ): WebViewClient =
        object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onPageStarted(url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean {
                val url = request.url
                return !(url.scheme == "https" && isHostAllowed(url.host, allowedHosts))
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // 只记错误码，不记 URL/证书细节（避免敏感信息进日志）
                Log.w(TAG, "ssl error, primary=${error.primaryError}")
                handler.cancel()
                onLoadError()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    Log.w(TAG, "main frame error, code=${error.errorCode}")
                    onLoadError()
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onPageFinished(url)
            }
        }

    /**
     * Chrome 客户端：页面加载进度回调 + `target=_blank`/`window.open` 统一转当前
     * WebView 打开。转交实现：临时 WebView 承接新窗口，其首个非 blank 导航/页面即目标
     * 地址——取到后回主 WebView loadUrl 并销毁临时实例（白名单最后一道闸）。
     */
    fun createChromeClient(
        spec: WebViewLoginSpec,
        onProgress: (Int) -> Unit,
        onOpenWindow: (String) -> Unit,
    ): WebChromeClient = chromeClient(
        allowedHosts = spec.allowedHosts,
        userAgent = spec.userAgent,
        onProgress = onProgress,
        onOpenWindow = onOpenWindow,
    )

    /** 教务课表页的 Chrome 客户端：规格同 [createChromeClient]，没有 dataHost 概念 */
    fun createTimetableChromeClient(
        spec: WebViewTimetableSpec,
        onProgress: (Int) -> Unit,
        onOpenWindow: (String) -> Unit,
    ): WebChromeClient = chromeClient(
        allowedHosts = spec.allowedHosts,
        userAgent = spec.userAgent,
        onProgress = onProgress,
        onOpenWindow = onOpenWindow,
    )

    private fun chromeClient(
        allowedHosts: Set<String>,
        userAgent: String?,
        onProgress: (Int) -> Unit,
        onOpenWindow: (String) -> Unit,
    ): WebChromeClient =
        object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean {
                if (resultMsg == null) return false
                val forwarded = booleanArrayOf(false)
                var temp: WebView? = null

                fun destroyTemp() {
                    val wv = temp ?: return
                    temp = null
                    wv.destroySafely()
                }

                fun forward(url: String) {
                    if (forwarded[0]) return
                    forwarded[0] = true
                    val uri = Uri.parse(url)
                    if (uri.scheme == "https" && isHostAllowed(uri.host, allowedHosts)) {
                        onOpenWindow(url)
                    }
                    destroyTemp()
                }
                temp = WebView(view.context).apply {
                    // 与主 WebView 同一套加固：禁 file/content、禁混合内容、学校 UA
                    harden(this, userAgent)
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedSslError(
                            v: WebView,
                            handler: SslErrorHandler,
                            error: SslError,
                        ) {
                            handler.cancel()
                            destroyTemp()
                        }

                        override fun shouldOverrideUrlLoading(
                            v: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            // JS「先开空白页再写入」的模式：blank 不转发，真实地址随后导航触发
                            val url = request.url.toString()
                            if (url == "about:blank") return true
                            forward(url)
                            return true
                        }

                        override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                            if (url != null && url != "about:blank") forward(url)
                        }
                    }
                }

                (resultMsg.obj as? WebView.WebViewTransport)?.let { transport ->
                    transport.webView = temp
                    resultMsg.sendToTarget()
                    return true
                }
                destroyTemp()
                return false
            }
        }

    // ---------- 内部：URL 校验 ----------

    /** 白名单主机集合：非空、全小写、合法域名、禁 IP 字面量 / 通配符 */
    private fun validateHosts(allowedHosts: Set<String>): List<String> {
        val issues = mutableListOf<String>()
        if (allowedHosts.isEmpty()) issues += "allowedHosts must not be empty"
        for (host in allowedHosts) {
            when {
                host != host.lowercase() -> issues += "host must be lowercase: '$host'"
                !HOST_PATTERN.matches(host) -> issues += "invalid host: '$host'"
                IPV4_LITERAL.matches(host) -> issues += "IP literal not allowed: '$host'"
                host.contains('*') -> issues += "wildcard not allowed: '$host'"
            }
        }
        return issues
    }

    /** UA 不得含换行（防请求头注入） */
    private fun validateUserAgent(userAgent: String?): List<String> =
        if (userAgent?.let { "\r" in it || "\n" in it } == true) {
            listOf("userAgent must not contain line breaks")
        } else {
            emptyList()
        }

    /** 纯 JVM 取 host（不依赖 android.net.Uri，校验逻辑可在单测直接覆盖） */
    private fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()
    } catch (_: Exception) {
        null
    }

    private fun validateUrl(
        field: String,
        raw: String,
        allowedHosts: Set<String>,
    ): List<String> {
        val issues = mutableListOf<String>()
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            return listOf("$field is not a valid URL")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            issues += "$field must use https"
        }
        if (uri.rawUserInfo != null) issues += "$field must not contain userinfo"
        if (uri.port != -1 && uri.port != 443) issues += "$field must use default https port"
        val host = uri.host?.lowercase()
        if (host == null) {
            issues += "$field must contain a host"
        } else if (host !in allowedHosts) {
            issues += "$field host must be in allowedHosts"
        }
        return issues
    }
}

/**
 * 页内取数脚本与结果读取（页面变量回传 + 原生轮询；evaluateJavascript 不 await Promise）。
 *
 * 两个端点路径来自 [WebViewLoginSpec]，已在注册表装配时通过 [WebViewPolicy.validate]；
 * 这里再做一次单引号转义，防配置错误把脚本拼坏（防注入的最后一道闸）。
 */
internal object WebLoginJs {

    private const val PROFILE_VAR = "window.__classppSyncProfile"
    private const val TODOS_VAR = "window.__classppSyncTodos"

    fun profileFetchJs(spec: WebViewLoginSpec): String =
        fetchJs(PROFILE_VAR, spec.profilePath)

    fun todosFetchJs(spec: WebViewLoginSpec): String =
        fetchJs(TODOS_VAR, spec.todosPath)

    private fun fetchJs(varName: String, path: String): String = """
        (function() {
            $varName = '';
            fetch('${escapeForSingleQuotedJs(path)}', {
                credentials: 'include',
                headers: { 'Accept': 'application/json' }
            })
            .then(function(r) {
                return r.text().then(function(t) {
                    $varName = JSON.stringify({ status: r.status, body: t });
                });
            })
            .catch(function(e) {
                $varName = JSON.stringify({ status: 0, body: 'network error' });
            });
        })();
    """.trimIndent()

    /** 轮询读取表达式：未写回时返回空串 */
    fun readVar(isTodos: Boolean): String =
        "(${if (isTodos) TODOS_VAR else PROFILE_VAR} || '')"

    private fun escapeForSingleQuotedJs(text: String): String = buildString {
        for (ch in text) {
            when (ch) {
                '\\' -> append("\\\\")
                '\'' -> append("\\'")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
    }
}

/** evaluateJavascript 的返回值是 JSON 编码的字面量，反转义回 JS 侧 JSON.stringify 的字符串 */
internal fun unquoteJsResult(raw: String?): String? {
    if (raw == null || raw.length < 2 || !raw.startsWith('"')) return null
    return try {
        Json.decodeFromString(String.serializer(), raw)
    } catch (_: Exception) {
        null
    }
}

/** 页内 fetch 回传的信封：{ status, body } 的 JSON 字符串 */
@Serializable
internal data class WebFetchEnvelope(val status: Int = 0, val body: String = "")
