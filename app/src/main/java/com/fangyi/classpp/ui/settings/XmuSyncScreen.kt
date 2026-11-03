package com.fangyi.classpp.ui.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.xmu.SkipReason
import com.fangyi.classpp.data.xmu.XmuAutoLoginError
import com.fangyi.classpp.data.xmu.XmuAutoLoginManager
import com.fangyi.classpp.data.xmu.XmuAutoLoginPolicy
import com.fangyi.classpp.data.xmu.XmuAutoLoginState
import com.fangyi.classpp.data.xmu.XmuAvatarImage
import com.fangyi.classpp.data.xmu.XmuCommitOutcome
import com.fangyi.classpp.data.xmu.XmuDailySyncRunner
import com.fangyi.classpp.data.xmu.XmuDailySyncScheduler
import com.fangyi.classpp.data.xmu.XmuDebugLog
import com.fangyi.classpp.data.xmu.XmuEnableOutcome
import com.fangyi.classpp.data.xmu.XmuFetchResult
import com.fangyi.classpp.data.xmu.XmuPrepareOutcome
import com.fangyi.classpp.data.xmu.XmuSyncError
import com.fangyi.classpp.data.xmu.XmuSyncPlan
import com.fangyi.classpp.data.xmu.XmuSyncPreferences
import com.fangyi.classpp.data.xmu.XmuSyncRepository
import com.fangyi.classpp.ui.components.CardSection
import com.fangyi.classpp.ui.components.ConfirmDialogCard
import com.fangyi.classpp.ui.components.DialogPillButton
import com.fangyi.classpp.ui.components.FadeOverlayDialog
import com.fangyi.classpp.ui.components.OverlaySheet
import com.fangyi.classpp.ui.components.SettingsCard
import com.fangyi.classpp.ui.components.SettingsCardItem
import com.fangyi.classpp.ui.components.SettingsInfoHeaderCard
import com.fangyi.classpp.ui.components.SheetBottomSlack
import com.fangyi.classpp.ui.components.SheetInfoBanner
import com.fangyi.classpp.ui.components.SheetTopAction
import com.fangyi.classpp.ui.motion.LocalPageOverlayActive
import com.fangyi.classpp.ui.motion.LocalPageOverlayPrewarm
import com.fangyi.classpp.ui.motion.rememberOffsetOverscrollEffect
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SettingsSectionSpacing
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.kyant.backdrop.backdrops.layerBackdrop
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 账号与同步子页（设置 → 账号与同步）：课程中心待办同步的唯一入口。
 *
 * 结构：未连接 → 隐私说明卡 +「去登录」（默认原生 OAuth 登录：输入学号/密码，登录即
 * 保存凭据并开启自动续期；Keystore 不可用时直接回退网页登录）；已连接 → 连接状态卡
 * （头像圆 + 姓名 + 上次同步时间，头像未抓到时圆内显示姓名首字）+「立即同步」+「退出登录」。页面底部「备用登录方式」提供可见
 * WebView 登录（[XmuLoginSheet]：统一身份认证页完成登录，App 不接触密码、不保存凭据，
 * 不支持教务课表导入与自动续期；原生登录遇验证码/页面改版时也自动切换到它）。同步一律
 * 走「预览 → 确认导入」（[XmuSyncPreviewSheet]，按课程分组、默认全选）；错误按类提示
 * （过期给横幅 + 重登入口，**不把所有失败说成登录过期**）。退出登录两档：仅清登录态与
 * 同步记录 / 连课程中心导入的待办一并删除。
 *
 * [repository] = null（容器未装配完）→ 加载指示。在途标记与同步预览用 remember 非
 * saveable——旋转/进程重建时协程一并取消、导入就地作废（同 Wakeup 导入的取舍）。
 *
 * 「自动续期」：[autoLogin] 非空时在连接卡下方展示开关。原生登录成功即保存凭据并开启
 * （开关随登录点亮）；关闭会删除已保存的密码（教务导入随之不可用），同步自动回退到
 * WebView 路径。
 */
