package com.fangyi.classpp.schools.xmu

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.webkit.CookieManager
import com.fangyi.classpp.R
import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.sync.AutoRenewState
import com.fangyi.classpp.data.sync.AutoRenewService
import com.fangyi.classpp.data.sync.Capability
import com.fangyi.classpp.data.sync.DailySyncToggle
import com.fangyi.classpp.data.sync.ImportBlockReason
import com.fangyi.classpp.data.sync.ImportError
import com.fangyi.classpp.data.sync.ImportOutcome
import com.fangyi.classpp.data.sync.ImportSemester
import com.fangyi.classpp.data.sync.PrefsDailySyncToggle
import com.fangyi.classpp.data.sync.ScheduleImportService
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolText
import com.fangyi.classpp.data.sync.resolve
import com.fangyi.classpp.data.sync.SchoolSession
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SemestersOutcome
import com.fangyi.classpp.data.sync.SessionDeps
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.data.sync.SyncState
import com.fangyi.classpp.data.sync.TimetableIntro
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import com.fangyi.classpp.data.sync.combineState
import com.fangyi.classpp.data.sync.net.AndroidCookieMirror
import com.fangyi.classpp.data.sync.credential.AutoRenewManager
import com.fangyi.classpp.data.sync.credential.AutoRenewSessionRouter
import com.fangyi.classpp.data.sync.credential.SchoolSecureStore
import com.fangyi.classpp.data.sync.engine.SchoolAutoGroupNames
import com.fangyi.classpp.data.sync.engine.SchoolSyncEngine
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore
import com.fangyi.classpp.data.sync.media.AvatarCache
import com.fangyi.classpp.data.sync.media.HttpAvatarFetcher
import com.fangyi.classpp.data.sync.net.OkHttpTransport
import com.fangyi.classpp.data.sync.net.SessionCookieHolder
import com.fangyi.classpp.data.sync.webview.WebFetcher
import com.fangyi.classpp.data.xmu.Ipv4FirstXmuDns
import com.fangyi.classpp.data.xmu.KeystoreXmuSecureStore
import com.fangyi.classpp.data.xmu.XmuCookieJar
import com.fangyi.classpp.data.xmu.XmuCredentials
import com.fangyi.classpp.data.xmu.XmuDebugLog
import com.fangyi.classpp.data.xmu.XmuJwImportError
import com.fangyi.classpp.data.xmu.XmuJwLoginClient
import com.fangyi.classpp.data.xmu.XmuJwScheduleApi
import com.fangyi.classpp.data.xmu.XmuJwScheduleImporter
import com.fangyi.classpp.data.xmu.XmuJwScheduleResult
import com.fangyi.classpp.data.xmu.XmuJwSemester
import com.fangyi.classpp.data.xmu.XmuJwSemestersResult
import com.fangyi.classpp.data.xmu.XmuLoginClient
import com.fangyi.classpp.data.xmu.XmuPasswordCipher
import com.fangyi.classpp.data.xmu.XmuProfileDto
import com.fangyi.classpp.data.xmu.XmuWebViewPolicy
import com.fangyi.classpp.data.xmu.XmuDailySyncAlarmReceiver
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 厦大适配（指导 §2）：把冻结的 `data/xmu` **协议实现**（DTO/映射/CAS 登录链路/教务导入）
 * 装配进通用方案——同步编排走 [SchoolSyncEngine]、自动续期走 [AutoRenewManager]、
 * WebView 取数走 [WebFetcher]、头像走 [AvatarCache]、开关走 [PrefsDailySyncToggle]，
 * 存量数据由 [XmuLegacyMigrator] 一次性搬进通用存储。
 *
 * 厦大保留自己的部分只有协议与网络特化：CAS/OAuth 登录链路（[XmuLoginClient]）、
 * IPv4 优先 DNS（[Ipv4FirstXmuDns]，XMU 主机发布不可达 AAAA 记录，先试 v6 会黑洞）、
 * 教务站独立登录链路、每日同步的冻结闹钟锚点（[XmuDailySyncAlarmReceiver]）。
 */
class XmuSchoolProvider : SchoolProvider {

    override val spec: SchoolSpec = XMU_SCHOOL_SPEC

