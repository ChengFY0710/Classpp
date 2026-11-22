package com.fangyi.classpp.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 对话历史存储文件的格式版本；装载时拒绝更高版本（与 TODO_FORMAT_VERSION 同策略）。
 *
 * v3 起 `AiChatMessage` 增加工具轮次（`toolRounds`）：旧 v2 文件因字段默认值可正常装载；
 * 旧版应用读到 v3 会按「未来版本」隔离重置（项目既有策略，降级丢历史）。
 */
const val AI_CHAT_FORMAT_VERSION = 3

/**
 * 推理等级按位置映射的档位范围（含首档「关」）：2~5 个。
 * 超出范围的等级列表不识别「关」档，展示也回退为等级原文（见 [AiModelConfig.isReasoningOff]）；
 * 拉取模型补「关」占位档时同样受此上限约束。
 */
internal val ReasoningLevelCountRange = 2..5

/**
 * 请求协议（协议差异的编解码见 [AiStreamCodec]）：
 * - [OpenAiCompat]：OpenAI 兼容 `/chat/completions`（DeepSeek/智谱/通义/Kimi/Ollama 同协议），
 *   旧持久化配置无此字段时回落此默认值；
 * - [Anthropic]：Anthropic Messages API（`/v1/messages`）。
 */
@Serializable
enum class AiProtocol { OpenAiCompat, Anthropic }

/**
 * 单个模型的附加配置：模型 id 在 [AiConnection.models] 里，这里只存与该模型一起落库的
 * 模型级参数（当前为推理等级）。
 *
 * @param reasoningLevels 可选的推理等级（用户自由填写，忽略大小写去重）
 * @param reasoningLevel 当前选中的推理等级；等级列表非空时必为其中一项（空列表时为空串）
 */
@Serializable
data class AiModelConfig(
    val reasoningLevels: List<String> = emptyList(),
    val reasoningLevel: String = "",
) {
    /**
     * 保存前的规范化：等级 trim、去空串、忽略大小写去重（保留首次出现的写法）；
     * 列表非空时选中项忽略大小写对齐列表写法，缺失则回落首个——SelectCard 单选必须有值。
     */
    fun normalized(): AiModelConfig {
        val cleaned = ArrayList<String>(reasoningLevels.size)
        reasoningLevels.forEach { raw ->
            val candidate = raw.trim()
            if (candidate.isNotEmpty() && cleaned.none { it.equals(candidate, ignoreCase = true) }) {
                cleaned += candidate
            }
        }
        val selected = cleaned.firstOrNull { it.equals(reasoningLevel.trim(), ignoreCase = true) }
            ?: cleaned.firstOrNull()
            ?: ""
        return AiModelConfig(reasoningLevels = cleaned, reasoningLevel = selected)
    }

    /** 空配置不落库：没有推理等级 = 该模型不使用推理等级 */
    val isEmpty: Boolean get() = reasoningLevels.isEmpty() && reasoningLevel.isBlank()

    /**
     * 当前选中等级是否表示「不开启思考」：等级列表按从低到高排列（2~5 个档位），
     * 第一个即「关」——选中它时请求不发送任何推理参数。其它数量（1 / >5）没有
     * 「关」档位概念，按原样发送等级。
     *
     * 仅对支持关闭思考的控制形态生效（[AiThinkingControl.supportsOff]）；始终思考的
     * 模型族（Kimi K3、GLM-5.3）首档是最低强度，请求侧不会把首档当关。
     */
    fun isReasoningOff(): Boolean {
        if (reasoningLevels.size !in ReasoningLevelCountRange) return false
        val first = reasoningLevels.firstOrNull() ?: return false
        return reasoningLevel.equals(first, ignoreCase = true)
    }
}

/**
 * AI 连接配置（BYOK）：服务商预设 id + 端点根路径 + 模型名 + 接口协议。
 *
 * 一份连接可保存多个模型名（[models]），[model] 始终是**当前生效**的那一个：供应商与
 * API Key 配置一次，对话浮层即可在列表里切换，请求层只读 [model]。旧版本落盘的 JSON
 * 只有单模型字段，反序列化后 [models] 为空列表，[modelOptions] 自动回落为 `[model]`，
 * 不需要迁移。模型级参数（推理等级）按模型 id 另存在 [modelConfigs]：带默认值，旧 JSON
 * 缺字段时为空 map，行为与改造前一致。
 *
 * 这份是**明文可备份**的全部——API Key 永不在此，只存 Keystore 加密后的
 * SharedPreferences（见 [AiSettingsStore]，与 xmu_secure 的凭据策略同源），
 * 备份/迁移规则同步排除 `ai_secure.xml`（Keystore 密钥不随备份迁移，密文解不开）。
 *
 * @param providerId 服务商预设 id（[AiProviderPresets]，"custom" = 用户自定义）
 * @param baseUrl 端点根路径（如 https://api.deepseek.com，路径拼接由各协议编解码器负责）
 * @param model 当前生效的模型名（如 deepseek-flash）
 * @param models 方案内保存的模型名列表（含当前模型）；空列表 = 旧配置，按 [model] 单模型解释
 * @param modelConfigs 模型级配置，key 为模型 id（忽略大小写匹配，统一成 [modelOptions] 的写法）
 * @param protocol 接口协议；带默认值——旧版本落盘的 JSON 无此字段，反序列化得
 *   [AiProtocol.OpenAiCompat]，既有配置行为不变
 */
