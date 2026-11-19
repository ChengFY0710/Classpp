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
 * - [None]：不发送开关字段（默认）。关 = 不发任何推理参数，由服务商默认行为决定；
 * - [ThinkingType]：发送 `thinking: {"type": "enabled"/"disabled"}`。DeepSeek 的思考
 *   模式**默认开启**，省略参数等于继续思考，必须显式传 `disabled` 才真正关闭
 *   （见 DeepSeek 思考模式文档 api-docs.deepseek.com/zh-cn/guides/thinking_mode）。
 *
 * 新增支持该开关形态的服务商时在预设表里切换本类型；未配置的预设保持 [None]，
 * 避免把不认识该字段的网关（如 OpenAI 官方）打到 400。
 */
enum class AiThinkingControl { None, ThinkingType }

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
 * @param protocol 选中预设时回填的接口协议（预设自带协议，设置页仅自定义服务商展示选择器）
 * @param modelListApi 该服务商的模型列表拉取方案（见 [AiModelListApi]）；
 *   默认通用 OpenAI 兼容，百炼等端点形态不同的服务商单独配置
 * @param thinkingControl 思考开关的请求形态（见 [AiThinkingControl]）；默认不发送开关字段
 */
data class AiProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val suggestedModel: String,
    val requiresManualModel: Boolean = false,
    val protocol: AiProtocol = AiProtocol.OpenAiCompat,
    val modelListApi: AiModelListApi = AiModelListApi.OpenAiCompat,
    val thinkingControl: AiThinkingControl = AiThinkingControl.None,
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
}
