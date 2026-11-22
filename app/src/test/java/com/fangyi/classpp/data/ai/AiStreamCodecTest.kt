package com.fangyi.classpp.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic Messages 协议编解码（经 AiClient + 脚本化假流，与 AiClientTest 同一套惯例）：
 * 请求形态（端点/认证头/system 提升/max_tokens/消息交替化）与 SSE 解析
 * （content_block_delta 增量 / message_stop 终止 / 心跳与元事件忽略 / 流内错误分类 /
 * EOF 无终止标记语义）。OpenAI 兼容侧的回归由 AiClientTest 全量覆盖（默认协议未变）。
 */
class AiStreamCodecTest {

    // ---------- 脚本化假流（同 AiClientTest 的本地 Fake 惯例） ----------

    private class FakeAiStreamResponse(
        override val status: Int,
        private val lines: List<String?>,
    ) : AiStreamResponse {
        override fun readLine(): String? = lines.getOrNull(index++)
        override fun readRemaining(): String = ""
        override fun cancel() = Unit
        override fun close() = Unit
        private var index = 0
    }

    private class FakeAiTransport : AiHttpTransport {
        var lastRequest: AiHttpRequest? = null
            private set

        override fun open(request: AiHttpRequest): AiStreamResponse {
            lastRequest = request
            return response ?: error("no scripted response")
        }

        var response: AiStreamResponse? = null
    }

    private fun transport(vararg lines: String?) = FakeAiTransport().apply {
        response = FakeAiStreamResponse(200, lines.toList())
    }

    // ---------- 脚手架 ----------

    private fun anthropicConnection(
        baseUrl: String = "https://api.anthropic.com/v1",
        // 模型级推理等级配置（形状与「配置模型」浮层落库一致）：null = 不配置
        reasoningLevel: String? = null,
    ) = AiConnection(
        providerId = "anthropic",
        baseUrl = baseUrl,
        model = "claude-sonnet-4-5",
        modelConfigs = reasoningLevel
            ?.let { mapOf("claude-sonnet-4-5" to AiModelConfig(listOf(it), it)) }
            ?: emptyMap(),
        protocol = AiProtocol.Anthropic,
    )

    private val turns = listOf(
        AiChatTurn("system", "你是课表助手"),
        AiChatTurn("user", "帮我看看下周安排"),
    )

