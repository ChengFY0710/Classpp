package com.fangyi.classpp.ui.schedule

import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ai.AiChatRepository
import com.fangyi.classpp.data.ai.AiConnection
import com.fangyi.classpp.data.ai.AiProviderPresets
import com.fangyi.classpp.data.ai.AiWebImportUrl
import com.fangyi.classpp.data.ai.AiWebPageExtract
import com.fangyi.classpp.data.destroySafely
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import com.fangyi.classpp.data.sync.webview.unquoteJsResult
import com.fangyi.classpp.ui.ai.AiAuroraAnchor
import com.fangyi.classpp.ui.ai.AiModelSwitchChip
import com.fangyi.classpp.ui.ai.AiReasoningSwitchChip
import com.fangyi.classpp.ui.ai.AiThinkingAurora
import com.fangyi.classpp.ui.ai.ModelSwitchChipGap
import com.fangyi.classpp.ui.ai.ModelSwitchChipHeight
import com.fangyi.classpp.ui.ai.SwitchChipSpacing
import com.fangyi.classpp.ui.components.EmptyState
import com.fangyi.classpp.ui.components.WebAddressBar
import com.fangyi.classpp.ui.components.WebAddressBarHeight
import com.fangyi.classpp.ui.motion.CardShrinkMotionState
import com.fangyi.classpp.ui.motion.rememberCardShrinkMotion
import com.fangyi.classpp.ui.settings.SettingsTopBar
import com.fangyi.classpp.ui.settings.SettingsTopBarAction
import com.fangyi.classpp.ui.theme.LocalNavigationBarHeight
import com.fangyi.classpp.ui.theme.LocalNavigationBarInsets
import com.fangyi.classpp.ui.theme.LocalStatusBarHeight
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.isGlassStyleEnabled
import com.fangyi.classpp.ui.theme.rememberGlassBackdrop
import com.fangyi.classpp.ui.webview.WebDesktopUserAgent
import com.fangyi.classpp.ui.webview.rememberWebMobileUserAgent
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.shapes.RoundedRectangle
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * AI 识别教务网页的全屏页（从教务网页识别）：打开后**自带底部地址栏**，用户可以随时
 * 改网址，登录与导航完全由页面完成。与 `SchoolTimetableWebViewSheet` 的区别是**没有
 * 域名白名单**：目标由用户决定，客户端只按协议放行 http/https（见
 * [WebViewPolicy.createUserPageClient]），其余加固不变。
 *
 * 页面结构：
 * - 顶部：设置页同款 [SettingsTopBar]（返回圆钮 + 居中标题），右侧一颗 primary 圆钮；
 * - 内容区：WebView 只占顶栏以下区域，底部 [WebAddressBar] 浮在页面之上；
 * - 确认：抓一张 WebView 可见内容的 Bitmap，冻结成快照卡（原 WebView 保持挂载但
 *   alpha = 0），卡片按 [rememberCardShrinkMotion] 缩小到屏幕中间：先缩过目标再回弹
 *   （复用弹层果冻曲线），圆角从直角渐变为 20dp；
 * - 缩小动画一开始 [AiThinkingAurora] 就在卡片下方亮起，并保持整个冻结期；
 * - 缩小动画完整播完后才开始抓取页面文本（[AiWebPageExtract]）：抓取期间卡片静止在
 *   缩小态、光效保持，顶栏标题为「抓取中」；
 * - 抓取成功：文本交给宿主开始 AI 识别，标题切为「识别中」，右上角从 ic_checkmark
 *   变为 ic_stop，点击取消识别；
 * - 抓取失败 / 识别失败 / 停止：卡片只用 320ms 非线性放大回原页面（不回弹），恢复
 *   WebView；成功：沿用宿主的确认弹窗与导入后整页退出流程，不改成功路径。
 *
 * 地址栏行为：
 * - [initialUrl] 非空时直接加载（重开页面记住上次地址）；为空时显示空态，点击地址栏输入；
 * - 页面跳转 / 重定向时，地址栏在未编辑状态下跟随当前 URL；
 * - 键盘「前往」与右侧箭头同路，先经 [AiWebImportUrl] 归一化校验再加载，成功后回调
 *   [onUrlChanged] 记住地址；UA 切换改由地址栏更多菜单触发，切换后 reload 当前页；
 * - 地址栏上方接入与 AI 对话浮层同款的模型 / 思考强度胶囊（多模型 / 多推理等级才出现，
 *   行间距与到地址栏的间距均为 10dp）；切换直接写回同一仓库，本次识别即使用新配置；
 * - 冻结 / 读取 / 识别期间地址栏与胶囊锁定并显示状态文案，避免重载 / 切换破坏抓取结果。
 *
 * 错误提示：页面读取失败、AI 识别失败、网址校验失败、HTTP 警告与主文档加载失败一律
 * 走系统 Toast，不再在底部叠信息条 / 红字或整页错误遮罩，网页始终保留可操作状态。
 *
 * 视口：页面按自身宽视口渲染（[WebSettings.setUseWideViewPort]），不再缩放到设备宽度
 * （[WebSettings.setLoadWithOverviewMode] 关闭），宽课表保持原始尺寸、横向拖动查看；
 * 同时开启 WebView 内置缩放，用户可捏合放大/缩小（不显示悬浮缩放钮）。
 */
