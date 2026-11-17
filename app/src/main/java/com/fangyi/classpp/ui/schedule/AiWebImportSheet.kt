package com.fangyi.classpp.ui.schedule

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiWebImportUrl
import com.fangyi.classpp.data.ai.AiWebPageExtract
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.data.sync.webview.unquoteJsResult
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.motion.PressShade
import com.fangyi.classpp.ui.motion.pressClickable
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.LocalNavigationBarInsets
import com.fangyi.classpp.ui.theme.LocalStatusBarInsets
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.PillShape
import com.fangyi.classpp.ui.theme.SheetCardShape
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.glassPressFeedback
import com.fangyi.classpp.ui.theme.glassSurface
import com.fangyi.classpp.ui.theme.isDarkTheme
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AI 识别教务网页的全屏页：打开后**自带底部地址栏**，用户可以随时改网址，不再有单独的
 * 网址输入弹卡。登录与导航完全由页面完成。
 *
 * 与 `SchoolTimetableWebViewSheet` 的区别是**没有域名白名单**：目标由用户决定，客户端只
 * 按协议放行 http/https（见 [WebViewPolicy.createUserPageClient]），其余加固不变。
 *
 * 「识别导入」：注入 [AiWebPageExtract] 的静态脚本，把页面可见文本与表格网格写进约定
 * 变量，原生侧轮询读取；成功回调 [onExtract] 把文本交给宿主发 AI 请求。抓取与识别期间
 * 网页保持打开，失败可在原页重试。返回键：识别中先取消识别，其次地址栏编辑中先收键盘，
 * 再次网页后退，最后关闭。
 *
 * 地址栏行为：
 * - [initialUrl] 非空时直接加载（重开页面记住上次地址）；为空时自动聚焦地址栏并弹键盘；
 * - 页面跳转/重定向时，地址栏在未编辑状态下跟随当前 URL；
 * - 输入框聚焦时全选，便于整段替换；键盘「前往」与右侧箭头同路，先经
 *   [AiWebImportUrl] 归一化校验再加载，成功后回调 [onUrlChanged] 记住地址。
 */