    private fun delta(text: String) =
        """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    private fun messageStop() = """data: {"type":"message_stop"}"""

    private fun bodyOf(request: AiHttpRequest) = Json.parseToJsonElement(request.jsonBody).jsonObject

    private fun rolesOf(body: kotlinx.serialization.json.JsonObject): List<String> =
        body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }

    // ---------- 请求形态 ----------

    @Test
    fun `anthropic request targets messages endpoint with api key headers`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(anthropicConnection(), "sk-ant-secret", turns)

        val request = transport.lastRequest!!
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant-secret", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        assertNull("Anthropic 原生端点不用 Bearer", request.headers["Authorization"])

        val body = bodyOf(request)
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals("4096", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic appends v1 when base url has no version segment`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(
            anthropicConnection(
                "https://ws-usmipu1b1bhqhywo.cn-beijing.maas.aliyuncs.com/apps/anthropic",
            ),
            "sk",
            turns,
        )

        assertEquals(
            "https://ws-usmipu1b1bhqhywo.cn-beijing.maas.aliyuncs.com/apps/anthropic/v1/messages",
            transport.lastRequest!!.url,
        )
    }

    @Test
    fun `anthropic keeps versioned base url without duplicating v1`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(anthropicConnection("https://api.anthropic.com/v1/"), "sk", turns)

        assertEquals("https://api.anthropic.com/v1/messages", transport.lastRequest!!.url)
    }

    @Test
    fun `anthropic keeps full messages endpoint as is`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(
            anthropicConnection(
                "https://ws-usmipu1b1bhqhywo.cn-beijing.maas.aliyuncs.com/apps/anthropic/v1/messages",
            ),
            "sk",
            turns,
        )

        assertEquals(
            "https://ws-usmipu1b1bhqhywo.cn-beijing.maas.aliyuncs.com/apps/anthropic/v1/messages",
            transport.lastRequest!!.url,
        )
    }

    @Test
    fun `anthropic hoists system turn to top level field`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(
            anthropicConnection(),
            "sk",
            turns + listOf(AiChatTurn("assistant", "好的"), AiChatTurn("user", "明天呢")),
        )

        val body = bodyOf(transport.lastRequest!!)
        assertEquals("你是课表助手", body["system"]!!.jsonPrimitive.content)
        assertEquals(listOf("user", "assistant", "user"), rolesOf(body))
    }

    @Test
    fun `anthropic omits system field when no system turn`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(
            anthropicConnection(),
            "sk",
            listOf(AiChatTurn("user", "hi")),
        )

        assertNull(bodyOf(transport.lastRequest!!)["system"])
        assertEquals(listOf("user"), rolesOf(bodyOf(transport.lastRequest!!)))
    }

    @Test
    fun `anthropic merges consecutive user turns and drops leading assistant`() = runBlocking {
        val transport = transport(messageStop(), null)
        AiClient(transport).streamChat(
            anthropicConnection(),
            "sk",
            listOf(
                AiChatTurn("system", "修复契约"),
                AiChatTurn("assistant", "窗口截断的开头"), // HISTORY_WINDOW 截断可能以 assistant 开头
                AiChatTurn("user", "上一轮回复"),
                AiChatTurn("user", "请按契约修复"),
            ),
        )

        val body = bodyOf(transport.lastRequest!!)
        assertEquals("修复契约", body["system"]!!.jsonPrimitive.content)
        assertEquals(listOf("user"), rolesOf(body))
        assertEquals(
            "上一轮回复\n\n请按契约修复",
            body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    // ---------- 输出上限与 thinking 预算 ----------

    private fun anthropicWithConfig(
        level: String,
        maxOutputTokens: Int = 0,
    ) = AiConnection(
        providerId = "anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
        modelConfigs = mapOf(
            "claude-sonnet-4-5" to AiModelConfig(
                reasoningLevels = listOf(level),
                reasoningLevel = level,
                maxOutputTokens = maxOutputTokens,
            ),
        ),
        protocol = AiProtocol.Anthropic,
    )

    @Test
    fun `anthropic max output caps thinking budget`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicWithConfig("xhigh", maxOutputTokens = 8_192), "sk", turns)

        val body = bodyOf(t.lastRequest!!)
        assertEquals("8192", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals(
            "thinking 预算必须给答案留出余量",
            "7168",
            body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic numeric reasoning level is clamped`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicWithConfig("100000"), "sk", turns)

        val body = bodyOf(t.lastRequest!!)
        assertEquals("32000", body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        // 未配置 maxOutputTokens：沿用「预算 + 4096」的旧口径
        assertEquals("36096", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic tiny max output disables thinking`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicWithConfig("high", maxOutputTokens = 2_048), "sk", turns)

        val body = bodyOf(t.lastRequest!!)
        assertEquals("2048", body["max_tokens"]!!.jsonPrimitive.content)
        assertNull("放不下最小思考预算时不启用 thinking", body["thinking"])
    }

    @Test
    fun `openai skips temperature when model rejects it`() = runBlocking {
        val t = transport("data: [DONE]", null)
        val connection = AiConnection(
            providerId = "openai",
            baseUrl = "https://api.openai.com/v1",
            model = "o3",
            modelConfigs = mapOf("o3" to AiModelConfig(supportsTemperature = false)),
        )
        AiClient(t).streamChat(
            connection,
            "sk",
            listOf(AiChatTurn("user", "hi")),
            temperature = 0.0,
        )

        assertNull("模型不接受 temperature 时请求体不得携带该字段", bodyOf(t.lastRequest!!)["temperature"])
    }

    @Test
    fun `openai sends temperature by default`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            openAiConnection(),
            "sk",
            listOf(AiChatTurn("user", "hi")),
            temperature = 0.0,
        )

        assertEquals("0.0", bodyOf(t.lastRequest!!)["temperature"]!!.jsonPrimitive.content)
    }

    // ---------- 带图 turn 的 content 形状 ----------

    private fun openAiConnection(
        baseUrl: String = "https://api.openai.com/v1",
        // 模型级推理等级配置（形状与「配置模型」浮层落库一致）：null = 不配置
        reasoningLevel: String? = null,
    ) = AiConnection(
        providerId = "openai",
        baseUrl = baseUrl,
        model = "gpt-4o-mini",
        modelConfigs = reasoningLevel
            ?.let { mapOf("gpt-4o-mini" to AiModelConfig(listOf(it), it)) }
            ?: emptyMap(),
    )

    private val image = AiTurnImage(mediaType = "image/jpeg", base64 = "QUJD")

    @Test
    fun `openai turn with images serializes multimodal content array`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            openAiConnection(),
            "sk",
            listOf(AiChatTurn("user", "这是什么", listOf(image))),
        )

        val content = bodyOf(t.lastRequest!!)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("这是什么", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `openai image only turn omits text block`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(openAiConnection(), "sk", listOf(AiChatTurn("user", "", listOf(image))))

        val content = bodyOf(t.lastRequest!!)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic turn with images serializes base64 image blocks`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(
            anthropicConnection(),
            "sk",
            listOf(AiChatTurn("user", "这是什么", listOf(image))),
        )

        val content = bodyOf(t.lastRequest!!)["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("这是什么", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val source = content[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `turn without images keeps plain string content on both protocols`() = runBlocking {
        // 兼容面回归：无图请求的 content 保持字符串（部分网关对数组 content 支持不佳）
        val openAi = transport("data: [DONE]", null)
        AiClient(openAi).streamChat(openAiConnection(), "sk", listOf(AiChatTurn("user", "你好")))
        val openAiContent = bodyOf(openAi.lastRequest!!)["messages"]!!.jsonArray[0].jsonObject["content"]!!
        assertTrue("无图 content 应保持字符串", openAiContent is JsonPrimitive)
        assertEquals("你好", openAiContent.jsonPrimitive.content)

        val anthropic = transport(messageStop(), null)
        AiClient(anthropic).streamChat(anthropicConnection(), "sk", listOf(AiChatTurn("user", "你好")))
        val anthropicContent = bodyOf(anthropic.lastRequest!!)["messages"]!!.jsonArray[0].jsonObject["content"]!!
        assertTrue("无图 content 应保持字符串", anthropicContent is JsonPrimitive)
        assertEquals("你好", anthropicContent.jsonPrimitive.content)
    }

    // ---------- 推理等级（请求侧生效） ----------

    /** 多等级配置：第一个为「关」档（2~5 个等级按从低到高排列，关 = 不开启思考） */
    private fun openAiConnectionWithLevels(levels: List<String>, selected: String) = AiConnection(
        providerId = "openai",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
        modelConfigs = mapOf("gpt-4o-mini" to AiModelConfig(levels, selected)),
    )

    private fun anthropicConnectionWithLevels(levels: List<String>, selected: String) = AiConnection(
        providerId = "anthropic",
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-4-5",
        modelConfigs = mapOf("claude-sonnet-4-5" to AiModelConfig(levels, selected)),
        protocol = AiProtocol.Anthropic,
    )

    @Test
    fun `openai off level omits reasoning effort`() = runBlocking {
        // 等级列表第一个即「关」：未声明 thinking 开关形态的服务商不发任何推理参数
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            openAiConnectionWithLevels(listOf("none", "high"), "none"),
            "sk",
            listOf(AiChatTurn("user", "hi")),
        )

        val body = bodyOf(t.lastRequest!!)
        assertNull("关档位不得发送 reasoning_effort", body["reasoning_effort"])
        assertNull("未声明 ThinkingType 的服务商不发 thinking（避免严格网关 400）", body["thinking"])
    }

    /** DeepSeek 预设：思考默认开启，关档位必须显式 thinking.disabled 才真正关闭 */
    private fun deepSeekConnectionWithLevels(levels: List<String>, selected: String) = AiConnection(
        providerId = "deepseek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-flash",
        modelConfigs = mapOf("deepseek-flash" to AiModelConfig(levels, selected)),
    )

    @Test
    fun `deepseek off level explicitly disables thinking`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            deepSeekConnectionWithLevels(listOf("off", "high"), "off"),
            "sk",
            listOf(AiChatTurn("user", "hi")),
        )

        val body = bodyOf(t.lastRequest!!)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull("关档位不发送 reasoning_effort", body["reasoning_effort"])
    }

    @Test
    fun `deepseek non-off level enables thinking with effort`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            deepSeekConnectionWithLevels(listOf("off", "high"), "high"),
            "sk",
            listOf(AiChatTurn("user", "hi")),
        )

        val body = bodyOf(t.lastRequest!!)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai non-off level in list still passes through`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            openAiConnectionWithLevels(listOf("none", "high"), "high"),
            "sk",
            listOf(AiChatTurn("user", "hi")),
        )

        assertEquals("high", bodyOf(t.lastRequest!!)["reasoning_effort"]!!.jsonPrimitive.content)
    }

    // ---------- 服务商 / 模型族思考控制矩阵 ----------

    private fun providerConnection(
        providerId: String,
        model: String,
        levels: List<String>,
        selected: String,
    ) = AiConnection(
        providerId = providerId,
        baseUrl = "https://api.example.com/v1",
        model = model,
        modelConfigs = mapOf(model to AiModelConfig(levels, selected)),
    )

    private suspend fun openAiBody(
        providerId: String,
        model: String,
        levels: List<String>,
        selected: String,
    ): kotlinx.serialization.json.JsonObject {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(
            providerConnection(providerId, model, levels, selected),
            "sk",
            listOf(AiChatTurn("user", "hi")),
        )
        return bodyOf(t.lastRequest!!)
    }

    @Test
    fun `always thinking families send effort without thinking or off`() = runBlocking {
        // GLM-5.3 与 Kimi K3：思考始终开启，首档是最低强度，必须照常发送
        val glm = openAiBody("zhipu", "glm-5.3", listOf("low", "high", "max"), "low")
        assertEquals("low", glm["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull("始终思考的模型族不发 thinking", glm["thinking"])

        val kimi = openAiBody("moonshot", "kimi-k3.1-pro", listOf("low", "high", "max"), "low")
        assertEquals("low", kimi["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull("Kimi K3 不接受 thinking 参数", kimi["thinking"])
    }

    @Test
    fun `glm 5_2 off disables thinking and level enables with effort`() = runBlocking {
        val levels = listOf("none", "low", "medium", "high", "max")
        val off = openAiBody("zhipu", "glm-5.2", levels, "none")
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull("关档位不发送 reasoning_effort", off["reasoning_effort"])

        val on = openAiBody("zhipu", "glm-5.2", levels, "high")
        assertEquals("enabled", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", on["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `switch only families send thinking type without effort`() = runBlocking {
        // GLM 开关族 / MiMo / Kimi K2.6：档位只用于 UI 映射，线上只发 thinking 开关
        listOf(
            "zhipu" to "glm-4.6",
            "mimo" to "mimo-v2.6-pro",
            "moonshot" to "kimi-k2.6",
        ).forEach { (providerId, model) ->
            val off = openAiBody(providerId, model, listOf("off", "on"), "off")
            assertEquals(
                "$model 关档应发 thinking.disabled",
                "disabled",
                off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            )
            assertNull("$model 不得发送 reasoning_effort", off["reasoning_effort"])

            val on = openAiBody(providerId, model, listOf("off", "on"), "on")
            assertEquals(
                "$model 非关档应发 thinking.enabled",
                "enabled",
                on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
            )
            assertNull("$model 不得发送 reasoning_effort", on["reasoning_effort"])
        }
    }

    @Test
    fun `qwen families use enable thinking with or without effort`() = runBlocking {
        val qwen38 = listOf("none", "low", "medium", "xhigh")
        val off = openAiBody("qwen", "qwen3.8-flash", qwen38, "none")
        assertEquals("false", off["enable_thinking"]!!.jsonPrimitive.content)
        assertNull(off["reasoning_effort"])

        val on = openAiBody("qwen", "qwen3.8-flash", qwen38, "medium")
        assertEquals("true", on["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("medium", on["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull("千问不使用 thinking 对象", on["thinking"])

        // qwen-plus 等 hybrid 只有开关，没有 effort
        val hybridOn = openAiBody("qwen", "qwen-plus", listOf("off", "on"), "on")
        assertEquals("true", hybridOn["enable_thinking"]!!.jsonPrimitive.content)
        assertNull(hybridOn["reasoning_effort"])
    }

    @Test
    fun `bailian vendor families use enable thinking`() = runBlocking {
        val deepseekOff = openAiBody(
            "bailian",
            "deepseek-v4-pro",
            listOf("none", "low", "high", "max"),
            "none",
        )
        assertEquals("false", deepseekOff["enable_thinking"]!!.jsonPrimitive.content)
        assertNull(deepseekOff["reasoning_effort"])

        val deepseekOn = openAiBody(
            "bailian",
            "deepseek-v4-pro",
            listOf("none", "low", "high", "max"),
            "high",
        )
        assertEquals("true", deepseekOn["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("high", deepseekOn["reasoning_effort"]!!.jsonPrimitive.content)

        val glm = openAiBody("bailian", "glm-4.6", listOf("none", "high", "max"), "high")
        assertEquals("true", glm["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("high", glm["reasoning_effort"]!!.jsonPrimitive.content)

        val kimi = openAiBody("bailian", "kimi-k2.6", listOf("off", "on"), "on")
        assertEquals("true", kimi["enable_thinking"]!!.jsonPrimitive.content)
        assertNull(kimi["reasoning_effort"])
    }

    @Test
    fun `anthropic off level keeps request without thinking`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(
            anthropicConnectionWithLevels(listOf("off", "high"), "off"),
            "sk",
            turns,
            temperature = 0.0,
        )

        val body = bodyOf(t.lastRequest!!)
        assertNull("关档位不得发送 thinking", body["thinking"])
        assertEquals("4096", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("0.0", body["temperature"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic non-off level in list maps to thinking budget`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicConnectionWithLevels(listOf("off", "high"), "high"), "sk", turns)

        val body = bodyOf(t.lastRequest!!)
        assertEquals("16384", body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("20480", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai reasoning effort passes through verbatim`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(openAiConnection(reasoningLevel = "xhigh"), "sk", listOf(AiChatTurn("user", "hi")))

        // 等级原样透传（minimal/low/medium/high/xhigh 及各家自定义值），不做本地校验
        assertEquals("xhigh", bodyOf(t.lastRequest!!)["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai request without reasoning level omits reasoning effort`() = runBlocking {
        val t = transport("data: [DONE]", null)
        AiClient(t).streamChat(openAiConnection(), "sk", listOf(AiChatTurn("user", "hi")))

        assertNull("未配置等级时不得发送 reasoning_effort（既有请求形状不变）", bodyOf(t.lastRequest!!)["reasoning_effort"])
    }

    @Test
    fun `anthropic named reasoning level maps to thinking budget and raises max tokens`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicConnection(reasoningLevel = "medium"), "sk", turns, temperature = 0.0)

        val body = bodyOf(t.lastRequest!!)
        val thinking = body["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals("8192", thinking["budget_tokens"]!!.jsonPrimitive.content)
        // max_tokens = budget + 默认 4096：答案余量与无思考时持平，且 > budget_tokens
        assertEquals("12288", body["max_tokens"]!!.jsonPrimitive.content)
        assertNull("thinking 启用时不得携带 temperature（Anthropic 只接受 1）", body["temperature"])
    }

    @Test
    fun `anthropic reasoning level matching ignores case`() = runBlocking {
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicConnection(reasoningLevel = "HIGH"), "sk", turns)

        val body = bodyOf(t.lastRequest!!)
        assertEquals("16384", body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("20480", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic numeric reasoning level passes as budget clamped to minimum`() = runBlocking {
        val plain = transport(messageStop(), null)
        AiClient(plain).streamChat(anthropicConnection(reasoningLevel = "4096"), "sk", turns)
        assertEquals(
            "4096",
            bodyOf(plain.lastRequest!!)["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content,
        )

        // Anthropic 对 budget_tokens 的硬性下限 1024：更小的数字钳到下限
        val clamped = transport(messageStop(), null)
        AiClient(clamped).streamChat(anthropicConnection(reasoningLevel = "512"), "sk", turns)
        val body = bodyOf(clamped.lastRequest!!)
        assertEquals("1024", body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("5120", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic unmapped reasoning level keeps request without thinking`() = runBlocking {
        // 未知写法不猜（错发预算比不发更糟）：无 thinking、max_tokens 保持默认，
        // temperature 原样保留（识别路径的 greedy 采样不受影响）
        val t = transport(messageStop(), null)
        AiClient(t).streamChat(anthropicConnection(reasoningLevel = "低"), "sk", turns, temperature = 0.0)

        val body = bodyOf(t.lastRequest!!)
        assertNull("无法映射的等级不得发送 thinking", body["thinking"])
        assertEquals("4096", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("0.0", body["temperature"]!!.jsonPrimitive.content)
    }

    // ---------- SSE 解析 ----------

    @Test
    fun `anthropic deltas accumulate until message stop`() = runBlocking {
        val deltas = mutableListOf<String>()
        val result = transport(
            """data: {"type":"message_start","message":{"id":"msg_1"}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            delta("下"),
            delta("周三"),
            "event: ping",
            """data: {"type":"ping"}""", // 心跳：忽略
            """data: {"type":"content_block_stop","index":0}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            messageStop(),
            null,
        ).let { t ->
            AiClient(t).streamChat(anthropicConnection(), "sk", turns, onDelta = { deltas += it })
        }

        assertEquals("下周三", result)
        assertEquals(listOf("下", "周三"), deltas)
    }

    @Test
    fun `anthropic thinking deltas are delivered via onReasoning`() = runBlocking {
        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val result = transport(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先想"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"再答"}}""",
            // 工具调用参数 / 思考签名等非正文、非思考增量：忽略
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            delta("正文"),
            "data: not-json",
            messageStop(),
            null,
        ).let { t ->
            AiClient(t).streamChat(
                anthropicConnection(),
                "sk",
                turns,
                onDelta = { deltas += it },
                onReasoning = { reasoning += it },
            )
        }

