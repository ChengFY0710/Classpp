package com.fangyi.classpp.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.ScheduleRepository
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.shiguang.OkHttpShiguangWarehouseFetcher
import com.fangyi.classpp.data.shiguang.ShiguangAdapter
import com.fangyi.classpp.data.shiguang.ShiguangAdapterStore
import com.fangyi.classpp.data.shiguang.ShiguangCatalog
import com.fangyi.classpp.data.shiguang.ShiguangErrorCode
import com.fangyi.classpp.data.shiguang.ShiguangScheduleMapper
import com.fangyi.classpp.data.shiguang.ShiguangScriptShim
import com.fangyi.classpp.data.shiguang.ShiguangWarehouseConfig
import com.fangyi.classpp.data.shiguang.parseShiguangScriptResult
import com.fangyi.classpp.ui.components.LoadingOverlay
import com.fangyi.classpp.ui.motion.PageSlideOverlay
import com.fangyi.classpp.ui.motion.pageOverlayParallax
import com.fangyi.classpp.ui.motion.rememberPageOverlayTransitionState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 拾光导入的长生命周期服务（索引缓存 + 适配脚本下载器）：由课表页持有、跨浮层打开复用，
 * 浮层自身不再重建下载器、也不再重读/重解析索引（[ShiguangCatalog] 内部会缓存解析结果）。
 * 这样学校选择浮层每次打开都能在第一帧拿到全量列表。
 */
internal class ShiguangImportServices(
    val catalog: ShiguangCatalog,
    val adapterStore: ShiguangAdapterStore,
) {
    /**
     * 预热学校列表：只读本地磁盘缓存，**不联网**——首次自动拉取仍由浮层打开时的流程负责，
     * 用户没点「从拾光教务导入」时不产生任何请求。
     */
    suspend fun prewarm() {
        catalog.loadCached()
    }
}

/** 在课表页组合层创建一次；构造本身很轻（下载器内部惰性构建 OkHttpClient） */
@Composable
internal fun rememberShiguangImportServices(): ShiguangImportServices {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        val cacheRoot = File(appContext.filesDir, ShiguangWarehouseConfig.CACHE_DIR_NAME)
        val fetcher = OkHttpShiguangWarehouseFetcher()
        ShiguangImportServices(
            catalog = ShiguangCatalog(fetcher, cacheRoot),
            adapterStore = ShiguangAdapterStore(
                fetcher = fetcher,
                cacheDir = File(cacheRoot, ShiguangWarehouseConfig.ADAPTER_CACHE_DIR),
            ),
        )
    }
}

/**
 * 「拾光教务导入」流程宿主（自包含，避免继续膨胀 ScheduleScreen）：
 *
 * 导入浮层「从拾光教务导入」→ 学校选择浮层（[ShiguangImportSheet]）→ 下载适配脚本 →
 * 全屏 WebView（[ShiguangWebImportSheet]）→ 脚本结果映射 → 导入确认框 → 仓库落库。
 *
 * 状态生命周期：
 * - 学校列表：进程内已解析的索引直接作为浮层初值（[ShiguangImportServices] 跨打开复用，
 *   课表页在导入浮层打开时就预热），首帧即全量列表，入场动画期间不再插入 300 行列表；
 *   本进程首次打开仍走磁盘缓存 / 首次自动拉取，失败且无缓存 → 错误态，失败但有缓存
 *   → 列表照常可用并提示「已显示缓存」。
 * - 脚本：按选中适配下载/回退缓存；下载期间 [LoadingOverlay] 阻止重复点击。
 * - 网页：由 [PageSlideOverlay] 承载，退场播完才卸载（WebView 随之销毁）；
 *   网页在场期间上报 [onOverlayOverNavBarChange]，与教务网页页同款隐藏底部导航栏。
 * - 确认框：复用 [ScheduleImportConfirmDialog]（FadeOverlayDialog），不参与任何 covered。
 * - 宿主在 picker / 网页 / 确认框全部收场后才回调 [onDismissed]，外部可据此卸载。
 *
 * 导入成功只回调 [onImported]（新课表 id），关闭导入浮层链与切换课表由 ScheduleScreen
 * 统一处理（与 Wakeup/教务导入一致）。
 */