@Serializable
data class AiConnection(
    val providerId: String = AiProviderPresets.CUSTOM_ID,
    val baseUrl: String = "",
    val model: String = "",
    val models: List<String> = emptyList(),
    val modelConfigs: Map<String, AiModelConfig> = emptyMap(),
    val protocol: AiProtocol = AiProtocol.OpenAiCompat,
) {
    /** 是否已具备发起请求的最低配置；API Key 由 [AiSettingsStore.loadApiKey] 另查 */
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * 是否已具备发起请求的完整条件：地址与模型齐全，且服务商需要的 API Key 已配置。
     * 本地 Ollama 等无需 Key 的预设允许 [apiKey] 为 null（请求侧按空串处理，不发认证头）。
     */
    fun isReady(apiKey: String?): Boolean =
        isConfigured && (!apiKey.isNullOrBlank() || !AiProviderPresets.requiresApiKey(providerId))

    /**
     * 展示与切换用的模型列表：`models` + 当前模型，去首尾空白、去空串，忽略大小写去重
     * （保留首次出现的写法）。旧配置 `models` 为空时即 `[model]`。
     */
    val modelOptions: List<String>
        get() = normalizedModelOptions(models, model)

    /**
     * 某模型的附加配置（忽略大小写匹配模型 id）；不存在 = 空配置。
     */
    fun modelConfig(model: String): AiModelConfig {
        val target = model.trim()
        return modelConfigs.entries
            .firstOrNull { it.key.equals(target, ignoreCase = true) }
            ?.value
            ?: AiModelConfig()
    }

    /**
     * 写入某模型的附加配置：key 统一为模型列表里的写法，空配置自动移除（不留冗余项）。
     * 模型不在列表时按传入写法入 key——调用方通常先用 [withModels] 把模型写进列表。
     */
    fun withModelConfig(model: String, config: AiModelConfig): AiConnection {
        val target = model.trim()
        if (target.isEmpty()) return this
        val canonical = modelOptions.firstOrNull { it.equals(target, ignoreCase = true) } ?: target
        val next = LinkedHashMap<String, AiModelConfig>(modelConfigs.size + 1)
        modelConfigs.forEach { (key, value) ->
            if (!key.equals(canonical, ignoreCase = true)) next[key] = value
        }
        val normalized = config.normalized()
        if (!normalized.isEmpty) next[canonical] = normalized
        return copy(modelConfigs = next)
    }

    /**
     * 换模型列表与当前模型（加 / 删 / 改名共用）：与 [withNormalizedModels] 同一套规范化，
     * 并按新列表裁剪 [modelConfigs]——被删模型不残留配置，key 统一成列表里的写法。
     */
    fun withModels(models: List<String>, current: String): AiConnection {
        val trimmedCurrent = current.trim()
        val options = normalizedModelOptions(models, trimmedCurrent)
        val canonicalCurrent = options.firstOrNull {
            it.equals(trimmedCurrent, ignoreCase = true)
        } ?: trimmedCurrent
        val configs = LinkedHashMap<String, AiModelConfig>(modelConfigs.size)
        modelConfigs.forEach { (key, value) ->
            val canonical = options.firstOrNull { it.equals(key.trim(), ignoreCase = true) }
                ?: return@forEach
            val normalized = value.normalized()
            if (!normalized.isEmpty) configs[canonical] = normalized
        }
        return copy(model = canonicalCurrent, models = options, modelConfigs = configs)
    }

    /**
     * 保存前的模型规范化：当前模型与列表某项忽略大小写相同时统一为列表里的写法；
     * 当前模型非空且不在列表时补进列表。当前模型为空时原样保留（由 UI 决定是否补选）。
     * 模型级配置同步裁剪 / 规范化（见 [withModels]）。
     */
    fun withNormalizedModels(): AiConnection = withModels(models, model)
}

/** 合并模型列表与当前模型：trim、去空串、忽略大小写去重，保留首次出现的写法 */
private fun normalizedModelOptions(models: List<String>, current: String): List<String> {
    val options = ArrayList<String>(models.size + 1)
    (models + current).forEach { raw ->
        val candidate = raw.trim()
        if (candidate.isNotEmpty() && options.none { it.equals(candidate, ignoreCase = true) }) {
            options += candidate
        }
    }
    return options
}

