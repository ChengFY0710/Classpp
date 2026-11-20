package com.fangyi.classpp.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型族档位表：各家模型 id 前缀 → 档位/默认值/思考控制形态。
 * 表是拉取模型与请求侧共用的唯一事实源，改动模型族时同步补这里。
 */
class AiReasoningProfilesTest {

    private fun config(
        providerId: String,
        model: String,
        supportsReasoning: Boolean? = null,
    ): AiModelConfig? = AiReasoningProfiles.configFor(providerId, model, supportsReasoning)

    private fun control(providerId: String, model: String): AiThinkingControl =
        AiReasoningProfiles.controlFor(providerId, model)

    @Test
    fun `glm 5_3 is effort only and has no off level`() {
        val result = config("zhipu", "glm-5.3-flash")!!
        assertEquals(listOf("low", "high", "max"), result.reasoningLevels)
        assertEquals("max", result.reasoningLevel)
        assertEquals(AiThinkingControl.ReasoningEffortOnly, control("zhipu", "glm-5.3-flash"))
        assertFalse(AiThinkingControl.ReasoningEffortOnly.supportsOff)
        assertTrue(AiThinkingControl.ReasoningEffortOnly.sendsEffort)
    }

    @Test
    fun `glm 5_2 supports disabling and effort`() {
        val result = config("zhipu", "glm-5.2")!!
        assertEquals(listOf("none", "low", "medium", "high", "max"), result.reasoningLevels)
        assertEquals("max", result.reasoningLevel)
        assertEquals(AiThinkingControl.ThinkingType, control("zhipu", "glm-5.2"))
    }

    @Test
    fun `glm switch families map to thinking type only`() {
        listOf("glm-4.7-flash", "glm-4.7-flashx", "glm-4.6", "glm-4.5-air", "glm-5.1")
            .forEach { model ->
                val result = config("zhipu", model)!!
                assertEquals(model, listOf("off", "on"), result.reasoningLevels)
                assertEquals(model, "on", result.reasoningLevel)
                assertEquals(model, AiThinkingControl.ThinkingTypeOnly, control("zhipu", model))
            }
        assertFalse(AiThinkingControl.ThinkingTypeOnly.sendsEffort)
    }

    @Test
    fun `forced thinking glm models have no level config`() {
        // 强制思考且没有 effort 参数：不生成无法生效的档位
        assertNull(config("zhipu", "glm-4.7"))
        assertNull(config("zhipu", "glm-4.5v"))
        assertNull(config("zhipu", "glm-4.1v-thinking-flash"))
        assertNull(config("zhipu", "glm-4-flash"))
    }

    @Test
    fun `mimo chat models use thinking type toggle`() {
        listOf("mimo-v2.6-flash", "mimo-v2.6-pro", "mimo-v2.6-pro-ultraspeed")
            .forEach { model ->
                assertEquals(model, listOf("off", "on"), config("mimo", model)!!.reasoningLevels)
                assertEquals(model, AiThinkingControl.ThinkingTypeOnly, control("mimo", model))
            }
        assertNull(config("mimo", "mimo-v2.5-asr"))
    }

    @Test
    fun `kimi k3 is effort only and k2_6 is a toggle`() {
        val k3 = config("moonshot", "kimi-k3.1-pro")!!
        assertEquals(listOf("low", "high", "max"), k3.reasoningLevels)
        assertEquals("max", k3.reasoningLevel)
        assertEquals(AiThinkingControl.ReasoningEffortOnly, control("moonshot", "kimi-k3.1-pro"))

        val k26 = config("moonshot", "kimi-k2.6")!!
        assertEquals(listOf("off", "on"), k26.reasoningLevels)
        assertEquals("on", k26.reasoningLevel)
        assertEquals(AiThinkingControl.ThinkingTypeOnly, control("moonshot", "kimi-k2.6"))

        assertNull(config("moonshot", "kimi-k2.7-code"))
    }

    @Test
    fun `kimi supports_reasoning false suppresses inferred levels`() {
        assertNull(config("moonshot", "kimi-k3.1-pro", supportsReasoning = false))
        assertEquals(
            listOf("low", "high", "max"),
            config("moonshot", "kimi-k3.1-pro", supportsReasoning = true)!!.reasoningLevels,
        )
    }

