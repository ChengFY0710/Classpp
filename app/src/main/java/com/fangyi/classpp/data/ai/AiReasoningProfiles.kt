package com.fangyi.classpp.data.ai

/**
 * 服务商模型族的推理档位表（「从服务商拉取模型」与请求侧共用）。
 *
 * 只有 DeepSeek 的模型列表返回 `effort` 元数据；GLM、MiMo、Kimi、千问与百炼的列表
 * 只给模型 id（Kimi 额外给 `supports_reasoning` 布尔），档位按官方文档的模型族规则
 * 内置在这里——新增 / 更名模型族只改这张表。
 *
 * - 档位按从低到高排列；[AiThinkingControl.supportsOff] 为 true 时首档表示「关」；
 * - 始终思考的模型族（`glm-5.3*`、`kimi-k3*`）首档是最低强度，控制形态为
 *   [AiThinkingControl.ReasoningEffortOnly]，请求侧不会把首档当成关；
 * - 强制思考且没有 effort 参数的模型族（`glm-4.7`、`glm-4.5v`、`kimi-k2.7-code` 等）
 *   不写档位，避免给出无法生效的档位；
 * - 百炼是聚合平台：`qwen` / `deepseek` / `glm` / `kimi` 前缀分派到对应规则，开关形态
 *   统一换成百炼的 `enable_thinking`（千问规则本身已是 enable_thinking，直接复用）。
 */
internal object AiReasoningProfiles {

    /** 一个模型族的档位与请求控制形态 */
    data class Profile(
        val levels: List<String>,
        val defaultLevel: String,
        val control: AiThinkingControl,
    )

    /**
     * 拉取到的模型应写入的档位配置；null = 该模型不推断档位。
     * [supportsReasoning] 来自 Kimi 列表的 `supports_reasoning`：明确 false 时不推断。
     */
    fun configFor(
        providerId: String,
        model: String,
        supportsReasoning: Boolean? = null,
    ): AiModelConfig? {
        if (supportsReasoning == false) return null
        val profile = profileFor(providerId, model) ?: return null
        return AiModelConfig(
            reasoningLevels = profile.levels,
            reasoningLevel = profile.defaultLevel,
        ).normalized().takeUnless { it.isEmpty }
    }

    /** 请求侧控制形态；未命中模型族时回落预设自身的能力标记 */
    fun controlFor(providerId: String, model: String): AiThinkingControl =
        profileFor(providerId, model)?.control
            ?: AiProviderPresets.byId(providerId).thinkingControl

    private fun profileFor(providerId: String, model: String): Profile? {
        val id = model.trim().lowercase()
        if (id.isEmpty()) return null
        return when (AiProviderPresets.byId(providerId).id) {
            "zhipu" -> zhipuProfile(id)
            "mimo" -> mimoProfile(id)
            "moonshot" -> moonshotProfile(id)
            "qwen" -> qwenProfile(id)
            "bailian" -> bailianProfile(id)
            else -> null
        }
    }

    // ---------- 智谱 GLM ----------

    private fun zhipuProfile(id: String): Profile? = when {
        id.startsWith("glm-5.3") -> GLM_53
        id.startsWith("glm-5.2") -> GLM_52
        // GLM-4.7 强制思考且没有 effort（Flash 变体仍可按开关切换）
        id.startsWith("glm-4.7-flash") -> THINKING_TYPE_TOGGLE
        id == "glm-5" || id.startsWith("glm-5.1") || id.startsWith("glm-5-turbo") ||
            id.startsWith("glm-5v-turbo") || id.startsWith("glm-4.6") ||
            id == "glm-4.5" || id.startsWith("glm-4.5-air") ||
            id.startsWith("glm-4.5-flash") -> THINKING_TYPE_TOGGLE
        else -> null
    }

    // ---------- 小米 MiMo ----------

    private fun mimoProfile(id: String): Profile? = when {
        id.startsWith("mimo-v2.6-flash") || id.startsWith("mimo-v2.6-pro") ->
            THINKING_TYPE_TOGGLE
        else -> null
    }

    // ---------- Kimi ----------

    private fun moonshotProfile(id: String): Profile? = when {
        id.startsWith("kimi-k3") -> EFFORT_ONLY_KIMI
        id.startsWith("kimi-k2.6") -> THINKING_TYPE_TOGGLE
        else -> null
    }

    // ---------- 通义千问 / 百炼 ----------

