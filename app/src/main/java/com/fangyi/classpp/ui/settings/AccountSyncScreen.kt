package com.fangyi.classpp.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.AutoRenewState
import com.fangyi.classpp.data.sync.AvatarImage
import com.fangyi.classpp.data.sync.CommitOutcome
import com.fangyi.classpp.data.sync.DailySyncController
import com.fangyi.classpp.data.sync.EnableOutcome
import com.fangyi.classpp.data.sync.LoginError
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.PrepareOutcome
import com.fangyi.classpp.data.sync.SchoolSession
import com.fangyi.classpp.data.sync.SessionSlot
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.SyncError
import com.fangyi.classpp.data.sync.SyncPlan
import com.fangyi.classpp.data.sync.webview.SchoolCookieCleaner
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
import com.fangyi.classpp.ui.schools.SchoolUi
import com.fangyi.classpp.ui.schools.WebViewLoginOutcome
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.fangyi.classpp.ui.theme.isDarkTheme
import com.fangyi.classpp.ui.theme.themeAccent
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
 * 账号与同步子页（设置 → 账号与同步）：**能力驱动**的学校账号入口，不认学校 id。
 *
 * [SessionSlot] 三态：加载中显示转圈、装配失败显示错误卡、就绪按会话能力渲染：
 * - 默认登录入口：`autoRenew != null && available` → 通用凭据浮层（[CredentialLoginSheet]）；
 *   否则有 WebView 登录（且 UI 已绑定）→ 学校网页登录浮层；两者皆无 = 不可达（注册表校验
 *   至少一种登录方式）；
 * - 「备用登录方式」分组：原生登录与 WebView 登录**都**支持时显示（`available=false` 仍显示，
 *   默认入口回退 WebView，用户仍可直达备用入口）；
 * - 「自动续期」开关：仅 `autoRenew != null`；「每日同步」开关：已连接时；
 * - 头部/介绍/错误文案：学校专属部分来自 [SchoolSession.spec]，其余走中性键与旧 `xmu_*`
 *   无校名键（资源名不影响显示，见指导 §2.3 文案纪律）；
 * - 退出登录只清本校数据：同步记录按 source/id 前缀过滤（会话实现保证），网页 cookie 按
 *   [com.fangyi.classpp.data.sync.WebViewLoginSpec.allowedHosts] 逐域置过期，**不清**
 *   WebStorage、不调用 `removeAllCookies`（跨校隔离，指导 §2.4 第 10 条）。
 *
 * 页面状态（预览计划、待登录结果、各类浮层开关）随活跃学校重置：[key] 以学校 id 为键，
 * 切校不会把 A 校的 plan 提交给 B 校（指导 §3.1 切校状态重置；plan 的会话认领由
 * [com.fangyi.classpp.data.sync.SyncService] 保证）。
 *
 * 同步一律走「预览 → 确认导入」（[SyncPreviewSheet]，按课程分组、默认全选）；错误按类
 * 提示（过期给横幅 + 重登入口，**不把所有失败说成登录过期**）。原生登录遇验证码/页面
 * 改版/取数未建立时收起浮层自动转网页登录（学校支持网页登录时），其余错误留在浮层内
 * 允许重试。
 */
@Composable
fun AccountSyncScreen(
    slot: SessionSlot,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    schoolUi: SchoolUi? = null,
    dailySync: DailySyncController? = null,
    settingsTransforming: () -> Boolean = { false },
) {
    when (slot) {
        SessionSlot.Loading -> AccountSyncPlaceholder(
            onBack = onBack,
            modifier = modifier,
            settingsTransforming = settingsTransforming,
        )

        is SessionSlot.Failed -> AccountSyncPlaceholder(
            onBack = onBack,
            modifier = modifier,
            settingsTransforming = settingsTransforming,
            failed = true,
        )

        is SessionSlot.Ready -> key(slot.session.spec.id) {
            AccountSyncContent(
                session = slot.session,
                onBack = onBack,
                modifier = modifier,
                schoolUi = schoolUi,
                dailySync = dailySync,
                settingsTransforming = settingsTransforming,
            )
        }
    }
}

