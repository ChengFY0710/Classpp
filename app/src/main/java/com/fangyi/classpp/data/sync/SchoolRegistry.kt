package com.fangyi.classpp.data.sync

import android.app.PendingIntent
import android.content.Context
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 活跃学校的装配槽位：切校/首次装配期间为 [Loading]，装配成功 [Ready]、失败 [Failed]。
 * UI 只 collect 它，不阻塞首帧；装配由 [SchoolRegistry] 下沉到 IO 上下文。
 */
sealed interface SessionSlot {
    data object Loading : SessionSlot
    data class Ready(val session: SchoolSession) : SessionSlot
    data class Failed(val error: Throwable) : SessionSlot
}

/**
 * 学校注册表 + 单活账号状态（指导 §2.5）。
 *
 * - 注册顺序即展示顺序，**首项 = 默认活跃学校**（厦大注册第一，冷启动路径与改造前一致）；
 * - 启动硬校验：id 唯一且匹配 `^[a-z][a-z0-9_]{0,31}$`、至少一种登录方式、
 *   `timetableIntro ⇔ SCHEDULE_IMPORT`；会话装配后再校验 capabilities 与槽位一致；
 * - [session] 惰性装配 + 进程级缓存：同 id 同实例，并发首创只装配一次；
 * - [activeSession] 是 UI 的唯一入口；[dailySyncToggle] 不装配完整会话，供后台重排闹钟读取；
 * - 单活切换 [switchSchool]：先装配目标学校（失败保持原校），再持久化、更新 flow、
 *   通知宿主重排每日同步（指导 §2.5）。
 *
 * 装配动作由构造参数注入（生产 = `provider.createSession(deps)`），使注册表本身不持有
 * Android Context，纯 JVM 可测；`data/sync` 因此不依赖任何学校实现。活跃学校 id 的持久化
 * 同样以 [ActiveSchoolStore] 注入（生产 = `school_prefs`，测试 = 内存 fake）。
 */
