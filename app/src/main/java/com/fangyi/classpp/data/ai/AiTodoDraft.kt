package com.fangyi.classpp.data.ai

import kotlinx.serialization.Serializable

/**
 * AI 起草的单条待办草稿（JSON schema，即 system prompt 里声明的输出契约）。
 *
 * 字段全带默认值 + 宽容解析（ignoreUnknownKeys）：模型多给/少给/给错字段名都不炸，
 * 缺什么由用户在预填编辑器里补。**MVP 刻意不含重复规则**——让模型生成 RepeatRule
 * 极易出错，用户在编辑器里手动加。
 *
 * @param dates ISO 日期串（yyyy-MM-dd）；"下周三"这类相对日期由模型结合上下文推算
 * @param startTime/endTime "HH:mm" → Todo 的 startMinute/endMinute（成对有效才算时段）
 * @param deadlineDate/deadlineTime 截止；app 端校验要求**成对出现**（TodoValidator），
 *   只有其一视为待人工修正
 * @param urgency Critical|High|Medium|Low；非法值归 None
 * @param tags/group 名字串；映射时与现有清单做包含匹配（见 AiTodoMapper）
 * @param steps 每条一个子项标题 → TodoStep
 */
@Serializable
data class AiTodoDraft(
    val name: String = "",
    val dates: List<String> = emptyList(),
    val startTime: String? = null,
    val endTime: String? = null,
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val urgency: String? = null,
    val tags: List<String> = emptyList(),
    val group: String? = null,
    val note: String = "",
    val steps: List<String> = emptyList(),
    val location: String = "",
)

/** 围栏 JSON 的信封：`{"todos": [...]}` */
@Serializable
data class AiTodoDraftPayload(val todos: List<AiTodoDraft> = emptyList())

/**
 * 从回复文本提取并解析待办草稿。
 *
 * 约定：模型在回复里输出 ` ```json … ``` ` 围栏块；**多个块取最后一个**（修复重试
 * 时模型会再输出一份修正版，以最后为准）。无围栏块 = 纯对话，返回 null。
 */
object AiTodoDraftCodec {

    /** ```json …```（json 标记可省）；非贪婪匹配，取最后一个块 */
    private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```")

    /** 提取回复里最后一个围栏块的内容；无块返回 null */
    fun extractJsonBlock(reply: String): String? =
        FENCE.findAll(reply).lastOrNull()?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * 解析围栏内容为草稿列表；结构完全解析不了返回 null（调用方据此发起修复重试）。
     *
     * 宽容形态：标准信封 `{"todos":[...]}`；裸数组 `[...]`；单个裸对象 `{"name":...}`
     * ——三者都收，降低对模型遵循度的要求（裸对象须含 "name" 键才收，避免把模型
     * 偶尔围起来的错误对象包成无名草稿）。
     */
    fun parseTodoDrafts(json: String): AiTodoDraftPayload? {
        val text = json.trim()
        return runCatching {
            when {
                text.startsWith("[") ->
                    AiJson.storage.decodeFromString(
                        AiTodoDraftPayload.serializer(),
                        """{"todos":$text}""",
                    )
                text.startsWith("{") && text.contains("\"todos\"") ->
                    AiJson.storage.decodeFromString(AiTodoDraftPayload.serializer(), text)
                text.startsWith("{") && text.contains("\"name\"") ->
                    AiJson.storage.decodeFromString(
                        AiTodoDraftPayload.serializer(),
                        """{"todos":[$text]}""",
                    )
                else -> null
            }
        }.getOrNull()
    }
}
