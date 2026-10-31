package com.fangyi.classpp.ui.settings

import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fangyi.classpp.AppToasts
import com.fangyi.classpp.R
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.xmu.XmuCommitOutcome
import com.fangyi.classpp.data.xmu.XmuFetchResult
import com.fangyi.classpp.data.xmu.XmuPrepareOutcome
import com.fangyi.classpp.data.xmu.XmuSyncError
import com.fangyi.classpp.data.xmu.XmuSyncPlan
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
import com.fangyi.classpp.ui.motion.rubberBandVerticalScroll
import com.fangyi.classpp.ui.theme.ClassppTextTokens
import com.fangyi.classpp.ui.theme.PageHorizontalSpacing
import com.fangyi.classpp.ui.theme.SettingsSectionSpacing
import com.fangyi.classpp.ui.theme.SheetSectionSpacingBetween
import com.fangyi.classpp.ui.theme.classppColors
import com.fangyi.classpp.ui.theme.classppTextStyles
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.util.GregorianCalendar
import kotlinx.coroutines.launch

/**
 * 账号与同步子页（设置 → 账号与同步）：课程中心待办同步的唯一入口。
 *
 * 结构（指导 §7）：未连接 → 隐私说明卡 +「去登录」（全屏 WebView 浮层 [XmuLoginSheet]，
 * 官方统一身份认证页完成登录，App 不接触密码）；已连接 → 连接状态卡（姓名 + 上次同步
 * 时间）+「立即同步」+「退出登录」。同步一律走「预览 → 确认导入」（[XmuSyncPreviewSheet]，
 * 按课程分组、默认全选）；错误按类提示（过期给横幅 + 重登入口，**不把所有失败说成登录
 * 过期**）。退出登录两档：仅清登录态与同步记录 / 连课程中心导入的待办一并删除。
 *
 * [repository] = null（容器未装配完）→ 加载指示。在途标记与同步预览用 remember 非
 * saveable——旋转/进程重建时协程一并取消、导入就地作废（同 Wakeup 导入的取舍）。
 */
@Composable
fun XmuSyncScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: XmuSyncRepository? = null,
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

    // 登录浮层与登录结果（结果里带两段响应原文，账号切换确认后要用）
    var loginVisible by rememberSaveable { mutableStateOf(false) }
    var pendingLogin by remember { mutableStateOf<XmuFetchResult.Ok?>(null) }

    // 退出登录：一次确认（保留已同步待办）；账号切换确认。
    // 两个确认框都走两段式挂载：visible 置 false 后仍留在组合里播淡出，onDismissed 才卸载
    var logoutVisible by rememberSaveable { mutableStateOf(false) }
    var logoutMounted by remember { mutableStateOf(false) }
    if (logoutVisible) logoutMounted = true
    var switchVisible by rememberSaveable { mutableStateOf(false) }
    var switchMounted by remember { mutableStateOf(false) }
    if (switchVisible) switchMounted = true

    // 错误文案在组合期解析（回调里只引用，不经 LocalContext 查资源——lint 惯例）
    val errorTexts = remember {
        mapOf(
            XmuSyncError.SessionExpired to R.string.xmu_error_expired,
            XmuSyncError.ServiceBusy to R.string.xmu_error_busy,
            XmuSyncError.NetworkError to R.string.xmu_error_network,
            XmuSyncError.Timeout to R.string.xmu_error_timeout,
            XmuSyncError.FormatChanged to R.string.xmu_error_format,
            XmuSyncError.TooSoon to R.string.xmu_error_too_soon,
        )
    }
    val genericErrorText = stringResource(R.string.xmu_error_generic)
    val upToDateText = stringResource(R.string.xmu_up_to_date)
    val importDoneFormat = stringResource(R.string.xmu_import_done)
    val importFailedText = stringResource(R.string.xmu_error_import)
    val logoutDoneText = stringResource(R.string.xmu_logout_done)

    val showError: (XmuSyncError) -> Unit = { error ->
        AppToasts.show(
            context,
            errorTexts[error]?.let { context.getString(it) } ?: genericErrorText,
        )
    }

    // 无新增、无恢复 → 预览里没有任何可看得见的内容，不弹浮层，只提示已是最新
    val showPlanOrUpToDate: (XmuSyncPlan) -> Unit = { plan ->
        if (plan.newItems.isEmpty() && plan.restoreItems.isEmpty()) {
            AppToasts.show(context, upToDateText)
        } else {
            previewPlan = plan
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
        }
    }

    val doLogout: () -> Unit = {
        scope.launch {
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
                    is XmuCommitOutcome.Failed -> AppToasts.show(context, importFailedText)
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
                            .rubberBandVerticalScroll(rememberScrollState())
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
                                                loginVisible = true
                                            },
                                        ),
                                    ),
                                )
                            } else {
                                SettingsCard(
                                    items = listOf(
                                        SettingsCardItem.Custom(
                                            label = if (syncState.accountName.isBlank()) {
                                                stringResource(R.string.xmu_connected_label)
                                            } else {
                                                stringResource(R.string.xmu_connected_name, syncState.accountName)
                                            },
                                            description = if (syncState.lastSyncAtMillis > 0L) {
                                                stringResource(
                                                    R.string.xmu_last_sync_at,
                                                    formatSyncTime(syncState.lastSyncAtMillis),
                                                )
                                            } else {
                                                stringResource(R.string.xmu_last_sync_never)
                                            },
                                            trailing = {},
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

                        // 「同步可以做什么」说明分组：沿用原介绍卡（标题 + 完整说明）
                        CardSection(title = stringResource(R.string.xmu_section_capabilities)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Custom(
                                        label = stringResource(R.string.xmu_intro_title),
                                        description = stringResource(R.string.xmu_intro_body),
                                        trailing = {},
                                    ),
                                ),
                            )
                        }

                        // 「选择学校」分组：当前学校展示行，点击未来呼出学校选择浮层（暂不做，入口先占位）
                        CardSection(title = stringResource(R.string.xmu_section_school)) {
                            SettingsCard(
                                items = listOf(
                                    SettingsCardItem.Nav(
                                        label = stringResource(R.string.xmu_school_current),
                                        value = stringResource(R.string.xmu_school_xmu),
                                        onClick = {},
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
                hazeState = if (overlayActive) hazeState else null,
                buttonBackdrop = glassBackdrop,
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
}

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
            label = stringResource(R.string.switcher_close),
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
