package com.fangyi.classpp.data.xmu

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 课程中心 WebView 的公共基建：域名白名单、安全加固、页内取数脚本与会话驱动。
 * 可见登录浮层（ui.settings 的 XmuLoginSheet）与无头取数器（[XmuWebFetcher]）共用，
 * 保证两条路径的加固策略与解析口径严格一致。
 *
 * 安全模型（指导 §3.1/§8.3）：
 * - 域名白名单内才放行导航；SSL 错误一律 cancel；
 * - 禁 file/content 访问、禁混合内容；
 * - **登录页（身份域）不注入任何脚本**，fetch 只在 `lnt.xmu.edu.cn` 页面上下文执行；
 * - 不用 addJavascriptInterface——结果写页面变量，原生侧 evaluateJavascript 轮询读取
 *   （Android 的 evaluateJavascript 不 await Promise）。
 */
internal object XmuWebViewPolicy {

    const val LNT_HOST = "lnt.xmu.edu.cn"

    // ?version=v1 与站内入口一致（指导 §3.1），绕开旧缓存
    const val ENTRY_URL = "https://$LNT_HOST/user/index?version=v1"

    // 登录链路上的统一身份认证域（指导 §2.1）：白名单放行，但不注入取数脚本
    private val ALLOWED_HOSTS = setOf(
        LNT_HOST,
        "ids.xmu.edu.cn",
        "c-identity.xmu.edu.cn",
        "pass.xmu.edu.cn",
    )

    fun isAllowedHost(host: String?): Boolean = host != null && host in ALLOWED_HOSTS

    fun isLntHost(url: String?): Boolean = url != null && Uri.parse(url).host == LNT_HOST

    /** 停在身份域登录页（会话已过期、等用户输入）——无头模式据此把超时归类为「登录过期」 */
    fun isIdentityHost(url: String?): Boolean {
        if (url == null) return false
        val host = Uri.parse(url).host ?: return false
        return host != LNT_HOST && host in ALLOWED_HOSTS
    }

    /**
     * lnt 按 UA 分流：Android/iOS 移动 UA 会被 302 到 c-mobile 门户（不在白名单 →
     * 导航被吞、登录页白屏），桌面 UA 才走指导 §2.1 的统一认证链路
     * （c-identity → ids.xmu.edu.cn → 回 lnt）。无头取数同样需要它：过期会话要能
     * 被重定向到身份域，才能把超时归类为「登录过期」而不是普通超时。
     */
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 统一加固：JS/DOM 允许（学校页面需要），文件与混合内容全禁；多窗口开启（见 createChromeClient） */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION") // 两个 file 访问开关新 API 已默认关闭，这里显式置 false 兜底老设备（指导 §3.1）
    fun harden(webView: WebView) {
        with(webView.settings) {
            // 身份认证页按 UA 出桌面版布局（手机上略小但可正常登录），换来 lnt 不跳 c-mobile
            userAgentString = DESKTOP_USER_AGENT
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
        onLntPageFinished: () -> Unit,
        onLoadError: () -> Unit = {},
        onPageStarted: (String?) -> Unit = {},
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
                return !(url.scheme == "https" && isAllowedHost(url.host))
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
                if (isLntHost(url)) onLntPageFinished()
            }
        }

