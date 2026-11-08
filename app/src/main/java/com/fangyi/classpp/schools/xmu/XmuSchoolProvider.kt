package com.fangyi.classpp.schools.xmu

import android.content.Context
import android.util.Base64
import android.webkit.CookieManager
import com.fangyi.classpp.R
import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.AutoRenewService
import com.fangyi.classpp.data.sync.AutoRenewState
import com.fangyi.classpp.data.sync.AvatarImage
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.CommitOutcome
import com.fangyi.classpp.data.sync.DailySyncToggle
import com.fangyi.classpp.data.sync.EnableOutcome
import com.fangyi.classpp.data.sync.ImportBlockReason
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.ImportSemester
import com.fangyi.classpp.data.sync.LoginError
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.PrepareOutcome
import com.fangyi.classpp.data.sync.ScheduleImportService
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolSession
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SemestersOutcome
import com.fangyi.classpp.data.sync.SessionDeps
import com.fangyi.classpp.data.sync.SyncError
import com.fangyi.classpp.data.sync.SyncPlan
import com.fangyi.classpp.data.sync.SyncPlanItem
import com.fangyi.classpp.data.sync.SyncReport
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.data.sync.SyncState
import com.fangyi.classpp.data.sync.TimetableIntro
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.combineState
import com.fangyi.classpp.data.sync.mapState
import com.fangyi.classpp.data.xmu.AndroidXmuCookieMirror
import com.fangyi.classpp.data.xmu.KeystoreXmuSecureStore
import com.fangyi.classpp.data.xmu.XmuAutoGroupNames
import com.fangyi.classpp.data.xmu.SkipReason
import com.fangyi.classpp.data.xmu.XmuAutoLoginError
import com.fangyi.classpp.data.xmu.XmuAutoLoginManager
import com.fangyi.classpp.data.xmu.XmuAutoLoginPolicy
import com.fangyi.classpp.data.xmu.XmuAvatarStore
import com.fangyi.classpp.data.xmu.XmuCommitOutcome
import com.fangyi.classpp.data.xmu.XmuCookieJar
import com.fangyi.classpp.data.xmu.XmuEnableOutcome
import com.fangyi.classpp.data.xmu.XmuHttpAvatarLoader
import com.fangyi.classpp.data.xmu.XmuJwImportError
import com.fangyi.classpp.data.xmu.XmuJwLoginClient
import com.fangyi.classpp.data.xmu.XmuJwScheduleApi
import com.fangyi.classpp.data.xmu.XmuJwScheduleImporter
import com.fangyi.classpp.data.xmu.XmuJwScheduleResult
import com.fangyi.classpp.data.xmu.XmuJwSemester
import com.fangyi.classpp.data.xmu.XmuJwSemestersResult
import com.fangyi.classpp.data.xmu.XmuLoginClient
import com.fangyi.classpp.data.xmu.XmuNativeTodoApi
import com.fangyi.classpp.data.xmu.XmuOkHttpTransport
import com.fangyi.classpp.data.xmu.XmuPasswordCipher
import com.fangyi.classpp.data.xmu.XmuPrepareOutcome
import com.fangyi.classpp.data.xmu.XmuSessionCookieHolder
import com.fangyi.classpp.data.xmu.XmuSyncError
import com.fangyi.classpp.data.xmu.XmuSyncPlan
import com.fangyi.classpp.data.xmu.XmuSyncPreferences
import com.fangyi.classpp.data.xmu.XmuSyncRepository
import com.fangyi.classpp.data.xmu.XmuSyncStore
import com.fangyi.classpp.data.xmu.XmuTodoApiRouter
import com.fangyi.classpp.data.xmu.XmuWebFetcher
import com.fangyi.classpp.data.xmu.XmuWebViewPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 厦大适配：把冻结的 `data/xmu` 协议实现装配进通用会话契约。
 *
 * 装配代码自 `AppContainer.create` 原样搬入（逻辑与注释保留）；归一化包装
 * （[SyncService] / [AutoRenewService] / [ScheduleImportService] / [SyncPlan] /
 * [DailySyncToggle]）只做类型映射，不改变任何行为。`data/xmu` 协议实现零改动。
 */
class XmuSchoolProvider : SchoolProvider {

    override val spec: SchoolSpec = XMU_SCHOOL_SPEC

    @Volatile
    private var toggle: DailySyncToggle? = null

    private val toggleLock = Any()

