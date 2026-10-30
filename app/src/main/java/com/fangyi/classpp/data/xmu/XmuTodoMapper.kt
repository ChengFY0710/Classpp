package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoTimeKind
import java.security.MessageDigest

/** 单条远端待办的映射结果：成功映射成 [Todo]，或按原因跳过（同步层计数展示） */
sealed interface XmuMapOutcome {
    data class Mapped(val todo: Todo) : XmuMapOutcome
    data class Skipped(val reason: SkipReason) : XmuMapOutcome
}

/** 跳过原因（预览摘要聚合展示；InvalidId/NotStudent 属接口异常形态，计入「无效」） */
enum class SkipReason { NotStudent, InvalidId, BlankTitle, NoDeadline, Locked, Overdue }

/**
 * 课程中心待办 → Classpp [Todo] 的映射与过滤。
 *
 * 本地 id 取确定性 `xmu-{activityId}`（远端 id 已实测为全局 activity id）：重复同步天然
 * 幂等——再次导入只会撞 [com.fangyi.classpp.data.TodoError.DuplicateId]，不会产生重复待办。
 * 只设截止（timeKind=None + deadline 成对）、不设时段/日期；只增不改，不写回远端。
 */
object XmuTodoMapper {

    const val SOURCE_XMU = "xmu"
    const val ID_PREFIX = "${SOURCE_XMU}-"

    /**
     * 远端唯一键：保守拼上 courseId，防 activity id 语义在平台改版后漂移。
     * 同步映射文件（XmuSyncEntry）的哈希与配对都以它为口径。
     */
    fun remoteKey(dto: XmuTodoDto): String = "xmu:${dto.courseId}:${dto.id}"

    /** 确定性本地 id：同一远端条目永远映射到同一本地 id */
    fun localId(dto: XmuTodoDto): String = "$ID_PREFIX${dto.id}"

    /** 判断本地待办是否来自课程中心（来源标识之外的防御性口径，退出清理也按它圈定） */
    fun isSyncedLocalId(id: String): Boolean = id.startsWith(ID_PREFIX)

    /** 跳转链接：页面现有路由格式（推导自站内链接；平台改版则退化为课程主页，见指导 §2.4） */
    fun activityUrl(dto: XmuTodoDto): String =
        "https://lnt.xmu.edu.cn/course/${dto.courseId}/learning-activity#/${dto.id}"

    /**
     * 映射一条远端待办；不满足导入条件返回 Skipped（原因见 [SkipReason]）：
     * 非学生 / id 非法 / 标题空白 / 截止缺失或解析失败，以及默认滤掉的已锁定与已过期
     * （[includeClosedOrOverdue]=true 时后两类放行，预留给设置开关）。
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
        val deadline = XmuTime.parseDeadline(dto.endTime)
            ?: return XmuMapOutcome.Skipped(SkipReason.NoDeadline)
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
