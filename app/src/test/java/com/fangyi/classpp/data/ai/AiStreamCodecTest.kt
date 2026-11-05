package com.fangyi.classpp.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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

    private fun anthropicConnection(baseUrl: String = "https://api.anthropic.com/v1") = AiConnection(
        providerId = "anthropic",
        baseUrl = baseUrl,
        model = "claude-sonnet-4-5",
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
