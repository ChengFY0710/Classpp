package com.fangyi.classpp.ui.schedule

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fangyi.classpp.R
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.shiguang.ShiguangScriptShim
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.data.sync.webview.unquoteJsResult
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.LocalNavigationBarInsets
import com.fangyi.classpp.ui.theme.LocalStatusBarInsets
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 拾光教务导入的全屏 WebView 页：打开适配声明的登录页，用户自行登录并进入课表页面，
 * 点底部「执行导入」后才注入「垫片 + 适配脚本」。
 *
 * 与学校声明的教务课表页不同：拾光的 `import_url` 只是登录入口，登录链路会跨多个
 * 未知认证域，因此没有域名白名单（复用 [WebViewPolicy.createUserPageClient]，只按
 * http/https 放行，SSL 错误仍不 proceed、file/content 与混合内容仍全禁）。
 *
 * 脚本执行采用「页面变量 + 原生轮询」：垫片把课程数据写进
 * [ShiguangScriptShim.RESULT_VAR]，本页每 200ms 读一次，读到即把原文交回宿主解析/映射。
 * 运行中返回键只取消本次执行，再次返回才关页；关页/退场由宿主控制，卸载时销毁
 * WebView 并 flush cookie。
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
    var timedOut by remember { mutableStateOf(false) }
    var executionFailed by remember { mutableStateOf(false) }
    var executeJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    // 返回键：运行中先取消本次导入（脚本可能还在页面里跑，但不再收结果）；再次返回才关页
    BackHandler {
        if (running) {
            executeJob?.cancel()
            executeJob = null
            running = false
        } else {
            onDismiss()
        }
    }

    // 组合离场即销毁：WebView 随页面关闭释放；cookie 落盘供下次打开复用登录态
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

    LaunchedEffect(webView, entryUrl) {
        webView?.let { load(it) }
    }

    fun startExecution() {
        val wv = webView ?: return
        if (running || !pageReady || loadError) return
        onExecuteStart()
        timedOut = false
        executionFailed = false
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
                    executionFailed = true
                    return@launch
                }
                val deadline = System.currentTimeMillis() + ExecuteTimeoutMillis
                while (isActive) {
                    delay(PollIntervalMillis)
                    val raw = evaluateJavascript(wv, ShiguangScriptShim.readJs())
                    if (!raw.isNullOrEmpty()) {
                        onResult(raw)
                        return@launch
                    }
                    if (System.currentTimeMillis() >= deadline) {
                        timedOut = true
                        return@launch
                    }
                }
            } finally {
                running = false
            }
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
                .windowInsetsPadding(LocalStatusBarInsets.current)
                .padding(horizontal = PageHorizontalSpacing, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schoolName,
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.shiguang_web_hint),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
                if (currentUrl.startsWith("http://", ignoreCase = true)) {
                    Text(
                        text = stringResource(R.string.import_ai_web_http_warning),
                        style = ClassppTextTokens.settingsDescription,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
                    .clickable(
                        interactionSource = press,
                        indication = null,
                        onClick = {
                            // 关闭即取消在途导入：轮询停止，退场期间不再回调结果
                            executeJob?.cancel()
                            executeJob = null
                            running = false
                            onDismiss()
                        },
                    ),
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
        // 加载进度条：跳转身份认证链路时页面持续白底，进度条说明「在加载」而非「卡死」
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
                        WebViewPolicy.harden(wv, null)
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
                            onLoadError = { loadError = true },
                            onPageStarted = { url ->
                                if (!url.isNullOrBlank()) currentUrl = url
                                // 首次加载完成后保持可导：SPA/登录跳转不应把按钮永久置灰
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
                                    load(wv)
                                }
                            },
                        )
                    }
                }
            }
        }
        // 底部操作区：脚本错误/超时状态 + 「执行导入」；键盘弹起时整块抬到键盘上方
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(LocalNavigationBarInsets.current)
                .imePadding()
                .padding(horizontal = PageHorizontalSpacing, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (timedOut) {
                Text(
                    text = stringResource(R.string.shiguang_web_timeout),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (executionFailed) {
                Text(
                    text = stringResource(R.string.shiguang_web_failed),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (!errorText.isNullOrBlank()) {
                // 脚本错误文案可能很长（含堆栈/接口原文）：截断后交给信息条，避免把按钮挤出屏幕
                val shownError = if (errorText.length > MaxErrorChars) {
                    errorText.take(MaxErrorChars) + "…"
                } else {
                    errorText
                }
                SheetInfoBanner(
                    text = shownError,
                    isError = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DialogPillButton(
                label = if (running) {
                    stringResource(R.string.shiguang_web_executing)
                } else {
                    stringResource(R.string.shiguang_web_execute)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth(),
                enabled = pageReady && !running && !loadError,
                onClick = { startExecution() },
            )
        }
    }
}

/** 脚本执行轮询：多数适配脚本 1~5s 完成；60s 覆盖弱网与多接口串联 */
private const val ExecuteTimeoutMillis = 60_000L

/** 轮询间隔：够快感知结果写回，又不至于每秒多次 evaluateJavascript */
private const val PollIntervalMillis = 200L

/** 错误信息条最多展示的字符数：脚本异常/接口原文可能很长，截断避免挤掉底部按钮 */
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