        assertEquals("正文", result)
        assertEquals(listOf("先想", "再答"), reasoning)
        assertEquals(listOf("正文"), deltas)
    }

    // ---------- 流内错误 ----------

    @Test
    fun `anthropic in-stream error classifies by error type`() {
        val cases = mapOf(
            "authentication_error" to AiError.Auth,
            "permission_error" to AiError.Quota,
            "not_found_error" to AiError.NotFound,
            "rate_limit_error" to AiError.RateLimited,
            "api_error" to AiError.Server,
            "overloaded_error" to AiError.Server,
            "invalid_request_error" to AiError.BadResponse,
        )
        for ((type, expected) in cases) {
            val exception = runCatching {
                runBlocking {
                    transport(
                        """data: {"type":"error","error":{"type":"$type","message":"失败详情"}}""",
                        null,
                    ).let { t -> AiClient(t).streamChat(anthropicConnection(), "sk", turns) }
                }
            }.exceptionOrNull()
            assertTrue(type + " should throw", exception is AiException)
            assertEquals(type, expected, (exception as AiException).error)
            assertEquals(type, "失败详情", exception.detail)
        }
    }

    // ---------- EOF 无终止标记（与 OpenAI 侧同语义） ----------

    @Test
    fun `anthropic eof without message stop with content is network and deltas survive`() = runBlocking {
        val deltas = mutableListOf<String>()
        val exception = runCatching {
            transport(delta("半截"), null)
                .let { t -> AiClient(t).streamChat(anthropicConnection(), "sk", turns, onDelta = { deltas += it }) }
        }.exceptionOrNull() as AiException

        assertEquals(AiError.Network, exception.error)
        assertEquals(listOf("半截"), deltas)
    }

    @Test
    fun `anthropic eof without message stop and without content is bad response`() {
        val exception = runCatching {
            runBlocking { transport(null).let { t -> AiClient(t).streamChat(anthropicConnection(), "sk", turns) } }
        }.exceptionOrNull() as AiException
        assertEquals(AiError.BadResponse, exception.error)
    }
}
