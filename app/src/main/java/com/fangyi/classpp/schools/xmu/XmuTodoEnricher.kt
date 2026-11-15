package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.model.TodoAttachment
import com.fangyi.classpp.data.store.TodoAttachmentStore
import com.fangyi.classpp.data.sync.engine.SyncTodoEnricher
import com.fangyi.classpp.data.sync.engine.SyncTodoExtras
import com.fangyi.classpp.data.xmu.XmuActivityAttachment
import com.fangyi.classpp.data.xmu.XmuActivityRequirement
import com.fangyi.classpp.data.xmu.XmuActivityRequirementSource
import com.fangyi.classpp.data.xmu.XmuAttachmentMime
import com.fangyi.classpp.data.xmu.XmuTodoDto
import com.fangyi.classpp.data.xmu.XmuTodoMapper
import com.fangyi.classpp.data.xmu.XmuWebViewPolicy
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

/**
 * 厦大作业要求 → 待办备注 + 附件元数据的补充器。
 *
 * 按 [XmuTodoDto.courseId] 分组后调 [XmuActivityRequirementSource] 批量取课程活动，
 * 再按 activity id 匹配当前同步的远端条目：
 * - 备注 = 课程/类型/作业要求/活动链接（附件名不写进备注，改由详情页「附件」区展示）；
 * - 附件 = 远端上传元数据（id/name/type/size/reference_id → [TodoAttachment]），
 *   `remoteUrl` 指向课程中心 blob 地址，同步时**不下载文件**，用户点击时才下载；
 * - 活动在课程列表里找不到或取数失败 = 不产生该条，保持待办原备注/附件。
 *
 * 正文/文件名按上限截断；单个附件异常只跳过该附件。失败由 client 吞掉，这里再兜一层，
 * 保证同步主流程不受影响。
 */
internal class XmuTodoEnricher(
    private val requirements: XmuActivityRequirementSource,
) : SyncTodoEnricher<XmuTodoDto> {

    override suspend fun extrasFor(remotes: List<XmuTodoDto>): Map<String, SyncTodoExtras> {
        if (remotes.isEmpty()) return emptyMap()
        val courseIds = remotes.asSequence()
            .map { it.courseId }
            .filter { it > 0L }
            .distinct()
            .toList()
        if (courseIds.isEmpty()) return emptyMap()
        val fetched = try {
            requirements.fetch(courseIds)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyMap()
        }
        if (fetched.isEmpty()) return emptyMap()

        val extras = LinkedHashMap<String, SyncTodoExtras>()
        for (remote in remotes) {
            val requirement = fetched[remote.courseId]?.get(remote.id) ?: continue
            val capped = requirement.withCappedDescription()
            extras[XmuTodoMapper.remoteKey(remote)] = SyncTodoExtras(
                note = XmuTodoMapper.buildNote(remote, capped),
                remoteAttachments = requirement.attachments.mapNotNull { it.toTodoAttachment(remote) },
            )
        }
        return extras
    }

    private fun XmuActivityRequirement.withCappedDescription(): XmuActivityRequirement {
        val text = description.trim().let {
            if (it.length <= MAX_DESCRIPTION_CHARS) {
                it
            } else {
                // 不在 UTF-16 代理对中间截断，避免备注里出现半个 emoji
                val end = if (it[MAX_DESCRIPTION_CHARS - 1].isHighSurrogate()) {
                    MAX_DESCRIPTION_CHARS - 1
                } else {
                    MAX_DESCRIPTION_CHARS
                }
                it.take(end).trimEnd() + "…"
            }
        }
        return copy(description = text)
    }

    private fun XmuActivityAttachment.toTodoAttachment(remote: XmuTodoDto): TodoAttachment? {
        val displayName = name.trim()
        if (displayName.isEmpty()) return null
        val remoteId = referenceId.takeIf { it > 0L } ?: id.takeIf { it > 0L } ?: return null
        val base = "https://${XmuWebViewPolicy.LNT_HOST}"
        val remoteUrl = if (referenceId > 0L) {
            "$base/api/uploads/reference/$referenceId/blob"
        } else {
            "$base/api/uploads/$id/blob"
        }
        val rawId = "xmu-${remote.courseId}-${remote.id}-$remoteId"
        val attachmentId = if (rawId.length <= MAX_ATTACHMENT_ID_CHARS) {
            rawId
        } else {
            "xmu-" + sha256(rawId).take(40)
        }
        return TodoAttachment(
            id = attachmentId,
            name = displayName,
            fileName = TodoAttachmentStore.safeFileName(displayName),
            mimeType = XmuAttachmentMime.resolve(displayName, type),
            sizeBytes = sizeBytes.coerceAtLeast(0),
            remoteUrl = remoteUrl,
        )
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 1200

        /** 与 TodoAttachmentStore.isSafeId 的长度上限一致 */
        const val MAX_ATTACHMENT_ID_CHARS = 64
    }
}