@Composable
internal fun AiWebImportSheet(
    initialUrl: String,
    recognizing: Boolean,
    errorText: String?,
    // 模型 / 思考强度胶囊的数据与切换入口；null = 不显示胶囊
    aiRepository: AiChatRepository? = null,
    // 用户明确打开了一个新地址：宿主记住它，下次重开页面直接加载
    onUrlChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    // 抓取成功：页面文本交给宿主发 AI 请求
    onExtract: (String) -> Unit,
    // 识别中取消（右上角停止 / 返回键）
    onStopRecognize: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    // 主文档失败态；加载进度越过初段即视为恢复，错误遮罩自动退场
    var loadError by remember { mutableStateOf(false) }
    // 首屏加载完成前不开放识别：页面还没渲染出课表
    var pageReady by remember { mutableStateOf(false) }
    // 当前页地址（显示用）与 WebView 应加载的地址（旋转后重载最近一次明确打开的地址）
    var currentUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var pageUrl by rememberSaveable { mutableStateOf(initialUrl) }
    // 地址栏草稿（WebAddressBar 是受控 String 输入）
    var address by rememberSaveable { mutableStateOf(initialUrl) }
    var addressFocused by remember { mutableStateOf(false) }
    // 网页抓取：读取中态与本次读取失败的文案；重复点击只清结果变量后重新注入
    var extracting by remember { mutableStateOf(false) }
    var extractAttempt by remember { mutableIntStateOf(0) }
    var readError by remember { mutableStateOf<String?>(null) }

    // 冻结快照：确认时抓一张 WebView 位图；卡片回弹播完后释放并恢复 WebView
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var cardVisible by remember { mutableStateOf(false) }
    var cardFrozen by remember { mutableStateOf(false) }
    // 识别请求是否真正开始过：只在 true → false 的落沿触发回弹，避免首次组合误判
    var sawRecognizing by remember { mutableStateOf(false) }

    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // 手机 UA 取系统 WebView 默认值：与设备/内核最匹配；电脑 UA 用固定桌面标识
    val mobileUserAgent = rememberWebMobileUserAgent()
    // 默认电脑版：教务系统常按 UA 分流，桌面门户更适合展示完整课表
    var desktopMode by rememberSaveable { mutableStateOf(true) }

    // 模型 / 思考强度胶囊与 AI 对话浮层同源：读活动连接，切换直接落库到同一仓库。
    // 网页识别走仓库的当前连接，因此这里切换的模型 / 推理等级就是随后识别使用的配置
    val activeAiConnection by aiRepository?.activeConnection?.collectAsState()
        ?: remember { mutableStateOf(AiConnection()) }
    val aiConfigured by aiRepository?.configured?.collectAsState()
        ?: remember { mutableStateOf(false) }

    val readErrorMessage = stringResource(R.string.import_ai_web_error_empty)
    val readingText = stringResource(R.string.import_ai_web_reading)
    val fetchingTitle = stringResource(R.string.import_ai_web_fetching_title)
    val recognizingHint = stringResource(R.string.import_ai_web_recognizing_hint)
    val recognizingTitle = stringResource(R.string.import_ai_web_recognizing_title)
    val sheetTitle = stringResource(R.string.import_ai_web_sheet_title)
    val httpWarning = stringResource(R.string.import_ai_web_http_warning)
    val addressInvalidText = stringResource(R.string.import_ai_web_url_invalid)
    val loadErrorText = stringResource(R.string.sync_login_load_error)
    val isHttp = currentUrl.startsWith("http://", ignoreCase = true)

    // HTTP 入口只提示一次：进入 http 页面时 Toast，同域继续跳转不重复打扰。
    // 所有错误都走 Toast，网页本身始终保持可操作
    LaunchedEffect(isHttp) {
        if (isHttp) AppToasts.show(context, httpWarning)
    }
    // 页面读取失败 / 宿主 AI 识别失败：各提示一次（重新尝试时会先清空再置位）
    LaunchedEffect(readError) {
        readError?.let { AppToasts.show(context, it) }
    }
    LaunchedEffect(errorText) {
        errorText?.let { AppToasts.show(context, it) }
    }

    // 冻结 / 读取 / 识别共用同一条「页面不可交互」判定：标题、地址栏锁定与底部提示都看它
    val busy = cardVisible || extracting || recognizing
    // 模型 / 思考强度胶囊：与 AI 对话浮层同一显隐条件（多模型 / 多推理等级才出现）
    val modelOptions = activeAiConnection.modelOptions
    val showModelChip = aiConfigured && modelOptions.size > 1
    val reasoningConfig = activeAiConnection.modelConfig(activeAiConnection.model)
    val reasoningLevels = reasoningConfig.reasoningLevels
    val showReasoningChip = aiConfigured && reasoningLevels.size > 1
    val showChipRow = showModelChip || showReasoningChip
    val reasoningOffSupported = AiProviderPresets.thinkingControlFor(
        activeAiConnection.providerId,
        activeAiConnection.model,
    ).supportsOff
    val chipBlockHeight = if (showChipRow) ModelSwitchChipHeight + ModelSwitchChipGap else 0.dp
    val aiSaveFailedMessage = stringResource(R.string.ai_save_failed)
    val scope = rememberCoroutineScope()
    // 冻结卡的居中基准：内容区减去底栏（地址栏 + 胶囊行 + 上下内距 + 系统导航栏）后的中心
    val navigationBarHeight = LocalNavigationBarHeight.current
    val bottomReserve = WebAddressBarHeight + BottomBarVerticalPadding * 2 +
        navigationBarHeight + chipBlockHeight

    fun load(wv: WebView, target: String) {
        loadError = false
        pageReady = false
        readError = null
        currentUrl = target
        wv.loadUrl(target)
    }

    // 把当前模式对应的 UA 写进 WebView；切换后必须 reload 才生效
    fun applyUserAgent(wv: WebView) {
        wv.settings.userAgentString =
            if (desktopMode) WebDesktopUserAgent else mobileUserAgent
    }

    // 打开地址栏输入的新网址：空输入不动作；归一化失败只标错，不改页面
    fun openAddress() {
        if (address.isBlank()) return
        val target = AiWebImportUrl.normalize(address)
        if (target == null) {
            AppToasts.show(context, addressInvalidText)
            return
        }
        address = target
        pageUrl = target
        currentUrl = target
        onUrlChanged(target)
        focusManager.clearFocus()
        keyboard?.hide()
        val wv = webView
        if (wv != null) load(wv, target)
    }

    // 手机 / 电脑版切换：改 UA 后重载当前页，让服务端按新 UA 返回对应门户
    fun switchDesktopMode(desktop: Boolean) {
        if (desktop == desktopMode) return
        desktopMode = desktop
        val wv = webView ?: return
        applyUserAgent(wv)
        val target = currentUrl.ifBlank { pageUrl }
        if (target.isNotBlank()) {
            pageUrl = target
            onUrlChanged(target)
            load(wv, target)
        }
    }

    // 确认识别：先冻结快照并启动缩小动画；文本抓取在动画完整播完后由 cardMotion.onShrunk
    // 触发（抓取期间卡片保持缩小态，不亮光效）。快照失败时降级为直接抓取
    fun startRecognition() {
        if (!pageReady || loadError || busy) return
        focusManager.clearFocus()
        keyboard?.hide()
        val snapshot = webView?.let(::captureWebView)
        if (snapshot != null) {
            frozenBitmap = snapshot
            cardVisible = true
            cardFrozen = true
        } else {
            // 无法冻结：不做缩小动画，直接进入文本抓取（成功后由宿主识别）
            extractAttempt++
        }
    }

    fun stopRecognition() {
        onStopRecognize()
        cardFrozen = false
    }

    fun handleBack() {
        when {
            recognizing -> stopRecognition()
            cardVisible -> onDismiss()
            // 抓取期间页面仍是原尺寸、WebView 可后退：别让返回键在轮询中导航换页
            extracting -> onDismiss()
            addressFocused -> {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            webView?.canGoBack() == true -> webView?.goBack()
            else -> onDismiss()
        }
    }

    // 识别请求期间页面已冻结；返回键先停识别，冻结中直接关闭页面，地址编辑中先收键盘
    BackHandler(onBack = ::handleBack)

    // 组合离场即销毁：WebView 随页面关闭释放；cookie 落盘供下次打开复用登录态
    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.destroySafely()
            webView = null
        }
    }

    // 首次加载：有初始地址直接打开
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        if (pageUrl.isNotBlank()) load(wv, pageUrl)
    }
    // 冻结期间隐藏但保持挂载：JS 抓取继续工作；View 属性比 Compose 图层更可靠地盖住
    // WebView 的硬件合成内容。读取 / 识别期间同时禁用输入，避免抓取途中点链接换页
    LaunchedEffect(webView, cardVisible, busy) {
        val wv = webView ?: return@LaunchedEffect
        wv.alpha = if (cardVisible) 0f else 1f
        wv.isEnabled = !busy
    }
    // 识别开始即收键盘；识别结束后（成功 / 失败 / 取消）让冻结卡回弹到铺满状态
    LaunchedEffect(recognizing) {
        if (recognizing) {
            sawRecognizing = true
            focusManager.clearFocus()
            keyboard?.hide()
        } else if (sawRecognizing) {
            sawRecognizing = false
            cardFrozen = false
        }
    }
    // 抓取 / AI 失败：立即解除冻结，卡片非线性放大回原页面
    LaunchedEffect(readError, errorText) {
        if (readError != null || errorText != null) cardFrozen = false
    }

    // 冻结卡：active = 冻结中，回弹播完才卸载快照并恢复 WebView 显示。
    // WebView 的 alpha 在回调里同步复位，保证「快照卡卸载」与「网页重新出现」同帧，
    // 不依赖下一次组合后的 LaunchedEffect（否则末尾会闪一帧背景 / 光场）
    val cardMotion = rememberCardShrinkMotion(
        active = cardFrozen,
        // 缩小动画完整播完后才抓取页面文本：抓取期间卡片静止在缩小态，光效尚未亮起
        onShrunk = { extractAttempt++ },
    ) {
        webView?.let { wv ->
            wv.alpha = 1f
            wv.isEnabled = true
        }
        cardVisible = false
        frozenBitmap = null
    }

    val currentOnExtract by rememberUpdatedState(onExtract)

    // 网页抓取：缩小动画完成后由 cardMotion.onShrunk 触发；清空旧结果 → 注入提取脚本 →
    // 轮询约定变量。脚本同步写回，通常一轮即得；成功才把文本交给宿主发 AI 请求并点亮
    // 光效，失败 / 超时则回弹卡片、恢复网页原状
    LaunchedEffect(extractAttempt) {
        if (extractAttempt == 0) return@LaunchedEffect
        val wv = webView
        if (wv == null) {
            extracting = false
            readError = readErrorMessage
            cardFrozen = false
            return@LaunchedEffect
        }
        extracting = true
        readError = null
        try {
            wv.evaluateJavascript(AiWebPageExtract.resetResult(), null)
            wv.evaluateJavascript(AiWebPageExtract.script, null)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            extracting = false
            readError = readErrorMessage
            cardFrozen = false
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
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val capture = AiWebPageExtract.parse(raw)
            if (capture != null) {
                extracting = false
                if (capture.ok && capture.content.isNotBlank()) {
                    // 抓取成功：文本交给宿主开始 AI 识别（光效亮起），卡片保持缩小态
                    currentOnExtract(capture.content)
                } else {
                    readError = readErrorMessage
                    // 抓取失败：走回弹动画，网页恢复原位
                    cardFrozen = false
                }
                return@LaunchedEffect
            }
            if (System.currentTimeMillis() >= deadline) {
                extracting = false
                readError = readErrorMessage
                cardFrozen = false
                return@LaunchedEffect
            }
        }
    }

    val background = MaterialTheme.colorScheme.background
    val glassEnabled = isGlassStyleEnabled()
    // 液态玻璃采样层：整页底色 + 顶栏以下的内容（WebView / 空态 / 冻结卡）。
    // 顶栏圆钮与底部地址栏在采样节点之外，玻璃不会照见自己；关闭开关时该层不挂载
    val glassBackdrop = rememberGlassBackdrop(background)
    val buttonBackdrop = if (glassEnabled) glassBackdrop else null
    val confirmContentColor = if (glassEnabled) {
        MaterialTheme.classppColors.onPrimaryGlass
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    val density = LocalDensity.current
    // 顶栏实测高度（含状态栏 inset）：初始用稳定 inset + 64dp 估算，测量后校正
    val initialTopBarHeight = LocalStatusBarHeight.current + SettingsTopBarContentHeight
    var topBarHeight by remember { mutableStateOf(initialTopBarHeight) }

    val topAction = if (recognizing) {
        SettingsTopBarAction(
            icon = R.drawable.ic_stop,
            contentDescription = stringResource(R.string.import_ai_web_stop),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = confirmContentColor,
            onClick = ::stopRecognition,
        )
    } else {
        SettingsTopBarAction(
            icon = R.drawable.ic_checkmark,
            contentDescription = stringResource(R.string.import_ai_web_extract),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = confirmContentColor,
            enabled = pageReady && !loadError && !busy,
            onClick = ::startRecognition,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 统一不避让横向 inset：铺满屏幕宽（左右顶到边缘），状态栏交给顶栏自理
            .background(background),
    ) {
        // 玻璃采样层只记录整页底色：WebView 是 AndroidView，放进 Compose 采样层会被
        // 反复录制，导致页面概率性闪烁；顶栏圆钮 / 地址栏仍采样同色背景，玻璃材质与
        // 按压反馈不受影响
        if (glassEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(glassBackdrop),
            )
        }
        // 内容区在采样层之外：顶栏以下才是 WebView 的加载 / 显示范围
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topBarHeight)
                    .clipToBounds(),
            ) {
                // 光场垫在 WebView 之下：缩小动画开始（cardFrozen）即亮起，抓取 / 识别
                // 期间保持；回弹时淡出，恢复后的不透明 WebView 会把残余直接盖住
                AiThinkingAurora(
                    active = cardFrozen,
                    anchor = AiAuroraAnchor.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(AuroraBandHeight)
                        .align(Alignment.Center)
                        .offset(y = bottomReserve * -0.5f),
                )
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            // 默认电脑版：UA 由底部地址栏的更多菜单控制，切换时 reload
                            WebViewPolicy.harden(
                                wv,
                                if (desktopMode) WebDesktopUserAgent else mobileUserAgent,
                            )
                            // 教务系统多为桌面布局：保留页面自身的宽视口，不缩放到设备宽度。
                            // 宽表格按原始尺寸渲染、左右拖动查看，再配合捏合缩放看清细节
                            wv.settings.useWideViewPort = true
                            wv.settings.loadWithOverviewMode = false
                            wv.settings.setSupportZoom(true)
                            wv.settings.builtInZoomControls = true
                            // 只保留捏合手势，不显示悬浮的 +/− 缩放钮
                            wv.settings.displayZoomControls = false
                            // 登录链路跨多个域跳转：放行跨站 cookie
                            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                            wv.webViewClient = WebViewPolicy.createUserPageClient(
                                onLoadError = {
                                    loadError = true
                                    AppToasts.show(context, loadErrorText)
                                },
                                onPageStarted = { started ->
                                    pageReady = false
                                    if (started != null) {
                                        currentUrl = started
                                        if (!addressFocused) {
                                            address = started
                                        }
                                    }
                                },
                                onPageFinished = { finished ->
                                    pageReady = true
                                    if (finished != null) {
                                        currentUrl = finished
                                        if (!addressFocused) {
                                            address = finished
                                        }
                                    }
                                },
                            )
                            wv.webChromeClient = WebViewPolicy.createUserPageChromeClient(
                                onProgress = { value ->
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
                    // 还没输入网址：盖住空白 WebView，提示用户从底部地址栏开始。
                    // 高度与课表页「还没有课表」空态对齐：组件顶边距屏幕 250dp（EmptyState
                    // 自带 48dp 上下内距）；内容区从顶栏下沿开始，故扣掉实测顶栏高度
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        EmptyState(
                            icon = R.drawable.ic_globe,
                            title = stringResource(R.string.import_ai_web_start_title),
                            description = stringResource(R.string.import_ai_web_start_desc),
                            modifier = Modifier.padding(
                                top = (EmptyStateScreenTopPadding - topBarHeight)
                                    .coerceAtLeast(0.dp),
                            ),
                        )
                    }
                }
                if (cardVisible) {
                    // 冻结期间吞掉落到 WebView 上的触摸（页面已不可操作）
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(Unit) { detectTapGestures { } },
                    )
                }
                val snapshot = remember(frozenBitmap) { frozenBitmap?.asImageBitmap() }
                if (cardVisible && snapshot != null) {
                    FrozenPageCard(
                        snapshot = snapshot,
                        motion = cardMotion,
                        bottomReserve = bottomReserve,
                    )
                }
            }
        }
        // 底部浮层：在采样层之外，玻璃地址栏可以采样页面
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(LocalNavigationBarInsets.current)
                .imePadding()
                .padding(horizontal = PageHorizontalSpacing, vertical = BottomBarVerticalPadding),
        ) {
            // 与 AI 对话浮层同款的模型 / 思考强度胶囊：行间距与到地址栏的间距都是 10dp
            if (showChipRow) {
                Row(horizontalArrangement = Arrangement.spacedBy(SwitchChipSpacing)) {
                    if (showModelChip) {
                        AiModelSwitchChip(
                            models = modelOptions,
                            currentModel = activeAiConnection.model,
                            // 冻结 / 抓取 / 识别期间禁用：避免本次识别用的模型产生歧义
                            enabled = !busy,
                            hazeState = null,
                            backdrop = buttonBackdrop,
                            onSwitch = { model ->
                                scope.launch {
                                    val repository = aiRepository
                                    if (repository == null || !repository.switchModel(model)) {
                                        AppToasts.show(context, aiSaveFailedMessage)
                                    }
                                }
                            },
                        )
                    }
                    if (showReasoningChip) {
                        AiReasoningSwitchChip(
                            levels = reasoningLevels,
                            currentLevel = reasoningConfig.reasoningLevel,
                            enabled = !busy,
                            hazeState = null,
                            backdrop = buttonBackdrop,
                            offSupported = reasoningOffSupported,
                            onSwitch = { level ->
                                scope.launch {
                                    val repository = aiRepository
                                    if (repository == null || !repository.setReasoningLevel(level)) {
                                        AppToasts.show(context, aiSaveFailedMessage)
                                    }
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(ModelSwitchChipGap))
            }
            WebAddressBar(
                value = address,
                onValueChange = { next ->
                    address = next
                },
                onOpen = ::openAddress,
                modifier = Modifier.fillMaxWidth(),
                placeholder = stringResource(R.string.import_ai_web_url_placeholder),
                openedUrl = currentUrl.ifBlank { null },
                loading = !busy && currentUrl.isNotBlank() && !pageReady && !loadError,
                statusText = when {
                    recognizing -> recognizingHint
                    extracting -> readingText
                    else -> null
                },
                readOnly = busy,
                onFocusChanged = { addressFocused = it },
                desktopMode = desktopMode,
                onDesktopModeChange = { desktop ->
                    if (!busy) switchDesktopMode(desktop)
                },
                backdrop = buttonBackdrop,
            )
        }
        // 顶栏最后绘制：圆钮投影盖在内容与底栏之上，玻璃圆钮采样整页底色 / 内容
        SettingsTopBar(
            // 抓取是卡片缩小后的独立阶段（不亮光效）；识别开始后切回「识别中」
            title = when {
                extracting -> fetchingTitle
                recognizing || cardVisible -> recognizingTitle
                else -> sheetTitle
            },
            onBack = ::handleBack,
            trailingAction = topAction,
            buttonBackdrop = buttonBackdrop,
            modifier = Modifier
                .align(Alignment.TopStart)
                .onGloballyPositioned { coords ->
                    topBarHeight = with(density) { coords.size.height.toDp() }
                },
        )
    }
}

/**
 * 冻结网页快照卡：进度 0 = 铺满内容区（与确认前的 WebView 视觉一致），1 = 居中缩小态。
 *
 * 尺寸按进度插值（过冲时略小于目标），圆角从 0 渐变到 20dp 连续曲率；卡片中心随进度
 * 上移到「内容区扣除底栏后的中心」，正好落在设计稿的屏幕中间位置。
 */
@Composable
private fun FrozenPageCard(
    snapshot: ImageBitmap,
    motion: CardShrinkMotionState,
    bottomReserve: Dp,
    modifier: Modifier = Modifier,
) {
    val progress = motion.progress.value.coerceAtLeast(0f)
    val ramp = progress.coerceIn(0f, 1f)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val scale = 1f + (FrozenCardScale - 1f) * progress
        val cardWidth = maxWidth * scale
        val cardHeight = maxHeight * scale
        val cornerRadius = FrozenCardCornerRadius * ramp
        val lift = bottomReserve * 0.5f * ramp
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = lift * -1f)
                .size(width = cardWidth, height = cardHeight)
                .clip(RoundedRectangle(cornerRadius)),
        ) {
            Image(
                bitmap = snapshot,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 抓取 WebView 当前可见内容：`draw(Canvas)` 只截页面本身，不含顶部 / 底部浮层。
 * 硬件合成视频等极少数内容可能不完整；失败返回 null，调用方降级为不冻结、继续抓取。
 */
private fun captureWebView(webView: WebView): Bitmap? {
    if (webView.width <= 0 || webView.height <= 0) return null
    return runCatching {
        Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888).also { bitmap ->
            webView.draw(Canvas(bitmap))
        }
    }.getOrNull()
}

/** 与课表页无课表空态对齐：空态组件顶边距屏幕顶部 250dp（EmptyState 另带 48dp 内距） */
private val EmptyStateScreenTopPadding = 250.dp

/** 设置页顶栏内容高度（不含状态栏）：CenterAlignedTopAppBar 默认 64dp */
private val SettingsTopBarContentHeight = 64.dp

/** 冻结卡目标尺寸占内容区的比例（设计稿约 52%） */
private const val FrozenCardScale = 0.52f

/** 冻结卡目标圆角：连续曲率 20dp，随进度从直角渐变 */
private val FrozenCardCornerRadius = 20.dp

/** 底部地址栏上方 / 下方的呼吸量（与底部浮层的 vertical padding 同值） */
private val BottomBarVerticalPadding = 10.dp

/** 识别光场高度：与图片识别浮层同一量级，中心对齐冻结卡 */
private val AuroraBandHeight = 600.dp

/** 抓取轮询超时：脚本同步写回，5s 覆盖大页面表格展开与主线程排队 */
private const val ExtractTimeoutMillis = 5_000L

/** 轮询间隔：同步脚本通常首轮即得，100ms 兼顾响应与 evaluateJavascript 次数 */
private const val ExtractPollIntervalMillis = 100L