    /**
     * provider 级与 session 级共用同一 toggle 实例（同一 `xmu_sync_prefs` 单键）：
     * 后台重排只读开关时不会装配完整会话。
     */
    override fun dailySyncToggle(context: Context): DailySyncToggle {
        toggle?.let { return it }
        return synchronized(toggleLock) {
            toggle ?: XmuDailySyncToggle(context.applicationContext).also { toggle = it }
        }
    }

    override suspend fun createSession(deps: SessionDeps): SchoolSession {
        val appContext = deps.appContext

        // 方案 B：原生 OAuth 登录 + 自动续期。传输层（OkHttp + IPv4 优先 DNS，
        // XMU 主机发布不可达 AAAA 记录，HttpURLConnection 会先试 v6 黑洞）与
        // cookie 持有者由登录客户端和原生取数共享；WebView 路径仍由 XmuWebFetcher 保持原样。
        val transport = XmuOkHttpTransport()
        val session = XmuSessionCookieHolder()
        // minSdk 24 不能用 java.util.Base64；密码加密的编码器在此注入（两条登录链路共用）
        val cipher = XmuPasswordCipher(
            base64Encoder = { Base64.encodeToString(it, Base64.NO_WRAP) },
        )
        val autoLoginManager = XmuAutoLoginManager(
            store = KeystoreXmuSecureStore(appContext),
            loginClient = XmuLoginClient(
                transport = transport,
                cipher = cipher,
            ),
            nativeApi = XmuNativeTodoApi(transport, session),
            session = session,
            cookieMirror = AndroidXmuCookieMirror,
        ).also { it.bootstrap() }

        // 教务导入：与课程中心共用传输层与密码加密，但登录链路（CAS service 指回
        // 教务站）与会话 cookie（jw 域，仅内存）各自独立；cookie 罐由登录与业务
        // 取数共享，随响应继续演进（负载均衡路由 cookie 等）
        val jwCookieJar = XmuCookieJar()
        val scheduleImporter = XmuJwScheduleImporter(
            loginClient = XmuJwLoginClient(transport = transport, cipher = cipher),
            api = XmuJwScheduleApi(transport = transport, cookieJar = jwCookieJar),
            credentialsProvider = { autoLoginManager.peekCredentials() },
            cookieJar = jwCookieJar,
        )

        // 头像抓取：与同步同一次会话里下载，缓存进 filesDir/xmu/。Cookie 两路合并——
        // 原生登录写进 session.header，网页登录/mirror 在 WebView 的 CookieManager 里；
        // 实际是否带上由 loader 按域名判定（只发给 xmu.edu.cn，外域头像按匿名请求取）
        val avatarCookie: () -> String? = {
            val mirrored = runCatching {
                CookieManager.getInstance()
                    .getCookie("https://${XmuWebViewPolicy.LNT_HOST}")
            }.getOrNull()
            listOfNotNull(session.header, mirrored)
                .filter { it.isNotBlank() }
                .joinToString("; ")
                .ifBlank { null }
        }

        val repository = XmuSyncRepository(
            store = XmuSyncStore(deps.filesDir),
            todoRepository = deps.todoRepository,
            // 自动续期关闭/暂停/不可用时原样委托无头 WebView（方案 A）
            api = XmuTodoApiRouter(XmuWebFetcher(appContext), autoLoginManager),
            avatarStore = XmuAvatarStore(deps.filesDir),
            avatarLoader = XmuHttpAvatarLoader(avatarCookie),
            autoGroupNames = XmuAutoGroupNames(
                homework = appContext.getString(R.string.xmu_group_homework),
                quiz = appContext.getString(R.string.xmu_group_quiz),
            ),
        ).also { it.bootstrap() }

        val sync = XmuSyncService(repository)
        val autoRenew = XmuAutoRenewService(autoLoginManager)
        val scheduleImport = XmuScheduleImportService(
            importer = scheduleImporter,
            syncState = sync.state,
            autoRenewState = autoRenew.state,
            fallbackScheduleName = appContext.getString(spec.defaultScheduleNameRes),
        )
        return XmuSchoolSession(
            spec = spec,
            repository = repository,
            autoLoginManager = autoLoginManager,
            scheduleImporter = scheduleImporter,
            sync = sync,
            autoRenew = autoRenew,
            scheduleImport = scheduleImport,
            webViewLogin = XMU_WEB_VIEW_LOGIN,
            dailySync = dailySyncToggle(appContext),
        )
    }
}

/**
 * 厦大运行时会话：对外只暴露通用契约；[repository] / [autoLoginManager] /
 * [scheduleImporter] 供 `AppContainer` 在 Phase 2 前保留的旧字段做引用别名
 * （单一装配源，不是第二份数据源）。
 */
