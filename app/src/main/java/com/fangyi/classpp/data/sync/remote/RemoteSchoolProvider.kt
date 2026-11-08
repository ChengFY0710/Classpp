package com.fangyi.classpp.data.sync.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import com.fangyi.classpp.data.sync.DailySyncToggle
import com.fangyi.classpp.data.sync.PrefsDailySyncToggle
import com.fangyi.classpp.data.sync.ScheduleImportService
import com.fangyi.classpp.data.sync.SchoolProvider
import com.fangyi.classpp.data.sync.SchoolSession
import com.fangyi.classpp.data.sync.SchoolSpec
import com.fangyi.classpp.data.sync.SessionDeps
import com.fangyi.classpp.data.sync.SyncDebugLog
import com.fangyi.classpp.data.sync.SyncService
import com.fangyi.classpp.data.sync.WebViewLoginSpec
import com.fangyi.classpp.data.sync.WebViewTimetableSpec
import com.fangyi.classpp.data.sync.resolve
import com.fangyi.classpp.data.sync.engine.SchoolAutoGroupNames
import com.fangyi.classpp.data.sync.engine.SchoolSyncEngine
import com.fangyi.classpp.data.sync.engine.SchoolSyncStore
import com.fangyi.classpp.data.sync.media.AvatarCache
import com.fangyi.classpp.data.sync.media.HttpAvatarFetcher
import com.fangyi.classpp.data.sync.webview.WebFetcher
import java.io.File

/**
 * 远端声明学校（Tier 1）的通用装配：声明 → spec（内联文案）→ [SchoolSyncEngine] +
 * 无头 [WebFetcher]（WebView 登录/取数）+ [AvatarCache]，零学校代码。
 *
 * 能力集：登录恒为 {WEBVIEW_LOGIN}；声明带可选 `timetable` 块时追加
 * {SCHEDULE_IMPORT} 并装配 [RemoteScheduleImportService]（WebView-only，无凭据保存/
 * 自动续期）。对应槽位按能力显隐，全部远端学校**共享同一个**每日同步锚点
 * （[SchoolDailySyncReceiver]，requestCode 2002 与厦大冻结锚点 1001 区分）——
 * 单活语义下同一时刻只有一个活跃学校、一个闹钟位，换校锚点切换由
 * `SchoolDailySyncController` 的换轨逻辑兜住。
 */
class RemoteSchoolProvider(private val declaration: SchoolDeclaration) : SchoolProvider {

    init {
        val issues = declaration.validate()
        require(issues.isEmpty()) {
            "invalid school declaration '${declaration.id}': ${issues.joinToString("; ")}"
        }
    }

    override val spec: SchoolSpec = declaration.toSpec()

    /** 内容令牌 = 声明本身（data class 结构相等）：注册表据此识别已注册校的声明变化 */
    override val remoteDeclarationToken: Any = declaration

    @Volatile
    private var toggle: DailySyncToggle? = null

    private val toggleLock = Any()

    override val dailySyncFireIntent: ((Context) -> PendingIntent)? = ::fireRemoteDailySync

    /** 首次连接确认浮层读取登录域：声明在构造期已校验，这里直接暴露 */
    override val webViewLoginSpec: WebViewLoginSpec = declaration.toWebViewLoginSpec()

    /** 首次连接确认浮层读取课表页域名 + 会话装配复用同一份（含生成脚本） */
    override val webViewTimetableSpec: WebViewTimetableSpec? by lazy {
        declaration.timetable?.let { timetable ->
            timetable.toWebViewTimetableSpec(RemoteTimetableScript.script(timetable))
        }
    }

    override fun dailySyncToggle(context: Context): DailySyncToggle {
        toggle?.let { return it }
        return synchronized(toggleLock) {
            toggle ?: PrefsDailySyncToggle(context.applicationContext, spec.id).also { toggle = it }
        }
    }

    override suspend fun createSession(deps: SessionDeps): SchoolSession {
        val appContext = deps.appContext
        val clock = System::currentTimeMillis
        val codec = DeclarativeSyncCodec(declaration, clock)
        val webView = declaration.toWebViewLoginSpec()

        val avatar = declaration.profile.avatar?.let { section ->
            AvatarCache(
                dir = File(File(deps.filesDir, SchoolSyncStore.ROOT_DIR_NAME), spec.id),
                fetcher = HttpAvatarFetcher(
                    // WebView-only 学校：会话 cookie 全在系统 CookieManager（登录浮层 flush 过）
                    cookie = { mirroredCookie(section.cookieHosts) },
                    cookieHosts = section.cookieHosts.toSet(),
                    userAgent = when (
                        val ua = declaration.webView.userAgent?.takeUnless { it.isBlank() }
                    ) {
                        null -> null
                        SchoolDeclaration.UA_ALIAS_DESKTOP -> DECLARATION_DESKTOP_USER_AGENT
                        else -> ua
                    },
                ),
            )
        }
        val engine = SchoolSyncEngine(
            spec = spec,
            codec = codec,
            store = SchoolSyncStore(deps.filesDir, spec.id),
            todoRepository = deps.todoRepository,
            api = WebFetcher(appContext, webView, codec.profileValidator()),
            groupNames = SchoolAutoGroupNames(
                source = spec.id,
                homework = spec.groupHomework.resolve(appContext),
                quiz = spec.groupQuiz.resolve(appContext),
            ),
            clock = clock,
            avatar = avatar,
            logger = SyncDebugLog::d,
        ).also { it.bootstrap() }

        val scheduleImport = declaration.timetable?.let { timetable ->
            RemoteScheduleImportService(
                syncState = engine.state,
                timetable = timetable,
                fallbackName = timetable.defaultName.toSchoolText().resolve(appContext),
                webViewTimetable = webViewTimetableSpec
                    ?: timetable.toWebViewTimetableSpec(RemoteTimetableScript.script(timetable)),
            )
        }

        return RemoteSchoolSession(
            spec = spec,
            sync = engine,
            webViewLogin = webView,
            scheduleImport = scheduleImport,
            dailySync = dailySyncToggle(appContext),
            webViewProfileValidator = codec.profileValidator(),
        )
    }

    /** 头像 cookie：逐声明域从系统 CookieManager 取（外域头像自然匿名——fetcher 按域判定） */
    private fun mirroredCookie(hosts: List<String>): String? = runCatching {
        hosts.mapNotNull { host ->
            CookieManager.getInstance().getCookie("https://$host")
        }.filter { it.isNotBlank() }
            .joinToString("; ")
            .ifBlank { null }
    }.getOrNull()
}

/** 远端学校运行时会话：只暴露通用契约（无 autoRenew；课表导入按声明可选） */
internal class RemoteSchoolSession(
    override val spec: SchoolSpec,
    override val sync: SyncService,
    override val webViewLogin: WebViewLoginSpec,
    override val scheduleImport: ScheduleImportService?,
    override val dailySync: DailySyncToggle,
    override val webViewProfileValidator: (String) -> Boolean,
) : SchoolSession {
    override val autoRenew = null
}

/** 到点执行广播：全部远端学校共享的通用锚点（接收类 + requestCode + action 由本文件锁定） */
private fun fireRemoteDailySync(context: Context): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, SchoolDailySyncReceiver::class.java)
            .setAction(SchoolDailySyncReceiver.ACTION_FIRE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

/** 单条闹钟的固定 requestCode（与厦大冻结锚点 1001、进 App 通知 1002 区分） */
private const val REQUEST_CODE = 2002
