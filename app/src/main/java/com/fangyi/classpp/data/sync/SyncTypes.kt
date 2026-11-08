package com.fangyi.classpp.data.sync

import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.Todo
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * 学校无关的归一化模型与运行时契约（指导 §2.3）。各校适配层把自身协议类型映射到这里，
 * UI 只依赖本文件的类型，不 import 任何学校实现。
 *
 * 通用编排不变量（指导 §2.4，两套编排共同遵守）：
 * 1. 单飞：探活/同步/清空共用一把互斥；
 * 2. 预览-确认：prepare 不写待办与映射，commit 才落库；但 prepare 会落盘连接与探活状态；
 * 3. 只增不改：远端变化不回写、远端消失不删；回收站条目允许复活；
 * 4. 账号一致性：uid 与已存映射不一致 → [SyncError.AccountChanged]，UI 确认后才清；
 * 5. entries 只存哈希，不存标题原文；
 * 6. 原子写：tmp(fsync)→bak→rename，双坏隔离留证；
 * 7. 退出清理按 source + id 前缀过滤，只删本校同步待办；
 * 8. 失败分类不兜底：绝不把所有失败说成登录过期；
 * 9. 自动分组回填：组键按校命名空间（`<id>:homework` 等）；
 * 10. 跨校隔离：退出/换号只动本校数据；
 * 11. 会话认领 plan：[SyncPlan] 是会话持有的不透明句柄，跨会话传入必须失败。
 */

/** 连接状态（与各校同步状态文件一一同构；UI 只读本类型） */
data class SyncState(
    /** 当前连接的远端用户 id；空串 = 未连接，账号切换检测的比对键 */
    val uid: String = "",
    val accountName: String = "",
    val avatarUrl: String = "",
    val lastSyncAtMillis: Long = 0,
    /** 上次成功探活时刻（3 秒去抖基线） */
    val lastProbeAtMillis: Long = 0,
)

/** 已缓存头像（与 `filesDir/sync/<id>/avatar.img` 成对；version 用于 Coil 失效旧结果） */
data class AvatarImage(val file: File, val version: Long)

/** 登录/取数拿到的两段响应原文（解析与分类交给 [SyncService]） */
data class LoginRaw(val profileRaw: String, val todosRaw: String)

/**
 * 待办同步服务（必备槽位）。[busy] 在 prepare/commit/markSynced/reset 期间为 true，
 * UI 据此禁用学校选择入口。
 */
interface SyncService {
    val state: StateFlow<SyncState>
    val avatarImage: StateFlow<AvatarImage?>
    val busy: StateFlow<Boolean>

    suspend fun prepareSync(force: Boolean = false): PrepareOutcome
    suspend fun prepareFromLogin(raw: LoginRaw): PrepareOutcome
    suspend fun commitSync(plan: SyncPlan, selectedNewIds: Set<String>): CommitOutcome
    suspend fun markSynced(plan: SyncPlan): Boolean
    suspend fun resetSyncData(deleteSyncedTodos: Boolean)
}

/**
 * 预览计划（会话持有的不透明句柄）：UI 只读归一化属性，commit/markSynced 时由产出它的
 * 会话认领自己的实现。预览按 `todo.sourceDetail` 分组（与现状一致）；[SyncPlanItem.courseName]
 * 仅作引擎内部分组键，UI 不读。
 */
interface SyncPlan {
    val newItems: List<SyncPlanItem>
    val restoreCount: Int
    val skippedTotal: Int
    val deadlineUnparseableCount: Int
}

data class SyncPlanItem(val todo: Todo, val courseName: String)

sealed interface PrepareOutcome {
    data class Ready(val plan: SyncPlan) : PrepareOutcome
    data class Failed(val error: SyncError) : PrepareOutcome
}

data class SyncReport(val imported: Int, val restored: Int, val skippedFiltered: Int)

sealed interface CommitOutcome {
    data class Done(val report: SyncReport) : CommitOutcome
    /** 落库失败分类沿用 data.TodoError（与手动同步同口径） */
    data class Failed(val error: TodoError) : CommitOutcome
}

/** prepare/commit 的失败分类（指导 §5.5）；宽严与各校实现一一同构，绝不兜底成登录过期 */
enum class SyncError {
    SessionExpired,
    ServiceBusy,
    NetworkError,
    Timeout,
    FormatChanged,
    AccountChanged,
    TooSoon,
    PersistFailed,
}