internal class XmuSchoolSession(
    override val spec: SchoolSpec,
    internal val repository: XmuSyncRepository,
    internal val autoLoginManager: XmuAutoLoginManager,
    internal val scheduleImporter: XmuJwScheduleImporter,
    override val sync: SyncService,
    override val autoRenew: AutoRenewService,
    override val scheduleImport: ScheduleImportService,
    override val webViewLogin: WebViewLoginSpec,
    override val dailySync: DailySyncToggle,
) : SchoolSession

/** [XmuSyncRepository] 的归一化包装（状态/头像/计划全部映射到 data.sync 类型） */
internal class XmuSyncService(
    private val repository: XmuSyncRepository,
) : SyncService {

    override val state: StateFlow<SyncState> = repository.state.mapState { state ->
        SyncState(
            uid = state.uid,
            accountName = state.accountName,
            avatarUrl = state.avatarUrl,
            lastSyncAtMillis = state.lastSyncAtMillis,
            lastProbeAtMillis = state.lastProbeAtMillis,
        )
    }

    override val avatarImage: StateFlow<AvatarImage?> = repository.avatarImage.mapState { image ->
        image?.let { AvatarImage(file = it.file, version = it.version) }
    }

    private val busyTracker = BusyTracker()
    override val busy: StateFlow<Boolean> = busyTracker.busy

    override suspend fun prepareSync(force: Boolean): PrepareOutcome = busyTracker.track {
        when (val outcome = repository.prepareSync(force)) {
            is XmuPrepareOutcome.Ready ->
                PrepareOutcome.Ready(XmuSyncPlanView(outcome.plan, this@XmuSyncService))
            is XmuPrepareOutcome.Failed -> PrepareOutcome.Failed(outcome.error.toSyncError())
        }
    }

    override suspend fun prepareFromLogin(raw: LoginRaw): PrepareOutcome = busyTracker.track {
        when (val outcome = repository.prepareFromLogin(raw.profileRaw, raw.todosRaw)) {
            is XmuPrepareOutcome.Ready ->
                PrepareOutcome.Ready(XmuSyncPlanView(outcome.plan, this@XmuSyncService))
            is XmuPrepareOutcome.Failed -> PrepareOutcome.Failed(outcome.error.toSyncError())
        }
    }

    override suspend fun commitSync(
        plan: SyncPlan,
        selectedNewIds: Set<String>,
    ): CommitOutcome = busyTracker.track {
        when (val outcome = repository.commitSync(claim(plan).plan, selectedNewIds)) {
            is XmuCommitOutcome.Done -> CommitOutcome.Done(
                SyncReport(
                    imported = outcome.report.imported,
                    restored = outcome.report.restored,
                    skippedFiltered = outcome.report.skippedFiltered,
                ),
            )
            is XmuCommitOutcome.Failed -> CommitOutcome.Failed(outcome.error)
        }
    }

    override suspend fun markSynced(plan: SyncPlan): Boolean = busyTracker.track {
        repository.markSynced(claim(plan).plan)
    }

    override suspend fun resetSyncData(deleteSyncedTodos: Boolean) {
        busyTracker.track { repository.resetSyncData(deleteSyncedTodos) }
    }

    /**
     * 会话认领计划（指导 §2.4 #11）：plan 是产出它的会话持有的不透明句柄，跨 session
     * 传入是编程错误（不能静默按新校解释），直接失败而非返回误导性结果。
     */
    private fun claim(plan: SyncPlan): XmuSyncPlanView {
        require(plan is XmuSyncPlanView && plan.owner === this) {
            "sync plan was produced by another school session"
        }
        return plan
    }
}

/** [XmuAutoLoginManager] 的归一化包装 */
internal class XmuAutoRenewService(
    private val manager: XmuAutoLoginManager,
) : AutoRenewService {

    override val state: StateFlow<AutoRenewState> = manager.state.mapState { state ->
        AutoRenewState(
            policy = state.policy.toAutoRenewPolicy(),
            hasCredentials = state.hasCredentials,
            available = state.available,
        )
    }

    private val busyTracker = BusyTracker()
    override val busy: StateFlow<Boolean> = busyTracker.busy

    override suspend fun enable(username: String, password: String): EnableOutcome =
        busyTracker.track {
            when (val outcome = manager.enable(username, password)) {
                is XmuEnableOutcome.Ready ->
                    EnableOutcome.Ready(LoginRaw(outcome.profileRaw, outcome.todosRaw))
                is XmuEnableOutcome.Failed ->
                    EnableOutcome.Failed(outcome.error.toLoginError())
            }
        }

    override suspend fun disable() {
        busyTracker.track { manager.disable() }
    }
}

