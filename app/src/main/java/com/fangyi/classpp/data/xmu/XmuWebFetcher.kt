package com.fangyi.classpp.data.xmu

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * 无头 WebView 取数器（[XmuTodoApi] 的生产实现）：已连接用户点「立即同步」时用。
 * 复用 [WakeupImportExecutor]（data.wakeup）的同构模式——应用级上下文创建 WebView
 * （不入视图树）、主线程驱动、总超时看门狗、finally NonCancellable 销毁不泄漏。
 *
 * cookie 由 WebView 全局 CookieManager 持久化：登录浮层成功后 flush 过，这里直接复用；
 * 会话过期时页面会被 302 到身份域登录页，驱动器把「停在身份域」的超时归类为过期。
 * 探活最小间隔/单飞由上层 XmuSyncRepository 的 mutex 保证，这里只负责一次会话。
 */
class XmuWebFetcher(private val appContext: Context) : XmuTodoApi {

    /** 总预算：页面加载（含过期时的身份域重定向链）+ 两次页内 fetch + 轮询 */
    private companion object {
        const val TOTAL_TIMEOUT_MS = 30_000L
        const val TAG = "XmuSync"
    }

    override suspend fun fetchSession(): XmuFetchResult = withContext(Dispatchers.Main) {
        try {
            withTimeout(TOTAL_TIMEOUT_MS + 5_000L) { runSession() }
        } catch (e: TimeoutCancellationException) {
            // 驱动器自己的看门狗会先行按 URL 分类；这里只是兜底
            XmuFetchResult.Timeout
        } catch (e: CancellationException) {
            throw e // 协程取消必须继续向上传播
        } catch (e: Exception) {
            // WebView 缺失/初始化失败等：不让异常冒到 UI 协程（没有全局异常处理器会直接崩）
            Log.w(TAG, "fetch session failed: ${e::class.simpleName}")
            XmuFetchResult.NetworkError
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runSession(): XmuFetchResult {
        val mainHandler = Handler(Looper.getMainLooper())
        val result = CompletableDeferred<XmuFetchResult>()

        val webView = WebView(appContext)
        XmuWebViewPolicy.harden(webView)
        // CAS 登录链路跨 lnt/ids/c-identity 多域跳转：放行跨站 cookie（仅此一个 WebView 用途）
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        val driver = XmuWebSessionDriver(
            webView = webView,
            mainHandler = mainHandler,
            ownsWebView = true,
            totalTimeoutMillis = TOTAL_TIMEOUT_MS,
            onDone = { result.complete(it) },
        )

        try {
            driver.start()
            return result.await()
        } finally {
            withContext(NonCancellable) { driver.destroy() }
        }
    }
}
