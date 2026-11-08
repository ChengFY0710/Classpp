package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.ReadResult
import com.fangyi.classpp.data.export.ShareCodec
import com.fangyi.classpp.data.model.Schedule

/**
 * AI 识别导入的回复解析：从模型回复里取 JSON 代码块，再走课表分享载荷的导入管线。
 *
 * 约定与 [AiScheduleImportPrompt] 一致：最后一个 ```json 围栏块即文件内容（修复重试
 * 时以最后一份为准）；容忍模型省略围栏、整段回复就是 JSON 的情况。解析与校验完全复用
 * [ShareCodec.decodeForImport]（版本/kind 闸门 → id 全量重铸 → `ScheduleValidator`），
 * 因此走到 [AiScheduleRecognition.Ok] 的课表必然可被 `ScheduleRepository.importSchedule`
 * 接受（名称去重除外）。
 */
internal object AiScheduleImportCodec {

    /** ```json …```（json 标记可省）；非贪婪匹配，取最后一个块 */
    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")

    /** 提取回复里最后一个围栏块；无块时若整段像 JSON（首 `{` 到末 `}`）也收下 */
    fun extractJsonBlock(reply: String): String? {
        val fenced = FENCE.findAll(reply).lastOrNull()
            ?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fenced != null) return fenced

        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return reply.substring(start, end + 1).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 解析回复；null = 回复里没有 JSON（模型纯文字回复），否则返回导入管线的结果。
     * 错误只承载解析/校验原因，网络与通道错误由仓库层另行映射。
     */
    fun parse(reply: String): ReadResult<Schedule>? {
        val json = extractJsonBlock(reply) ?: return null
        return ShareCodec.decodeForImport(json)
    }
}