/**
 * 一套可复用的 AI 配置方案（服务商 + 端点 + 模型 + 协议）。
 *
 * 方案库与「自定义」槽位同在 [AiSettingsStore]：活动配置二选一——指向某个方案，
 * 或落在自定义槽位（复用旧版 `connection` / `api_key` 键，老配置无需迁移）。
 * API Key 不进本模型，按 [id] 单独加密存储（`api_key_<id>`），删除方案一并删除。
 *
 * @param id UUID；方案身份以它为准，名称允许重复
 * @param name 用户可见名称（保存/重命名时去首尾空白后非空）
 */
@Serializable
data class AiProfile(
    val id: String,
    val name: String,
    val connection: AiConnection,
)

/**
 * 一场独立对话（v2 格式）：ai_chat.json 里 [AiChatFile.conversations] 的成员。
 *
 * @param id UUID；对话身份以它为准
 * @param title 列表显示名；空串 = 未命名（发首条消息时由仓库自动截取命名），
 *   UI 层回落「新对话」文案，不在数据层写死中文
 * @param createdAtMillis 创建时刻（epoch millis）
 * @param updatedAtMillis 最近一次消息追加时刻（epoch millis）：删除活跃对话后回落
 *   「最近更新」的对话、列表按最近在前排序，都以它为准
 * @param messages 本场对话的消息（与 v1 顶层 messages 同构）
 * @param lastError 本场对话最近一次失败的分类错误：随对话一起落盘，退出浮层 / 切走再
 *   切回 / 进程重启后错误条仍在原位；用户关闭或重新发送时清空。旧文件无此字段时为 null
 */
@Serializable
data class AiConversation(
    val id: String,
    val title: String = "",
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
    val messages: List<AiChatMessage> = emptyList(),
    val lastError: AiError? = null,
)

/** 对话消息的角色；历史里只存 user / assistant（system prompt 每次现拼，不入文件） */
@Serializable
enum class AiChatRole { User, Assistant }

/**
 * 一次工具调用（协议中性）：OpenAI 的 `tool_calls[]` / Anthropic 的 `tool_use` 块
 * 都归一到这里，仓库与执行层不感知 wire 形状。
 *
 * @param argumentsJson 模型给出的参数原文（JSON 字符串）；非法 JSON 不在这里抛错，
 *   由执行层转成 `invalid_arguments` 结果交还模型
 */
@Serializable
data class AiToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String = "{}",
)

/**
 * 一次工具结果（协议中性）：OpenAI 的 `role=tool` 消息 / Anthropic 的 `tool_result` 块。
 *
 * @param contentJson 统一结果信封 `{ok,data}` 或 `{ok:false,error}` 的序列化文本
 * @param isError 结果是否失败（Anthropic 侧同时落 `is_error`）
 */
@Serializable
data class AiToolResult(
    val callId: String,
    val name: String,
    val contentJson: String,
    val isError: Boolean = false,
)

/** 工具轮次的执行状态；装载时 Pending/Running 会被归一为 Cancelled */
@Serializable
enum class AiToolRoundStatus { Pending, Running, Completed, Failed, Cancelled }

/**
 * 一条 assistant 消息内的一轮工具执行：本轮模型输出的文本 + 发起的调用 + 回填结果。
 *
 * 消息的 `content` 仍是全部轮次文本的拼接（复制/搜索/旧 UI 兼容）；[text] 保存该轮
 * 文本前缀，用于气泡内与工具卡交错渲染，以及回放时重建配对。
 */
@Serializable
data class AiToolRound(
    val text: String = "",
    val calls: List<AiToolCall> = emptyList(),
    val results: List<AiToolResult> = emptyList(),
    val status: AiToolRoundStatus = AiToolRoundStatus.Pending,
)

