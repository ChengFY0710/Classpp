package com.fangyi.classpp.ui.schools

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.data.sync.SchoolText
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.engine.FetchError
import com.fangyi.classpp.data.sync.engine.FetchOutcome
import com.fangyi.classpp.data.sync.webview.WebSessionDriver
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface

/**
 * 通用 WebView 登录浮层（指导 §3.4/§四 Phase 4）：**零 UI 代码**的纯声明学校缺省绑定。
 *
 * 行为由 [WebViewLoginSpec] 声明驱动：入口 URL/白名单/两个端点/UA 全部来自 spec，
 * 加固锁死在 [WebViewPolicy]（spec 无法关闭任何加固）。浮层内嵌可见 WebView 走学校
 * 统一身份认证，App 不接触账号密码；用户完成登录回到 `dataHost` 时自动页内 fetch
 * profile + todos，拿到完整结果经 [onResult] 交还宿主（宿主负责关浮层）；用户主动
 * 关闭经 [SchoolUi.WebViewLoginSheet] 的 onDismiss。
 *
 * 登录态判定由 provider 注入的 [profileValidator] 决定（默认 JSON 对象）：未登录时的
 * 探活失败（401/403、登录页 HTML、跨域被拦）只复位探针、等登录完成后再探，**不关闭浮层**。
 *
 * 生命周期与既有的可见登录浮层一致：WebView 由组合持有（AndroidView factory 创建、
 * onDispose 销毁并 flush cookie）；驱动器在组合落定后（LaunchedEffect）才 start；
 * 进度条 + 错误态 + 重试，避免无声白屏；驱动器无总超时（用户在登录页停留多久都等）。
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
    // 页面加载进度（0..100）与加载失败态；进度越过初段即视为恢复，错误遮罩自动退场
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }

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
            onLoadError = { loadError = true },
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 统一不避让横向 inset：铺满屏幕宽（左右顶到边缘），状态栏交给内容行自理
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = PageHorizontalSpacing, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schoolText(title),
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // 不含校名的通用提示：可复用既有资源键（资源名不影响显示）
                    text = stringResource(R.string.sync_login_hint),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
            // 圆形关闭按钮：与设置页返回钮同规格（47dp 圆 + 按压反馈，玻璃关闭时实心底）
            val press = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .glassPressFeedback(press, CircleShape, null)
                    .glassSurface(
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surface,
                        backdrop = null,
                    )
                    .clickable(interactionSource = press, indication = null, onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_dismiss_circle),
                    contentDescription = stringResource(R.string.cd_settings_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        // 加载进度条：跳转认证链路时页面持续白底，进度条说明「在加载」而非「卡死」；
        // 首个进度回包前显示不确定进度（连接阶段），之后随 onProgressChanged 走
        if (progress in 0..99 && !loadError) {
            if (progress == 0) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PageHorizontalSpacing),
                )
            } else {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PageHorizontalSpacing),
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).also { wv ->
                        WebViewPolicy.harden(wv, spec.userAgent)
                        // 登录链路跨多个声明域跳转：放行跨站 cookie
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        webView = wv
                    }
                },
            )
            if (loadError) {
                // 主文档加载失败（网络/SSL）：遮罩 + 可见错误 + 重试，不留无声白屏
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painter = painterResource(R.drawable.ic_alert),
                            contentDescription = null,
                            tint = MaterialTheme.classppColors.secondaryText,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.sync_login_load_error),
                            style = ClassppTextTokens.settingsDescription,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogPillButton(
                            label = stringResource(R.string.sync_login_retry),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            onClick = {
                                val wv = webView
                                if (wv != null) {
                                    CookieManager.getInstance().flush()
                                    startSession(wv)
                                }
                            },
                        )
                    }
                }
            }
        }
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