@Composable
internal fun ShiguangImportFlow(
    visible: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onImported: (String) -> Unit,
    repository: ScheduleRepository,
    services: ShiguangImportServices,
    onOverlayOverNavBarChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val catalog = services.catalog
    val adapterStore = services.adapterStore

    // 进程内已解析的索引直接作为初值：整段列表组合落在浮层还在屏幕外的那一帧，
    // 入场动画期间不会再出现「空态 → 300 行列表」的结构性插入（那一次长帧就是打开卡顿）
    val memoryAdapters = remember { catalog.cachedAdaptersOrNull() }
    var adapters by remember { mutableStateOf(memoryAdapters.orEmpty()) }
    var loading by remember { mutableStateOf(memoryAdapters == null) }
    var loadFailed by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    var pickedAdapter by remember { mutableStateOf<ShiguangAdapter?>(null) }
    var scriptLoading by remember { mutableStateOf(false) }
    var webVisible by remember { mutableStateOf(false) }
    var webScript by remember { mutableStateOf("") }
    var webError by remember { mutableStateOf<String?>(null) }
    var parsed by remember { mutableStateOf<Schedule?>(null) }
    var importing by remember { mutableStateOf(false) }

    var pickerMounted by remember { mutableStateOf(false) }
    if (visible) pickerMounted = true
    var confirmMounted by remember { mutableStateOf(false) }
    val confirmVisible = parsed != null
    if (confirmVisible) confirmMounted = true

    val webTransition = rememberPageOverlayTransitionState()
    // derivedStateOf 只在跨端点时翻转：退场动画逐帧不触发重组
    val webActive by remember(webTransition) {
        derivedStateOf { webVisible || webTransition.progress.value > 0f }
    }
    // 外部关闭（切换课表等）时 visible 直接翻转：取消内部页面/确认框，避免流程残留
    val visibleState = rememberUpdatedState(visible)
    LaunchedEffect(visible) {
        if (!visible) {
            webVisible = false
            parsed = null
            webError = null
            scriptLoading = false
        }
    }
    LaunchedEffect(webActive) {
        onOverlayOverNavBarChange(webActive)
    }
    DisposableEffect(Unit) {
        onDispose {
            onOverlayOverNavBarChange(false)
        }
    }

    val scriptLoadingText = stringResource(R.string.shiguang_script_loading)
    val scriptFailedText = stringResource(R.string.shiguang_script_failed)
    val refreshLoadingText = stringResource(R.string.shiguang_refreshing)
    val refreshDoneText = stringResource(R.string.shiguang_refresh_done)
    val refreshFailedText = stringResource(R.string.shiguang_refresh_failed)
    val noCoursesText = stringResource(R.string.shiguang_no_courses)
    val webFailedText = stringResource(R.string.shiguang_web_failed)
    val defaultName = stringResource(R.string.shiguang_default_name)
    val confirmTitle = stringResource(R.string.shiguang_confirm_title)

    // 学校列表：内存缓存命中 = 初值已带全量列表，这里只收尾 loading；否则读一次磁盘缓存，
    // 只有「从未拉取过」的首次打开才自动联网一次；之后（包括首次失败）一律缓存 / 错误态，
    // 更新只由浮层「更新适配列表」手动触发
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        if (catalog.cachedAdaptersOrNull() != null) {
            loading = false
            loadFailed = false
            return@LaunchedEffect
        }
        loading = true
        val cached = catalog.loadCachedOrNull()
        if (cached != null) {
            adapters = cached
            loading = false
            loadFailed = false
            return@LaunchedEffect
        }
        if (catalog.hasInitialFetchAttempted()) {
            loading = false
            loadFailed = true
            return@LaunchedEffect
        }
        // 先置 loading，避免标记/网络开始前的极短窗口里手动更新并发再拉一次
        loadFailed = false
        catalog.markInitialFetchAttempted()
        val fresh = catalog.refresh()
        loading = false
        if (fresh != null) {
            adapters = fresh
            loadFailed = false
        } else {
            loadFailed = true
        }
    }

    // 手动更新：首次自动拉取之外唯一的联网入口；失败保留现有列表并提示
    fun refreshCatalog() {
        if (refreshing || loading) return
        refreshing = true
        scope.launch {
            try {
                val fresh = catalog.refresh()
                if (!visibleState.value) return@launch
                if (fresh != null) {
                    adapters = fresh
                    loadFailed = false
                    AppToasts.show(context, refreshDoneText)
                } else {
                    loadFailed = true
                    AppToasts.show(context, refreshFailedText)
                }
            } finally {
                refreshing = false
            }
        }
    }

    val onPick: (ShiguangAdapter) -> Unit = { adapter ->
        if (!scriptLoading) {
            scriptLoading = true
            pickedAdapter = adapter
            scope.launch {
                val script = try {
                    adapterStore.ensureScript(adapter)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } finally {
                    scriptLoading = false
                }
                if (!visibleState.value) return@launch
                if (script == null) {
                    AppToasts.show(context, scriptFailedText)
                } else {
                    webScript = ShiguangScriptShim.build(script)
                    webError = null
                    webVisible = true
                }
            }
        }
    }

    // 脚本结果：失败留在网页可重试；成功映射成课表 → 关网页 → 确认框。
    // 页面已关闭（用户点关闭/外部收起）时丢弃迟到的脚本结果，避免退出动画期间弹确认框
    val onWebResult: (String) -> Unit = onWebResult@ { raw ->
        if (!webVisible) return@onWebResult
        val result = parseShiguangScriptResult(raw)
        when {
            result == null -> webError = webFailedText
            !result.ok -> {
                webError = when (result.errorCode) {
                    ShiguangErrorCode.NO_COURSES -> noCoursesText
                    ShiguangErrorCode.INVALID_DATA -> webFailedText
                    else -> result.error?.takeIf { it.isNotBlank() } ?: webFailedText
                }
            }
            else -> {
                val schedule = ShiguangScheduleMapper.toScheduleOrNull(
                    result = result,
                    fallbackName = pickedAdapter?.schoolName?.takeIf { it.isNotBlank() }
                        ?: defaultName,
                )
                if (schedule == null) {
                    webError = noCoursesText
                } else {
                    webError = null
                    webVisible = false
                    parsed = schedule
                }
            }
        }
    }

    if (pickerMounted) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 网页盖上来时学校列表左移让位（同教务导入浮层 → 教务网页页）
                .pageOverlayParallax(webTransition),
        ) {
            ShiguangImportSheet(
                visible = visible,
                onDismissed = { pickerMounted = false },
                onDismiss = onDismiss,
                adapters = adapters,
                loading = loading,
                loadFailed = loadFailed,
                onRefresh = { refreshCatalog() },
                onPick = onPick,
            )
        }
    }

    // 脚本下载 / 手动更新适配列表期间盖住学校列表；放在网页层之前，
    // 脚本就绪开页时不会压在新页面上
    LoadingOverlay(
        visible = refreshing || scriptLoading,
        message = if (refreshing) refreshLoadingText else scriptLoadingText,
    )

    PageSlideOverlay(
        visible = webVisible,
        state = webTransition,
        modifier = Modifier.fillMaxSize(),
    ) {
        val adapter = pickedAdapter
        if (adapter != null && webScript.isNotBlank()) {
            ShiguangWebImportSheet(
                schoolName = adapter.schoolName,
                entryUrl = adapter.importUrl,
                executionScript = webScript,
                errorText = webError,
                onDismiss = { webVisible = false },
                onExecuteStart = { webError = null },
                onResult = onWebResult,
            )
        }
    }

    if (confirmMounted) {
        ScheduleImportConfirmDialog(
            title = confirmTitle,
            courseCount = parsed?.courses?.size ?: 0,
            scheduleName = parsed?.name.orEmpty(),
            // 开学日为推算默认值：确认时展示，导入后仍可在课表设置里调整
            detailLine = parsed?.let {
                stringResource(R.string.import_confirm_detail, it.termStart.toString())
            },
            visible = confirmVisible,
            onDismissed = { confirmMounted = false },
            onCancel = { parsed = null },
            onConfirm = {
                val schedule = parsed
                parsed = null
                if (schedule != null && !importing) {
                    importing = true
                    scope.launch {
                        try {
                            when (
                                val r = repository.importSchedule(
                                    ShareCodec.encode(schedule, System.currentTimeMillis()),
                                )
                            ) {
                                is ReadResult.Ok -> onImported(r.value)
                                is ReadResult.Err -> AppToasts.show(
                                    context,
                                    r.error.toEditMessage(context),
                                )
                            }
                        } finally {
                            importing = false
                        }
                    }
                }
            },
        )
    }

    // 全部收场（含网页/确认框退场）后才让外部卸载宿主
    val allDismissed = !visible && !pickerMounted && !confirmMounted && !webActive
    LaunchedEffect(allDismissed) {
        if (allDismissed) onDismissed()
    }
}
