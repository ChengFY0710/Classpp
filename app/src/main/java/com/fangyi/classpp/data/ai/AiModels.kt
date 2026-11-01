package com.fangyi.classpp.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 对话历史存储文件的格式版本；装载时拒绝更高版本（与 TODO_FORMAT_VERSION 同策略） */
const val AI_CHAT_FORMAT_VERSION = 1

/**
 * 请求协议（协议差异的编解码见 [AiStreamCodec]）：
 * - [OpenAiCompat]：OpenAI 兼容 `/chat/completions`（DeepSeek/智谱/通义/Kimi/Ollama 同协议），
 *   旧持久化配置无此字段时回落此默认值；
 * - [Anthropic]：Anthropic Messages API（`/v1/messages`）。
 */
@Serializable
enum class AiProtocol { OpenAiCompat, Anthropic }

/**
 * AI 连接配置（BYOK）：服务商预设 id + 端点根路径 + 模型名 + 接口协议。
 *
 * 这份是**明文可备份**的全部——API Key 永不在此，只存 Keystore 加密后的
 * SharedPreferences（见 [AiSettingsStore]，与 xmu_secure 的凭据策略同源），
 * 备份/迁移规则同步排除 `ai_secure.xml`（Keystore 密钥不随备份迁移，密文解不开）。
 *
 * @param providerId 服务商预设 id（[AiProviderPresets]，"custom" = 用户自定义）
 * @param baseUrl 端点根路径（如 https://api.deepseek.com/v1，路径拼接由各协议编解码器负责）
 * @param model 模型名（如 deepseek-chat）
 * @param protocol 接口协议；带默认值——旧版本落盘的 JSON 无此字段，反序列化得
 *   [AiProtocol.OpenAiCompat]，既有配置行为不变
 */
@Serializable
data class AiConnection(
    val providerId: String = AiProviderPresets.CUSTOM_ID,
    val baseUrl: String = "",
    val model: String = "",
    val protocol: AiProtocol = AiProtocol.OpenAiCompat,
) {
    /** 是否已具备发起请求的最低配置；API Key 由 [AiSettingsStore.loadApiKey] 另查 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/** 对话消息的角色；历史里只存 user / assistant（system prompt 每次现拼，不入文件） */
@Serializable
enum class AiChatRole { User, Assistant }

/** 草稿卡上单条草稿的处置状态；Saved/Dismissed 后重进对话不再重复弹卡（见 AiTodoDraftCard） */
@Serializable
enum class AiDraftStatus { Pending, Saved, Dismissed }

/**
 * assistant 消息携带的待办草稿卡：内容 + 每条处置状态（两表等长，按位对应）。
 * 草稿的「待人工修正」标注（[AiDraftField]）不落盘——装载时由 AiTodoMapper 重算。
 */
@Serializable
data class AiMessageDrafts(
    val drafts: List<AiTodoDraft> = emptyList(),
    val statuses: List<AiDraftStatus> = emptyList(),
)

/**
 * 一条对话消息（ai_chat.json 的成员）。
 *
 * @param createdAtMillis 创建时刻（epoch millis），由仓库在追加时补齐
 * @param interrupted 流式中断标记：true = 这条 assistant 消息收到一半被停止/失败，
 *   UI 显示「已中断 + 重试」；user 消息恒 false
 * @param drafts 消息携带的待办草稿卡；null = 无草稿（纯对话消息）
 */
@Serializable
data class AiChatMessage(
    val role: AiChatRole,
    val content: String,
    val createdAtMillis: Long = 0,
    val interrupted: Boolean = false,
    val drafts: AiMessageDrafts? = null,
)

/**
 * AI 调用的错误分类。数据层只分类不产出文案——文案由 UI 层映射 stringResource
 * （项目有 values-en，中文硬编码在数据层会破坏本地化）。
 */
enum class AiError { Auth, Quota, NotFound, RateLimited, Server, Network, BadResponse }

/**
 * 携带分类的 AI 调用异常；[detail] 供日志/排查，内容来自服务商响应体或 IO 消息，
 * **不含 API Key 与 Authorization 头**（客户端不打印请求头，见 AiClient）。
 */
class AiException(val error: AiError, val detail: String? = null) :
    Exception(detail ?: error.name)

/** 一次对话调用中的一条 wire 消息（协议中性的内部表示，role 用小写串；各协议编解码器负责落成自己的请求形状） */
@Serializable
data class AiChatTurn(val role: String, val content: String)

/** AI 模块的 Json 配置：与 ScheduleJson.storage 同参（ignoreUnknownKeys + encodeDefaults + 严格） */
internal object AiJson {
    val storage: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        prettyPrint = false
    }
}