    @Volatile
    private var toggle: DailySyncToggle? = null

    private val toggleLock = Any()

    /**
     * 每日同步的到点广播锚点：接收类、requestCode、action 三项是已发布闹钟的冻结锚点，
     * 不得改动（[SchoolDailySyncController] 按活跃学校取用，通用层不引用 receiver 类）。
     */
    override val dailySyncFireIntent: ((Context) -> PendingIntent)? = ::firePendingIntent

    /**
     * provider 级与 session 级共用同一 toggle 实例（同一 `sync_prefs` 分键）：
     * 后台重排只读开关时不会装配完整会话。
     */
    override fun dailySyncToggle(context: Context): DailySyncToggle {
        XmuLegacyMigrator.migrateDailySyncToggle(context)
        toggle?.let { return it }
        return synchronized(toggleLock) {
            toggle ?: PrefsDailySyncToggle(context.applicationContext, spec.id).also { toggle = it }
        }
    }

    override suspend fun createSession(deps: SessionDeps): SchoolSession {
        val appContext = deps.appContext
        // 通用层（引擎/续期/取数）的诊断出口接到厦大日志实现：日志纪律与冻结层零改动
        XmuDebugLog.init(appContext)
        SyncDebugLog.init(XmuDebugLog::d)

        // 存量迁移（幂等）：状态文件/头像/凭据/开关搬进通用存储，先于引擎与凭据的磁盘载入
        XmuLegacyMigrator(
            filesDir = deps.filesDir,
            legacySecure = KeystoreXmuSecureStore(appContext),
            secure = SchoolSecureStore(appContext, spec.id),
        ).run()

        // 原生 OAuth 登录 + 自动续期：通用 OkHttp 传输 + IPv4 优先 DNS，登录客户端、
        // 原生取数与教务链路共享同一条传输
        val transport = XmuTransportAdapter(
            OkHttpTransport(
                userAgent = XmuWebViewPolicy.DESKTOP_USER_AGENT,
                dns = Ipv4FirstXmuDns(),
            ),
        )
        // minSdk 24 不能用 java.util.Base64；密码加密的编码器在此注入（两条登录链路共用）
        val cipher = XmuPasswordCipher(
            base64Encoder = { Base64.encodeToString(it, Base64.NO_WRAP) },
        )
        val cookies = SessionCookieHolder()
        val autoRenew = AutoRenewManager(
            store = SchoolSecureStore(appContext, spec.id),
            loginClient = XmuNativeLoginClient(
                XmuLoginClient(transport = transport, cipher = cipher),
            ),
            sessionApi = XmuNativeSessionApi(transport = transport, session = cookies),
            cookieHolder = cookies,
            cookieMirror = AndroidCookieMirror,
            mirrorHost = XmuWebViewPolicy.LNT_HOST,
        ).also { it.bootstrap() }

        // 教务导入：与课程中心共用传输层与密码加密，但登录链路（CAS service 指回教务站）
        // 与会话 cookie（jw 域，仅内存）各自独立；cookie 罐由登录与业务取数共享，
        // 随响应继续演进（负载均衡路由 cookie 等）
        val jwCookieJar = XmuCookieJar()
        val scheduleImporter = XmuJwScheduleImporter(
            loginClient = XmuJwLoginClient(transport = transport, cipher = cipher),
            api = XmuJwScheduleApi(transport = transport, cookieJar = jwCookieJar),
            credentialsProvider = {
                autoRenew.peekCredentials()?.let { XmuCredentials(it.username, it.password) }
            },
            cookieJar = jwCookieJar,
        )

        // 头像抓取：与同步同一次会话里下载，缓存进 filesDir/sync/xmu/。Cookie 两路合并——
        // 原生登录写进 cookies.header，网页登录/mirror 在 WebView 的 CookieManager 里；
        // 实际是否带上由 fetcher 按域名判定（只发给 xmu.edu.cn，外域头像按匿名请求取）
        val avatarCookie: () -> String? = {
            val mirrored = runCatching {
                CookieManager.getInstance().getCookie("https://${XmuWebViewPolicy.LNT_HOST}")
            }.getOrNull()
            listOfNotNull(cookies.header, mirrored)
                .filter { it.isNotBlank() }
                .joinToString("; ")
                .ifBlank { null }
        }

        val clock = System::currentTimeMillis
        val engine = SchoolSyncEngine(
            spec = spec,
            codec = XmuSyncCodec(clock = clock),
            store = SchoolSyncStore(deps.filesDir, spec.id),
            todoRepository = deps.todoRepository,
            // 自动续期关闭/暂停/不可用时原样委托无头 WebView（方案 A，路由语义不变）
            api = AutoRenewSessionRouter(autoRenew, WebFetcher(appContext, XMU_WEB_VIEW_LOGIN, ::isXmuProfile)),
            groupNames = SchoolAutoGroupNames(
                source = spec.id,
                homework = spec.groupHomework.resolve(appContext),
                quiz = spec.groupQuiz.resolve(appContext),
            ),
            clock = clock,
            avatar = AvatarCache(
                dir = File(File(deps.filesDir, SchoolSyncStore.ROOT_DIR_NAME), spec.id),
                fetcher = HttpAvatarFetcher(
                    cookie = avatarCookie,
                    cookieHosts = setOf(XMU_DOMAIN),
                    userAgent = XmuWebViewPolicy.DESKTOP_USER_AGENT,
                    dns = Ipv4FirstXmuDns(),
                ),
            ),
            logger = SyncDebugLog::d,
        ).also { it.bootstrap() }

        val scheduleImport = XmuScheduleImportService(
            importer = scheduleImporter,
            syncState = engine.state,
            autoRenewState = autoRenew.state,
            fallbackScheduleName = spec.defaultScheduleName.resolve(appContext),
            webViewTimetable = XMU_WEB_VIEW_TIMETABLE,
        )
        return XmuSchoolSession(
            spec = spec,
            sync = engine,
            autoRenew = autoRenew,
            scheduleImport = scheduleImport,
            webViewLogin = XMU_WEB_VIEW_LOGIN,
            dailySync = dailySyncToggle(appContext),
            webViewProfileValidator = ::isXmuProfile,
        )
    }

