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
 * @param requiresManualModel true = 无公认默认模型（自定义），模型名必手填
 * @param protocol 选中预设时回填的接口协议（预设自带协议，设置页仅自定义服务商展示选择器）
 */
data class AiProviderPreset(
    val id: String,
    val label: String,
    val baseUrl: String,
    val suggestedModel: String,
    val requiresManualModel: Boolean = false,
    val protocol: AiProtocol = AiProtocol.OpenAiCompat,
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