    /**
     * Chrome 客户端：页面加载进度回调 + `target=_blank`/`window.open` 统一转当前
     * WebView 打开（指导 §3.1）。转交实现：临时 WebView 承接新窗口，其首个非 blank
     * 导航/页面即目标地址——取到后回主 WebView loadUrl 并销毁临时实例。
     */
    fun createChromeClient(
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

                // 不在 WebViewClient 回调里同步 destroy：destroySafely 推迟一轮消息循环，
                // 已排队的 Chromium 回调先派发；也不依赖 view.post（detach 后会挂起不执行）
                fun destroyTemp() {
                    val wv = temp ?: return
                    temp = null
                    wv.destroySafely()
                }

                // 取到目标 URL 后回主 WebView、销毁临时实例（只转发一次）。
                // loadUrl 不经过 shouldOverrideUrlLoading：这里是弹窗路径的白名单最后一道闸。
                fun forward(url: String) {
                    if (forwarded[0]) return
                    forwarded[0] = true
                    val uri = Uri.parse(url)
                    if (uri.scheme == "https" && isAllowedHost(uri.host)) {
                        onOpenWindow(url)
                    }
                    destroyTemp()
                }
                temp = WebView(view.context).apply {
                    // 与主 WebView 同一套加固：禁 file/content、禁混合内容、桌面 UA
                    harden(this)
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

    private val TAG = "XmuSync"
}

/** 页内取数脚本与结果读取（细节见类 KDoc：页面变量回传 + 原生轮询） */
internal object XmuWebJs {

    private const val PROFILE_VAR = "window.__classppXmuProfile"
    private const val TODOS_VAR = "window.__classppXmuTodos"

    // 先清变量再 fetch：原生侧读到非空即认为本次请求已收口；异常路径也写回（status 0）
    private fun fetchJs(varName: String, path: String): String = """
        (function() {
            $varName = '';
            fetch('$path', {
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

    val PROFILE_FETCH_JS: String = fetchJs(PROFILE_VAR, "/api/profile")
    val TODOS_FETCH_JS: String = fetchJs(TODOS_VAR, "/api/todos")

    /** 轮询读取表达式：未写回时返回空串 */
    fun readVar(isTodos: Boolean): String =
        "(${if (isTodos) TODOS_VAR else PROFILE_VAR} || '')"
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
internal data class XmuFetchEnvelope(val status: Int = 0, val body: String = "")

/**
 * 一次会话的驱动器：lnt 页面加载完成 → 注入 /api/profile 探活 → 有效则继续 /api/todos，
 * 结果经 [onDone] 收口。轮询与超时都跑在 [mainHandler]（主线程）上。
 *
 * 两种宿主：
 * - 无头（[XmuWebFetcher]）：ownsWebView=true，超时按当前 URL 分类（停在身份域 = 过期）；
 * - 可见登录浮层：ownsWebView=false（WebView 由组合持有、随浮层销毁），**无总超时**——
 *   用户在登录页停留多久都等，只在拿到结果或浮层关闭（dispose 调 [destroy]）时结束。
 *   [awaitInteractiveLogin]=true 时，未登录的探活失败（401/403、被重定向到登录页的
 *   HTML、跨域被 CORS 拦下的 status 0）**不是终态**：复位探针等页面回到 lnt 再探，
 *   否则浮层一打开就误判「未检测到登录」并关闭（指导 §3.1：返回 HTML 或 302 继续等待）。
 */
internal class XmuWebSessionDriver(
    private val webView: WebView,
    private val mainHandler: Handler,
    private val ownsWebView: Boolean,
    private val totalTimeoutMillis: Long,
    private val onDone: (XmuFetchResult) -> Unit,
    // 可见登录浮层的可观测性钩子：加载进度（0..100）与主文档加载失败（SSL/网络错误）
    private val onProgress: (Int) -> Unit = {},
    private val onLoadError: () -> Unit = {},
    /** 可见登录浮层模式：未登录的探活失败复位重试，不向宿主收口 */
    private val awaitInteractiveLogin: Boolean = false,
) {
    private var destroyed = false
    private var completed = false
    private var profileInjected = false
    private var todosInjected = false
    private var profileRaw: String? = null

    // 轮询代际：页面跳转/探针复位后，旧页面上下文的 evaluateJavascript 回调一律作废，
    // 避免在登录页上继续轮询、或回到 lnt 后新旧两条轮询链同时跑
    private var probeGeneration = 0
    private var pollRunnable: Runnable? = null

    private val timeoutRunnable = Runnable {
        complete(
            if (XmuWebViewPolicy.isIdentityHost(webView.url)) {
                XmuFetchResult.SessionExpired
            } else {
                XmuFetchResult.Timeout
            },
        )
    }

    fun start() {
        webView.webViewClient = XmuWebViewPolicy.createClient(
            onLntPageFinished = {
                if (completed || destroyed || profileInjected) return@createClient
                resetProbe()
                profileInjected = true
                inject(XmuWebJs.PROFILE_FETCH_JS)
                schedulePoll()
            },
            onLoadError = onLoadError,
            onPageStarted = {
                // 主文档跳转（去统一认证登录 / 登录完回 lnt）会重建 JS 上下文，
                // 在途探针随旧页面一起作废：复位等新页面落定后由 onPageFinished 重新探活
                if (!completed && !destroyed) resetProbe()
            },
        )
        // target=_blank / window.open 统一转当前 WebView（指导 §3.1）；
        // 白名单已在 createChromeClient 收口，这里再防御一次
        webView.webChromeClient = XmuWebViewPolicy.createChromeClient(
            onProgress = onProgress,
            onOpenWindow = { url ->
                val uri = Uri.parse(url)
                if (!completed && !destroyed &&
                    uri.scheme == "https" && XmuWebViewPolicy.isAllowedHost(uri.host)
                ) {
                    webView.loadUrl(url)
                }
            },
        )
        if (totalTimeoutMillis > 0) mainHandler.postDelayed(timeoutRunnable, totalTimeoutMillis)
        webView.loadUrl(XmuWebViewPolicy.ENTRY_URL)
    }

    private fun schedulePoll() {
        pollRunnable?.let(mainHandler::removeCallbacks)
        val generation = probeGeneration
        val runnable = Runnable { poll(generation) }
        pollRunnable = runnable
        mainHandler.postDelayed(runnable, POLL_INTERVAL_MS)
    }

    private fun poll(generation: Int) {
        pollRunnable = null
        if (completed || destroyed || generation != probeGeneration) return
        val isTodos = todosInjected
        webView.evaluateJavascript(XmuWebJs.readVar(isTodos)) { raw ->
            if (completed || destroyed || generation != probeGeneration) {
                return@evaluateJavascript
            }
            val value = unquoteJsResult(raw)
            when {
                value.isNullOrEmpty() -> schedulePoll()
                else -> onFetched(value, isTodos)
            }
        }
    }

    private fun onFetched(value: String, isTodos: Boolean) {
        val envelope = try {
            Json.decodeFromString<XmuFetchEnvelope>(value)
        } catch (_: Exception) {
            null
        }
        if (envelope == null) {
            // 变量已写回却不符信封结构 = 接口形态变化，继续轮询只会拿到同样的值
            if (awaitInteractiveLogin) resetProbe() else complete(XmuFetchResult.Timeout)
            return
        }
        when {
            envelope.status in 500..599 -> complete(XmuFetchResult.ServiceBusy)
            envelope.status == 0 -> {
                // 未登录时 fetch 跟随 302 跨域会被 CORS 拦下（status 0）：可见登录里
                // 这仍是「没登录」，不能当网络错误收口；无头模式按网络异常处理
                if (awaitInteractiveLogin) resetProbe() else complete(XmuFetchResult.NetworkError)
            }
            isTodos -> when {
                envelope.status == 200 -> complete(XmuFetchResult.Ok(profileRaw ?: "", envelope.body))
                awaitInteractiveLogin && isUnauthorized(envelope.status) -> resetProbe()
                else -> complete(XmuFetchResult.SessionExpired)
            }
            envelope.status == 200 -> {
                if (awaitInteractiveLogin && !isProfileBody(envelope.body)) {
                    // 200 但 body 是统一认证登录页 HTML / 无效 JSON（fetch 跟着 302 到了登录页）：未登录
                    resetProbe()
                    return
                }
                profileRaw = envelope.body
                todosInjected = true
                inject(XmuWebJs.TODOS_FETCH_JS)
                schedulePoll()
            }
            awaitInteractiveLogin && isUnauthorized(envelope.status) -> resetProbe()
            else -> complete(XmuFetchResult.SessionExpired)
        }
    }

    /** 复位探针：作废在途轮询（代际 +1），等页面落定/回到 lnt 后再探一次 */
    private fun resetProbe() {
        probeGeneration++
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        profileInjected = false
        todosInjected = false
        profileRaw = null
    }

    /** 401/403 = 未登录/会话过期（可见登录浮层里都按「继续等用户登录」处理） */
    private fun isUnauthorized(status: Int): Boolean = status == 401 || status == 403

    /** 登录态确认口径（指导 §3.1）：200 且 JSON 含有效 id；统一认证登录页是 HTML */
    private fun isProfileBody(body: String): Boolean = try {
        ScheduleJson.decodeStorage<XmuProfileDto>(body).id > 0
    } catch (_: Exception) {
        false
    }

    private fun inject(script: String) {
        if (destroyed) return
        try {
            webView.evaluateJavascript(script, null)
        } catch (_: Exception) {
        }
    }

    private fun complete(result: XmuFetchResult) {
        if (completed || destroyed) return
        completed = true
        mainHandler.removeCallbacksAndMessages(null)
        onDone(result)
    }

    /** 浮层关闭/协程取消时调用；ownsWebView=false 时只摘回调，WebView 归组合层销毁 */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (ownsWebView) {
            // destroySafely：延迟一轮销毁，避免在途加载回调派发到已销毁实例
            webView.destroySafely()
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 400L
    }
}