@Composable
fun XmuSyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: XmuSyncRepository? = null,
    autoLogin: XmuAutoLoginManager? = null,
    // 设置页任一覆盖层是否正在转场：顶栏据此冻结重放就位帧
    settingsTransforming: () -> Boolean = { false },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlayActive = LocalPageOverlayActive.current

    // 会话过期横幅：只在探活/登录明确判定过期时点亮；重新登录或探活成功即熄灭
    var sessionExpired by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }

    // 同步加载浮层（点「立即同步」立刻压上）：两段式挂载——preparing 置 false 后仍留在
    // 组合里播淡出，onDismissed 才卸载；淡出期间预览浮层滑入、压在本浮层之上
    var loadingMounted by remember { mutableStateOf(false) }
    if (preparing) loadingMounted = true

    // 同步预览（两段式挂载）：plan 非 null 即可见；onDismiss 清 plan（转场退场），播完才卸载
    var previewPlan by remember { mutableStateOf<XmuSyncPlan?>(null) }
    var previewMounted by remember { mutableStateOf(false) }
    if (previewPlan != null) previewMounted = true

    // 登录浮层与登录结果（结果里带两段响应原文，账号切换确认后要用）。
    // 原文都是字符串，用 Saver 一并存进 savedInstanceState：旋转后换号确认框的按钮仍可用，
    // 不会出现「框还在、pendingLogin 已丢」的死对话框。
    var loginVisible by rememberSaveable { mutableStateOf(false) }
    var pendingLogin by rememberSaveable(
        stateSaver = Saver<XmuFetchResult.Ok?, ArrayList<String>>(
            save = { ok -> ok?.let { arrayListOf(it.profileRaw, it.todosRaw) } },
            restore = { saved ->
                saved.takeIf { it.size == 2 }?.let { XmuFetchResult.Ok(it[0], it[1]) }
            },
        ),
    ) { mutableStateOf<XmuFetchResult.Ok?>(null) }

    // 退出登录：一次确认（保留已同步待办）；账号切换确认。
    // 两个确认框都走两段式挂载：visible 置 false 后仍留在组合里播淡出，onDismissed 才卸载
    var logoutVisible by rememberSaveable { mutableStateOf(false) }
    var logoutMounted by remember { mutableStateOf(false) }
    if (logoutVisible) logoutMounted = true
    var switchVisible by rememberSaveable { mutableStateOf(false) }
    var switchMounted by remember { mutableStateOf(false) }
    if (switchVisible) switchMounted = true

    // 自动续期（方案 B）：策略状态来自 manager。凭据浮层不 saveable——密码只活在本次
    // 组合里，旋转后重输可接受，避免密码进入 savedInstanceState。
    // fallback flow 保证 collectAsState 是无条件 composable 调用（条件调用会破坏槽位表）。
    val fallbackAutoLoginState = remember { MutableStateFlow(XmuAutoLoginState()) }
    val autoLoginState = (autoLogin?.state ?: fallbackAutoLoginState).collectAsState().value
    var autoLoginSheetVisible by remember { mutableStateOf(false) }
    var autoLoginSheetMounted by remember { mutableStateOf(false) }
    if (autoLoginSheetVisible) autoLoginSheetMounted = true
    var autoLoginBusy by remember { mutableStateOf(false) }
    var autoLoginError by remember { mutableStateOf<XmuAutoLoginError?>(null) }
    var disableAutoVisible by remember { mutableStateOf(false) }
    var disableAutoMounted by remember { mutableStateOf(false) }
    if (disableAutoVisible) disableAutoMounted = true

    // 每日自动同步：开关是 SharedPreferences 单键（后台 receiver 也读），进页读一次；
    // 切换即落盘（回调里），本状态只跟随开关刷新 UI
    var dailySyncEnabled by remember {
        mutableStateOf(XmuSyncPreferences.isDailySyncEnabled(context))
    }

    // 错误文案在组合期解析（回调里只引用，不经 LocalContext 查资源——lint 惯例）
    val errorTexts = remember {
        mapOf(
            XmuSyncError.SessionExpired to R.string.xmu_error_expired,
            XmuSyncError.ServiceBusy to R.string.xmu_error_busy,
            XmuSyncError.NetworkError to R.string.xmu_error_network,
            XmuSyncError.Timeout to R.string.xmu_error_timeout,
            XmuSyncError.FormatChanged to R.string.xmu_error_format,
            XmuSyncError.AccountChanged to R.string.xmu_error_account_changed,
            XmuSyncError.TooSoon to R.string.xmu_error_too_soon,
            XmuSyncError.PersistFailed to R.string.xmu_error_persist,
        )
    }
    val genericErrorText = stringResource(R.string.xmu_error_generic)
    // 自动续期失败文案（浮层横幅与 Toast 共用；失败时两处都提示，避免只滚到输入框而漏看）
    val autoLoginErrorTexts = remember {
        mapOf(
            XmuAutoLoginError.NeedCaptcha to R.string.xmu_auto_login_need_captcha,
            XmuAutoLoginError.InvalidCredentials to R.string.xmu_auto_login_invalid_credentials,
            XmuAutoLoginError.StorageUnavailable to R.string.xmu_auto_login_storage_unavailable,
            XmuAutoLoginError.ServiceBusy to R.string.xmu_error_busy,
            XmuAutoLoginError.NetworkError to R.string.xmu_error_network,
            XmuAutoLoginError.Timeout to R.string.xmu_error_timeout,
            XmuAutoLoginError.FormatChanged to R.string.xmu_error_format,
            XmuAutoLoginError.SessionNotEstablished to R.string.xmu_auto_login_not_established,
        )
    }
    val upToDateText = stringResource(R.string.xmu_up_to_date)
    val importDoneFormat = stringResource(R.string.xmu_import_done)
    val importFailedText = stringResource(R.string.xmu_error_import)
    val persistFailedText = stringResource(R.string.xmu_error_persist)
    val logoutDoneText = stringResource(R.string.xmu_logout_done)
    val deadlineWarningFormat = stringResource(R.string.xmu_deadline_unparseable)

    val showError: (XmuSyncError) -> Unit = { error ->
        AppToasts.show(
            context,
            errorTexts[error]?.let { context.getString(it) } ?: genericErrorText,
        )
    }

    // 无新增、无恢复 → 预览里没有任何可看得见的内容，不弹浮层；但仍要补写映射基线与
    // lastSyncAt（备份恢复/「仅退出」后重新登录靠这一步重建「上次同步」）。落盘失败不能报
    // 「已是最新」，否则用户以为同步成功、实际映射没保存。全部条目都因截止格式被跳过时同理：
    // 不能说「已是最新」，否则接口改版会被当成同步成功。
    suspend fun showPlanOrUpToDate(plan: XmuSyncPlan) {
        if (plan.newItems.isNotEmpty() || plan.restoreItems.isNotEmpty()) {
            previewPlan = plan
            return
        }
        val repo = repository
        val unparseable = plan.skipped[SkipReason.DeadlineUnparseable] ?: 0
        if (repo == null || repo.markSynced(plan)) {
            AppToasts.show(
                context,
                if (unparseable > 0) deadlineWarningFormat.format(unparseable) else upToDateText,
            )
        } else {
            AppToasts.show(context, persistFailedText)
        }
    }

    // ---------- 动作 ----------

    val onSyncClick: () -> Unit = {
        val repo = repository
        if (repo != null && !preparing) {
            // 立即点亮加载浮层：网络拉取期间用户看到转圈，而不是卡在原页面
            preparing = true
            scope.launch {
                try {
                    when (val outcome = repo.prepareSync()) {
                        is XmuPrepareOutcome.Ready -> {
                            sessionExpired = false
                            showPlanOrUpToDate(outcome.plan)
                        }
                        is XmuPrepareOutcome.Failed -> {
                            if (outcome.error == XmuSyncError.SessionExpired) sessionExpired = true
                            showError(outcome.error)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e // 返回上一页等取消：正常传播，不弹错误
                } catch (e: Exception) {
                    // WebView 初始化失败等未预期异常：兜底提示，不让协程异常把进程带崩
                    AppToasts.show(context, genericErrorText)
                } finally {
                    // 成功/失败/异常都收掉浮层（不留常驻转圈）
                    preparing = false
                }
            }
        }
    }

    fun onLoginResult(result: XmuFetchResult) {
        loginVisible = false
        val repo = repository ?: return
        when (result) {
            is XmuFetchResult.Ok -> scope.launch {
                try {
                    // 网页登录不保存密码：此前若有原生登录留下的凭据，一并清除（与
                    // 「WebView 登录不保存账号密码」的定位一致）；CookieManager 里刚建立的
                    // 会话不受影响，关闭续期后同步回退到 WebView 路径正好复用它
                    autoLogin?.disable()
                    when (val outcome = repo.prepareFromLogin(result.profileRaw, result.todosRaw)) {
                        is XmuPrepareOutcome.Ready -> {
                            sessionExpired = false
                            showPlanOrUpToDate(outcome.plan)
                        }
                        is XmuPrepareOutcome.Failed -> when (outcome.error) {
                            XmuSyncError.AccountChanged -> {
                                pendingLogin = result
                                switchVisible = true
                            }
                            else -> {
                                if (outcome.error == XmuSyncError.SessionExpired) sessionExpired = true
                                showError(outcome.error)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppToasts.show(context, genericErrorText)
                }
            }
            // 未登录的探活失败已在驱动器里复位等待（浮层不关、不提示）；
            // 能走到这里的只剩真实异常（5xx/超时/接口形态变化），按类提示
            XmuFetchResult.SessionExpired -> {
                sessionExpired = true
                showError(XmuSyncError.SessionExpired)
            }
            XmuFetchResult.ServiceBusy -> showError(XmuSyncError.ServiceBusy)
            XmuFetchResult.NetworkError -> showError(XmuSyncError.NetworkError)
            XmuFetchResult.Timeout -> showError(XmuSyncError.Timeout)
            XmuFetchResult.FormatChanged -> showError(XmuSyncError.FormatChanged)
        }
    }

    // 自动续期：打开开关 → 输入凭据并原生登录一次；关闭 → 确认后删除已保存密码。
    // 关闭只删凭据，不动当前连接（原生登录时已把 cookie 镜像进 WebView）。
    val onAutoLoginToggle: (Boolean) -> Unit = { wantOn ->
        XmuDebugLog.d("autoLogin toggle wantOn=$wantOn policy=${autoLoginState.policy}")
        if (wantOn) {
            if (!autoLoginBusy) {
                autoLoginError = null
                autoLoginSheetVisible = true
            }
        } else {
            disableAutoVisible = true
        }
    }

    // 每日自动同步开关：先落盘再调度（scheduleNext 读 prefs 决定排或取消）；开启时
    // 立即跑一次补漏判定——当天 6:00 已过且还没同步过就当场同步，与「进 App 补漏」
    // 同一条静默路径（导入不问确认、失败只留日志）
    val onDailySyncToggle: (Boolean) -> Unit = { wantOn ->
        XmuSyncPreferences.setDailySyncEnabled(context, wantOn)
        dailySyncEnabled = wantOn
        scope.launch {
            if (wantOn) XmuDailySyncRunner.runIfDue(context)
            XmuDailySyncScheduler.scheduleNext(context)
        }
    }

    val onEnableAutoLogin: (String, String) -> Unit = { username, password ->
        // 诊断点：点确认后第一行日志。若这行都没出现 = 点击没到达本回调（UI 层问题）
        XmuDebugLog.d("enable submit received manager=${autoLogin != null} busy=$autoLoginBusy")
        val manager = autoLogin
        if (manager != null && !autoLoginBusy) {
            autoLoginBusy = true
            autoLoginError = null
            scope.launch {
                try {
                    // 兜底超时：任何环节挂死，busy 态也在 90s 内退出并给出提示
                    val outcome = withTimeout(ENABLE_TIMEOUT_MS) {
                        manager.enable(username, password)
                    }
                    XmuDebugLog.d("enable outcome=${outcome::class.simpleName}")
                    when (outcome) {
                        is XmuEnableOutcome.Ready -> {
                            autoLoginSheetVisible = false
                            val repo = repository
                            if (repo != null) {
                                // 与网页登录共用同一条「预览 → 导入」路径
                                when (
                                    val prepared = repo.prepareFromLogin(
                                        outcome.profileRaw,
                                        outcome.todosRaw,
                                    )
                                ) {
                                    is XmuPrepareOutcome.Ready -> {
                                        sessionExpired = false
                                        showPlanOrUpToDate(prepared.plan)
                                    }
                                    is XmuPrepareOutcome.Failed -> when (prepared.error) {
                                        // 原生登录进了另一个账号：与网页登录同一套换号确认；
                                        // 确认后 autoLogin.disable() 会清掉刚保存的凭据
                                        XmuSyncError.AccountChanged -> {
                                            pendingLogin = XmuFetchResult.Ok(
                                                outcome.profileRaw,
                                                outcome.todosRaw,
                                            )
                                            switchVisible = true
                                        }
                                        else -> {
                                            if (prepared.error == XmuSyncError.SessionExpired) {
                                                sessionExpired = true
                                            }
                                            showError(prepared.error)
                                        }
                                    }
                                }
                            }
                        }
                        is XmuEnableOutcome.Failed -> {
                            XmuDebugLog.d("enable failed error=${outcome.error}")
                            AppToasts.show(
                                context,
                                autoLoginErrorTexts[outcome.error]
                                    ?.let { context.getString(it) }
                                    ?: genericErrorText,
                            )
                            // 验证码/页面改版/登录后仍读不到数据：原生链路走不通，收起浮层
                            // 自动转网页登录（浮层先关，网页登录浮层叠上）；其余错误（密码
                            // 错误、存储不可用、网络等）留在浮层内横幅提示，便于直接重试
                            when (outcome.error) {
                                XmuAutoLoginError.NeedCaptcha,
                                XmuAutoLoginError.FormatChanged,
                                XmuAutoLoginError.SessionNotEstablished,
                                -> {
                                    autoLoginSheetVisible = false
                                    loginVisible = true
                                }
                                else -> autoLoginError = outcome.error
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    XmuDebugLog.d("enable timeout")
                    autoLoginError = XmuAutoLoginError.Timeout
                    AppToasts.show(context, context.getString(R.string.xmu_error_timeout))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    XmuDebugLog.d("enable crashed ${e::class.simpleName}")
                    autoLoginError = XmuAutoLoginError.NetworkError
                } finally {
                    autoLoginBusy = false
                }
            }
        }
    }

    val doLogout: () -> Unit = {
        scope.launch {
            // 退出登录 = 连自动续期凭据一起清掉，否则下次同步会再次自动登录
            autoLogin?.disable()
            repository?.resetSyncData(deleteSyncedTodos = false)
            // localStorage / IndexedDB 同属登录态残留，一并清
            WebStorage.getInstance().deleteAllData()
            val cookieManager = CookieManager.getInstance()
            // 异步删除：等回调再 flush，保证提示「已退出登录」时旧 cookie 已不可用
            cookieManager.removeAllCookies {
                cookieManager.flush()
                sessionExpired = false
                pendingLogin = null
                AppToasts.show(context, logoutDoneText)
            }
        }
    }

    val onSwitchResolved: (Boolean) -> Unit = { deleteSyncedTodos ->
        val pending = pendingLogin
        val repo = repository
        if (pending != null && repo != null) {
            scope.launch {
                // 换号时旧账号的自动续期凭据必须一起清掉，否则下次会登回旧账号
                autoLogin?.disable()
                repo.resetSyncData(deleteSyncedTodos)
                when (val outcome = repo.prepareFromLogin(pending.profileRaw, pending.todosRaw)) {
                    is XmuPrepareOutcome.Ready -> showPlanOrUpToDate(outcome.plan)
                    is XmuPrepareOutcome.Failed -> showError(outcome.error)
                }
            }
        }
        pendingLogin = null
    }

    val onPreviewConfirm: (Set<String>) -> Unit = { selected ->
        val plan = previewPlan
        val repo = repository
        if (plan != null && repo != null && !committing) {
            committing = true
            scope.launch {
                when (val r = repo.commitSync(plan, selected)) {
                    is XmuCommitOutcome.Done -> {
                        AppToasts.show(
                            context,
                            importDoneFormat.format(r.report.imported, r.report.restored),
                        )
                        previewPlan = null
                    }
                    is XmuCommitOutcome.Failed -> {
                        // 落盘失败单独提示：待办可能已导入但映射没保存，重试前先修存储
                        val message = if (r.error is TodoError.PersistFailed) {
                            persistFailedText
                        } else {
                            importFailedText
                        }
                        AppToasts.show(context, message)
                    }
                }
                committing = false
            }
        }
    }

    // ---------- 页面 ----------

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val hazeState = rememberHazeState()
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            // 滚动状态上提：冻结键要读滚动位置
            val scrollState = rememberScrollState()
            // 标题渐显要把过滚动算进内容位移（可滚页面读边缘橡皮筋，不足一屏读整页橡皮筋）
            val overscrollEffect = rememberOffsetOverscrollEffect()
            // 预热帧也要挂 Haze；对话框在场或覆盖层隐藏时不挂、也不录制（保留上次的帧）
            val hazeActive = (overlayActive || LocalPageOverlayPrewarm.current) &&
                !loadingMounted && !logoutMounted && !switchMounted && !disableAutoMounted
            val darkTheme = MaterialTheme.isDarkTheme
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            // 冻结键：主题/顶栏高度/滚动位置/账号与对话框状态任一变化都回退实时绘制
            val freezeKey: () -> Any = remember(
                darkTheme,
                scrollState,
                repository,
                freezeEpoch,
            ) {
                {
                    val sync = repository?.state?.value
                    SettingsTopBarFreezeKey(
                        page = SettingsPage.XmuSync,
                        darkTheme = darkTheme,
                        topBarHeight = topBarHeight,
                        scroll = scrollState.value,
                        epoch = freezeEpoch,
                        inputs = listOf(
                            sync?.uid,
                            sync?.accountName,
                            sessionExpired,
                            loadingMounted,
                            logoutMounted,
                            switchMounted,
                            disableAutoMounted,
                        ),
                    )
                }
            }
            val navBarPadding = WindowInsets.navigationBars
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()

            when {
                // 采样源始终存在：加载态也挂 hazeSource，避免顶栏背后无源可采
                repository == null -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                else -> {
                    val syncState by repository.state.collectAsState()
                    // 已缓存的头像（null = 还没抓到/已退出登录）：随同步下载、退出登录更新
                    val avatarImage by repository.avatarImage.collectAsState()
                    val connected = syncState.uid.isNotEmpty()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(hazeState)
                            .then(
                                if (glassBackdrop != null) {
                                    Modifier.layerBackdrop(glassBackdrop)
                                } else {
                                    Modifier
                                },
                            )
                            .rubberBandVerticalScroll(
                                scrollState,
                                overscrollEffect = overscrollEffect,
                            )
                            .padding(horizontal = PageHorizontalSpacing)
                            .padding(
                                top = topBarHeight + TopBarContentGap,
                                bottom = navBarPadding + SheetBottomSlack,
                            ),
                        verticalArrangement = Arrangement.spacedBy(SettingsSectionSpacing),
                    ) {
                        if (sessionExpired) {
                            SheetInfoBanner(text = stringResource(R.string.xmu_error_expired))
                        }

                        // 顶部无题分组：头部卡 + 账号状态/登录入口，组内卡间距 10dp
                        CardSection {
                            SettingsInfoHeaderCard(
                                icon = R.drawable.ic_arrow_sync_circle,
                                title = stringResource(R.string.xmu_header_title),
                                description = stringResource(R.string.xmu_header_description),
                            )

                            if (!connected) {
                                SettingsCard(
                                    items = listOf(
                                        SettingsCardItem.Nav(
                                            label = stringResource(R.string.xmu_login_button),
                                            onClick = {
                                                sessionExpired = false
                                                if (autoLogin != null && autoLoginState.available) {
                                                    // 默认原生 OAuth 登录：输学号/密码，成功即
                                                    // 保存凭据并开启自动续期
                                                    autoLoginError = null
                                                    autoLoginSheetVisible = true
                                                } else {
                                                    // 自动续期未装配或 Keystore 不可用：原生
                                                    // 登录不了，直接走网页登录兜底
                                                    loginVisible = true
                                                }
                                            },
                                        ),
                                    ),
                                )
                            } else {
                                SettingsCard(
                                    items = listOf(
                                        SettingsCardItem.Custom(
                                            // 已连接后标题直接是账户名（不再加「已连接：」前缀），
                                            // 姓名缺省时退回「已连接」单字
                                            label = syncState.accountName.ifBlank {
                                                stringResource(R.string.xmu_connected_label)
                                            },
                                            description = if (syncState.lastSyncAtMillis > 0L) {
                                                stringResource(
                                                    R.string.xmu_last_sync_at,
                                                    formatSyncTime(syncState.lastSyncAtMillis),
                                                )
                                            } else {
                                                stringResource(R.string.xmu_last_sync_never)
                                            },
                                            // 头像圆只在已连接分支出现：未连接时这张卡不存在，圆随之不渲染
                                            leading = {
                                                AccountAvatar(
                                                    name = syncState.accountName,
                                                    image = avatarImage,
                                                )
                                            },
                                        ),
                                    ),
                                )
                                SettingsCard(
                                    items = listOf(
                                        SettingsCardItem.Nav(
                                            label = stringResource(R.string.xmu_sync_button),
                                            onClick = onSyncClick,
                                        ),
                                        SettingsCardItem.Nav(
                                            label = stringResource(R.string.xmu_logout_button),
                                            onClick = { logoutVisible = true },
                                        ),
                                    ),
                                )
                            }
                        }

                        // 「自动续期和同步」分组：两个开关合并一张卡，能力缺失时逐行隐藏。
                        // 自动续期未连接时置灰（原生登录即保存凭据并自动开启，无需先开），
                        // 关闭/暂停/不可用时同步仍走 WebView 路径，开启需要现场输入凭据完成
                        // 一次原生登录；自动同步每天 6:00 后台跑一次并自动导入（需已连接），
                        // 未开自动续期时登录过期该次静默跳过（行为不变，文案统一不区分）
                        if (autoLogin != null || connected) {
                            CardSection(
                                title = stringResource(R.string.xmu_section_auto_login_sync),
                            ) {
                                SettingsCard(
                                    items = buildList {
                                        if (autoLogin != null) {
                                            add(
                                                SettingsCardItem.Toggle(
                                                    label =
                                                        stringResource(R.string.xmu_auto_login_title),
                                                    description = if (!autoLoginState.available) {
                                                        stringResource(
                                                            R.string.xmu_auto_login_desc_unavailable,
                                                        )
                                                    } else {
                                                        when (autoLoginState.policy) {
                                                            XmuAutoLoginPolicy.ENABLED ->
                                                                stringResource(
                                                                    R.string.xmu_auto_login_desc_enabled,
                                                                )
                                                            XmuAutoLoginPolicy.BLOCKED ->
                                                                stringResource(
                                                                    R.string.xmu_auto_login_desc_blocked,
                                                                )
                                                            XmuAutoLoginPolicy.USER_LOGGED_OUT ->
                                                                stringResource(
                                                                    R.string.xmu_auto_login_desc_off,
                                                                )
                                                        }
                                                    },
                                                    checked = autoLoginState.policy ==
                                                        XmuAutoLoginPolicy.ENABLED,
                                                    onCheckedChange = onAutoLoginToggle,
                                                    // 未连接时置灰：登录（原生 OAuth）即保存
                                                    // 凭据并自动开启，没有「先开开关再登录」
                                                    enabled =
                                                        !autoLoginBusy &&
                                                            autoLoginState.available &&
                                                            connected,
                                                ),
                                            )
                                        }
                                        if (connected) {
                                            add(
                                                SettingsCardItem.Toggle(
                                                    label =
                                                        stringResource(R.string.xmu_auto_sync_title),
                                                    description =
                                                        stringResource(R.string.xmu_auto_sync_desc),
                                                    checked = dailySyncEnabled,
                                                    onCheckedChange = onDailySyncToggle,
                                                ),
                                            )
                                        }
                                    },
                                )
                            }
                        }

                        // 「备用登录方式」分组：可见 WebView 登录。默认登录走原生 OAuth
                        // （保存凭据，支持自动续期与教务导入）；网页登录不保存密码、存在过期
                        // 风险，也是原生登录遇验证码/页面改版时的自动回退目标
                        CardSection(title = stringResource(R.string.xmu_section_alt_login)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Nav(
                                        label = stringResource(R.string.xmu_webview_login),
                                        description =
                                            stringResource(R.string.xmu_webview_login_desc),
                                        onClick = {
                                            sessionExpired = false
                                            loginVisible = true
                                        },
                                    ),
                                ),
                            )
                        }

                        // 「同步可以做什么」说明分组：两张纯标题 + 完整说明的介绍卡
                        CardSection(title = stringResource(R.string.xmu_section_capabilities)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Custom(
                                        label = stringResource(R.string.xmu_intro_title),
                                        description = stringResource(R.string.xmu_intro_body),
                                    ),
                                ),
                            )
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Custom(
                                        label = stringResource(R.string.xmu_intro_timetable_title),
                                        description = stringResource(R.string.xmu_intro_timetable_body),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_account_sync),
                onBack = onBack,
                // 全屏模拟对话框（FadeOverlayDialog：加载/退出/换号确认）用整屏
                // graphicsLayer(alpha) 做淡入淡出；与顶栏磨砂的 Haze 源层同帧增删时，
                // 部分设备（实测 HyperOS / Android 17）会触发 RenderThread 自引用
                // RenderNode 递归崩溃（SIGSEGV、栈溢出）。对话框在场时顶栏已被遮罩
                // 压暗、磨砂不可见——期间卸掉效果，对话框彻底卸载后再恢复。
                hazeState = if (hazeActive) hazeState else null,
                buttonBackdrop = glassBackdrop,
                freezeKey = freezeKey,
                recordWhen = { hazeActive },
                isTransforming = settingsTransforming,
                titleAlpha = rememberSettingsTitleAlpha(scrollState, overscrollEffect),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .onGloballyPositioned { coords ->
                        topBarHeight = with(density) { coords.size.height.toFloat().toDp() }
                    },
            )

            // 登录浮层：组合序在内容之后 = 画在上层；返回键优先关登录浮层
            if (loginVisible) {
                XmuLoginSheet(
                    onDismiss = { loginVisible = false },
                    onResult = { onLoginResult(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    BackHandler(enabled = loginVisible) {
        loginVisible = false
    }

    // 自动续期凭据浮层（OverlaySheet 自身不接返回键）：未在登录时收起浮层；
    // 登录进行中吞掉返回，避免把整页退掉导致协程取消在半途
    BackHandler(enabled = autoLoginSheetVisible) {
        if (!autoLoginBusy) autoLoginSheetVisible = false
    }

    // ---------- 浮层 ----------

    // 同步加载浮层：点「立即同步」当场压上，prepare 返回后淡出；在途不可关闭
    //（遮罩点击与返回键都不放行，网络请求自带超时兜底）。
    // 组合序在预览浮层之前：结束时预览滑入压在其上，加载卡同步淡出
    if (loadingMounted) {
        FadeOverlayDialog(
            visible = preparing,
            onDismiss = {},
            onDismissed = { loadingMounted = false },
        ) {
            XmuSyncLoadingCard()
        }
    }

    if (previewMounted) {
        XmuSyncPreviewSheet(
            plan = previewPlan,
            visible = previewPlan != null,
            importing = committing,
            onDismiss = { previewPlan = null },
            onDismissed = { previewMounted = false },
            onConfirm = onPreviewConfirm,
        )
    }

    if (logoutMounted) {
        FadeOverlayDialog(
            visible = logoutVisible,
            onDismiss = { logoutVisible = false },
            onDismissed = { logoutMounted = false },
        ) {
            ConfirmDialogCard(
                title = stringResource(R.string.xmu_logout_confirm_title),
                message = stringResource(R.string.xmu_logout_confirm_message),
            ) {
                DialogPillButton(
                    label = stringResource(R.string.settings_cancel),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    enabled = logoutVisible,
                    onClick = { logoutVisible = false },
                )
                DialogPillButton(
                    label = stringResource(R.string.xmu_logout_confirm),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    enabled = logoutVisible,
                    onClick = {
                        logoutVisible = false
                        doLogout()
                    },
                )
            }
        }
    }

    if (switchMounted) {
        FadeOverlayDialog(
            visible = switchVisible,
            onDismiss = { switchVisible = false },
            onDismissed = { switchMounted = false },
        ) {
            ConfirmDialogCard(
                title = stringResource(R.string.xmu_switch_title),
                message = stringResource(R.string.xmu_switch_message),
            ) {
                DialogPillButton(
                    label = stringResource(R.string.xmu_switch_keep_records),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    enabled = switchVisible,
                    onClick = {
                        switchVisible = false
                        onSwitchResolved(false)
                    },
                )
                DialogPillButton(
                    label = stringResource(R.string.xmu_switch_delete_all),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    enabled = switchVisible,
                    onClick = {
                        switchVisible = false
                        onSwitchResolved(true)
                    },
                )
            }
        }
    }

    // 原生登录凭据浮层（默认「去登录」与开启自动续期共用）：输入学号/密码 → 原生登录一次
    if (autoLoginSheetMounted) {
        XmuAutoLoginSheet(
            visible = autoLoginSheetVisible,
            busy = autoLoginBusy,
            error = autoLoginError,
            onDismiss = { autoLoginSheetVisible = false },
            onDismissed = { autoLoginSheetMounted = false },
            onSubmit = onEnableAutoLogin,
        )
    }

    // 关闭自动续期确认：删除已保存密码，保留当前连接
    if (disableAutoMounted) {
        FadeOverlayDialog(
            visible = disableAutoVisible,
            onDismiss = { disableAutoVisible = false },
            onDismissed = { disableAutoMounted = false },
        ) {
            ConfirmDialogCard(
                title = stringResource(R.string.xmu_auto_login_disable_title),
                message = stringResource(R.string.xmu_auto_login_disable_message),
            ) {
                DialogPillButton(
                    label = stringResource(R.string.settings_cancel),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    enabled = disableAutoVisible,
                    onClick = { disableAutoVisible = false },
                )
                DialogPillButton(
                    label = stringResource(R.string.xmu_auto_login_disable_confirm),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    enabled = disableAutoVisible,
                    onClick = {
                        disableAutoVisible = false
                        scope.launch { autoLogin?.disable() }
                    },
                )
            }
        }
    }
}

/**
 * 账户头像圆：[AccountAvatarSize] 圆底取 `primaryContainer` 蓝染，圆内先铺账户名首字
 * （`primary`，20sp SemiBold），有缓存头像时再用 [AsyncImage] 盖满整圆——图片加载中或
 * 加载失败都自然露出首字，不需要额外的占位/错误态。深浅模式随主题走；
 * 只在已连接分支渲染（未连接没有这张卡，圆自然不出现）。
 *
 * 换头像时文件路径不变（`avatar.img` 就地覆盖），所以用[image]的版本号做 `key`：
 * 版本变了整块重建，Coil 才会丢掉旧结果重新解码。
 */
@Composable
private fun AccountAvatar(name: String, image: XmuAvatarImage?) {
    Box(
        modifier = Modifier
            .size(AccountAvatarSize)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val initial = name.trim().firstOrNull()
        if (initial != null) {
            Text(
                text = initial.toString(),
                style = ClassppTextTokens.avatarInitial,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (image != null) {
            key(image.version) {
                AsyncImage(
                    model = image.file,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/** 头像圆直径：56dp，行卡内与文字块垂直居中（行高相应为 56 + 上下内距 14×2） */
private val AccountAvatarSize = 56.dp

/** 同步加载卡：小转圈 + 「正在同步…」，垂直居中；浮层在途不可关闭 */
@Composable
private fun XmuSyncLoadingCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.xmu_syncing),
            style = ClassppTextTokens.dialogMessage,
            color = MaterialTheme.classppColors.secondaryText,
        )
    }
}

/** 同步预览浮层：内容顶部用横幅汇总（随勾选实时变化）与复活计数，按课程分组列新增项（默认全选可取消） */
@Composable
private fun XmuSyncPreviewSheet(
    plan: XmuSyncPlan?,
    visible: Boolean,
    importing: Boolean,
    onDismiss: () -> Unit,
    onDismissed: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    // 系统返回 = 收起预览（取消），不穿透到设置页把「账号与同步」整页退掉。
    // 退场期间 visible 已为 false，返回键让位给页面（同其他浮层的两段式语义）
    BackHandler(enabled = visible, onBack = onDismiss)

    // 退场动画期间保留最后一次非空数据（WakeupImportConfirmDialog 的 stale-content retention）
    var shownPlan by remember { mutableStateOf<XmuSyncPlan?>(null) }
    if (plan != null) shownPlan = plan
    val current = shownPlan

    // 勾选随新 plan 重置为全选
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(current) {
        current?.let { selected = it.newItems.mapTo(mutableSetOf()) { item -> item.todo.id } }
    }

    val summaryFormat = stringResource(R.string.xmu_preview_summary)
    val restoreFormat = stringResource(R.string.xmu_preview_restore)

    OverlaySheet(
        title = stringResource(R.string.xmu_preview_title),
        confirmLabel = stringResource(R.string.xmu_preview_confirm),
        confirmAtEnd = true,
        onConfirm = {
            if (!importing && current != null) onConfirm(selected)
        },
        rightAction = SheetTopAction(
            label = stringResource(R.string.settings_cancel),
            icon = R.drawable.ic_dismiss_circle,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            onClick = onDismiss,
        ),
        onDismiss = onDismiss,
        visible = visible,
        onDismissed = onDismissed,
    ) {
        val p = current ?: return@OverlaySheet
        Column(verticalArrangement = Arrangement.spacedBy(SheetSectionSpacingBetween)) {
            // 顶部汇总：条数随勾选实时变化，SheetInfoBanner 的等宽数字避免整行居中抖动
            SheetInfoBanner(text = summaryFormat.format(selected.size, p.skipped.values.sum()))
            if (p.restoreItems.isNotEmpty()) {
                SheetInfoBanner(text = restoreFormat.format(p.restoreItems.size))
            }
            // 截止时间非空但解析失败：单条不阻断导入，但要让用户看到接口可能已变化。
            // 用可换行的文本块而不是单行横幅，长文案不会被省略号截断。
            val unparseable = p.skipped[SkipReason.DeadlineUnparseable] ?: 0
            if (unparseable > 0) {
                CardSection {
                    Text(
                        text = stringResource(R.string.xmu_deadline_unparseable, unparseable),
                        style = ClassppTextTokens.settingsDescription,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
            if (p.newItems.isEmpty()) {
                CardSection {
                    Text(
                        text = stringResource(R.string.xmu_preview_empty),
                        style = ClassppTextTokens.settingsDescription,
                        color = MaterialTheme.classppColors.secondaryText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            } else {
                // 按课程分组（组间留缝，组内逐条勾选行）；组序 = 远端返回序
                p.newItems.groupBy { item -> item.todo.sourceDetail.orEmpty() }
                    .forEach { (course, items) ->
                        CardSection(title = course.ifEmpty { stringResource(R.string.xmu_source_badge) }) {
                            SettingsCard(
                                items = items.map { item ->
                                    SettingsCardItem.Custom(
                                        label = item.todo.name,
                                        description = deadlineText(item.todo.deadlineDate, item.todo.deadlineMinute),
                                        trailing = {
                                            Checkbox(
                                                checked = item.todo.id in selected,
                                                onCheckedChange = { checked ->
                                                    selected = if (checked) {
                                                        selected + item.todo.id
                                                    } else {
                                                        selected - item.todo.id
                                                    }
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                    }
            }
        }
    }
}

/** 截止预览文本：北京时间口径的「M月d日 HH:mm」（IsoDate + 当天分钟数） */
@Composable
private fun deadlineText(date: IsoDate?, minute: Int?): String {
    if (date == null || minute == null) return ""
    val (_, month, day) = date.yearMonthDay()
    return stringResource(R.string.xmu_deadline_format, month, day, minute / 60, minute % 60)
}

/** 同步时刻展示（设备本地时区的 yyyy-MM-dd HH:mm；数字形态两种语言通用） */
private fun formatSyncTime(millis: Long): String {
    val cal = GregorianCalendar().apply { timeInMillis = millis }
    return "%04d-%02d-%02d %02d:%02d".format(
        cal.get(GregorianCalendar.YEAR),
        cal.get(GregorianCalendar.MONTH) + 1,
        cal.get(GregorianCalendar.DAY_OF_MONTH),
        cal.get(GregorianCalendar.HOUR_OF_DAY),
        cal.get(GregorianCalendar.MINUTE),
    )
}

/** 开启自动续期的兜底超时：登录（60s）+ 首次取数（30s）上限再留余量 */
private const val ENABLE_TIMEOUT_MS = 90_000L
