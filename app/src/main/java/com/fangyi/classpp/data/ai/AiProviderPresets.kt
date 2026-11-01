package com.fangyi.classpp.data.ai

/**
 * 一个服务商预设：用户在 AI 设置里选预设即回填端点、协议与建议模型，
 * 只需再填自己的 API Key（label 为品牌名，不参与本地化）。
 *
 * @param id 稳定标识，存进 [AiConnection.providerId]；表内唯一，删除条目时
 *   旧配置经 [AiProviderPresets.byId] 回落 custom，不抛异常
 * @param label 设置页展示名
 * @param baseUrl 端点根路径（不含资源路径，拼接由各协议编解码器负责）
 * @param suggestedModel 选中预设时回填的建议模型名
 * @param requiresManualModel true = 无公认默认模型（本地/自定义），模型名必手填
 * @param protocol 选中预设时回填的接口协议（预设自带协议，设置页仅自定义服务商展示选择器）
 * @param releaseVisible false = 仅 DEBUG 构建在设置页展示（见 [AiProviderPresets.available]）；
 *   发布版不再给出必然失败的选项，旧配置仍可经 [AiProviderPresets.byId] 解析
 */
data class AiProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val suggestedModel: String,
    val requiresManualModel: Boolean = false,
    val protocol: AiProtocol = AiProtocol.OpenAiCompat,
    val releaseVisible: Boolean = true,
)

/**
 * 服务商预设表（MVP 名单与建议模型见 .mimocode/docs/AI功能计划.md §4.2；
 * 更名/新增/下架只动这张表，不动任何逻辑）。
 *
 * 注意：`ollama` 指向 http://localhost——Android 默认禁明文流量，真机访问本地模型
 * 还需网络策略放行本地回环。M1 不提供该网络策略，因此该预设仅 DEBUG 构建可见
 * （发布版隐藏，见 [available]；计划 §4.2 / §11 cleartext 条目）。
 */
object AiProviderPresets {

    const val CUSTOM_ID = "custom"

    val all: List<AiProviderPreset> = listOf(
        AiProviderPreset(
            id = "deepseek",
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1",
            suggestedModel = "deepseek-chat",
        ),
        AiProviderPreset(
            id = "zhipu",
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            suggestedModel = "glm-4-flash",
        ),
        AiProviderPreset(
            id = "qwen",
            label = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            suggestedModel = "qwen-turbo",
        ),
        AiProviderPreset(
            id = "moonshot",
            label = "Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            suggestedModel = "moonshot-v1-8k",
        ),
        AiProviderPreset(
            id = "openai",
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            suggestedModel = "gpt-4o-mini",
        ),
        AiProviderPreset(
            id = "anthropic",
            label = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1",
            suggestedModel = "claude-sonnet-4-5",
            protocol = AiProtocol.Anthropic,
        ),
        AiProviderPreset(
            id = "ollama",
            label = "本地 Ollama",
            baseUrl = "http://localhost:11434/v1",
            suggestedModel = "",
            requiresManualModel = true,
            releaseVisible = false,
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

    /**
     * 设置页可见的预设：release 构建隐藏必然失败的明文预设（ollama 指向
     * http://localhost），DEBUG 构建保留以便本地联调。
     */
    fun available(debuggable: Boolean): List<AiProviderPreset> =
        all.filter { debuggable || it.releaseVisible }

    /** 按 id 取预设；未知 id（含被下架的旧预设）一律回落 custom，绝不抛异常 */
    fun byId(id: String): AiProviderPreset = all.firstOrNull { it.id == id }
        ?: all.first { it.id == CUSTOM_ID }
}
