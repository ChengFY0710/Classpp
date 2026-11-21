package com.fangyi.classpp.ui.schools

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.SchoolText
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.engine.FetchError
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.webview.WebSessionDriver
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.ui.webview.WebViewPageScaffold
import com.fangyi.classpp.ui.webview.applyPageViewport
import com.fangyi.classpp.ui.webview.isDesktopUserAgent
import com.fangyi.classpp.ui.webview.rememberWebMobileUserAgent
import com.fangyi.classpp.ui.webview.resolveWebUserAgent

/**
 * 通用 WebView 登录浮层（指导 §3.4/§四 Phase 4）：**零 UI 代码**的纯声明学校缺省绑定。
 *
 * 行为由 [WebViewLoginSpec] 声明驱动：入口 URL/白名单/两个端点/UA 全部来自 spec，
 * 加固锁死在 [WebViewPolicy]（spec 无法关闭任何加固）。浮层内嵌可见 WebView 走学校
 * 统一身份认证，App 不接触账号密码；用户完成登录回到 `dataHost` 时自动页内 fetch
 * profile + todos，拿到完整结果经 [onResult] 交还宿主（宿主负责关浮层）；用户主动
 * 关闭经 [SchoolUi.WebViewLoginSheet] 的 onDismiss。
 *
 * 页面骨架与「从教务网页识别」一致：设置页同款顶栏（返回圆钮 + 居中标题）、网页内容
 * 位于顶栏下、底部只读地址栏（可切换手机 / 电脑版 UA，切换后重载当前页）。登录页
 * 没有确认动作，登录成功自动返回。
 *
 * 登录态判定由 provider 注入的 [profileValidator] 决定（默认 JSON 对象）：未登录时的
 * 探活失败（401/403、登录页 HTML、跨域被拦）只复位探针、等登录完成后再探，**不关闭浮层**。
 *
 * 错误提示一律 Toast：主文档加载失败不再画整页遮罩 / 重试按钮，用户可关闭页面后重新
 * 打开重试；登录结果错误仍由宿主 `AccountSyncScreen` 现有 Toast 处理。
 *
 * 生命周期与既有的可见登录浮层一致：WebView 由组合持有（AndroidView factory 创建、
 * onDispose 销毁并 flush cookie）；驱动器在组合落定后（LaunchedEffect）才 start；
 * 驱动器无总超时（用户在登录页停留多久都等）。
 */
class GenericSchoolUi(
    private val spec: WebViewLoginSpec,
    private val title: SchoolText,
    private val profileValidator: ((String) -> Boolean)? = null,
) : SchoolUi {

    @Composable
    override fun WebViewLoginSheet(
        onDismiss: () -> Unit,
        onResult: (WebViewLoginOutcome) -> Unit,
    ) {
        GenericWebViewLoginSheet(
            spec = spec,
            title = title,
            profileValidator = profileValidator ?: WebViewPolicy::defaultProfileValidator,
            onDismiss = onDismiss,
            onResult = onResult,
        )
    }
}