    @Test
    fun `qwen 4 and 3_8 families carry effort plus enable switch`() {
        val qwen4 = config("qwen", "qwen4-max")!!
        assertEquals(listOf("none", "low", "medium", "high", "xhigh"), qwen4.reasoningLevels)
        assertEquals("high", qwen4.reasoningLevel)
        assertEquals(AiThinkingControl.EnableThinking, control("qwen", "qwen4-max"))

        val qwen38 = config("qwen", "qwen3.8-flash")!!
        assertEquals(listOf("none", "low", "medium", "xhigh"), qwen38.reasoningLevels)
        assertEquals("xhigh", qwen38.reasoningLevel)
        assertEquals(AiThinkingControl.EnableThinking, control("qwen", "qwen3.8-flash"))

        val omni = config("qwen", "qwen3.8-omni-flash")!!
        assertEquals(listOf("none", "low", "medium", "high", "xhigh"), omni.reasoningLevels)
        assertEquals("xhigh", omni.reasoningLevel)
    }

    @Test
    fun `qwen hybrid families use enable thinking only`() {
        val plus = config("qwen", "qwen-plus")!!
        assertEquals(listOf("off", "on"), plus.reasoningLevels)
        assertEquals("off", plus.reasoningLevel)
        assertEquals(AiThinkingControl.EnableThinkingOnly, control("qwen", "qwen-plus"))

        val qwen37 = config("qwen", "qwen3.7-plus")!!
        assertEquals("on", qwen37.reasoningLevel)
        assertEquals(AiThinkingControl.EnableThinkingOnly, control("qwen", "qwen3.7-plus"))

        assertNull(config("qwen", "qwen3.7-text-embedding-flash"))
        assertNull(config("qwen", "qwen-image-3.0-pro"))
        assertNull(config("qwen", "qwen-max"))
    }

    @Test
    fun `bailian dispatches vendor prefixes and uses enable thinking`() {
        val qwen = config("bailian", "qwen3.8-flash")!!
        assertEquals(listOf("none", "low", "medium", "xhigh"), qwen.reasoningLevels)
        assertEquals(AiThinkingControl.EnableThinking, control("bailian", "qwen3.8-flash"))

        val plus = config("bailian", "qwen-plus")!!
        assertEquals(AiThinkingControl.EnableThinkingOnly, control("bailian", "qwen-plus"))

        val deepseek = config("bailian", "deepseek-v4-pro")!!
        assertEquals(listOf("none", "low", "high", "max"), deepseek.reasoningLevels)
        assertEquals("high", deepseek.reasoningLevel)
        assertEquals(AiThinkingControl.EnableThinking, control("bailian", "deepseek-v4-pro"))

        val glm53 = config("bailian", "glm-5.3")!!
        assertEquals(listOf("low", "high", "max"), glm53.reasoningLevels)
        assertEquals("max", glm53.reasoningLevel)
        assertEquals(AiThinkingControl.ReasoningEffortOnly, control("bailian", "glm-5.3"))

        val glmOther = config("bailian", "glm-4.6")!!
        assertEquals(listOf("none", "high", "max"), glmOther.reasoningLevels)
        assertEquals("high", glmOther.reasoningLevel)
        assertEquals(AiThinkingControl.EnableThinking, control("bailian", "glm-4.6"))

        val kimi = config("bailian", "kimi-k3")!!
        assertEquals(listOf("low", "high", "max"), kimi.reasoningLevels)
        assertEquals(AiThinkingControl.ReasoningEffortOnly, control("bailian", "kimi-k3"))

        val kimiToggle = config("bailian", "kimi-k2.6")!!
        assertEquals(listOf("off", "on"), kimiToggle.reasoningLevels)
        assertEquals(AiThinkingControl.EnableThinkingOnly, control("bailian", "kimi-k2.6"))

        assertNull(config("bailian", "minimax-m3.1-flash"))
    }

    @Test
    fun `unknown model falls back to the preset control`() {
        assertNull(config("zhipu", "some-unknown-model"))
        assertEquals(AiThinkingControl.None, control("zhipu", "some-unknown-model"))
        // DeepSeek 全系走 thinking.type，MiMo 未命中模型族时仍按开关型处理
        assertEquals(AiThinkingControl.ThinkingType, control("deepseek", "deepseek-flash"))
        assertEquals(AiThinkingControl.ThinkingType, control("deepseek", "some-unknown-model"))
        assertEquals(AiThinkingControl.ThinkingTypeOnly, control("mimo", "some-unknown-model"))
        assertEquals(AiThinkingControl.None, control("custom", "gpt-4o-mini"))
        // 已下架预设回落 custom，不发送任何开关
        assertEquals(AiThinkingControl.None, control("openai", "gpt-4o-mini"))
    }
}
