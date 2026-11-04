package com.fangyi.classpp.data.ai

import kotlinx.serialization.Serializable

/**
 * AI 起草的单条待办草稿（JSON schema，即 system prompt 里声明的输出契约）。
 *
 * 字段覆盖 [com.fangyi.classpp.data.model.Todo] 的完整参数面：日期/全天/时段/截止/
 * 截止随重复/紧急度/标签/分组/备注/步骤/地点/倒计时/置顶/重复规则。字段全带默认值 +
 * 宽容解析（ignoreUnknownKeys）：模型多给/少给/给错字段名都不炸，映射时由
 * [AiTodoMapper] 做安全兜底——非法值退化为默认值而不是让整批落库失败。
 *
 * @param dates ISO 日期串（yyyy-MM-dd）；"下周三"这类相对日期由模型结合上下文推算
 * @param allDay 全天待办（时段两参缺席时生效）；有合法时段时以时段为准
 * @param startTime/endTime "HH:mm" → Todo 的 startMinute/endMinute（成对有效才算时段）
 * @param deadlineDate/deadlineTime 截止；app 端校验要求**成对出现**（TodoValidator）
 * @param deadlineBeforeRepeat 截止随重复：true = 截止日期自动调整为下一次重复开始前
 * @param deadlineBeforeDays 「下一次重复开始前」提前的天数
 * @param urgency Critical|High|Medium|Low；非法值归 None
 * @param tags/group 名字串；映射时与现有清单做包含匹配（见 AiTodoMapper）
 * @param steps 每条一个子项标题 → TodoStep
 * @param countdown 卡面倒计时开关（无截止时映射器置 false）
 * @param pinned 置顶
 * @param repeat 重复规则；null / type=None 表示不重复
 */
@Serializable
data class AiTodoDraft(
    val name: String = "",
    val dates: List<String> = emptyList(),
    val allDay: Boolean = false,
    val startTime: String? = null,
    val endTime: String? = null,
    val deadlineDate: String? = null,
    val deadlineTime: String? = null,
    val deadlineBeforeRepeat: Boolean = false,
    val deadlineBeforeDays: Int = 0,
    val urgency: String? = null,
    val tags: List<String> = emptyList(),
    val group: String? = null,
    val note: String = "",
    val steps: List<String> = emptyList(),
    val location: String = "",
    val countdown: Boolean = false,
    val pinned: Boolean = false,
    val repeat: AiRepeatDraft? = null,
)

/**
 * 草稿里的重复规则（[com.fangyi.classpp.data.model.RepeatRule] 的模型友好镜像）。
 *
 * 枚举全部用字符串声明、映射时大小写不敏感匹配；越界/非法值由 [AiTodoMapper]
 * 夹紧或退回默认——模型不需要精确记住 Kotlin 枚举名。
 *
 * @param type None|Daily|Weekly|Monthly|Yearly|Custom
 * @param unit Custom 的频率单位 Day|Week|Month|Year
 * @param interval Custom 的间隔（每 N 个单位）
 * @param marks Custom 的方格标记：Week=周几 1..7、Month=几号 1..31、Year=几月 1..12
 * @param endType Never|Date|Count（Date 需 endDate、Count 需 endCount）
 * @param endDate 结束日期（不含当天）
 * @param endCount 次数（普通模式期数 / 打卡模式完成次数）
 * @param merge 重复待办在列表里合并为一条滚动卡
 * @param checkIn 打卡模式（仅 Count 档有效）
 */
@Serializable
data class AiRepeatDraft(
    val type: String = "",
    val unit: String? = null,
    val interval: Int? = null,
    val marks: List<Int> = emptyList(),
    val endType: String? = null,
    val endDate: String? = null,
    val endCount: Int? = null,
    val merge: Boolean = true,
    val checkIn: Boolean = false,
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
     * 去掉回复里最后一个围栏块（待办已落库、改由卡片呈现时，正文不再展示原始 JSON）；
     * 无块时原样返回（只 trim）。流式过程中围栏未闭合时也不会误删正文。
     */
    fun stripJsonBlock(reply: String): String =
        FENCE.findAll(reply).lastOrNull()
            ?.let { reply.removeRange(it.range) }
            ?.trim()
            ?: reply.trim()

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