    /** 登录态确认口径：profile 正文含有效 id（与冻结驱动器 `isProfileBody` 一致） */
    private fun isXmuProfile(raw: String): Boolean = try {
        ScheduleJson.decodeStorage<XmuProfileDto>(raw).id > 0
    } catch (_: Exception) {
        false
    }
}

/** 到点执行广播：接收类、requestCode、action 三项是已发布闹钟的冻结锚点，不得改动 */
private fun firePendingIntent(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, XmuDailySyncAlarmReceiver::class.java)
            .setAction(XmuDailySyncAlarmReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** 单条闹钟的固定 requestCode（本功能只有一个闹钟位） */
private const val REQUEST_CODE = 1001

/** XMU 域（含子域）才携带会话 cookie 的判定域（与冻结头像加载器的口径一致） */
private const val XMU_DOMAIN = "xmu.edu.cn"

/**
 * 厦大运行时会话：对外只暴露通用契约（引擎/续期管理器直接实现通用接口，
 * 教务导入仍由适配层包装组合派生 blockedReason）。
 */
internal class XmuSchoolSession(
    override val spec: SchoolSpec,
    override val sync: SyncService,
    override val autoRenew: AutoRenewService,
    override val scheduleImport: ScheduleImportService,
    override val webViewLogin: WebViewLoginSpec,
    override val dailySync: DailySyncToggle,
    override val webViewProfileValidator: (String) -> Boolean,
) : SchoolSession

/**
 * [XmuJwScheduleImporter] 的归一化包装：只取数 + 映射，落库仍由 UI 调
 * `ScheduleRepository.importSchedule`。
 *
 * [webViewTimetable]：WebView 登录（无保存凭据）时的网页导入入口——打开教务
 * 「我的课表」页，由用户在页面内登录/查看；本期不做数据抽取。
 */
internal class XmuScheduleImportService(
    private val importer: XmuJwScheduleImporter,
    syncState: StateFlow<SyncState>,
    autoRenewState: StateFlow<AutoRenewState>,
    private val fallbackScheduleName: String,
    override val webViewTimetable: WebViewTimetableSpec? = null,
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

// ---------- 失败分类映射（一一同构，不做兜底合并） ----------

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

/** 厦大白名单与端点声明：注册表装配时经 `WebViewPolicy.validate` 硬校验（测试锁值） */
internal val XMU_WEB_VIEW_LOGIN = WebViewLoginSpec(
    entryUrl = XmuWebViewPolicy.ENTRY_URL,
    allowedHosts = XMU_ALLOWED_HOSTS,
    dataHost = XmuWebViewPolicy.LNT_HOST,
    profilePath = "/api/profile",
    todosPath = "/api/todos",
    userAgent = XmuWebViewPolicy.DESKTOP_USER_AGENT,
)

/**
 * 教务课表页（WebView 登录模式下的网页导入入口）：入口 = 教务「我的课表」应用页
 * （[XmuJwLoginClient.APP_INDEX_URL]，与原生导入同一页面）；白名单 = 教务站 + CAS
 * 身份域（未登录时页面会跳转身份认证；`ALLOWED_HOSTS` 与冻结登录链路按值一致）。
 */
internal val XMU_WEB_VIEW_TIMETABLE = WebViewTimetableSpec(
    entryUrl = XmuJwLoginClient.APP_INDEX_URL,
    allowedHosts = setOf(
        XmuJwLoginClient.JW_HOST,
        "ids.xmu.edu.cn",
        "pass.xmu.edu.cn",
    ),
    userAgent = XmuWebViewPolicy.DESKTOP_USER_AGENT,
)

/** 厦大静态声明：全部文案指向现有 `xmu_*` 资源键，视觉效果与改造前逐字一致（测试锁值） */
internal val XMU_SCHOOL_SPEC = SchoolSpec(
    id = "xmu",
    displayName = SchoolText.Res(R.string.xmu_school_name),
    capabilities = setOf(
        Capability.NATIVE_LOGIN,
        Capability.WEBVIEW_LOGIN,
        Capability.SCHEDULE_IMPORT,
    ),
    headerTitle = SchoolText.Res(R.string.xmu_header_title),
    headerDescription = SchoolText.Res(R.string.xmu_header_description),
    introTitle = SchoolText.Res(R.string.xmu_intro_title),
    introBody = SchoolText.Res(R.string.xmu_intro_body),
    usernameLabel = SchoolText.Res(R.string.xmu_auto_login_username),
    usernameHint = SchoolText.Res(R.string.xmu_auto_login_username_hint),
    loginBlankHint = SchoolText.Res(R.string.xmu_auto_login_blank),
    loginNotes = SchoolText.Res(R.string.xmu_auto_login_warning),
    loginSheetTitle = SchoolText.Res(R.string.xmu_login_sheet_title),
    sourceBadge = SchoolText.Res(R.string.xmu_source_badge),
    groupHomework = SchoolText.Res(R.string.xmu_group_homework),
    groupQuiz = SchoolText.Res(R.string.xmu_group_quiz),
    defaultScheduleName = SchoolText.Res(R.string.sync_import_default_name),
    syncNotifTitle = SchoolText.Res(R.string.xmu_auto_sync_notif_title),
    accountChanged = SchoolText.Res(R.string.xmu_error_account_changed),
    logoutMessage = SchoolText.Res(R.string.xmu_logout_confirm_message),
    switchMessage = SchoolText.Res(R.string.xmu_switch_message),
    // 以下四项的旧文案含「课程中心/学号」等校别措辞，指向旧键保持视觉零变化；
    // 新校不提供时回落到 data/sync 的通用 UI 中性默认键
    serviceBusy = SchoolText.Res(R.string.xmu_error_busy),
    invalidCredentials = SchoolText.Res(R.string.xmu_auto_login_invalid_credentials),
    sessionNotEstablished = SchoolText.Res(R.string.xmu_auto_login_not_established),
    autoLoginDisableMessage = SchoolText.Res(R.string.xmu_auto_login_disable_message),
    loginImages = listOf(R.drawable.xmu1, R.drawable.xmu2),
    timetableIntro = TimetableIntro(
        title = SchoolText.Res(R.string.xmu_intro_timetable_title),
        body = SchoolText.Res(R.string.xmu_intro_timetable_body),
    ),
)
