package com.fangyi.classpp.ui.settings

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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.R
import com.fangyi.classpp.data.xmu.XmuFetchResult
import com.fangyi.classpp.data.xmu.XmuWebSessionDriver
import com.fangyi.classpp.data.xmu.XmuWebViewPolicy
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface

/**
 * 课程中心登录浮层（全屏，由 [XmuSyncScreen] 挂在子页之上）：内嵌可见 WebView 走学校
 * 统一身份认证，App 不接触学号密码。加载 lnt.xmu.edu.cn 后由 [XmuWebSessionDriver]
 * 驱动——用户完成登录回到 lnt 域时自动页内 fetch /api/profile 与 /api/todos，拿到完整
 * 结果经 [onResult] 交还宿主（宿主负责关浮层）；用户主动关闭经 [onDismiss]。
 *
 * 生命周期要点：
 * - WebView 由组合持有（AndroidView factory 创建、onDispose 销毁并 flush cookie——
 *   持久化由系统 CookieManager 负责，下次登录/无头同步直接复用会话）；
 * - **驱动器在组合落定后（LaunchedEffect）才 start**——factory 里只创建与加固，
 *   loadUrl 不进组合阶段（部分机型 WebView 在 attach/布局前 loadUrl 会渲染成白屏）；
 * - 进度条（onProgressChanged）+ 错误态（主文档加载失败/SSL 失败 → 遮罩 + 重试），
 *   白屏不再无声无息；重试 = 重建驱动器重新 loadUrl；
 * - 驱动器无总超时（用户在登录页停留多久都等）。登录成功判定只认 /api/profile 的
 *   200+有效 JSON，不认 URL（SPA 路由不一定触发 onPageFinished）；未登录时的探活失败
 *   （401/403、登录页 HTML、跨域被拦）只复位探针、等登录完成后再探，**不关闭浮层**。
 *   安全加固（域名白名单/
 *   SSL 拒错/禁 file 访问）全部收在 data.xmu 的 [XmuWebViewPolicy]，登录页（身份域）
 *   不注入任何脚本。
 */
@Composable
internal fun XmuLoginSheet(
    onDismiss: () -> Unit,
    onResult: (XmuFetchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var driver by remember { mutableStateOf<XmuWebSessionDriver?>(null) }
    var delivered by remember { mutableStateOf(false) }
    // 页面加载进度（0..100）与加载失败态；进度越过初段即视为恢复，错误遮罩自动退场
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }

    val onDone: (XmuFetchResult) -> Unit = { result ->
        if (!delivered) {
            delivered = true
            CookieManager.getInstance().flush()
            onResult(result)
        }
    }

    // 驱动器构建 + 启动（WebView 就绪后调用；重试也走这里，旧驱动器先销毁）
    fun startSession(wv: WebView) {
        driver?.destroy()
        loadError = false
        progress = 0
        driver = XmuWebSessionDriver(
            webView = wv,
            mainHandler = Handler(Looper.getMainLooper()),
            ownsWebView = false,
            totalTimeoutMillis = 0L,
            // 未登录时的探活失败不是终态：复位后等登录完成回到 lnt 再探，
            // 否则浮层刚打开就会被判「未检测到登录」并关闭
            awaitInteractiveLogin = true,
            onDone = onDone,
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
            webView?.let { wv ->
                wv.stopLoading()
                wv.destroy()
            }
            webView = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 沉浸式：吃左右 inset，状态栏/手势条交给内容与顶栏自己铺满
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
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
                    text = stringResource(R.string.xmu_login_sheet_title),
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.xmu_login_hint),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
            }
            // 圆形关闭按钮：与设置页返回钮同规格（47dp 圆 + 按压反馈，玻璃关闭时实心底）
            val press = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .glassPressFeedback(press, CircleShape, null)
                    .glassSurface(shape = CircleShape, containerColor = MaterialTheme.colorScheme.surface, backdrop = null)
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
        // 加载进度条：跳转 CAS 链路时页面持续白底，进度条说明「在加载」而非「卡死」；
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
                        XmuWebViewPolicy.harden(wv)
                        // CAS 登录链路跨 lnt/ids/c-identity 多域跳转：放行跨站 cookie
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
                            text = stringResource(R.string.xmu_login_load_error),
                            style = ClassppTextTokens.settingsDescription,
                            color = MaterialTheme.classppColors.secondaryText,
                        )
                        Spacer(Modifier.height(14.dp))
                        DialogPillButton(
                            label = stringResource(R.string.xmu_login_retry),
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
