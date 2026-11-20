package com.fangyi.classpp.ui.schedule

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.shiguang.ShiguangScriptShim
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.data.sync.webview.unquoteJsResult
import com.fangyi.classpp.ui.webview.WebViewPageAction
import com.fangyi.classpp.ui.webview.WebViewPageScaffold
import com.fangyi.classpp.ui.webview.rememberWebMobileUserAgent
import com.fangyi.classpp.ui.webview.resolveWebUserAgent
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 拾光教务导入的全屏 WebView 页：打开适配声明的登录页，用户自行登录并进入课表页面，
 * 点右上角确认后才注入「垫片 + 适配脚本」。
 *
 * 页面骨架与「从教务网页识别」一致：设置页同款顶栏（返回圆钮 + 居中标题）、网页内容
 * 位于顶栏下、底部只读地址栏（可切换手机 / 电脑版 UA）。右上角确认圆钮执行导入，
 * 运行中变为停止圆钮，可取消本次执行；返回键运行中先取消，空闲时关页。
 *
 * 与学校声明的教务课表页不同：拾光的 `import_url` 只是登录入口，登录链路会跨多个
 * 未知认证域，因此没有域名白名单（复用 [WebViewPolicy.createUserPageClient]，只按
 * http/https 放行，SSL 错误仍不 proceed、file/content 与混合内容仍全禁）。
 *
 * 脚本执行采用「页面变量 + 原生轮询」：垫片把课程数据写进
 * [ShiguangScriptShim.RESULT_VAR]，本页每 200ms 读一次，读到即把原文交回宿主解析/映射。
 * 错误提示一律 Toast：加载失败、执行超时、脚本失败与宿主回传的错误文案都不再占据
 * 页面内空间。关页/退场由宿主控制，卸载时取消在途执行、销毁 WebView 并 flush cookie。
 */