@Composable
private fun GenericWebViewLoginSheet(
    spec: WebViewLoginSpec,
    title: SchoolText,
    profileValidator: (String) -> Boolean,
    onDismiss: () -> Unit,
    onResult: (WebViewLoginOutcome) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var driver by remember { mutableStateOf<WebSessionDriver?>(null) }
    var delivered by remember { mutableStateOf(false) }
    // 页面加载进度（0..100）与加载失败态；进度越过初段即视为恢复
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    // 地址栏展示当前页：登录链路会跨多个认证域，跟着主文档跳转走
    var currentUrl by remember { mutableStateOf(spec.entryUrl) }
    // UA 选择：null = 尚未切换，沿用 spec 声明的 UA（自定义 UA 不被静默覆盖）
    var userAgentChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val mobileUserAgent = rememberWebMobileUserAgent()
    val desktopMode = userAgentChoice ?: isDesktopUserAgent(spec.userAgent)

    val context = LocalContext.current
    val loadErrorText = stringResource(R.string.sync_login_load_error)

    val onDone: (FetchOutcome) -> Unit = { result ->
        if (!delivered) {
            delivered = true
            CookieManager.getInstance().flush()
            onResult(result.toWebViewLoginOutcome())
        }
    }

    // 驱动器构建 + 启动（WebView 就绪后调用；重试也走这里，旧驱动器先销毁）
    fun startSession(wv: WebView) {
        driver?.destroy()
        loadError = false
        progress = 0
        driver = WebSessionDriver(
            webView = wv,
            spec = spec,
            mainHandler = Handler(Looper.getMainLooper()),
            ownsWebView = false,
            totalTimeoutMillis = 0L,
            // 未登录时的探活失败不是终态：复位后等登录完成回到数据域再探，
            // 否则浮层刚打开就会被判「未检测到登录」并关闭
            awaitInteractiveLogin = true,
            onDone = onDone,
            profileValidator = profileValidator,
            onProgress = { value ->
                progress = value
                if (value > 5) loadError = false
            },
            onLoadError = {
                loadError = true
                AppToasts.show(context, loadErrorText)
            },
            onUrlChanged = { url ->
                if (!url.isNullOrBlank()) currentUrl = url
            },
        ).also { it.start() }
    }

    LaunchedEffect(webView) {
        webView?.let { startSession(it) }
    }

    // 组合离场即销毁：WebView 随浮层关闭释放；driver ownsWebView=false，这里一并停收口
    DisposableEffect(Unit) {
        onDispose {
            delivered = true
            driver?.destroy()
            driver = null
            // destroySafely：延迟一轮销毁，避免在途加载回调派发到已销毁实例
            webView?.destroySafely()
            webView = null
        }
    }

    // 手机 / 电脑版切换：改 UA 后重载当前页，让服务端按新 UA 返回对应门户
    fun switchDesktopMode(desktop: Boolean) {
        if (desktop == desktopMode) return
        userAgentChoice = desktop
        val wv = webView ?: return
        wv.settings.userAgentString =
            resolveWebUserAgent(userAgentChoice, spec.userAgent, mobileUserAgent)
        val target = wv.url
        if (target.isNullOrBlank() || target == "about:blank") {
            wv.loadUrl(spec.entryUrl)
        } else {
            wv.reload()
        }
    }

    WebViewPageScaffold(
        title = schoolText(title),
        onBack = onDismiss,
        address = currentUrl,
        desktopMode = desktopMode,
        onDesktopModeChange = ::switchDesktopMode,
        // 地址栏左侧圆环即加载进度，不再单独画 LinearProgressIndicator
        loading = progress in 0..99 && !loadError,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    WebViewPolicy.harden(
                        wv,
                        resolveWebUserAgent(userAgentChoice, spec.userAgent, mobileUserAgent),
                    )
                    // 登录页按桌面 UA 返回宽布局：宽视口按宽度整体缩放，避免被压进控件宽度
                    wv.applyPageViewport(fitWidth = true)
                    // 登录链路跨多个声明域跳转：放行跨站 cookie
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                    webView = wv
                }
            },
        )
    }
}

/** 通用取数结果 → UI 结果（失败分类一一同构，不兜底合并） */
private fun FetchOutcome.toWebViewLoginOutcome(): WebViewLoginOutcome = when (this) {
    is FetchOutcome.Ok -> WebViewLoginOutcome.Ok(raw)
    is FetchOutcome.Failed -> WebViewLoginOutcome.Failed(
        when (error) {
            FetchError.SessionExpired -> com.fangyi.classpp.data.sync.SyncError.SessionExpired
            FetchError.ServiceBusy -> com.fangyi.classpp.data.sync.SyncError.ServiceBusy
            FetchError.NetworkError -> com.fangyi.classpp.data.sync.SyncError.NetworkError
            FetchError.Timeout -> com.fangyi.classpp.data.sync.SyncError.Timeout
            FetchError.FormatChanged -> com.fangyi.classpp.data.sync.SyncError.FormatChanged
        },
    )
}