@Composable
internal fun AiWebImportSheet(
    initialUrl: String,
    recognizing: Boolean,
    errorText: String?,
    // 用户明确打开了一个新地址：宿主记住它，下次重开页面直接加载
    onUrlChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    // 抓取成功：页面文本交给宿主发 AI 请求
    onExtract: (String) -> Unit,
    // 识别中取消（底部按钮 / 返回键）
    onStopRecognize: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 页面加载进度（0..100）与主文档失败态；进度越过初段即视为恢复，错误遮罩自动退场
    var progress by remember { mutableIntStateOf(0) }
    var loadError by remember { mutableStateOf(false) }
    // 首屏加载完成前不开放识别：页面还没渲染出课表
    var pageReady by remember { mutableStateOf(false) }
    // 当前页地址（显示用）与 WebView 应加载的地址（旋转后重载最近一次明确打开的地址）
    var currentUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var pageUrl by rememberSaveable { mutableStateOf(initialUrl) }
    // 地址栏草稿：TextFieldValue 便于聚焦时全选
    var address by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialUrl))
    }
    var addressFocused by remember { mutableStateOf(false) }
    var addressError by remember { mutableStateOf(false) }
    // 网页抓取：读取中态与本次读取失败的文案；重复点击只清结果变量后重新注入
    var extracting by remember { mutableStateOf(false) }
    var extractAttempt by remember { mutableIntStateOf(0) }
    var readError by remember { mutableStateOf<String?>(null) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val addressFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    // 手机 UA 取系统 WebView 默认值：与设备/内核最匹配；电脑 UA 用固定桌面标识
    val mobileUserAgent = remember(context) {
        runCatching { WebSettings.getDefaultUserAgent(context) }
            .getOrDefault(FALLBACK_MOBILE_USER_AGENT)
    }
    // 默认电脑版：教务系统常按 UA 分流，桌面门户更适合展示完整课表
    var desktopMode by rememberSaveable { mutableStateOf(true) }

    val readErrorMessage = stringResource(R.string.import_ai_web_error_empty)
    val recognizingText = stringResource(R.string.import_ai_web_recognizing)
    val readingText = stringResource(R.string.import_ai_web_reading)
    val privacyHint = stringResource(R.string.import_ai_web_privacy_hint)
    val httpWarning = stringResource(R.string.import_ai_web_http_warning)
    val addressInvalidText = stringResource(R.string.import_ai_web_url_invalid)
    val isHttp = currentUrl.startsWith("http://", ignoreCase = true)

    fun load(wv: WebView, target: String) {
        loadError = false
        progress = 0
        pageReady = false
        readError = null
        currentUrl = target
        wv.loadUrl(target)
    }

    // 把当前模式对应的 UA 写进 WebView；切换后必须 reload 才生效
    fun applyUserAgent(wv: WebView) {
        wv.settings.userAgentString =
            if (desktopMode) DESKTOP_USER_AGENT else mobileUserAgent
    }

    // 打开地址栏输入的新网址：空输入不动作；归一化失败只标错，不改页面
    fun openAddress() {
        if (address.text.isBlank()) return
        val target = AiWebImportUrl.normalize(address.text)
        if (target == null) {
            addressError = true
            return
        }
        addressError = false
        address = TextFieldValue(target, TextRange(target.length))
        pageUrl = target
        currentUrl = target
        onUrlChanged(target)
        addressFocused = false
        focusManager.clearFocus()
        keyboard?.hide()
        val wv = webView
        if (wv != null) load(wv, target)
    }

    // 手机/电脑版切换：改 UA 后重载当前页，让服务端按新 UA 返回对应门户
    fun switchUserAgent() {
        desktopMode = !desktopMode
        val wv = webView ?: return
        applyUserAgent(wv)
        val target = currentUrl.ifBlank { pageUrl }
        if (target.isNotBlank()) {
            pageUrl = target
            onUrlChanged(target)
            load(wv, target)
        }
    }

    // 识别请求期间页面可继续滚动；返回键先停识别，地址栏编辑中先收键盘，
    // 再按才走网页后退/关闭
    BackHandler {
        when {
            recognizing -> onStopRecognize()
            addressFocused -> {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onDismiss()
        }
    }

    // 组合离场即销毁：WebView 随页面关闭释放；cookie 落盘供下次打开复用登录态
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.destroySafely()
            webView = null
        }
    }

    // 首次加载：有初始地址直接打开；没有则聚焦地址栏，用户立刻可输入
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        if (pageUrl.isNotBlank()) load(wv, pageUrl)
    }
    LaunchedEffect(Unit) {
        if (initialUrl.isBlank()) {
            addressFocusRequester.requestFocus()
            keyboard?.show()
        }
    }
    // 识别开始即收键盘：结果确认弹窗在页内居中，键盘盖住底部按钮会挡住确认/取消
    LaunchedEffect(recognizing) {
        if (recognizing) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val currentOnExtract by rememberUpdatedState(onExtract)

    // 网页抓取：清空旧结果 → 注入提取脚本 → 轮询约定变量。脚本同步写回，通常一轮即得；
    // 超时只意味着页面没内容（登录页/跨域 iframe/未渲染完），错误交给 UI 提示重试
    LaunchedEffect(extractAttempt) {
        if (extractAttempt == 0) return@LaunchedEffect
        val wv = webView ?: return@LaunchedEffect
        extracting = true
        readError = null
        try {
            wv.evaluateJavascript(AiWebPageExtract.resetResult(), null)
            wv.evaluateJavascript(AiWebPageExtract.script, null)
        } catch (_: Exception) {
            extracting = false
            readError = readErrorMessage
            return@LaunchedEffect
        }
        val deadline = System.currentTimeMillis() + ExtractTimeoutMillis
        while (isActive) {
            delay(ExtractPollIntervalMillis)
            val raw = try {
                suspendCancellableCoroutine<String?> { cont ->
                    wv.evaluateJavascript(AiWebPageExtract.readResult()) { value ->
                        if (cont.isActive) cont.resume(unquoteJsResult(value))
                    }
                }
            } catch (_: Exception) {
                null
            }
            val capture = AiWebPageExtract.parse(raw)
            if (capture != null) {
                extracting = false
                if (capture.ok && capture.content.isNotBlank()) {
                    currentOnExtract(capture.content)
                } else {
                    readError = readErrorMessage
                }
                return@LaunchedEffect
            }
            if (System.currentTimeMillis() >= deadline) {
                extracting = false
                readError = readErrorMessage
                return@LaunchedEffect
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
                    text = stringResource(R.string.import_ai_web_sheet_title),
                    style = MaterialTheme.classppTextStyles.fieldLabel,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.import_ai_web_sheet_hint),
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.classppColors.secondaryText,
                )
                if (isHttp) {
                    Text(
                        text = httpWarning,
                        style = ClassppTextTokens.settingsDescription,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 手机/电脑版切换：单颗胶囊显示当前模式，点击切换 UA 并重载当前页
            val modeLabel = stringResource(
                if (desktopMode) {
                    R.string.import_ai_web_mode_desktop
                } else {
                    R.string.import_ai_web_mode_mobile
                },
            )
            val modeSwitchCd = stringResource(R.string.cd_ai_web_switch_mode)
            val modePressShade = if (MaterialTheme.isDarkTheme) PressShade.Brighten else PressShade.Darken
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .pressClickable(
                        shape = PillShape,
                        shade = modePressShade,
                        onClick = ::switchUserAgent,
                    )
                    .clip(PillShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modeLabel,
                    style = ClassppTextTokens.secondaryTextLarge.copy(
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_up_down),
                    contentDescription = modeSwitchCd,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
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
                    contentDescription = stringResource(R.string.cd_ai_web_close),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(23.dp),
                )
            }
        }
        // 加载进度条：跳转身份认证链路时页面持续白底，进度条说明「在加载」而非「卡死」；
        // 首个进度回包前显示不确定进度（连接阶段），之后随 onProgressChanged 走。
        // 主文档已加载完成（pageReady）就收起，避免个别页面不报 100 时进度条常驻
        if (!pageReady && progress in 0..99 && !loadError) {
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
                        // 默认电脑版：UA 由顶栏「电脑版/手机版」切换控制，切换时 reload
                        WebViewPolicy.harden(
                            wv,
                            if (desktopMode) DESKTOP_USER_AGENT else mobileUserAgent,
                        )
                        // 教务系统多为桌面布局：按视口宽度渲染并整体缩放，避免窄视口把
                        // 课表列挤成一条；有 viewport meta 的移动页仍按设备宽度排版
                        wv.settings.useWideViewPort = true
                        wv.settings.loadWithOverviewMode = true
                        // 登录链路跨多个域跳转：放行跨站 cookie
                        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                        wv.webViewClient = WebViewPolicy.createUserPageClient(
                            onLoadError = { loadError = true },
                            onPageStarted = { started ->
                                pageReady = false
                                if (started != null) {
                                    currentUrl = started
                                    addressError = false
                                    if (!addressFocused) {
                                        address = TextFieldValue(started, TextRange(started.length))
                                    }
                                }
                            },
                            onPageFinished = { finished ->
                                pageReady = true
                                if (finished != null) {
                                    currentUrl = finished
                                    if (!addressFocused) {
                                        address = TextFieldValue(finished, TextRange(finished.length))
                                    }
                                }
                            },
                        )
                        wv.webChromeClient = WebViewPolicy.createUserPageChromeClient(
                            onProgress = { value ->
                                progress = value
                                if (value > 5) loadError = false
                            },
                            // target=_blank / window.open 在当前 WebView 打开
                            onOpenWindow = { target -> wv.loadUrl(target) },
                        )
                        webView = wv
                    }
                },
            )
            if (currentUrl.isBlank()) {
                // 还没输入网址：盖住空白 WebView，提示用户从底部地址栏开始
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = R.drawable.ic_globe,
                        title = stringResource(R.string.import_ai_web_start_title),
                        description = stringResource(R.string.import_ai_web_start_desc),
                    )
                }
            }
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
                                if (wv != null && pageUrl.isNotBlank()) {
                                    CookieManager.getInstance().flush()
                                    load(wv, pageUrl)
                                }
                            },
                        )
                    }
                }
            }
        }
        // 底部操作区：识别状态/错误 + 识别按钮 + 地址栏（最下方，随时可改网址）。
        // 键盘弹起时整块经 imePadding 抬到键盘上方，WebView 相应缩短
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(LocalNavigationBarInsets.current)
                .imePadding()
                .padding(horizontal = PageHorizontalSpacing, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (recognizing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
            Text(
                text = when {
                    recognizing -> recognizingText
                    extracting -> readingText
                    else -> privacyHint
                },
                style = ClassppTextTokens.settingsDescription,
                color = MaterialTheme.classppColors.secondaryText,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            // 本次页面读取失败优先于上一次 AI 失败：先提示「没读到页面」，
            // 读取成功后的 AI 失败再由宿主 errorText 展示
            val shownError = readError ?: errorText
            if (shownError != null) {
                SheetInfoBanner(
                    text = shownError,
                    isError = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            DialogPillButton(
                label = when {
                    recognizing -> stringResource(R.string.import_ai_web_stop)
                    extracting -> stringResource(R.string.import_ai_web_reading)
                    else -> stringResource(R.string.import_ai_web_extract)
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.fillMaxWidth(),
                enabled = when {
                    recognizing -> true
                    extracting -> false
                    else -> pageReady && !loadError
                },
                onClick = {
                    when {
                        recognizing -> onStopRecognize()
                        extracting -> Unit
                        else -> extractAttempt++
                    }
                },
            )
            if (addressError) {
                Text(
                    text = addressInvalidText,
                    style = ClassppTextTokens.settingsDescription,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrowserAddressBar(
                    value = address,
                    onValueChange = { next ->
                        // 地址栏是逻辑单行：粘贴带入的换行直接滤掉
                        val text = next.text.replace("\n", "").replace("\r", "")
                        address = if (text == next.text) next else next.copy(text = text)
                        addressError = false
                    },
                    onOpen = ::openAddress,
                    focusRequester = addressFocusRequester,
                    onFocusChanged = { focused ->
                        addressFocused = focused
                        if (focused) {
                            address = address.copy(selection = TextRange(0, address.text.length))
                        }
                    },
                    isFocused = addressFocused,
                    isError = addressError,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(AddressGoSize)
                        .alpha(if (address.text.isNotBlank()) 1f else 0.4f)
                        .pressClickable(
                            shape = CircleShape,
                            enabled = address.text.isNotBlank(),
                            onClick = ::openAddress,
                        )
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = stringResource(R.string.cd_ai_web_open),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * 底部浏览器地址栏：白底圆角输入框 + 聚焦/错误描边；聚焦时由宿主全选内容。
 * 键盘「前往」与右侧箭头都由宿主接到同一个打开动作。
 */
@Composable
private fun BrowserAddressBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onOpen: () -> Unit,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    isFocused: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val strokeColor: Color = when {
        isError -> MaterialTheme.colorScheme.error
        isFocused -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .heightIn(min = AddressBarHeight)
            .clip(SheetCardShape)
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (strokeColor != Color.Transparent) {
                    Modifier.border(2.dp, strokeColor, SheetCardShape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = AddressBarHorizontalPadding, vertical = AddressBarVerticalPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = ClassppTextTokens.secondaryTextLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onOpen() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state -> onFocusChanged(state.isFocused) },
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.text.isEmpty()) {
                        Text(
                            text = stringResource(R.string.import_ai_web_url_placeholder),
                            style = MaterialTheme.classppTextStyles.fieldPlaceholder,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/** 地址栏高度与内距：46dp 单行条，左右 14dp 让长网址不贴圆角 */
private val AddressBarHeight = 46.dp
private val AddressBarHorizontalPadding = 14.dp
private val AddressBarVerticalPadding = 8.dp

/** 地址栏右侧「前往」圆钮尺寸 */
private val AddressGoSize = 44.dp

/** 桌面 UA：教务系统常按 UA 分流桌面/移动门户；与项目其它桌面 UA 保持一致 */
private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

/** 系统默认 UA 取不到时的手机兜底（极少见；明确带 Mobile 标识） */
private const val FALLBACK_MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

/** 抓取轮询超时：脚本同步写回，5s 覆盖大页面表格展开与主线程排队 */
private const val ExtractTimeoutMillis = 5_000L

/** 轮询间隔：同步脚本通常首轮即得，100ms 兼顾响应与 evaluateJavascript 次数 */
private const val ExtractPollIntervalMillis = 100L