/** 自动续期服务（仅 [Capability.NATIVE_LOGIN] 学校提供） */
interface AutoRenewService {
    val state: StateFlow<AutoRenewState>
    /** enable/disable 在途；UI 据此禁用学校选择入口 */
    val busy: StateFlow<Boolean>

    suspend fun enable(username: String, password: String): EnableOutcome
    suspend fun disable()
}

/** 自动续期对 UI 暴露的状态（[available] = 系统密钥库可用） */
data class AutoRenewState(
    val policy: AutoRenewPolicy = AutoRenewPolicy.USER_LOGGED_OUT,
    val hasCredentials: Boolean = false,
    val available: Boolean = true,
)

enum class AutoRenewPolicy {
    ENABLED,
    BLOCKED,
    USER_LOGGED_OUT,
}

sealed interface EnableOutcome {
    data class Ready(val raw: LoginRaw) : EnableOutcome
    data class Failed(val error: LoginError) : EnableOutcome
}

/** 原生登录失败分类（UI 按类给文案） */
enum class LoginError {
    NeedCaptcha,
    InvalidCredentials,
    StorageUnavailable,
    ServiceBusy,
    NetworkError,
    Timeout,
    FormatChanged,
    /** 登录成功但随后取数仍被判定为过期：链路异常，改用网页登录 */
    SessionNotEstablished,
}

/** 教务课表导入服务（可选能力；不实现则 UI 自动隐藏入口） */
interface ScheduleImportService {
    /** null = 可导入；否则 UI 直接显示阻断提示（不再由 UI 拼 hasCredentials） */
    val blockedReason: StateFlow<ImportBlockReason?>

    /**
     * 非空 = 支持网页方式导入：WebView 登录（无保存凭据）时，教务导入浮层在
     * 「账号与同步」卡下方显示入口，点击打开 [entryUrl] 对应的教务课表页。
     * null = 不提供该入口。
     */
    val webViewTimetable: WebViewTimetableSpec? get() = null

    suspend fun fetchSemesters(): SemestersOutcome

    /** 只取数 + 映射；落库仍由 UI 调 `ScheduleRepository.importSchedule(ShareCodec…)` */
    suspend fun fetchSchedule(semester: ImportSemester): ImportOutcome
}

enum class ImportBlockReason {
    NotConnected,
    NoCredentials,
    Unsupported,
}

/** 可导入学期（教务学期代码 + 展示文案） */
data class ImportSemester(val id: String, val name: String)

sealed interface SemestersOutcome {
    data class Ok(val semesters: List<ImportSemester>) : SemestersOutcome
    data class Failed(val error: ImportError) : SemestersOutcome
}

sealed interface ImportOutcome {
    data class Ok(val schedule: Schedule) : ImportOutcome
    data object NoCourses : ImportOutcome
    data class Failed(val error: ImportError) : ImportOutcome
}

/** 教务导入失败分类（9 类全部保留，含 LoginFormatChanged，勿合并） */
enum class ImportError {
    NeedLogin,
    NeedCaptcha,
    InvalidCredentials,
    SessionExpired,
    ServiceBusy,
    NetworkError,
    Timeout,
    LoginFormatChanged,
    FormatChanged,
}

/**
 * WebView 登录的声明式配置（纯数据：入口 URL / 白名单 / 端点 / UA）。
 * 加固策略由通用 `WebViewPolicy` 锁死，spec 无法关闭任何加固项。
 */
data class WebViewLoginSpec(
    val entryUrl: String,
    /** 允许导航的 host（仅 https）；必须含 [dataHost]，不同学校的集合不得重叠 */
    val allowedHosts: Set<String>,
    /** 唯一注入取数脚本的页面 host */
    val dataHost: String,
    /** 如 `/api/profile` */
    val profilePath: String,
    /** 如 `/api/todos` */
    val todosPath: String,
    /** null = 系统默认；厦大用桌面 UA */
    val userAgent: String?,
)

/**
 * 教务课表页的 WebView 声明（纯数据：入口 URL / 白名单 / UA）。
 *
 * 用途：WebView 登录模式下没有保存凭据、无法走原生教务导入时，从「教务导入」浮层
 * 打开学校教务课表页（登录与查看由页面完成）。加固策略与 [WebViewLoginSpec] 同源，
 * 锁死在 `WebViewPolicy`，spec 无法关闭任何加固项。
 */
data class WebViewTimetableSpec(
    val entryUrl: String,
    /** 允许导航的 host（仅 https）；入口域名与登录跳转涉及的 CAS 域都要列入 */
    val allowedHosts: Set<String>,
    /** null = 系统默认；教务站通常需要桌面 UA */
    val userAgent: String? = null,
)