    private fun qwenProfile(id: String): Profile? {
        if (id.isNonChatModel()) return null
        return when {
            id.startsWith("qwen4-") -> QWEN_4
            id.startsWith("qwen3.8-omni-flash") -> QWEN_38_OMNI
            id.startsWith("qwen3.8-max") || id.startsWith("qwen3.8-flash") ||
                id.startsWith("qwen3.8-2.4t-a95b") || id.startsWith("qwen3.8-27b") ->
                QWEN_38
            id.startsWith("qwen3.7") || id.startsWith("qwen3.6") ||
                id.startsWith("qwen3.5") || id.startsWith("qwen3-") ->
                ENABLE_THINKING_TOGGLE_ON
            id.startsWith("qwen-plus") || id.startsWith("qwen-flash") ->
                ENABLE_THINKING_TOGGLE_OFF
            else -> null
        }
    }

    private fun bailianProfile(id: String): Profile? = when {
        id.startsWith("qwen") -> qwenProfile(id)?.viaEnableThinking()
        id.startsWith("deepseek") -> BAILIAN_DEEPSEEK
        id.startsWith("glm-5.3") -> GLM_53
        id.startsWith("glm-") -> BAILIAN_GLM
        id.startsWith("kimi-k3") -> EFFORT_ONLY_KIMI
        id.startsWith("kimi-k2.5") || id.startsWith("kimi-k2.6") ->
            ENABLE_THINKING_TOGGLE_ON
        else -> null
    }

    /** 非对话模型（向量 / 重排 / 语音 / 实时 / 图像）不写档位 */
    private fun String.isNonChatModel(): Boolean = listOf(
        "-embedding", "-rerank", "-tts", "-asr", "-audio", "-realtime", "-image",
    ).any(::contains)

    /** 千问规则复用到百炼：开关字段从 thinking.type 换成 enable_thinking */
    private fun Profile.viaEnableThinking(): Profile = copy(
        control = when (control) {
            AiThinkingControl.ThinkingType -> AiThinkingControl.EnableThinking
            AiThinkingControl.ThinkingTypeOnly -> AiThinkingControl.EnableThinkingOnly
            else -> control
        },
    )

    private val THINKING_TYPE_TOGGLE = Profile(
        levels = listOf("off", "on"),
        defaultLevel = "on",
        control = AiThinkingControl.ThinkingTypeOnly,
    )
    private val ENABLE_THINKING_TOGGLE_ON = Profile(
        levels = listOf("off", "on"),
        defaultLevel = "on",
        control = AiThinkingControl.EnableThinkingOnly,
    )
    private val ENABLE_THINKING_TOGGLE_OFF = Profile(
        levels = listOf("off", "on"),
        defaultLevel = "off",
        control = AiThinkingControl.EnableThinkingOnly,
    )
    private val GLM_53 = Profile(
        levels = listOf("low", "high", "max"),
        defaultLevel = "max",
        control = AiThinkingControl.ReasoningEffortOnly,
    )
    private val GLM_52 = Profile(
        levels = listOf("none", "low", "medium", "high", "max"),
        defaultLevel = "max",
        control = AiThinkingControl.ThinkingType,
    )
    private val EFFORT_ONLY_KIMI = Profile(
        levels = listOf("low", "high", "max"),
        defaultLevel = "max",
        control = AiThinkingControl.ReasoningEffortOnly,
    )
    private val QWEN_4 = Profile(
        levels = listOf("none", "low", "medium", "high", "xhigh"),
        defaultLevel = "high",
        control = AiThinkingControl.EnableThinking,
    )
    private val QWEN_38_OMNI = Profile(
        levels = listOf("none", "low", "medium", "high", "xhigh"),
        defaultLevel = "xhigh",
        control = AiThinkingControl.EnableThinking,
    )
    private val QWEN_38 = Profile(
        levels = listOf("none", "low", "medium", "xhigh"),
        defaultLevel = "xhigh",
        control = AiThinkingControl.EnableThinking,
    )
    private val BAILIAN_DEEPSEEK = Profile(
        levels = listOf("none", "low", "high", "max"),
        defaultLevel = "high",
        control = AiThinkingControl.EnableThinking,
    )
    private val BAILIAN_GLM = Profile(
        levels = listOf("none", "high", "max"),
        defaultLevel = "high",
        control = AiThinkingControl.EnableThinking,
    )
}