/** 加载中/装配失败的极简页：顶栏保持一致，正文只给状态（不提供假操作） */
@Composable
private fun AccountSyncPlaceholder(
    onBack: () -> Unit,
    modifier: Modifier,
    settingsTransforming: () -> Boolean,
    failed: Boolean = false,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val hazeState = rememberHazeState()
            val glassBackdrop = rememberSettingsGlassBackdrop()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState),
                contentAlignment = Alignment.Center,
            ) {
                if (failed) {
                    Text(
                        text = stringResource(R.string.account_sync_load_failed),
                        style = ClassppTextTokens.settingsDescription,
                        color = MaterialTheme.classppColors.secondaryText,
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
            SettingsTopBar(
                title = stringResource(R.string.settings_account_sync),
                onBack = onBack,
                hazeState = hazeState,
                buttonBackdrop = glassBackdrop,
                recordWhen = { true },
                isTransforming = settingsTransforming,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

/**
 * 已就绪的账号与同步页：所有页内状态与浮层都在这里（外层按学校 id `key` 重置）。
 */
@Composable
private fun AccountSyncContent(
    session: SchoolSession,
    onBack: () -> Unit,
    modifier: Modifier,
    schoolUi: SchoolUi?,
    dailySync: DailySyncController?,
    settingsTransforming: () -> Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val overlayActive = LocalPageOverlayActive.current
    val spec = session.spec
    val sync = session.sync
    val autoRenew = session.autoRenew
    // 网页登录需要「学校声明了 WebView 登录」+「UI 已绑定」两个条件；未绑定的纯声明学校
    // 暂不显示网页登录入口（Phase 4 的 GenericSchoolUi 会补齐缺省绑定）
    val canWebViewLogin = session.webViewLogin != null && schoolUi != null
    val canNativeLogin = autoRenew != null

    // 会话过期横幅：只在探活/登录明确判定过期时点亮；重新登录或探活成功即熄灭
    var sessionExpired by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }

    // 同步加载浮层（点「立即同步」立刻压上）：两段式挂载——preparing 置 false 后仍留在
    // 组合里播淡出，onDismissed 才卸载；淡出期间预览浮层滑入、压在本浮层之上
    var loadingMounted by remember { mutableStateOf(false) }
    if (preparing) loadingMounted = true

    // 同步预览（两段式挂载）：plan 非 null 即可见；onDismiss 清 plan（转场退场），播完才卸载
    var previewPlan by remember { mutableStateOf<SyncPlan?>(null) }
    var previewMounted by remember { mutableStateOf(false) }
    if (previewPlan != null) previewMounted = true

    // 登录浮层与登录结果（结果里带两段响应原文，账号切换确认后要用）。
    // 原文都是字符串，用 Saver 一并存进 savedInstanceState：旋转后换号确认框的按钮仍可用，
    // 不会出现「框还在、pendingLogin 已丢」的死对话框。
    var loginVisible by rememberSaveable { mutableStateOf(false) }
    var pendingLogin by rememberSaveable(
        stateSaver = Saver<LoginRaw?, ArrayList<String>>(
            save = { ok -> ok?.let { arrayListOf(it.profileRaw, it.todosRaw) } },
            restore = { saved ->
                saved.takeIf { it.size == 2 }?.let { LoginRaw(it[0], it[1]) }
            },
        ),
    ) { mutableStateOf<LoginRaw?>(null) }

    // 退出登录：一次确认（保留已同步待办）；账号切换确认。
    // 两个确认框都走两段式挂载：visible 置 false 后仍留在组合里播淡出，onDismissed 才卸载
    var logoutVisible by rememberSaveable { mutableStateOf(false) }
    var logoutMounted by remember { mutableStateOf(false) }
    if (logoutVisible) logoutMounted = true
    var switchVisible by rememberSaveable { mutableStateOf(false) }
    var switchMounted by remember { mutableStateOf(false) }
    if (switchVisible) switchMounted = true

    // 自动续期（方案 B）：策略状态来自会话。凭据浮层不 saveable——密码只活在本次
    // 组合里，旋转后重输可接受，避免密码进入 savedInstanceState。
    // fallback flow 保证 collectAsState 是无条件 composable 调用（条件调用会破坏槽位表）。
    val fallbackAutoRenewState = remember { MutableStateFlow(AutoRenewState()) }
    val autoRenewState = (autoRenew?.state ?: fallbackAutoRenewState).collectAsState().value
    var autoLoginSheetVisible by remember { mutableStateOf(false) }
    var autoLoginSheetMounted by remember { mutableStateOf(false) }
    if (autoLoginSheetVisible) autoLoginSheetMounted = true
    var autoLoginBusy by remember { mutableStateOf(false) }
    var autoLoginError by remember { mutableStateOf<LoginError?>(null) }
    var disableAutoVisible by remember { mutableStateOf(false) }
    var disableAutoMounted by remember { mutableStateOf(false) }
    if (disableAutoVisible) disableAutoMounted = true

    // 每日自动同步：开关状态来自会话（与后台 receiver 同一存储）；切换即落盘 + 重排闹钟
    val dailySyncEnabled by session.dailySync.enabled.collectAsState()

    // 错误文案在组合期解析（回调里只引用，不经 LocalContext 查资源——lint 惯例）。
    // 含学校/服务名措辞的条目由 spec 覆盖，其余复用无校名的旧键或中性键
    val errorTexts: Map<SyncError, String> = mapOf(
        SyncError.SessionExpired to stringResource(R.string.xmu_error_expired),
        SyncError.ServiceBusy to stringResource(spec.serviceBusyRes ?: R.string.sync_error_busy),
        SyncError.NetworkError to stringResource(R.string.xmu_error_network),
        SyncError.Timeout to stringResource(R.string.xmu_error_timeout),
        SyncError.FormatChanged to stringResource(R.string.xmu_error_format),
        SyncError.AccountChanged to stringResource(
            spec.accountChangedRes ?: R.string.sync_error_account_changed,
        ),
        SyncError.TooSoon to stringResource(R.string.xmu_error_too_soon),
        SyncError.PersistFailed to stringResource(R.string.xmu_error_persist),
    )
    val genericErrorText = stringResource(R.string.xmu_error_generic)
    // 自动续期失败文案（浮层横幅与 Toast 共用；失败时两处都提示，避免只滚到输入框而漏看）
    val autoLoginErrorTexts: Map<LoginError, String> = mapOf(
        LoginError.NeedCaptcha to stringResource(R.string.xmu_auto_login_need_captcha),
        LoginError.InvalidCredentials to stringResource(
            spec.invalidCredentialsRes ?: R.string.sync_login_invalid_credentials,
        ),
        LoginError.StorageUnavailable to stringResource(R.string.xmu_auto_login_storage_unavailable),
        LoginError.ServiceBusy to stringResource(spec.serviceBusyRes ?: R.string.sync_error_busy),
        LoginError.NetworkError to stringResource(R.string.xmu_error_network),
        LoginError.Timeout to stringResource(R.string.xmu_error_timeout),
        LoginError.FormatChanged to stringResource(R.string.xmu_error_format),
        LoginError.SessionNotEstablished to stringResource(
            spec.sessionNotEstablishedRes ?: R.string.sync_login_not_established,
        ),
    )
    val upToDateText = stringResource(R.string.xmu_up_to_date)
    val importDoneFormat = stringResource(R.string.xmu_import_done)
    val importFailedText = stringResource(R.string.xmu_error_import)
    val persistFailedText = stringResource(R.string.xmu_error_persist)
    val logoutDoneText = stringResource(R.string.xmu_logout_done)
    val deadlineWarningFormat = stringResource(R.string.xmu_deadline_unparseable)

    val showError: (SyncError) -> Unit = { error ->
        AppToasts.show(context, errorTexts[error] ?: genericErrorText)
    }

    // 无新增、无恢复 → 预览里没有任何可看得见的内容，不弹浮层；但仍要补写映射基线与
    // lastSyncAt（备份恢复/「仅退出」后重新登录靠这一步重建「上次同步」）。落盘失败不能报
    // 「已是最新」，否则用户以为同步成功、实际映射没保存。全部条目都因截止格式被跳过时同理：
    // 不能说「已是最新」，否则接口改版会被当成同步成功。
    suspend fun showPlanOrUpToDate(plan: SyncPlan) {
        if (plan.newItems.isNotEmpty() || plan.restoreCount > 0) {
            previewPlan = plan
            return
        }
        val unparseable = plan.deadlineUnparseableCount
        if (sync.markSynced(plan)) {
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
        if (!preparing) {
            // 立即点亮加载浮层：网络拉取期间用户看到转圈，而不是卡在原页面
            preparing = true
            scope.launch {
                try {
                    when (val outcome = sync.prepareSync()) {
                        is PrepareOutcome.Ready -> {
                            sessionExpired = false
                            showPlanOrUpToDate(outcome.plan)
                        }
                        is PrepareOutcome.Failed -> {
                            if (outcome.error == SyncError.SessionExpired) sessionExpired = true
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

    fun onLoginResult(result: WebViewLoginOutcome) {
        loginVisible = false
        when (result) {
            is WebViewLoginOutcome.Ok -> scope.launch {
                try {
                    // 网页登录不保存密码：此前若有原生登录留下的凭据，一并清除（与
                    // 「WebView 登录不保存账号密码」的定位一致）；CookieManager 里刚建立的
                    // 会话不受影响，关闭续期后同步回退到 WebView 路径正好复用它
                    autoRenew?.disable()
                    when (val outcome = sync.prepareFromLogin(result.raw)) {
                        is PrepareOutcome.Ready -> {
                            sessionExpired = false
                            showPlanOrUpToDate(outcome.plan)
                        }
                        is PrepareOutcome.Failed -> when (outcome.error) {
                            SyncError.AccountChanged -> {
                                pendingLogin = result.raw
                                switchVisible = true
                            }
                            else -> {
                                if (outcome.error == SyncError.SessionExpired) sessionExpired = true
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
            is WebViewLoginOutcome.Failed -> {
                if (result.error == SyncError.SessionExpired) sessionExpired = true
                showError(result.error)
            }
        }
    }

    // 自动续期：打开开关 → 输入凭据并原生登录一次；关闭 → 确认后删除已保存密码。
    // 关闭只删凭据，不动当前连接（原生登录时已把 cookie 镜像进 WebView）。
    val onAutoLoginToggle: (Boolean) -> Unit = { wantOn ->
        SyncDebugLog.d("autoLogin toggle wantOn=$wantOn policy=${autoRenewState.policy}")
        if (wantOn) {
            if (!autoLoginBusy) {
                autoLoginError = null
                autoLoginSheetVisible = true
            }
        } else {
            disableAutoVisible = true
        }
    }

    // 每日自动同步开关：先落盘再调度（scheduleNext 读开关决定排或取消）；开启时
    // 立即跑一次补漏判定——当天 6:00 已过且还没同步过就当场同步，与「进 App 补漏」
    // 同一条静默路径（导入不问确认、失败只留日志）
    val onDailySyncToggle: (Boolean) -> Unit = { wantOn ->
        session.dailySync.setEnabled(wantOn)
        scope.launch {
            if (wantOn) dailySync?.runIfDue(context)
            dailySync?.scheduleNext(context)
        }
    }

    val onEnableAutoLogin: (String, String) -> Unit = { username, password ->
        // 诊断点：点确认后第一行日志。若这行都没出现 = 点击没到达本回调（UI 层问题）
        SyncDebugLog.d("enable submit received manager=${autoRenew != null} busy=$autoLoginBusy")
        val manager = autoRenew
        if (manager != null && !autoLoginBusy) {
            autoLoginBusy = true
            autoLoginError = null
            scope.launch {
                try {
                    // 兜底超时：任何环节挂死，busy 态也在 90s 内退出并给出提示
                    val outcome = withTimeout(ENABLE_TIMEOUT_MS) {
                        manager.enable(username, password)
                    }
                    SyncDebugLog.d("enable outcome=${outcome::class.simpleName}")
                    when (outcome) {
                        is EnableOutcome.Ready -> {
                            autoLoginSheetVisible = false
                            // 与网页登录共用同一条「预览 → 导入」路径
                            when (val prepared = sync.prepareFromLogin(outcome.raw)) {
                                is PrepareOutcome.Ready -> {
                                    sessionExpired = false
                                    showPlanOrUpToDate(prepared.plan)
                                }
                                is PrepareOutcome.Failed -> when (prepared.error) {
                                    // 原生登录进了另一个账号：与网页登录同一套换号确认；
                                    // 确认后 autoRenew.disable() 会清掉刚保存的凭据
                                    SyncError.AccountChanged -> {
                                        pendingLogin = outcome.raw
                                        switchVisible = true
                                    }
                                    else -> {
                                        if (prepared.error == SyncError.SessionExpired) {
                                            sessionExpired = true
                                        }
                                        showError(prepared.error)
                                    }
                                }
                            }
                        }
                        is EnableOutcome.Failed -> {
                            SyncDebugLog.d("enable failed error=${outcome.error}")
                            AppToasts.show(
                                context,
                                autoLoginErrorTexts[outcome.error] ?: genericErrorText,
                            )
                            // 验证码/页面改版/登录后仍读不到数据：原生链路走不通，收起浮层
                            // 自动转网页登录（浮层先关，网页登录浮层叠上）；不支持网页登录的
                            // 学校留在浮层内横幅提示（不让用户点进死路）；其余错误（密码
                            // 错误、存储不可用、网络等）同样留在浮层内便于直接重试
                            when (outcome.error) {
                                LoginError.NeedCaptcha,
                                LoginError.FormatChanged,
                                LoginError.SessionNotEstablished,
                                -> if (canWebViewLogin) {
                                    autoLoginSheetVisible = false
                                    loginVisible = true
                                } else {
                                    autoLoginError = outcome.error
                                }
                                else -> autoLoginError = outcome.error
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    SyncDebugLog.d("enable timeout")
                    autoLoginError = LoginError.Timeout
                    AppToasts.show(context, context.getString(R.string.xmu_error_timeout))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SyncDebugLog.d("enable crashed ${e::class.simpleName}")
                    autoLoginError = LoginError.NetworkError
                } finally {
                    autoLoginBusy = false
                }
            }
        }
    }

    val doLogout: () -> Unit = {
        scope.launch {
            // 退出登录 = 连自动续期凭据一起清掉，否则下次同步会再次自动登录
            autoRenew?.disable()
            // 只清本校同步数据（会话按 source + id 前缀过滤，跨校隔离）
            sync.resetSyncData(deleteSyncedTodos = false)
            // 跨校边界：只让本校声明域的网页 cookie 过期；不清 WebStorage、不清全局 cookie
            //（全局清理会登出其他学校的网页登录，见指导 §2.4 第 10 条 / §2.6）
            SchoolCookieCleaner.clearForHosts(session.webViewLogin?.allowedHosts.orEmpty())
            sessionExpired = false
            pendingLogin = null
            AppToasts.show(context, logoutDoneText)
        }
    }

    val onSwitchResolved: (Boolean) -> Unit = { deleteSyncedTodos ->
        val pending = pendingLogin
        if (pending != null) {
            scope.launch {
                // 换号时旧账号的自动续期凭据必须一起清掉，否则下次会登回旧账号
                autoRenew?.disable()
                sync.resetSyncData(deleteSyncedTodos)
                when (val outcome = sync.prepareFromLogin(pending)) {
                    is PrepareOutcome.Ready -> showPlanOrUpToDate(outcome.plan)
                    is PrepareOutcome.Failed -> showError(outcome.error)
                }
            }
        }
        pendingLogin = null
    }

    val onPreviewConfirm: (Set<String>) -> Unit = { selected ->
        val plan = previewPlan
        if (plan != null && !committing) {
            committing = true
            scope.launch {
                when (val r = sync.commitSync(plan, selected)) {
                    is CommitOutcome.Done -> {
                        AppToasts.show(
                            context,
                            importDoneFormat.format(r.report.imported, r.report.restored),
                        )
                        previewPlan = null
                    }
                    is CommitOutcome.Failed -> {
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
        // 统一不避让横向 inset：内容全宽出血（左右顶到屏幕边缘）；纵向由内容与顶栏自理
        contentWindowInsets = WindowInsets(0),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            val hazeState = rememberHazeState()
            val glassBackdrop = rememberSettingsGlassBackdrop()
            var topBarHeight by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            // 滚动状态上提：冻结键要读滚动位置
            val scrollState = rememberScrollState()
            // 标题渐显要把过滚动算进内容位移（可滚页面读边缘橡皮筋，不足一屏读整页橡皮筋）
            val overscrollEffect = rememberOffsetOverscrollEffect()
            // 预热帧也要挂 Haze；隐藏态不挂、也不录制（保留上一次活跃期的就位帧）。
            // 对话框在场不再卸磨砂：那曾是 HyperOS 崩溃的退让，Behind 采样确认修复后
            // 已移除（见下方 SettingsTopBar 注释）
            val hazeActive = overlayActive || LocalPageOverlayPrewarm.current
            val darkTheme = MaterialTheme.isDarkTheme
            val themeAccent = MaterialTheme.themeAccent
            val freezeEpoch = LocalSettingsFreezeEpoch.current
            // 冻结键：主题/顶栏高度/滚动位置/账号与对话框状态任一变化都回退实时绘制
            val freezeKey: () -> Any = remember(
                darkTheme,
                themeAccent,
                scrollState,
                sync,
                freezeEpoch,
            ) {
                {
                    val state = sync.state.value
                    SettingsTopBarFreezeKey(
                        page = SettingsPage.AccountSync,
                        darkTheme = darkTheme,
                        topBarHeight = topBarHeight,
                        scroll = scrollState.value,
                        epoch = freezeEpoch,
                        inputs = listOf(
                            themeAccent,
                            state.uid,
                            state.accountName,
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

            val syncState by sync.state.collectAsState()
            // 已缓存的头像（null = 还没抓到/已退出登录）：随同步下载、退出登录更新
            val avatarImage by sync.avatarImage.collectAsState()
            val connected = syncState.uid.isNotEmpty()
            val showLoginEntry = canNativeLogin || canWebViewLogin

            SettingsScrollContent(
                scrollState = scrollState,
                overscrollEffect = overscrollEffect,
                hazeState = hazeState,
                glassBackdrop = glassBackdrop,
                topPadding = topBarHeight + TopBarContentGap,
                bottomPadding = navBarPadding + SheetBottomSlack,
            ) {
                if (sessionExpired) {
                    SheetInfoBanner(text = stringResource(R.string.xmu_error_expired))
                }

                // 顶部无题分组：头部卡 + 账号状态/登录入口，组内卡间距 10dp
                CardSection {
                    SettingsInfoHeaderCard(
                        icon = R.drawable.ic_arrow_sync_circle,
                        title = stringResource(spec.headerTitleRes),
                        description = stringResource(spec.headerDescriptionRes),
                    )

                    if (!connected) {
                        if (showLoginEntry) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Nav(
                                        label = stringResource(R.string.xmu_login_button),
                                        onClick = {
                                            sessionExpired = false
                                            if (
                                                canNativeLogin &&
                                                autoRenewState.available
                                            ) {
                                                // 默认原生 OAuth 登录：输账号/密码，成功即
                                                // 保存凭据并开启自动续期
                                                autoLoginError = null
                                                autoLoginSheetVisible = true
                                            } else if (canWebViewLogin) {
                                                // 无原生登录、或密钥库不可用：直接走网页登录
                                                loginVisible = true
                                            }
                                        },
                                    ),
                                ),
                            )
                        }
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
                                    // 头像圆只在已连接分支出现：未连接时这张卡不存在，圆随之不渲染。
                                    // 放尾部槽、与文字块留 12dp（对齐原先左侧头像的行卡图标↔文本间距）
                                    trailing = {
                                        Spacer(Modifier.width(12.dp))
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
                if (canNativeLogin || connected) {
                    CardSection(
                        title = stringResource(R.string.xmu_section_auto_login_sync),
                    ) {
                        SettingsCard(
                            items = buildList {
                                if (autoRenew != null) {
                                    add(
                                        SettingsCardItem.Toggle(
                                            label = stringResource(R.string.xmu_auto_login_title),
                                            // 正常态（开/关）共用同一句描述；
                                            // 暂停/不可用保留原因与操作提示
                                            description = when {
                                                !autoRenewState.available -> stringResource(
                                                    R.string.xmu_auto_login_desc_unavailable,
                                                )
                                                autoRenewState.policy ==
                                                    AutoRenewPolicy.BLOCKED -> stringResource(
                                                    R.string.xmu_auto_login_desc_blocked,
                                                )
                                                else -> stringResource(
                                                    R.string.xmu_auto_login_desc,
                                                )
                                            },
                                            checked = autoRenewState.policy ==
                                                AutoRenewPolicy.ENABLED,
                                            onCheckedChange = onAutoLoginToggle,
                                            // 未连接时置灰：登录（原生 OAuth）即保存
                                            // 凭据并自动开启，没有「先开开关再登录」
                                            enabled = !autoLoginBusy &&
                                                autoRenewState.available &&
                                                connected,
                                        ),
                                    )
                                }
                                if (connected) {
                                    add(
                                        SettingsCardItem.Toggle(
                                            label = stringResource(R.string.xmu_auto_sync_title),
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
                // 风险，也是原生登录遇验证码/页面改版时的自动回退目标。
                // 仅「原生 + 网页都支持」时显示（`available=false` 仍显示，见矩阵）
                if (canNativeLogin && canWebViewLogin) {
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
                }

                // 「同步可以做什么」说明分组：两张纯标题 + 完整说明的介绍卡。
                // 教务导入是可选能力：学校未声明（或未给介绍卡）时整卡隐藏
                CardSection(title = stringResource(R.string.xmu_section_capabilities)) {
                    SettingsCard(
                        items = listOf(
                            SettingsCardItem.Custom(
                                label = stringResource(spec.introTitleRes),
                                description = stringResource(spec.introBodyRes),
                            ),
                        ),
                    )
                    spec.timetableIntro?.let { intro ->
                        SettingsCard(
                            items = listOf(
                                SettingsCardItem.Custom(
                                    label = stringResource(intro.titleRes),
                                    description = stringResource(intro.bodyRes),
                                ),
                            ),
                        )
                    }
                }
            }

            SettingsTopBar(
                title = stringResource(R.string.settings_account_sync),
                onBack = onBack,
                // 崩溃与修复史：全屏模拟对话框（FadeOverlayDialog：加载/退出/换号/关
                // 自动同步确认）的整屏 graphicsLayer(alpha) 与顶栏磨砂的 Haze 源层同帧
                // 增删时，部分设备（实测 HyperOS / Android 17）曾触发 RenderThread 自引用
                // RenderNode 递归崩溃（SIGSEGV、栈溢出），当时对话框在场就卸掉磨砂、
                // 彻底卸载后再恢复。根因是采样输入 HazeSourceSelection.All 无视 z 序采样，
                // 统一改 Behind（f958dbb）后真机确认修复，卸载机制随之移除——对话框在场
                // 时磨砂保持挂载（顶栏已被遮罩压暗，观感无差）。覆盖层隐藏时的卸载保留，
                // 属转场冻结体系的共享约定（见 SettingsTopBar KDoc）。
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
            if (loginVisible && schoolUi != null) {
                schoolUi.WebViewLoginSheet(
                    onDismiss = { loginVisible = false },
                    onResult = { onLoginResult(it) },
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
            SyncLoadingCard()
        }
    }

    if (previewMounted) {
        SyncPreviewSheet(
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
                message = stringResource(
                    spec.logoutMessageRes ?: R.string.sync_logout_confirm_message,
                ),
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
                message = stringResource(
                    spec.switchMessageRes ?: R.string.sync_switch_message,
                ),
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

    // 原生登录凭据浮层（默认「去登录」与开启自动续期共用）：输入账号/密码 → 原生登录一次
    if (autoLoginSheetMounted) {
        CredentialLoginSheet(
            spec = spec,
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
                message = stringResource(
                    spec.autoLoginDisableMessageRes ?: R.string.sync_auto_login_disable_message,
                ),
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
                        scope.launch { autoRenew?.disable() }
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
private fun AccountAvatar(name: String, image: AvatarImage?) {
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

/** 头像圆直径：52dp，行卡内与文字块垂直居中*/
private val AccountAvatarSize = 52.dp

/** 同步加载卡：小转圈 + 「正在同步…」，垂直居中；浮层在途不可关闭 */
@Composable
private fun SyncLoadingCard() {
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
private fun SyncPreviewSheet(
    plan: SyncPlan?,
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
    var shownPlan by remember { mutableStateOf<SyncPlan?>(null) }
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
            SheetInfoBanner(text = summaryFormat.format(selected.size, p.skippedTotal))
            if (p.restoreCount > 0) {
                SheetInfoBanner(text = restoreFormat.format(p.restoreCount))
            }
            // 截止时间非空但解析失败：单条不阻断导入，但要让用户看到接口可能已变化。
            // 用可换行的文本块而不是单行横幅，长文案不会被省略号截断。
            if (p.deadlineUnparseableCount > 0) {
                CardSection {
                    Text(
                        text = stringResource(
                            R.string.xmu_deadline_unparseable,
                            p.deadlineUnparseableCount,
                        ),
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
                // 按课程分组（组间留缝，组内逐条勾选行）；组序 = 远端返回序。
                // 分组键是归一化的 todo.sourceDetail（与改造前一致；courseName 不读）
                p.newItems.groupBy { item -> item.todo.sourceDetail.orEmpty() }
                    .forEach { (course, items) ->
                        CardSection(
                            title = course.ifEmpty { stringResource(R.string.xmu_source_badge) },
                        ) {
                            SettingsCard(
                                items = items.map { item ->
                                    SettingsCardItem.Custom(
                                        label = item.todo.name,
                                        description = deadlineText(
                                            item.todo.deadlineDate,
                                            item.todo.deadlineMinute,
                                        ),
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
    return "%d/%d/%d %d:%02d".format(
        cal.get(GregorianCalendar.YEAR),
        cal.get(GregorianCalendar.MONTH) + 1,
        cal.get(GregorianCalendar.DAY_OF_MONTH),
        cal.get(GregorianCalendar.HOUR_OF_DAY),
        cal.get(GregorianCalendar.MINUTE),
    )
}

/** 开启自动续期的兜底超时：登录（60s）+ 首次取数（30s）上限再留余量 */
private const val ENABLE_TIMEOUT_MS = 90_000L
