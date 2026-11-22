package com.fangyi.classpp.data.ai

/**
 * 预设的模型列表拉取方案（「从服务商拉取模型」使用；随预设单独配置）：
 * - [OpenAiCompat]：通用 `GET {baseUrl}/models`，响应 `data[].id`，单页；
 * - [DashScope]：阿里云百炼原生 `GET {origin}/api/v1/models?page_no=&page_size=`，
 *   响应 `output.models[].model`，按 `page_no` / `page_size` / `total` 分页
 *   （百炼的 OpenAI 兼容路径不提供模型列表，不能走通用方案）。
 *
 * 新增服务商时按需在预设里切换本类型；未显式配置的预设保持通用方案。
 */
enum class AiModelListApi { OpenAiCompat, DashScope }

/**
 * 思考开关在 OpenAI 兼容请求体里的表达方式（「关」档位的执行语义）：
 * - [None]：不发送开关字段。关 = 不发任何推理参数，由服务商默认行为决定；
 *   非关档位仍原样发送 `reasoning_effort`（未知服务商的既有兼容面）；
 * - [ThinkingType]：发送 `thinking: {"type": "enabled"/"disabled"}` + 非关档位的
 *   `reasoning_effort`。DeepSeek 的思考模式**默认开启**，省略参数等于继续思考，
 *   必须显式传 `disabled` 才真正关闭（见 DeepSeek 思考模式文档
 *   api-docs.deepseek.com/zh-cn/guides/thinking_mode）；
 * - [ThinkingTypeOnly]：只发 `thinking` 开关，不发 `reasoning_effort`
 *   （MiMo、GLM 开关族、Kimi K2.6 没有等级参数，档位字符串不落到线上）；
 * - [EnableThinking]：发送 `enable_thinking: true/false` + 非关档位的
 *   `reasoning_effort`（千问/百炼的 OpenAI 兼容形态）；
 * - [EnableThinkingOnly]：只发 `enable_thinking` 开关（千问 hybrid 老模型）；
 * - [ReasoningEffortOnly]：只发 `reasoning_effort`，没有「关」档——模型始终思考，
 *   首档是最低强度而不是关闭（Kimi K3、GLM-5.3）。
 *
 * 控制形态由 [AiProviderPresets.thinkingControlFor] 按「服务商 + 模型族」解析；
 * 未命中的模型回落预设表里的能力标记，避免把不认识该字段的网关（如 OpenAI 官方）打到 400。
 */
enum class AiThinkingControl {
    None,
    ThinkingType,
    ThinkingTypeOnly,
    EnableThinking,
    EnableThinkingOnly,
    ReasoningEffortOnly,
    ;

    /** 「关」档是否存在：始终思考的模型族首档就是最低强度，不能按关处理 */
    val supportsOff: Boolean get() = this != ReasoningEffortOnly

    /** 是否把推理等级原样作为 `reasoning_effort` 发送（纯开关型档位不发） */
    val sendsEffort: Boolean
        get() = this != ThinkingTypeOnly && this != EnableThinkingOnly
}

/**
 * 一个服务商预设：用户在 AI 设置里选预设即回填端点、协议与建议模型，
 * 只需再填自己的 API Key（label 为品牌名，不参与本地化）。
 *
 * @param id 稳定标识，存进 [AiConnection.providerId]；表内唯一，删除条目时
 *   旧配置经 [AiProviderPresets.byId] 回落 custom，不抛异常
 * @param label 设置页展示名
 * @param baseUrl 端点根路径（不含资源路径，拼接由各协议编解码器负责）
 * @param suggestedModel 选中预设时回填的建议模型名
 * @param requiresManualModel true = 无公认默认模型（自定义），模型名必手填
 * @param requiresApiKey true = 必须有 API Key 才能发起请求；本地 Ollama 无需 Key
 * @param protocol 选中预设时回填的接口协议（预设自带协议，设置页仅自定义服务商展示选择器）
 * @param modelListApi 该服务商的模型列表拉取方案（见 [AiModelListApi]）；
 *   默认通用 OpenAI 兼容，百炼等端点形态不同的服务商单独配置
 * @param thinkingControl 该预设下的默认思考开关形态（见 [AiThinkingControl]）；模型族
 *   有更细的档位/控制规则时由 [AiProviderPresets.thinkingControlFor] 覆盖，未命中模型回落本字段
 * @param sessionHeader 会话路由头名：部分网关（OpenCode Go）要求每次对话请求携带稳定会话
 *   标识，缺失直接 400 拒绝。null = 不发送；值由请求侧传入，未传时按端点生成稳定回退值
 */
data class AiProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val suggestedModel: String,
    val requiresManualModel: Boolean = false,
    val requiresApiKey: Boolean = true,
    val protocol: AiProtocol = AiProtocol.OpenAiCompat,
    val modelListApi: AiModelListApi = AiModelListApi.OpenAiCompat,
    val thinkingControl: AiThinkingControl = AiThinkingControl.None,
    val sessionHeader: String? = null,
)

