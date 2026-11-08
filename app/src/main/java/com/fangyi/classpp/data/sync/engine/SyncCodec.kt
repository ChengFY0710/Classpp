package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.sync.LoginRaw
import com.fangyi.classpp.data.sync.SchoolSpec

/**
 * 通用同步引擎的解析/映射契约（指导 §2.3/§四 Phase 4）：每所学校实现一份，
 * 把本校远端协议映射到 Classpp 的 [Todo]。[SchoolSyncEngine] 只依赖本接口，不认学校。
 *
 * 命名纪律：本地 id 必须以 `"<id>-"` 开头（[source] + "-"），退出清理与跨校隔离都靠它；
 * [matchesLocalId] 是 source 被清空时的兜底口径。
 */
interface SyncCodec<R : Any> {

    /** 学校 id（= [SchoolSpec.id]）：todo `source` 与自动分组键命名空间的派生源 */
    val source: String

    /**
     * 远端唯一键 → 本地 todo id（必须以 `"<source>-"` 开头）。
     * 重复同步天然幂等：同一远端条目恒得到同一本地 id。
     */
    fun localId(remoteKey: String): String

    /** 本地 id 是否属于本校（`source` 被清空时的兜底判定，退出清理也按它圈定） */
    fun matchesLocalId(id: String): Boolean

    /** 探活响应 → 账号信息；解析失败返回 null（引擎按响应体形态分类错误） */
    fun parseProfile(raw: String): RemoteProfile?

    /** 待办响应 → 远端条目列表；解析失败返回 null（引擎按响应体形态分类错误） */
    fun parseTodos(raw: String): List<R>?

    /** 校内唯一键（课程 + 条目等）：去重与 entries 映射的统一键 */
    fun remoteKey(remote: R): String

    /** 远端内容哈希（只落哈希，不存标题原文） */
    fun remoteHash(remote: R): String

    /** 远端条目 → 待办；null = 跳过（原因由 [skipReason] 提供，用于预览摘要计数） */
    fun toTodo(remote: R, fetchedAt: Long): Todo?

    /** 跳过原因（锁定/过期/非法等）；null = 可导入 */
    fun skipReason(remote: R): SkipReason?

    /** 远端类型 → 自动分组键（`"<id>:homework"` 等）；null = 不分组 */
    fun autoGroupKey(remote: R): String?

    /** 是否本校同步产生的待办（`source` 优先，id 前缀兜底） */
    fun isOwned(todo: Todo): Boolean
}

/** 账号信息（探活结果）：uid 是账号切换检测的比对键 */
interface RemoteProfile {
    val uid: String
    val accountName: String
    val avatarUrl: String?
}

/**
 * 跳过原因（预览摘要聚合展示）：
 * [NoDeadline]（远端没有截止时间）是合法业务形态；[DeadlineUnparseable]（非空但解析失败）
 * 是接口形态变化的信号，UI 单独提示，不能混在「无截止」里静默跳过。
 */
enum class SkipReason {
    NotStudent,
    InvalidId,
    BlankTitle,
    NoDeadline,
    DeadlineUnparseable,
    Locked,
    Overdue,
}

/** 取数失败分类（与各校实现一一同构，绝不兜底成登录过期） */
enum class FetchError {
    SessionExpired,
    ServiceBusy,
    NetworkError,
    Timeout,
    FormatChanged,
}

sealed interface FetchOutcome {
    data class Ok(val raw: LoginRaw) : FetchOutcome
    data class Failed(val error: FetchError) : FetchOutcome
}

/** 原生 / WebView 两条取数路径统一到此接口（指导 §2.3） */
fun interface SessionApi {
    suspend fun fetchSession(): FetchOutcome
}

/**
 * 自动分组的默认显示名（按校命名空间）：组键为 `"<source>:homework"` / `"<source>:quiz"`，
 * 名称只在分组**首次创建**时写入，之后按 `autoKey` 定位，用户重命名不会被同步覆盖。
 *
 * 多校组名冲突规则（指导 §九.2 建议默认）：新校默认名带校名（如「FZU 作业」），
 * 由学校在装配时传入；厦大保持「作业/测验」，视觉零变化。
 */
data class SchoolAutoGroupNames(
    /** = [SchoolSpec.id]（codec.source 必须与之一致） */
    val source: String,
    val homework: String = "作业",
    val quiz: String = "测验",
) {

    val homeworkKey: String = "$source:homework"
    val quizKey: String = "$source:quiz"

    /** 分组键的固定顺序（新建分组时按「作业、测验」追加，与远端返回顺序无关） */
    val orderedKeys: List<String> = listOf(homeworkKey, quizKey)

    /** 分组键 → 创建时使用的默认名；未知键返回 null（不建组） */
    fun nameFor(autoKey: String): String? = when (autoKey) {
        homeworkKey -> homework
        quizKey -> quiz
        else -> null
    }
}
