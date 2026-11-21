package com.fangyi.classpp.ui.schedule

import android.webkit.CookieManager
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
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.data.sync.webview.WebViewTimetableJs
import com.fangyi.classpp.data.sync.webview.unquoteJsResult
import com.fangyi.classpp.ui.webview.WebViewPageAction
import com.fangyi.classpp.ui.webview.WebViewPageScaffold
import com.fangyi.classpp.ui.webview.applyPageViewport
import com.fangyi.classpp.ui.webview.isDesktopUserAgent
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
 * 教务课表页（WebView 登录模式的网页导入入口）：全屏可见 WebView 打开
 * [WebViewTimetableSpec.entryUrl]，登录与课表查看都由页面完成。
 *
 * 页面骨架与「从教务网页识别」一致：设置页同款顶栏（返回圆钮 + 居中标题）、网页内容
 * 位于顶栏下、底部只读地址栏（可切换手机 / 电脑版 UA）。[spec.importScript] 非空且
 * [onExtract] 非空时，右上角出现确认圆钮：注入脚本（学校适配层提供，读取页面当前选中
 * 学期并同源取数），轮询 [WebViewTimetableJs] 约定的结果变量，取回原文交给
 * [onExtract]；解析/映射/落库都由调用方经 `ScheduleImportService.importFromWebTimetable`
 * 完成，本页不做任何学校协议。`evaluateJavascript` 不等待 Promise，脚本只写页面变量、
 * 原生侧轮询读取。抓取中圆钮变为停止，可取消本次轮询。
 *
 * 错误提示一律 Toast：主文档加载失败、抓取超时 / 注入异常都不再显示内联文案或整页
 * 遮罩；用户可关闭页面后重新打开重试。
 *
 * 加固与导航白名单由 [WebViewPolicy] 锁死（spec 无法关闭）：仅 https + 声明域放行、
 * SSL 错误不 proceed、file/content 与混合内容全禁；登录链路跨域跳转放行第三方 cookie。
 *
 * 视口：宽视口 + 按宽度整体缩放（[applyPageViewport]），桌面教务页不会被压进控件宽度，
 * 用户可捏合放大看清细节。
 *
 * 生命周期与 `GenericSchoolUi` 的可见登录浮层一致：WebView 由组合持有（AndroidView
 * factory 创建、onDispose flush cookie 后销毁）；关闭经 [onDismiss]（返回键同关闭）。
 */
