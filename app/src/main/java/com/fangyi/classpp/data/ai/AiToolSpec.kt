package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工具权限级别（设计稿 §7.1）：决定执行前是否需要用户确认。
 */
enum class AiToolPermission {
    /** 只读：自动执行 */
    Read,

    /** 可逆写：自动执行，UI 提供撤销 */
    WriteReversible,

    /** 破坏性写：只生成待确认记录，不直接写库 */
    WriteConfirm,
}

/**
 * 原生工具能力探测结果（按连接缓存，非敏感，明文 SharedPreferences）：
 * - [Unknown]：下一次请求带 tools 探测；
 * - [Supported]：曾成功收到 tool_calls；
 * - [Unsupported]：带 tools 失败、去掉 tools 重试成功——后续按 JSON 回退。
 */
enum class AiToolCapability { Unknown, Supported, Unsupported }

/**
 * 能力缓存的键：连接身份（不含 API Key）。端点/模型/协议任一变化都重新探测，
 * 避免把一套配置的结论错误复用到另一套。
 */
internal fun AiConnection.toolCapabilityKey(): String =
    "$providerId|$baseUrl|$model|${protocol.name}"

/**
 * JSON 回退信封里的一段动作（例如 `todos`、`updates`）。
 *
 * 原生 tools 模式不发送这些段落；回退模式由 [AiToolRegistry] 把各段的 [example]
 * 按声明顺序拼成 system prompt 里的契约文本——字段只在这里定义一次。
 *
 * @param key 信封顶层键（沿用既有 JSON 契约的 camelCase）
 * @param example 示例值（对象或数组）；序列化后即提示词里展示的契约
 */
data class AiToolEnvelopeSection(
    val key: String,
    val example: JsonElement,
)

/**
 * 一个工具的协议中性定义（设计稿 §4.1）。
 *
 * @param name 工具名（snake_case，与既有 JSON 契约一致）
 * @param description 1~3 句工具描述（每次请求都要付 token，避免写长）
 * @param parameters JSON Schema（扁平对象；只使用 type/properties/required/enum/description，
 *   不使用 $ref / oneOf / anyOf / allOf / patternProperties）
 * @param permission 执行策略
 * @param envelope JSON 回退信封里的动作段；空 = 该工具仅支持原生 tools
 * @param execute 参数已通过校验后的执行入口，返回结构化 JSON；P0 只落契约，
 *   执行器在 P1/P2 接入，未接入时为 null
 */
data class AiToolSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val permission: AiToolPermission,
    val envelope: List<AiToolEnvelopeSection> = emptyList(),
    val execute: (suspend (args: JsonObject, ctx: AiChatContext) -> JsonObject)? = null,
)