/**
 * [XmuJwScheduleImporter] 的归一化包装：只取数 + 映射，落库仍由 UI 调
 * `ScheduleRepository.importSchedule`。
 */
internal class XmuScheduleImportService(
    private val importer: XmuJwScheduleImporter,
    syncState: StateFlow<SyncState>,
    autoRenewState: StateFlow<AutoRenewState>,
    private val fallbackScheduleName: String,
) : ScheduleImportService {

    override val blockedReason: StateFlow<ImportBlockReason?> = combineState(
        syncState,
        autoRenewState,
    ) { sync, autoRenew ->
        when {
            sync.uid.isEmpty() -> ImportBlockReason.NotConnected
            !autoRenew.hasCredentials -> ImportBlockReason.NoCredentials
            else -> null
        }
    }

    override suspend fun fetchSemesters(): SemestersOutcome =
        when (val result = importer.fetchSemesters()) {
            is XmuJwSemestersResult.Ok ->
                SemestersOutcome.Ok(result.semesters.map { it.toImportSemester() })
            is XmuJwSemestersResult.Failed -> SemestersOutcome.Failed(result.error.toImportError())
        }

    override suspend fun fetchSchedule(semester: ImportSemester): ImportOutcome =
        when (val result = importer.fetchSchedule(semester.id, fallbackScheduleName)) {
            is XmuJwScheduleResult.Ok -> ImportOutcome.Ok(result.schedule)
            XmuJwScheduleResult.NoCourses -> ImportOutcome.NoCourses
            is XmuJwScheduleResult.Failed -> ImportOutcome.Failed(result.error.toImportError())
        }

    private fun XmuJwSemester.toImportSemester(): ImportSemester =
        ImportSemester(id = code, name = label)
}

/** 每日同步开关：包装冻结的 `xmu_sync_prefs` 单键存储 */
internal class XmuDailySyncToggle(context: Context) : DailySyncToggle {

    private val appContext = context.applicationContext
    private val _enabled = MutableStateFlow(XmuSyncPreferences.isDailySyncEnabled(appContext))

    override val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        XmuSyncPreferences.setDailySyncEnabled(appContext, enabled)
        _enabled.value = enabled
    }
}

/**
 * 同步计划的归一化视图：只读属性来自 [XmuSyncPlan]；[owner] 用于会话认领校验。
 * `courseName` 仅作引擎内部分组键（UI 按 `todo.sourceDetail` 分组，与现状一致）。
 */
internal class XmuSyncPlanView(
    internal val plan: XmuSyncPlan,
    internal val owner: XmuSyncService,
) : SyncPlan {

    override val newItems: List<SyncPlanItem> = plan.newItems.map { item ->
        SyncPlanItem(todo = item.todo, courseName = item.dto.courseName)
    }

    override val restoreCount: Int = plan.restoreItems.size

    override val skippedTotal: Int = plan.skipped.values.sum()

    override val deadlineUnparseableCount: Int =
        plan.skipped[SkipReason.DeadlineUnparseable] ?: 0
}

/** 在途计数：单飞调用期间 [busy] 为 true（并发调用只在计数归零时复位） */
private class BusyTracker {
    private val active = AtomicInteger(0)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    suspend fun <T> track(block: suspend () -> T): T {
        if (active.incrementAndGet() == 1) _busy.value = true
        try {
            return block()
        } finally {
            if (active.decrementAndGet() == 0) _busy.value = false
        }
    }
}

// ---------- 失败分类映射（一一同构，不做兜底合并） ----------

private fun XmuSyncError.toSyncError(): SyncError = when (this) {
    XmuSyncError.SessionExpired -> SyncError.SessionExpired
    XmuSyncError.ServiceBusy -> SyncError.ServiceBusy
    XmuSyncError.NetworkError -> SyncError.NetworkError
    XmuSyncError.Timeout -> SyncError.Timeout
    XmuSyncError.FormatChanged -> SyncError.FormatChanged
    XmuSyncError.AccountChanged -> SyncError.AccountChanged
    XmuSyncError.TooSoon -> SyncError.TooSoon
    XmuSyncError.PersistFailed -> SyncError.PersistFailed
}