/**
 * 一条对话消息（ai_chat.json 的成员）。
 *
 * @param createdAtMillis 创建时刻（epoch millis），由仓库在追加时补齐
 * @param interrupted 流式中断标记：true = 这条 assistant 消息收到一半被停止/失败，
 *   UI 显示「已中断 + 重试」；user 消息恒 false
 * @param todoIds 本条回复中 AI 创建成功的待办 id（按生成顺序落盘）：重进对话仍以
 *   NoteCard 卡片展示；卡片内容从 TodoRepository 现取，待办被删除/归档后自然消失
 * @param todoSaveFailed 解析出了草稿但落库失败（I/O 等异常）；UI 在原处提示重试
 * @param reasoning 推理模型的思考过程（OpenAI 兼容的 `reasoning_content` / Anthropic 的
 *   `thinking_delta` 增量拼接），只供气泡上方的折叠块展示，**不回放进后续请求**
 *   （推理内容不属于对话上下文）；普通模型恒为空串，旧文件无此字段时默认空串
 * @param images 随消息发送的图片附件（[AiImageStore] 目录内的文件名，恒 JPEG）：
 *   发送时按历史窗口原样回放进请求（与正文不同，图片是视觉上下文）；旧文件无此字段时默认空
 * @param id 消息标识（仓库追加时生成 UUID）：确认卡的应用/忽略按它定向定位消息；
 *   旧文件无此字段时默认空串（旧消息不可能有待确认变更，无需迁移）
 * @param todoChanges AI 生成、等待用户确认的修改/删除方案：确认前不写待办库，
 *   状态（待确认/已应用/失败/已忽略）随消息落盘
 * @param todoChangeDropped 解析出了修改/删除动作，但目标不存在或 patch 非法被丢弃；
 *   UI 在原处提示「部分变更无法识别」
 * @param todoCompletions AI 直接执行的完成/取消完成记录：可撤销，状态随消息落盘
 * @param todoLifecycles AI 直接执行的归档/取消归档/恢复记录：可撤销，状态随消息落盘
 * @param toolRounds 本条回复内的工具轮次（按发生顺序；v2 消息为空列表）：只读查询的
 *   调用与结果成对持久化，回放时最近两轮精确重放、更早轮次折叠
 * @param toolLoopCapped 工具循环因预算停止（UI 轻提示，不参与回放）
 */
@Serializable
data class AiChatMessage(
    val role: AiChatRole,
    val content: String,
    val createdAtMillis: Long = 0,
    val interrupted: Boolean = false,
    val todoIds: List<String> = emptyList(),
    val todoSaveFailed: Boolean = false,
    val reasoning: String = "",
    val images: List<String> = emptyList(),
    val id: String = "",
    val todoChanges: List<AiTodoChange> = emptyList(),
    val todoChangeDropped: Boolean = false,
    val todoCompletions: List<AiTodoCompletion> = emptyList(),
    val todoLifecycles: List<AiTodoLifecycle> = emptyList(),
    val toolRounds: List<AiToolRound> = emptyList(),
    val toolLoopCapped: Boolean = false,
)

/**
 * AI 调用的错误分类。数据层只分类不产出文案——文案由 UI 层映射 stringResource
 * （项目有 values-en，中文硬编码在数据层会破坏本地化）。
 * 随 [AiConversation.lastError] 落盘，故需序列化。
 */
@Serializable
enum class AiError { Auth, Quota, NotFound, RateLimited, Server, Network, BadResponse, EmptyResponse }

/**
 * 携带分类的 AI 调用异常；[detail] 供日志/排查，内容来自服务商响应体或 IO 消息，
 * **不含 API Key 与 Authorization 头**（客户端不打印请求头，见 AiClient）。
 */
class AiException(val error: AiError, val detail: String? = null) :
    Exception(detail ?: error.name)

/**
 * 一次对话调用中的一条 wire 消息（协议中性的内部表示，role 用小写串；各协议编解码器
 * 负责落成自己的请求形状）。
 *
 * @param images 随本条消息发给模型的图片（base64 已就绪）；无图时 content 保持纯字符串
 *   编码（既有网关兼容面不变），有图时由编解码器落成多模态 content 数组
 * @param toolCalls assistant 本轮发起的工具调用（有值时不编码成 multimodal content，
 *   OpenAI 侧同时落 `tool_calls`，Anthropic 侧落 `tool_use` 块）
 * @param toolResults tool 轮回填的结果（OpenAI 一条结果一条 tool 消息；Anthropic 合并进
 *   紧随其后的 user 消息 `tool_result` 块）
 */
@Serializable
data class AiChatTurn(
    val role: String,
    val content: String = "",
    val images: List<AiTurnImage> = emptyList(),
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolResults: List<AiToolResult> = emptyList(),
)

/** wire 层的图片附件：base64 已编码的 JPEG（当前导入管线统一转正压缩为 JPEG） */
@Serializable
data class AiTurnImage(val mediaType: String, val base64: String)

/** AI 模块的 Json 配置：storage 与 ScheduleJson.storage 同参；storageLean 用于整库紧凑写入 */
internal object AiJson {
    val storage: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = false
        prettyPrint = false
    }

    val storageLean: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        isLenient = false
        prettyPrint = false
    }

    /**
     * 工具参数解析：比 [storage] 宽容——模型常给显式 `null` 表示「没填这个字段」，
     * `coerceInputValues` 让非空字段（有默认值）回落到默认值，非法枚举同样回落，
     * 避免整条动作被解码异常吞掉。持久化读取继续用 [storage]，不静默修复损坏文件。
     */
    val toolArgs: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        coerceInputValues = true
        prettyPrint = false
    }
}
