package com.fangyi.classpp.data.sync.webview

import android.net.Uri
import android.os.Handler
import android.webkit.WebView
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.engine.FetchError
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import kotlinx.serialization.json.Json

/**
 * 一次 WebView 会话的驱动器（指导 §2.3）：`dataHost` 页面加载完成 → 注入 profile 探活 →
 * 有效则继续 todos，结果经 [onDone] 收口。轮询与超时都跑在 [mainHandler]（主线程）上。
 *
 * 两种宿主：
 * - 无头（[WebFetcher]）：`ownsWebView = true`，超时按当前 URL 分类（停在身份域 = 过期）；
 * - 可见登录浮层：`ownsWebView = false`（WebView 由组合持有、随浮层销毁），**无总超时**——
 *   用户在登录页停留多久都等，只在拿到结果或浮层关闭（dispose 调 [destroy]）时结束。
 *   [awaitInteractiveLogin] = true 时，未登录的探活失败（401/403、被重定向到登录页的
 *   HTML、跨域被 CORS 拦下的 status 0）**不是终态**：复位探针等页面回到 `dataHost`
 *   再探，否则浮层一打开就误判「未检测到登录」并关闭。
 *
 * 登录态判定口径由 [profileValidator] 注入（不进 spec）：默认是「JSON 对象」，
 * 学校可注入更严格的解析（如必须含非空 uid）。
 */
class WebSessionDriver(
    private val webView: WebView,
    private val spec: WebViewLoginSpec,
    private val mainHandler: Handler,
    private val ownsWebView: Boolean,
    private val totalTimeoutMillis: Long,
    private val onDone: (FetchOutcome) -> Unit,
    private val profileValidator: (String) -> Boolean = WebViewPolicy::defaultProfileValidator,
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
    // 避免在登录页上继续轮询、或回到数据域后新旧两条轮询链同时跑
    private var probeGeneration = 0
    private var pollRunnable: Runnable? = null

    private val timeoutRunnable = Runnable {
        complete(
            FetchOutcome.Failed(
                if (WebViewPolicy.isIdentityHost(spec, webView.url)) {
                    FetchError.SessionExpired
                } else {
                    FetchError.Timeout
                },
            ),
        )
    }

    fun start() {
        webView.webViewClient = WebViewPolicy.createClient(
            spec = spec,
            onDataPageFinished = {
                if (completed || destroyed || profileInjected) return@createClient
                resetProbe()
                profileInjected = true
                inject(WebLoginJs.profileFetchJs(spec))
                schedulePoll()
            },
            onLoadError = onLoadError,
            onPageStarted = {
                // 主文档跳转（去统一认证登录 / 登录完回数据域）会重建 JS 上下文，
                // 在途探针随旧页面一起作废：复位等新页面落定后由 onPageFinished 重新探活
                if (!completed && !destroyed) resetProbe()
            },
        )
        webView.webChromeClient = WebViewPolicy.createChromeClient(
            spec = spec,
            onProgress = onProgress,
            onOpenWindow = { url ->
                val uri = Uri.parse(url)
                if (!completed && !destroyed &&
                    uri.scheme == "https" && WebViewPolicy.isAllowedHost(spec, uri.host)
                ) {
                    webView.loadUrl(url)
                }
            },
        )
        if (totalTimeoutMillis > 0) mainHandler.postDelayed(timeoutRunnable, totalTimeoutMillis)
        webView.loadUrl(spec.entryUrl)
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
        webView.evaluateJavascript(WebLoginJs.readVar(isTodos)) { raw ->
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
            Json.decodeFromString<WebFetchEnvelope>(value)
        } catch (_: Exception) {
            null
        }
        if (envelope == null) {
            // 变量已写回却不符信封结构 = 接口形态变化，继续轮询只会拿到同样的值
            if (awaitInteractiveLogin) resetProbe() else complete(FetchOutcome.Failed(FetchError.Timeout))
            return
        }
        when {
            envelope.status in 500..599 -> complete(FetchOutcome.Failed(FetchError.ServiceBusy))
            envelope.status == 0 -> {
                // 未登录时 fetch 跟随 302 跨域会被 CORS 拦下（status 0）：可见登录里
                // 这仍是「没登录」，不能当网络错误收口；无头模式按网络异常处理
                if (awaitInteractiveLogin) {
                    resetProbe()
                } else {
                    complete(FetchOutcome.Failed(FetchError.NetworkError))
                }
            }
            isTodos -> when {
                envelope.status == 200 -> complete(
                    FetchOutcome.Ok(LoginRaw(profileRaw ?: "", envelope.body)),
                )
                awaitInteractiveLogin && isUnauthorized(envelope.status) -> resetProbe()
                else -> complete(FetchOutcome.Failed(FetchError.SessionExpired))
            }
            envelope.status == 200 -> {
                if (awaitInteractiveLogin && !profileValidator(envelope.body)) {
                    // 200 但 body 是统一认证登录页 HTML / 无效 JSON（fetch 跟着 302 到了
                    // 登录页）：未登录
                    resetProbe()
                    return
                }
                profileRaw = envelope.body
                todosInjected = true
                inject(WebLoginJs.todosFetchJs(spec))
                schedulePoll()
            }
            awaitInteractiveLogin && isUnauthorized(envelope.status) -> resetProbe()
            else -> complete(FetchOutcome.Failed(FetchError.SessionExpired))
        }
    }

    /** 复位探针：作废在途轮询（代际 +1），等页面落定/回到数据域后再探一次 */
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

    private fun inject(script: String) {
        if (destroyed) return
        try {
            webView.evaluateJavascript(script, null)
        } catch (_: Exception) {
        }
    }

    private fun complete(result: FetchOutcome) {
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
