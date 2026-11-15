package com.fangyi.classpp.schools.xmu

import com.fangyi.classpp.data.sync.engine.SyncNoteEnricher
import com.fangyi.classpp.data.xmu.XmuActivityRequirement
import com.fangyi.classpp.data.xmu.XmuActivityRequirementSource
import com.fangyi.classpp.data.xmu.XmuTodoDto
import com.fangyi.classpp.data.xmu.XmuTodoMapper
import kotlinx.coroutines.CancellationException

/**
 * 厦大作业要求 → 待办备注的补充器。
 *
 * 按 [XmuTodoDto.courseId] 分组后调 [XmuActivityRequirementsClient] 批量取课程活动，
 * 再按 activity id 匹配当前同步的远端条目；只有确实取到正文或附件时才返回备注，
 * 两者都空 = 不修改（避免把存量备注清空）。正文/附件名按上限截断，防止备注过长。
 *
 * 失败（网络/解析/课程缺失）由 client 吞掉；这里再兜一层异常，保证同步主流程不受影响。
 */
internal class XmuNoteEnricher(
    private val requirements: XmuActivityRequirementSource,
) : SyncNoteEnricher<XmuTodoDto> {

    override suspend fun notesFor(remotes: List<XmuTodoDto>): Map<String, String> {
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

        val notes = LinkedHashMap<String, String>()
        for (remote in remotes) {
            val requirement = fetched[remote.courseId]?.get(remote.id) ?: continue
            val capped = requirement.capped() ?: continue
            notes[XmuTodoMapper.remoteKey(remote)] = XmuTodoMapper.buildNote(remote, capped)
        }
        return notes
    }

    /** 截断到备注可接受的长度；正文与附件都为空时返回 null（不产生备注） */
    private fun XmuActivityRequirement.capped(): XmuActivityRequirement? {
        val text = description.trim().let {
            if (it.length <= MAX_DESCRIPTION_CHARS) {
                it
            } else {
                it.take(MAX_DESCRIPTION_CHARS).trimEnd() + "…"
            }
        }
        val names = attachments.asSequence()
            .map { it.trim().take(MAX_ATTACHMENT_NAME_CHARS) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_ATTACHMENTS)
            .toList()
        return XmuActivityRequirement(text, names).takeIf { it.hasContent }
    }

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 1200
        const val MAX_ATTACHMENTS = 8
        const val MAX_ATTACHMENT_NAME_CHARS = 100
    }
}