/**
 * 服务商预设表（MVP 名单与建议模型见 .mimocode/docs/AI功能计划.md §4.2；
 * 更名/新增/下架只动这张表，不动任何逻辑）。
 *
 * 下架预设不影响已存配置：旧 providerId 经 [byId] 回落 custom，端点、模型与
 * 已保存的 API Key 均按原配置继续使用。
 */
object AiProviderPresets {

    const val CUSTOM_ID = "custom"

    /** 本地 Ollama 预设 id：设置页按它展示局域网连接提示，无需 API Key */
    const val OLLAMA_ID = "ollama"

    val all: List<AiProviderPreset> = listOf(
        AiProviderPreset(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            suggestedModel = "deepseek-flash",
            // 思考模式默认开启：关档位必须显式发 thinking.disabled 才真正关闭
            thinkingControl = AiThinkingControl.ThinkingType,
        ),
        AiProviderPreset(
            id = "zhipu",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            suggestedModel = "glm-4.7-flash",
        ),
        AiProviderPreset(
            id = "qwen",
            label = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            suggestedModel = "qwen3.8-flash",
        ),
        // 百炼是阿里云模型平台（Qwen/DeepSeek/Kimi 等多家模型的统一入口）：对话仍走
        // OpenAI 兼容路径，模型列表用平台原生端点（见 AiModelListApi.DashScope）
        AiProviderPreset(
            id = "bailian",
            label = "阿里云百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            suggestedModel = "qwen3.8-flash",
            modelListApi = AiModelListApi.DashScope,
        ),
        AiProviderPreset(
            id = "moonshot",
            label = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            suggestedModel = "kimi-k3.1-pro",
        ),
        AiProviderPreset(
            id = "mimo",
            label = "Xiaomi MiMo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            suggestedModel = "mimo-v2.6-pro",
            // 文本模型默认开启思考，只有开关没有 reasoning_effort
            thinkingControl = AiThinkingControl.ThinkingTypeOnly,
        ),
        // OpenCode Go：文档公布的根地址是 /inference/go，但对话与模型列表都必须走 /v1
        // （不带 /v1 的对话路径 404）。对话端点强制要求 x-opencode-session，否则 400
        // MissingSessionID；该头不是开关字段，统一按 OpenAI 兼容形态发送档位即可
        AiProviderPreset(
            id = "opencode-go",
            label = "OpenCode Go",
            baseUrl = "https://opencode.ai/inference/go/v1",
            suggestedModel = "deepseek-v4.1-flash",
            sessionHeader = "x-opencode-session",
        ),
        // 本地 Ollama：OpenAI 兼容端点（/v1），默认监听回环地址；真机需改成电脑的
        // 局域网 IP（设置页有专门提示），协议层只对回环/私网地址放行明文 HTTP。
        // 建议模型只用于切换预设时替换掉上一家的模型名，实际可用模型以本机已拉取为准
        AiProviderPreset(
            id = OLLAMA_ID,
            label = "Ollama",
            baseUrl = "http://127.0.0.1:11434/v1",
            suggestedModel = "qwen3:8b",
            requiresApiKey = false,
        ),
        AiProviderPreset(
            id = CUSTOM_ID,
            label = "自定义",
            baseUrl = "",
            suggestedModel = "",
            requiresManualModel = true,
        ),
    )

    val custom: AiProviderPreset by lazy { byId(CUSTOM_ID) }

    /** 按 id 取预设；未知 id（含被下架的旧预设）一律回落 custom，绝不抛异常 */
    fun byId(id: String): AiProviderPreset = all.firstOrNull { it.id == id }
        ?: all.first { it.id == CUSTOM_ID }

    /** 该服务商是否必须配置 API Key；未知 id 回落 custom（必须） */
    fun requiresApiKey(providerId: String): Boolean = byId(providerId).requiresApiKey

    /** 该服务商要求的会话路由头名；null = 不需要（未知 id 回落 custom，同样不需要） */
    fun sessionHeaderFor(providerId: String): String? = byId(providerId).sessionHeader

    /**
     * 某服务商下具体模型实际使用的思考控制形态：先按 [AiReasoningProfiles] 的模型族
     * 档位表解析（GLM/MiMo/Kimi/千问/百炼的模型族各异），未命中时回落到预设自身的能力
     * 标记（DeepSeek 全系 [AiThinkingControl.ThinkingType]，MiMo 全系
     * [AiThinkingControl.ThinkingTypeOnly]，其余 [AiThinkingControl.None]）。
     */
    fun thinkingControlFor(providerId: String, model: String): AiThinkingControl =
        AiReasoningProfiles.controlFor(providerId, model)
}
