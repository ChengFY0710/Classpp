package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.ScheduleJson
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.sync.engine.RemoteProfile
import com.fangyi.classpp.data.sync.engine.SkipReason
import com.fangyi.classpp.data.sync.engine.SyncCodec
import com.fangyi.classpp.data.xmu.XmuAvatarExtractor
import com.fangyi.classpp.data.xmu.XmuMapOutcome
import com.fangyi.classpp.data.xmu.XmuProfileDto
import com.fangyi.classpp.data.xmu.XmuTodoDto
import com.fangyi.classpp.data.xmu.XmuTodoListDto
import com.fangyi.classpp.data.xmu.XmuTodoMapper
import com.fangyi.classpp.data.xmu.XmuTime
import com.fangyi.classpp.data.xmu.SkipReason as XmuSkipReason

/**
 * 厦大同步编码器：把冻结的 [XmuTodoMapper] 协议映射接到通用引擎的 [SyncCodec] 契约上。
 *
 * 全部语义委托冻结实现（零协议重复）：
 * - 本地 id 口径 `xmu-{courseId}-{activityId}`（formatVersion 2 起）与远端唯一键
 *   `xmu:{courseId}:{activityId}` 由 [XmuTodoMapper] 定义，与既有状态文件的哈希基线同源；
 * - 跳过/映射拆分经 [XmuTodoMapper.map]（[skipReason] 用注入时钟、[toTodo] 用引擎的
 *   `fetchedAt`，与改造前同一时刻口径）；`data.xmu` 与 `data.sync` 的 SkipReason 同名
 *   同值，映射函数一一同构不做兜底合并；
 * - [parseProfile] 的头像地址沿用 [XmuAvatarExtractor]（profile 原文候选键探测）。
 *
 * 哈希/键的稳定性是存量迁移的前提：迁移条目的 `remoteHash` 直接沿用旧文件，
 * 本类产出的哈希必须与旧编排实现（XmuSyncRepository，已退役）时代逐字节一致——由契约测试锁定。
 */
internal class XmuSyncCodec(
    private val clock: () -> Long,
) : SyncCodec<XmuTodoDto> {

    override val source: String = XmuTodoMapper.SOURCE_XMU

    override fun localId(remoteKey: String): String {
        // "xmu:{courseId}:{activityId}" → "xmu-{courseId}-{activityId}"（与 XmuTodoMapper.localId 同构）
        val parts = remoteKey.removePrefix("${XmuTodoMapper.SOURCE_XMU}:").split(':')
        val courseId = parts.getOrNull(0).orEmpty()
        val activityId = parts.getOrNull(1).orEmpty()
        return "${XmuTodoMapper.ID_PREFIX}$courseId-$activityId"
    }

    override fun matchesLocalId(id: String): Boolean = XmuTodoMapper.isSyncedLocalId(id)

    override fun parseProfile(raw: String): RemoteProfile? {
        val dto = try {
            ScheduleJson.decodeStorage<XmuProfileDto>(raw).takeIf { it.id > 0 }
        } catch (_: Exception) {
            null
        } ?: return null
        return object : RemoteProfile {
            override val uid: String = dto.id.toString()
            override val accountName: String = dto.name
            override val avatarUrl: String? = XmuAvatarExtractor.extract(raw)
        }
    }

    override fun parseTodos(raw: String): List<XmuTodoDto>? = try {
        ScheduleJson.decodeStorage<XmuTodoListDto>(raw).todoList
    } catch (_: Exception) {
        null
    }

    override fun remoteKey(remote: XmuTodoDto): String = XmuTodoMapper.remoteKey(remote)

    override fun remoteHash(remote: XmuTodoDto): String = XmuTodoMapper.remoteHash(remote)

    override fun toTodo(remote: XmuTodoDto, fetchedAt: Long): Todo? =
        when (val outcome = XmuTodoMapper.map(remote, fetchedAt)) {
            is XmuMapOutcome.Mapped -> outcome.todo
            is XmuMapOutcome.Skipped -> null
        }

    override fun skipReason(remote: XmuTodoDto): SkipReason? =
        when (val outcome = XmuTodoMapper.map(remote, clock())) {
            is XmuMapOutcome.Skipped -> outcome.reason.toEngineReason()
            is XmuMapOutcome.Mapped -> null
        }

    override fun autoGroupKey(remote: XmuTodoDto): String? = XmuTodoMapper.autoGroupKey(remote.type)

    override fun isOwned(todo: Todo): Boolean = todo.source == source || matchesLocalId(todo.id)

    override fun isOverdue(todo: Todo, nowMillis: Long): Boolean {
        val date = todo.deadlineDate ?: return false
        val minute = todo.deadlineMinute ?: return false
        return XmuTime.isOverdue(XmuTime.Deadline(date, minute), nowMillis)
    }

    /** 两个枚举同名同值（引擎与冻结层各持一份）：一一同构映射，不做兜底合并 */
    private fun XmuSkipReason.toEngineReason(): SkipReason = when (this) {
        XmuSkipReason.NotStudent -> SkipReason.NotStudent
        XmuSkipReason.InvalidId -> SkipReason.InvalidId
        XmuSkipReason.BlankTitle -> SkipReason.BlankTitle
        XmuSkipReason.NoDeadline -> SkipReason.NoDeadline
        XmuSkipReason.DeadlineUnparseable -> SkipReason.DeadlineUnparseable
        XmuSkipReason.Locked -> SkipReason.Locked
        XmuSkipReason.Overdue -> SkipReason.Overdue
    }
}