@Composable
internal fun SchoolTimetableWebViewSheet(
    spec: WebViewTimetableSpec,
    onDismiss: () -> Unit,
    // 非空 = 提供网页导入：抓取脚本回传原文后交给调用方解析/映射/落库
    onExtract: ((String) -> Unit)? = null,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 页面加载进度（0..100）与主文档失败态；进度越过初段即视为恢复
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    // 首屏加载完成前不开放网页导入（页面还没渲染出学期下拉框/课表）；
    // 停在统一认证/其它域时也不开放——脚本必须在教务课表页上下文里跑
    var pageReady by remember { mutableStateOf(false) }
    var onEntryHost by remember { mutableStateOf(false) }
    var currentUrl by remember { mutableStateOf(spec.entryUrl) }
    val entryHost = remember(spec) {
        runCatching { android.net.Uri.parse(spec.entryUrl).host?.lowercase() }.getOrNull()
    }
    // 网页抓取：可取消的在途任务；generation 保证停止后旧协程不再回写状态 / 回传结果
    var extracting by remember { mutableStateOf(false) }
    var extractGeneration by remember { mutableIntStateOf(0) }
    var extractJob by remember { mutableStateOf<Job?>(null) }
    // UA 选择：null = 尚未切换，沿用 spec 声明的 UA（自定义 UA 不被静默覆盖）
    var userAgentChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val mobileUserAgent = rememberWebMobileUserAgent()
    val desktopMode = userAgentChoice ?: isDesktopUserAgent(spec.userAgent)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadErrorText = stringResource(R.string.sync_login_load_error)
    val extractFailedText = stringResource(R.string.sync_import_webview_extract_failed)
    val extractingText = stringResource(R.string.sync_import_webview_extracting)

    // 返回键关页面（后注册于课表页的编辑取消，因此优先收到）
    BackHandler {
        onDismiss()
    }

    // 组合离场即销毁：先取消在途抓取，再落盘 cookie 并释放 WebView
    DisposableEffect(Unit) {
        onDispose {
            extractJob?.cancel()
            CookieManager.getInstance().flush()
            webView?.destroySafely()
            webView = null
        }
    }

    fun load(wv: WebView) {
        loadError = false
        progress = 0
        pageReady = false
        onEntryHost = false
        currentUrl = spec.entryUrl
        wv.loadUrl(spec.entryUrl)
    }

    // 把当前选择的 UA 写进 WebView；切换后必须重新加载才生效
    fun applyUserAgent(wv: WebView) {
        wv.settings.userAgentString =
            resolveWebUserAgent(userAgentChoice, spec.userAgent, mobileUserAgent)
    }

    // 手机 / 电脑版切换：改 UA 后重载当前页；抓取期间地址栏锁定，不会走到这里
    fun switchDesktopMode(desktop: Boolean) {
        if (desktop == desktopMode) return
        userAgentChoice = desktop
        val wv = webView ?: return
        applyUserAgent(wv)
        if (currentUrl.isNotBlank()) wv.loadUrl(currentUrl) else load(wv)
    }

    // WebView 就绪后加载入口页；spec 变化（理论上仅切校）重新加载
    LaunchedEffect(webView, spec) {
        webView?.let { load(it) }
    }

    val importScript = spec.importScript?.takeIf { it.isNotBlank() }
    val canImport = importScript != null && onExtract != null

    fun stopExtract() {
        extractGeneration++
        extractJob?.cancel()
        extractJob = null
        extracting = false
    }

    // 网页抓取：清空旧结果 → 注入学校脚本 → 轮询约定变量。校园网下四个接口通常在
    // 1s 内返回；超时只意味着没拿到原文（页面未登录/学期未就绪/网络异常），原文解析
    // 与失败分类仍交给调用方的 `importFromWebTimetable`
    fun startExtract() {
        val wv = webView ?: return
        val script = importScript ?: return
        val callback = onExtract ?: return
        if (extracting || !pageReady || !onEntryHost || loadError) return
        val generation = ++extractGeneration
        extracting = true
        extractJob = scope.launch {
            try {
                try {
                    wv.evaluateJavascript(WebViewTimetableJs.resetResult(), null)
                    wv.evaluateJavascript(script, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (generation == extractGeneration) {
                        AppToasts.show(context, extractFailedText)
                    }
                    return@launch
                }
                val deadline = System.currentTimeMillis() + ExtractTimeoutMillis
                while (isActive) {
                    delay(ExtractPollIntervalMillis)
                    val value = try {
                        suspendCancellableCoroutine<String?> { cont ->
                            wv.evaluateJavascript(WebViewTimetableJs.readResult()) { raw ->
                                if (cont.isActive) cont.resume(unquoteJsResult(raw))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (!value.isNullOrEmpty()) {
                        if (generation == extractGeneration) callback(value)
                        return@launch
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        if (generation == extractGeneration) {
                            AppToasts.show(context, extractFailedText)
                        }
                        return@launch
                    }
                }
            } finally {
                // 停止后旧协程的 finally 不能把新一轮抓取的状态清掉
                if (generation == extractGeneration) {
                    extracting = false
                    extractJob = null
                }
            }
        }
    }

    WebViewPageScaffold(
        title = stringResource(R.string.sync_import_webview_title),
        onBack = onDismiss,
        address = currentUrl,
        desktopMode = desktopMode,
        onDesktopModeChange = ::switchDesktopMode,
        // 地址栏左侧圆环即加载进度，不再单独画 LinearProgressIndicator
        loading = progress in 0..99 && !loadError && currentUrl.isNotBlank(),
        statusText = if (extracting) extractingText else null,
        // 抓取期间锁死地址栏与 UA 菜单：重载会破坏页面上下文
        readOnly = extracting,
        action = when {
            extracting -> WebViewPageAction(
                icon = R.drawable.ic_stop,
                contentDescription = stringResource(R.string.sync_import_webview_stop),
                onClick = ::stopExtract,
            )
            canImport -> WebViewPageAction(
                icon = R.drawable.ic_checkmark,
                contentDescription = stringResource(R.string.sync_import_webview_extract),
                enabled = pageReady && onEntryHost && !loadError,
                onClick = ::startExtract,
            )
            else -> null
        },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    WebViewPolicy.harden(
                        wv,
                        resolveWebUserAgent(userAgentChoice, spec.userAgent, mobileUserAgent),
                    )
                    // 教务课表按桌面 UA 返回宽布局：宽视口按宽度整体缩放，避免被压进控件宽度
                    wv.applyPageViewport(fitWidth = true)
                    // 登录链路跨多个声明域跳转：放行跨站 cookie
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    wv.webViewClient = WebViewPolicy.createTimetableClient(
                        spec = spec,
                        onLoadError = {
                            loadError = true
                            AppToasts.show(context, loadErrorText)
                        },
                        onPageStarted = { url ->
                            currentUrl = url ?: currentUrl
                            onEntryHost = url != null && runCatching {
                                android.net.Uri.parse(url).host?.lowercase()
                            }.getOrNull() == entryHost
                        },
                    )
                    wv.webChromeClient = WebViewPolicy.createTimetableChromeClient(
                        spec = spec,
                        onProgress = { value ->
                            progress = value
                            if (value > 5) loadError = false
                            if (value >= 100) pageReady = true
                        },
                        // target=_blank / window.open 在当前 WebView 打开（白名单已复核）
                        onOpenWindow = { url -> wv.loadUrl(url) },
                    )
                    webView = wv
                }
            },
        )
    }
}

/** 抓取轮询：校园网下四个教务接口通常 1s 内返回；20s 覆盖弱网与登录态探测 */
private const val ExtractTimeoutMillis = 20_000L

/** 轮询间隔：够快感知脚本写回，又不至于每秒多次 evaluateJavascript */
private const val ExtractPollIntervalMillis = 200L
