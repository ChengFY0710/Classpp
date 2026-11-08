package com.fangyi.classpp.ui.schedule

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fangyi.classpp.R
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface

/**
 * 教务课表页（WebView 登录模式的网页导入入口）：全屏可见 WebView 打开
 * [WebViewTimetableSpec.entryUrl]，登录与课表查看都由页面完成。
 *
 * 本期只负责「进入页面」：不注入取数脚本、不调用导入服务、不落库。加固与导航白名单
 * 由 [WebViewPolicy] 锁死（spec 无法关闭）：仅 https + 声明域放行、SSL 错误不 proceed、
 * file/content 与混合内容全禁；登录链路跨域跳转放行第三方 cookie。
 *
 * 生命周期与 `GenericSchoolUi` 的可见登录浮层一致：WebView 由组合持有（AndroidView
 * factory 创建、onDispose flush cookie 后销毁）；关闭经 [onDismiss]（返回键同关闭）。
 * 进度条 + 加载失败遮罩 + 重试，避免无声白屏。
 */
@Composable
internal fun SchoolTimetableWebViewSheet(
    spec: WebViewTimetableSpec,
    onDismiss: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 页面加载进度（0..100）与主文档失败态；进度越过初段即视为恢复，错误遮罩自动退场
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }

    // 返回键关页面（后注册于课表页的编辑取消，因此优先收到）
    BackHandler {
        onDismiss()
    }

    // 组合离场即销毁：WebView 随页面关闭释放；cookie 落盘供下次打开复用登录态
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.destroySafely()
            webView = null
        }
    }

    fun load(wv: WebView) {
        loadError = false
        progress = 0
        wv.loadUrl(spec.entryUrl)
    }

    // WebView 就绪后加载入口页；spec 变化（理论上仅切校）重新加载
    LaunchedEffect(webView, spec) {
        webView?.let { load(it) }
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
                    text = stringResource(R.string.sync_import_webview_title),
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.sync_import_webview_hint),
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
        // 加载进度条：跳转身份认证链路时页面持续白底，进度条说明「在加载」而非「卡死」；
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
                        wv.webViewClient = WebViewPolicy.createTimetableClient(
                            spec = spec,
                            onLoadError = { loadError = true },
                        )
                        wv.webChromeClient = WebViewPolicy.createTimetableChromeClient(
                            spec = spec,
                            onProgress = { value ->
                                progress = value
                                if (value > 5) loadError = false
                            },
                            // target=_blank / window.open 在当前 WebView 打开（白名单已复核）
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
    }
}
