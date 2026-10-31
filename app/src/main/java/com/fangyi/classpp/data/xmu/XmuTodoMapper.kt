package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import java.security.MessageDigest

/** 单条远端待办的映射结果：成功映射成 [Todo]，或按原因跳过（同步层计数展示） */
sealed interface XmuMapOutcome {
    data class Mapped(val todo: Todo) : XmuMapOutcome
    data class Skipped(val reason: SkipReason) : XmuMapOutcome
}

/**
 * 跳过原因（预览摘要聚合展示；InvalidId/NotStudent 属接口异常形态，计入「无效」）。
 * [NoDeadline]（end_time 为空）是合法业务形态；[DeadlineUnparseable]（非空但解析失败）
 * 是接口形态变化的信号，UI 单独提示，不能混在「无截止」里静默跳过。
 */
enum class SkipReason {
    NotStudent, InvalidId, BlankTitle, NoDeadline, DeadlineUnparseable, Locked, Overdue,
}

/**
 * 课程中心待办 → Classpp [Todo] 的映射与过滤。
 *
 * 本地 id 取确定性 `xmu-{courseId}-{activityId}`：重复同步天然幂等——再次导入只会撞
 * [com.fangyi.classpp.data.TodoError.DuplicateId]，不会产生重复待办。只设截止
 * （timeKind=None + deadline 成对）、不设时段/日期；只增不改，不写回远端。
 */
object XmuTodoMapper {

    const val SOURCE_XMU = "xmu"
    const val ID_PREFIX = "${SOURCE_XMU}-"

    /**
     * 远端唯一键（课程 + activity id）：buildPlan 去重与 entries 映射（内存/落盘）的统一键。
     * 与 [localId] 同含 courseId——activity id 若只在课程内唯一，同 id 不同课程也不会互相顶掉。
     */
    fun remoteKey(dto: XmuTodoDto): String = remoteKey(dto.courseId, dto.id)

    /** 映射条目的远端唯一键（与 dto 同口径）：entries 的内存 map 一律按它配对，不能按 remoteId 单键 */
    fun remoteKey(entry: XmuSyncEntry): String = remoteKey(entry.courseId, entry.remoteId)

    fun remoteKey(courseId: Long, activityId: Long): String = "xmu:$courseId:$activityId"

    /**
     * 确定性本地 id：`xmu-{courseId}-{activityId}`。courseId 一并入键——activity id 若只在
     * 课程内唯一，同 id 不同课程也不会互相顶掉（formatVersion 2 起）。
     */
    fun localId(dto: XmuTodoDto): String = "$ID_PREFIX${dto.courseId}-${dto.id}"

    /**
     * formatVersion 1 的本地 id 口径（不含 courseId）：**只用于**升级或映射丢失时找回旧条目，
     * 新导入不再使用。见 [com.fangyi.classpp.data.xmu.XmuSyncRepository] 的 buildPlan。
     */
    fun legacyLocalId(dto: XmuTodoDto): String = "$ID_PREFIX${dto.id}"

    /** 判断本地待办是否来自课程中心（来源标识之外的防御性口径，退出清理也按它圈定） */
    fun isSyncedLocalId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** 跳转链接：页面现有路由格式（推导自站内链接；平台改版则退化为课程主页，见指导 §2.4） */
    fun activityUrl(dto: XmuTodoDto): String =
        "https://lnt.xmu.edu.cn/course/${dto.courseId}/learning-activity#/${dto.id}"

    /**
     * 映射一条远端待办；不满足导入条件返回 Skipped（原因见 [SkipReason]）：
     * 非学生 / id 非法 / 标题空白 / 截止缺失或解析失败，以及默认滤掉的已锁定与已过期
     * （[includeClosedOrOverdue]=true 时后两类放行，预留给设置开关）。
     * 截止为空与「非空但解析不了」分开计数：后者是接口形态变化的信号。
     */
    fun map(
        dto: XmuTodoDto,
        nowMillis: Long,
        includeClosedOrOverdue: Boolean = false,
    ): XmuMapOutcome {
        if (!dto.isStudent) return XmuMapOutcome.Skipped(SkipReason.NotStudent)
        if (dto.id <= 0L) return XmuMapOutcome.Skipped(SkipReason.InvalidId)
        val name = dto.title.trim()
        if (name.isEmpty()) return XmuMapOutcome.Skipped(SkipReason.BlankTitle)
        val end = dto.endTime
        val deadline = XmuTime.parseDeadline(end)
            ?: return XmuMapOutcome.Skipped(
                if (end.isNullOrBlank()) SkipReason.NoDeadline else SkipReason.DeadlineUnparseable,
            )
        if (dto.isLocked && !includeClosedOrOverdue) return XmuMapOutcome.Skipped(SkipReason.Locked)
        if (!includeClosedOrOverdue && XmuTime.isOverdue(deadline, nowMillis)) {
            return XmuMapOutcome.Skipped(SkipReason.Overdue)
        }
        return XmuMapOutcome.Mapped(
            Todo(
                id = localId(dto),
                name = name,
                timeKind = TodoTimeKind.None,
                deadlineDate = deadline.date,
                deadlineMinute = deadline.minute,
                countdown = true,
                note = buildNote(dto),
                source = SOURCE_XMU,
                sourceDetail = dto.courseName.trim().ifEmpty { null },
            ),
        )
    }

    /** 备注正文：课程名 + 类型 + 跳转链接（详情浮层直接可见可复制） */
    fun buildNote(dto: XmuTodoDto): String = buildString {
        append("课程：").append(dto.courseName)
        append("\n类型：").append(dto.type)
        append("\n").append(activityUrl(dto))
    }

    /** 远端内容哈希（标题|截止|课程|类型）：P2 判断远端是否变化用；只落哈希不落标题原文 */
    fun remoteHash(dto: XmuTodoDto): String = sha256(
        listOf(dto.title.trim(), dto.endTime ?: "", dto.courseName, dto.type).joinToString("|"),
    )

    /** 本地内容哈希：导入时对映射结果取哈希，作为「用户是否编辑过」的基线（P2 冲突检测用） */
    fun localHash(todo: Todo): String = sha256(
        listOf(
            todo.name,
            todo.deadlineDate?.toString() ?: "",
            todo.deadlineMinute?.toString() ?: "",
            todo.note,
        ).joinToString("|"),
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