class SchoolRegistry internal constructor(
    providers: List<SchoolProvider>,
    private val assembler: suspend (SchoolProvider) -> SchoolSession,
    private val toggleOf: (SchoolProvider) -> DailySyncToggle,
    private val activeStore: ActiveSchoolStore = NoPersistActiveSchoolStore,
    private val onActiveSchoolChanged: (String) -> Unit = {},
) {
    /** 注册序（首项为默认活跃学校）；StateFlow：远端声明学校运行时合并后 UI 自动更新 */
    private val _schools: MutableStateFlow<List<SchoolSpec>>

    val schools: StateFlow<List<SchoolSpec>>

    // 运行时可追加（后台刷新线程）与 UI 读并发：用并发容器，注册顺序由 _schools 维护
    private val providersById: MutableMap<String, SchoolProvider>

    private val _activeSchoolId: MutableStateFlow<String>

    /** 当前活跃学校 id；启动时读 [ActiveSchoolStore]，失效 id 回退注册首项（见 init） */
    val activeSchoolId: StateFlow<String>

    private val _activeSession = MutableStateFlow<SessionSlot>(SessionSlot.Loading)

    /** UI 唯一入口：Compose 直接 collect；切校装配期间保持旧校就绪态，成功后一次切换 */
    val activeSession: StateFlow<SessionSlot> = _activeSession.asStateFlow()

    private val sessionMutex = Mutex()
    private val switchMutex = Mutex()
    private val sessions = ConcurrentHashMap<String, SchoolSession>()
    private val toggles = ConcurrentHashMap<String, DailySyncToggle>()

    /** 已通过校验的学校 WebView 白名单：装配时做跨校重叠检查（共用认证域 = 配置错误） */
    private val webViewHosts = ConcurrentHashMap<String, Set<String>>()

    init {
        require(providers.isNotEmpty()) { "at least one school provider is required" }
        val seen = HashSet<String>(providers.size)
        for (provider in providers) {
            val spec = provider.spec
            require(ID_PATTERN.matches(spec.id)) {
                "invalid school id '${spec.id}': must match ${ID_PATTERN.pattern}"
            }
            require(seen.add(spec.id)) { "duplicate school id '${spec.id}'" }
            require(
                spec.capabilities.any {
                    it == Capability.NATIVE_LOGIN || it == Capability.WEBVIEW_LOGIN
                },
            ) { "school '${spec.id}' must provide at least one login capability" }
            require(
                (spec.timetableIntro != null) == (Capability.SCHEDULE_IMPORT in spec.capabilities),
            ) { "school '${spec.id}': timetableIntro must be present iff SCHEDULE_IMPORT is declared" }
        }
        _schools = MutableStateFlow(providers.map { it.spec })
        schools = _schools.asStateFlow()
        providersById = ConcurrentHashMap()
        for (provider in providers) providersById[provider.spec.id] = provider
        _activeSchoolId = MutableStateFlow(
            activeStore.read()?.takeIf { providersById.containsKey(it) }
                ?: _schools.value.first().id,
        )
        activeSchoolId = _activeSchoolId.asStateFlow()
    }

    /**
     * 取指定学校的会话（默认活跃学校）：惰性装配 + 进程级缓存。
     *
     * 装配期间活跃学校的 [activeSession] 先置 [SessionSlot.Loading]；成功置
     * [SessionSlot.Ready]，失败置 [SessionSlot.Failed] 并把异常继续抛给调用方。
     * 非活跃学校不触碰 [activeSession]。
     */
    suspend fun session(id: String = activeSchoolId.value): SchoolSession {
        val provider = providersById[id]
            ?: throw IllegalArgumentException("unknown school id: '$id'")
        sessions[id]?.let { return it }

        val isActive = id == _activeSchoolId.value
        if (isActive) _activeSession.value = SessionSlot.Loading
        try {
            val assembled = sessionMutex.withLock {
                sessions[id] ?: run {
                    // 装配一律下沉 IO（指导 §2.5）：Compose 只读 activeSession，不在组合期读磁盘
                    val created = withContext(Dispatchers.IO) { assembler(provider) }
                    validateSession(provider.spec, created)
                    sessions[id] = created
                    created
                }
            }
            if (isActive) _activeSession.value = SessionSlot.Ready(assembled)
            return assembled
        } catch (e: CancellationException) {
            // 装配被取消不是学校实现错误：保持 Loading，下次调用重试
            throw e
        } catch (e: Throwable) {
            if (isActive) _activeSession.value = SessionSlot.Failed(e)
            throw e
        }
    }

    /**
     * 切到指定学校（单活账号，指导 §2.5）：校验 → IO 装配 → 持久化 → 更新 flow →
     * 通知宿主重排每日同步。
     *
     * 装配在切换生效**之前**完成，因此：
     * - 装配失败时活跃学校、持久化值与 [activeSession] 全部保持原样（可继续用旧校）；
     * - 装配期间 UI 仍显示旧校（不闪 Loading），成功后一次切成新校 Ready。
     *
     * 切换到当前学校是幂等空操作（不重复装配、不写盘、不重排闹钟）。busy 状态由 UI 侧
     * 用 [canSwitchSchool] 拦截；这里不做静默拒绝，避免调用方误以为切换成功。
     */
    suspend fun switchSchool(id: String) {
        if (id == _activeSchoolId.value) return
        providersById[id] ?: throw IllegalArgumentException("unknown school id: '$id'")
        switchMutex.withLock {
            if (id == _activeSchoolId.value) return
            // 目标学校尚未活跃：session(id) 不会触碰 activeSession，失败也不会污染旧校
            val assembled = session(id)
            activeStore.write(id)
            _activeSchoolId.value = id
            _activeSession.value = SessionSlot.Ready(assembled)
            onActiveSchoolChanged(id)
        }
    }

    /**
     * 每日同步开关：只读轻量状态，不装配完整 session（后台重排闹钟用）。
     * 同 id 恒返回同一实例，与 [SchoolSession.dailySync] 共用同一存储。
     */
    fun dailySyncToggle(id: String = activeSchoolId.value): DailySyncToggle {
        val provider = providersById[id]
            ?: throw IllegalArgumentException("unknown school id: '$id'")
        return toggles.computeIfAbsent(id) { toggleOf(provider) }
    }

    /**
     * 每日同步的到点广播锚点工厂：读活跃学校 [SchoolProvider.dailySyncFireIntent] 的声明
     * （不装配完整 session，与 [dailySyncToggle] 同级轻量）。null = 该校不参与每日闹钟。
     */
    fun dailySyncFireIntent(id: String = activeSchoolId.value): ((Context) -> PendingIntent)? {
        val provider = providersById[id]
            ?: throw IllegalArgumentException("unknown school id: '$id'")
        return provider.dailySyncFireIntent
    }

    /**
     * 学校 WebView 登录声明的轻量访问（不装配会话）：远端声明学校首次连接确认浮层用
     * 它读取登录域。null = 该校未声明（或适配层未暴露）。
     */
    fun webViewLoginSpec(id: String): WebViewLoginSpec? {
        val provider = providersById[id]
            ?: throw IllegalArgumentException("unknown school id: '$id'")
        return provider.webViewLoginSpec
    }

    /**
     * 运行时追加远端声明学校（data/sync/remote 分发链路）：逐个按 init 同规则校验，
     * **非法/重复 id 跳过不抛**（数据驱动的容错——单个坏声明不拖垮整批、不波及已注册学校）。
     *
     * - 绝不移除已注册项：活跃会话可能正运行在远端学校上；
     * - 追加后 [schools] 发出新列表（徽标/选择卡自动更新），活跃学校不受影响；
     * - 会话仍惰性装配：新校首次被选中时才 [session]，装配期校验（[validateSession]）
     *   照常执行，失败走既有 [SessionSlot.Failed] 路径。
     *
     * @return 实际新增的学校数（0 = 全部被跳过）
     */
    @Synchronized
    fun registerRemote(providers: List<SchoolProvider>): Int {
        val knownIds = _schools.value.mapTo(mutableSetOf()) { it.id }
        val added = mutableListOf<SchoolProvider>()
        // 本批已接受的白名单：批内两校重叠同样按配置错误跳过（不等装配）
        val batchHosts = mutableMapOf<String, Set<String>>()
        for (provider in providers) {
            val spec = provider.spec
            if (!ID_PATTERN.matches(spec.id) || spec.id in knownIds) continue
            if (!spec.capabilities.any {
                    it == Capability.NATIVE_LOGIN || it == Capability.WEBVIEW_LOGIN
                }
            ) {
                continue
            }
            if ((spec.timetableIntro != null) !=
                (Capability.SCHEDULE_IMPORT in spec.capabilities)
            ) {
                continue
            }
            // 白名单提前查重（不等装配）：与已装配/已注册/本批学校的认证域重叠 = 配置错误
            val webView = provider.webViewLoginSpec
            if (webView != null) {
                val conflict = (webViewHosts.entries + batchHosts.entries).firstOrNull { (id, hosts) ->
                    id != spec.id && (hosts intersect webView.allowedHosts).isNotEmpty()
                }
                if (conflict != null) continue
                batchHosts[spec.id] = webView.allowedHosts
            }
            knownIds += spec.id
            added += provider
        }
        if (added.isEmpty()) return 0
        for (provider in added) {
            providersById[provider.spec.id] = provider
            batchHosts[provider.spec.id]?.let { webViewHosts[provider.spec.id] = it }
        }
        _schools.value = _schools.value + added.map { it.spec }
        return added.size
    }

    private fun validateSession(spec: SchoolSpec, session: SchoolSession) {
        require(session.spec == spec) {
            "school '${spec.id}': session spec does not match provider spec"
        }
        val capabilities = spec.capabilities
        require((session.autoRenew != null) == (Capability.NATIVE_LOGIN in capabilities)) {
            "school '${spec.id}': autoRenew slot must match NATIVE_LOGIN capability"
        }
        require((session.webViewLogin != null) == (Capability.WEBVIEW_LOGIN in capabilities)) {
            "school '${spec.id}': webViewLogin slot must match WEBVIEW_LOGIN capability"
        }
        require((session.scheduleImport != null) == (Capability.SCHEDULE_IMPORT in capabilities)) {
            "school '${spec.id}': scheduleImport slot must match SCHEDULE_IMPORT capability"
        }
        val webView = session.webViewLogin ?: return
        // 声明校验（指导 §2.3）：只允许 https/默认端口/无 userinfo/IP/通配符，dataHost 在
        // 白名单内；加固策略锁死在 WebViewPolicy，spec 无法关闭任何加固
        val issues = WebViewPolicy.validate(webView)
        require(issues.isEmpty()) {
            "school '${spec.id}': invalid webViewLogin spec: ${issues.joinToString("; ")}"
        }
        // 不同学校的白名单不得重叠：共用 CAS/认证域会让 cookie 互串，属配置错误
        val conflict = webViewHosts.entries.firstOrNull { (id, hosts) ->
            id != spec.id && (hosts intersect webView.allowedHosts).isNotEmpty()
        }
        require(conflict == null) {
            "school '${spec.id}': webViewLogin hosts overlap with '${conflict?.key}': " +
                (conflict?.value?.intersect(webView.allowedHosts) ?: emptySet<String>())
        }
        webViewHosts[spec.id] = webView.allowedHosts
    }

    companion object {
        /** 存储命名空间 / todo source / 本地 id 前缀共用，不得含分隔符 */
        private val ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")

        /**
         * 切校入口的禁用判定（指导 §3.2）：同步在途或自动续期在途时不允许切校。
         *
         * 抽成纯函数供 UI 与单测共用——「整行不响应、不弹 toast」是产品约束，
         * 不依赖调用方各自的布尔拼接。
         */
        fun canSwitchSchool(syncBusy: Boolean, autoRenewBusy: Boolean): Boolean =
            !syncBusy && !autoRenewBusy
    }
}