@Composable
internal fun ShiguangWebImportSheet(
    schoolName: String,
    entryUrl: String,
    executionScript: String,
    errorText: String?,
    onDismiss: () -> Unit,
    onExecuteStart: () -> Unit,
    onResult: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(entryUrl) }
    var running by remember { mutableStateOf(false) }
    var executionGeneration by remember { mutableIntStateOf(0) }
    var executeJob by remember { mutableStateOf<Job?>(null) }
    // UA 选择：null = 尚未切换，沿用系统 UA
    var userAgentChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val mobileUserAgent = rememberWebMobileUserAgent()
    val desktopMode = userAgentChoice ?: false

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadErrorText = stringResource(R.string.sync_login_load_error)
    val executingText = stringResource(R.string.shiguang_web_executing)
    val timeoutText = stringResource(R.string.shiguang_web_timeout)
    val failedText = stringResource(R.string.shiguang_web_failed)
    val httpWarning = stringResource(R.string.import_ai_web_http_warning)
    val isHttp = currentUrl.startsWith("http://", ignoreCase = true)

    // 组合离场即销毁：先取消在途执行，再落盘 cookie 并释放 WebView
    DisposableEffect(Unit) {
        onDispose {
            executeJob?.cancel()
            CookieManager.getInstance().flush()
            webView?.destroySafely()
            webView = null
        }
    }

    fun load(wv: WebView) {
        loadError = false
        progress = 0
        pageReady = false
        currentUrl = entryUrl
        wv.loadUrl(entryUrl)
    }

    // 把当前选择的 UA 写进 WebView；切换后必须重新加载才生效
    fun applyUserAgent(wv: WebView) {
        wv.settings.userAgentString =
            resolveWebUserAgent(userAgentChoice, null, mobileUserAgent)
    }

    // 手机 / 电脑版切换：改 UA 后重载当前页；执行期间地址栏锁定，不会走到这里
    fun switchDesktopMode(desktop: Boolean) {
        if (desktop == desktopMode) return
        userAgentChoice = desktop
        val wv = webView ?: return
        applyUserAgent(wv)
        if (currentUrl.isNotBlank()) wv.loadUrl(currentUrl) else load(wv)
    }

    LaunchedEffect(webView, entryUrl) {
        webView?.let { load(it) }
    }

    // HTTP 入口只提示一次：进入 http 页面时 Toast，同域继续跳转不重复打扰
    LaunchedEffect(isHttp) {
        if (isHttp) AppToasts.show(context, httpWarning)
    }

    // 宿主解析 / 映射失败：留在网页可重试，错误文案走 Toast
    LaunchedEffect(errorText) {
        if (!errorText.isNullOrBlank()) {
            val shown = if (errorText.length > MaxErrorChars) {
                errorText.take(MaxErrorChars) + "…"
            } else {
                errorText
            }
            AppToasts.show(context, shown)
        }
    }

    fun stopExecution() {
        executionGeneration++
        executeJob?.cancel()
        executeJob = null
        running = false
    }

    fun startExecution() {
        val wv = webView ?: return
        if (running || !pageReady || loadError) return
        onExecuteStart()
        val generation = ++executionGeneration
        running = true
        executeJob = scope.launch {
            try {
                try {
                    wv.evaluateJavascript(ShiguangScriptShim.resetJs(), null)
                    wv.evaluateJavascript(executionScript, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // WebView 已销毁/页面正在切换：按执行失败收口，不让异常冒泡到宿主
                    if (generation == executionGeneration) {
                        AppToasts.show(context, failedText)
                    }
                    return@launch
                }
                val deadline = System.currentTimeMillis() + ExecuteTimeoutMillis
                while (isActive) {
                    delay(PollIntervalMillis)
                    val raw = evaluateJavascript(wv, ShiguangScriptShim.readJs())
                    if (!raw.isNullOrEmpty()) {
                        if (generation == executionGeneration) onResult(raw)
                        return@launch
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        if (generation == executionGeneration) {
                            AppToasts.show(context, timeoutText)
                        }
                        return@launch
                    }
                }
            } finally {
                // 停止后旧协程的 finally 不能把新一轮执行的状态清掉
                if (generation == executionGeneration) {
                    running = false
                    executeJob = null
                }
            }
        }
    }

    fun handleBack() {
        if (running) stopExecution() else onDismiss()
    }

    // 返回键：运行中先取消本次导入；再次返回才关页
    BackHandler(onBack = ::handleBack)

    WebViewPageScaffold(
        title = schoolName,
        onBack = ::handleBack,
        address = currentUrl,
        desktopMode = desktopMode,
        onDesktopModeChange = ::switchDesktopMode,
        loading = progress in 0..99 && !loadError && currentUrl.isNotBlank(),
        statusText = if (running) executingText else null,
        // 执行期间锁死地址栏与 UA 菜单：重载会破坏脚本上下文
        readOnly = running,
        action = if (running) {
            WebViewPageAction(
                icon = R.drawable.ic_stop,
                contentDescription = stringResource(R.string.shiguang_web_stop),
                onClick = ::stopExecution,
            )
        } else {
            WebViewPageAction(
                icon = R.drawable.ic_checkmark,
                contentDescription = stringResource(R.string.shiguang_web_execute),
                enabled = pageReady && !loadError,
                onClick = ::startExecution,
            )
        },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    WebViewPolicy.harden(
                        wv,
                        resolveWebUserAgent(userAgentChoice, null, mobileUserAgent),
                    )
                    // 与拾光官方客户端手机模式对齐：宽视口 + 概览缩放 + 双指缩放，
                    // 教务页面（尤其桌面布局）不会缩成一条窄内容
                    with(wv.settings) {
                        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    // 登录链路跨多个未知域：放行跨站 cookie
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.webViewClient = WebViewPolicy.createUserPageClient(
                        onLoadError = {
                            loadError = true
                            AppToasts.show(context, loadErrorText)
                        },
                        onPageStarted = { url ->
                            if (!url.isNullOrBlank()) currentUrl = url
                            // 首次加载完成后保持可导：SPA/登录跳转不应把确认钮永久置灰
                            progress = 0
                            loadError = false
                        },
                    )
                    wv.webChromeClient = WebViewPolicy.createUserPageChromeClient(
                        onProgress = { value ->
                            progress = value
                            if (value > 5) loadError = false
                            if (value >= 100) pageReady = true
                        },
                        // target=_blank / window.open 在当前 WebView 打开（协议已复核）
                        onOpenWindow = { url -> wv.loadUrl(url) },
                    )
                    webView = wv
                }
            },
        )
    }
}

/** 脚本执行轮询：多数适配脚本 1~5s 完成；60s 覆盖弱网与多接口串联 */
private const val ExecuteTimeoutMillis = 60_000L

/** 轮询间隔：够快感知结果写回，又不至于每秒多次 evaluateJavascript */
private const val PollIntervalMillis = 200L

/** 错误 Toast 最多展示的字符数：脚本异常/接口原文可能很长，截断避免整段堆在气泡里 */
private const val MaxErrorChars = 240

/** 读取页面结果变量；evaluateJavascript 失败/页面销毁竞态都按空结果处理 */
private suspend fun evaluateJavascript(webView: WebView, js: String): String? =
    suspendCancellableCoroutine { continuation ->
        try {
            webView.evaluateJavascript(js) { raw ->
                if (continuation.isActive) continuation.resume(unquoteJsResult(raw))
            }
        } catch (_: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }
