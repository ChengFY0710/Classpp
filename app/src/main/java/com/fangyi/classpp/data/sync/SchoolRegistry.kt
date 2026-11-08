package com.fangyi.classpp.data.sync

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
 * - [activeSession] 是 UI 的唯一入口；[dailySyncToggle] 不装配完整会话，供后台重排闹钟读取。
 *
 * 装配动作由构造参数注入（生产 = `provider.createSession(deps)`），使注册表本身不持有
 * Android Context，纯 JVM 可测；`data/sync` 因此不依赖任何学校实现。
 */
class SchoolRegistry internal constructor(
    providers: List<SchoolProvider>,
    private val assembler: suspend (SchoolProvider) -> SchoolSession,
    private val toggleOf: (SchoolProvider) -> DailySyncToggle,
    initialActiveSchoolId: String? = null,
) {
    /** 注册序（首项为默认活跃学校） */
    val schools: List<SchoolSpec>

    private val providersById: Map<String, SchoolProvider>

    private val _activeSchoolId: MutableStateFlow<String>

    /** 当前活跃学校 id；传入的初始 id 失效时回退注册首项（见 init） */
    val activeSchoolId: StateFlow<String>

    private val _activeSession = MutableStateFlow<SessionSlot>(SessionSlot.Loading)

    /** UI 唯一入口：Compose 直接 collect；切校装配期间为 [SessionSlot.Loading] */
    val activeSession: StateFlow<SessionSlot> = _activeSession.asStateFlow()

    private val sessionMutex = Mutex()
    private val sessions = ConcurrentHashMap<String, SchoolSession>()
    private val toggles = ConcurrentHashMap<String, DailySyncToggle>()

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
        schools = providers.map { it.spec }
        providersById = providers.associateBy { it.spec.id }
        _activeSchoolId = MutableStateFlow(
            initialActiveSchoolId?.takeIf { it in providersById } ?: schools.first().id,
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
     * 每日同步开关：只读轻量状态，不装配完整 session（后台重排闹钟用）。
     * 同 id 恒返回同一实例，与 [SchoolSession.dailySync] 共用同一存储。
     */
    fun dailySyncToggle(id: String = activeSchoolId.value): DailySyncToggle {
        val provider = providersById[id]
            ?: throw IllegalArgumentException("unknown school id: '$id'")
        return toggles.computeIfAbsent(id) { toggleOf(provider) }
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
    }

    private companion object {
        /** 存储命名空间 / todo source / 本地 id 前缀共用，不得含分隔符 */
        val ID_PATTERN = Regex("^[a-z][a-z0-9_]{0,31}$")
    }
}