private fun XmuAutoLoginError.toLoginError(): LoginError = when (this) {
    XmuAutoLoginError.NeedCaptcha -> LoginError.NeedCaptcha
    XmuAutoLoginError.InvalidCredentials -> LoginError.InvalidCredentials
    XmuAutoLoginError.StorageUnavailable -> LoginError.StorageUnavailable
    XmuAutoLoginError.ServiceBusy -> LoginError.ServiceBusy
    XmuAutoLoginError.NetworkError -> LoginError.NetworkError
    XmuAutoLoginError.Timeout -> LoginError.Timeout
    XmuAutoLoginError.FormatChanged -> LoginError.FormatChanged
    XmuAutoLoginError.SessionNotEstablished -> LoginError.SessionNotEstablished
}

private fun XmuJwImportError.toImportError(): ImportError = when (this) {
    XmuJwImportError.NeedLogin -> ImportError.NeedLogin
    XmuJwImportError.NeedCaptcha -> ImportError.NeedCaptcha
    XmuJwImportError.InvalidCredentials -> ImportError.InvalidCredentials
    XmuJwImportError.SessionExpired -> ImportError.SessionExpired
    XmuJwImportError.ServiceBusy -> ImportError.ServiceBusy
    XmuJwImportError.NetworkError -> ImportError.NetworkError
    XmuJwImportError.Timeout -> ImportError.Timeout
    XmuJwImportError.LoginFormatChanged -> ImportError.LoginFormatChanged
    XmuJwImportError.FormatChanged -> ImportError.FormatChanged
}

private fun XmuAutoLoginPolicy.toAutoRenewPolicy(): AutoRenewPolicy = when (this) {
    XmuAutoLoginPolicy.ENABLED -> AutoRenewPolicy.ENABLED
    XmuAutoLoginPolicy.BLOCKED -> AutoRenewPolicy.BLOCKED
    XmuAutoLoginPolicy.USER_LOGGED_OUT -> AutoRenewPolicy.USER_LOGGED_OUT
}

// ---------- 静态声明 ----------

/**
 * 厦大白名单：与 `XmuWebViewPolicy` 的 private 集合保持一致。冻结文件不改为读它，
 * 这里按值复制并加注释；若协议实现增删域名，两处必须同改。
 */
private val XMU_ALLOWED_HOSTS = setOf(
    "lnt.xmu.edu.cn",
    "ids.xmu.edu.cn",
    "c-identity.xmu.edu.cn",
    "pass.xmu.edu.cn",
)

private val XMU_WEB_VIEW_LOGIN = WebViewLoginSpec(
    entryUrl = XmuWebViewPolicy.ENTRY_URL,
    allowedHosts = XMU_ALLOWED_HOSTS,
    dataHost = XmuWebViewPolicy.LNT_HOST,
    profilePath = "/api/profile",
    todosPath = "/api/todos",
    userAgent = XmuWebViewPolicy.DESKTOP_USER_AGENT,
)

/** 厦大静态声明：全部文案指向现有 `xmu_*` 资源键，视觉效果与改造前逐字一致 */
private val XMU_SCHOOL_SPEC = SchoolSpec(
    id = "xmu",
    displayNameRes = R.string.xmu_school_name,
    capabilities = setOf(
        Capability.NATIVE_LOGIN,
        Capability.WEBVIEW_LOGIN,
        Capability.SCHEDULE_IMPORT,
    ),
    headerTitleRes = R.string.xmu_header_title,
    headerDescriptionRes = R.string.xmu_header_description,
    introTitleRes = R.string.xmu_intro_title,
    introBodyRes = R.string.xmu_intro_body,
    usernameLabelRes = R.string.xmu_auto_login_username,
    usernameHintRes = R.string.xmu_auto_login_username_hint,
    loginBlankHintRes = R.string.xmu_auto_login_blank,
    loginNotesRes = R.string.xmu_auto_login_warning,
    loginSheetTitleRes = R.string.xmu_login_sheet_title,
    sourceBadgeRes = R.string.xmu_source_badge,
    groupHomeworkRes = R.string.xmu_group_homework,
    groupQuizRes = R.string.xmu_group_quiz,
    defaultScheduleNameRes = R.string.import_xmu_default_name,
    syncNotifTitleRes = R.string.xmu_auto_sync_notif_title,
    accountChangedRes = R.string.xmu_error_account_changed,
    logoutMessageRes = R.string.xmu_logout_confirm_message,
    switchMessageRes = R.string.xmu_switch_message,
    loginImages = listOf(R.drawable.xmu1, R.drawable.xmu2),
    timetableIntro = TimetableIntro(
        titleRes = R.string.xmu_intro_timetable_title,
        bodyRes = R.string.xmu_intro_timetable_body,
    ),
)
