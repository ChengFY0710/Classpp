package com.fangyi.classpp.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原生 tools 的 wire 层：OpenAI 参数分片/多调用乱序/正文+工具同包/结束原因，
 * Anthropic tool_use 块累积与 block-aware 请求编码。全部走脚本化假流。
 */
class AiToolStreamTest {

    private fun connection(protocol: AiProtocol = AiProtocol.OpenAiCompat) = AiConnection(
        providerId = "custom",
        baseUrl = "https://api.example.com/v1",
        model = "test-model",
        protocol = protocol,
    )

    private class ScriptedResponse(
        private val lines: List<String?>,
        override val status: Int = 200,
    ) : AiStreamResponse {
        private var index = 0

        override fun readLine(): String? = lines.getOrNull(index++)

        override fun readRemaining(): String = ""

        override fun cancel() = Unit

        override fun close() = Unit
    }

    private class FakeTransport(private val responses: MutableList<AiStreamResponse>) : AiHttpTransport {
        val opened = mutableListOf<AiHttpRequest>()

        override fun open(request: AiHttpRequest): AiStreamResponse {
            opened += request
            return responses.removeAt(0)
        }
    }

    private fun client(vararg lines: String?): Pair<AiClient, FakeTransport> {
        val transport = FakeTransport(mutableListOf(ScriptedResponse(lines.toList())))
        return AiClient(transport) to transport
    }

    private fun openAiToolDelta(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ): String = "data: " + buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put(
                            "delta",
                            buildJsonObject {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("index", index)
                                                id?.let { put("id", it) }
                                                put("type", "function")
                                                put(
                                                    "function",
                                                    buildJsonObject {
                                                        name?.let { put("name", it) }
                                                        arguments?.let { put("arguments", it) }
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    private fun finish(reason: String): String =
        """data: {"choices":[{"delta":{},"finish_reason":"$reason"}]}"""

    // ---------- OpenAI ----------

    @Test
    fun `openai accumulates split arguments and reports finish reason`() = runBlocking {
        val (client, transport) = client(
            openAiToolDelta(index = 0, id = "call_1", name = "get_day_plan", arguments = """{"date":"""),
            openAiToolDelta(index = 0, arguments = """"2026-11-18"}"""),
            finish("tool_calls"),
            "data: [DONE]",
        )

        val result = client.streamChatWithTools(
            connection = connection(),
            apiKey = "sk",
            turns = listOf(AiChatTurn("user", "下周三有空吗")),
            tools = listOf(AiToolRegistry.find(AiToolRegistry.GET_DAY_PLAN)!!),
        )

        assertEquals(1, result.toolCalls.size)
        val call = result.toolCalls.single()
        assertEquals("call_1", call.id)
        assertEquals("get_day_plan", call.name)
        assertEquals("""{"date":"2026-11-18"}""", call.argumentsJson)
        assertEquals("tool_calls", result.finishReason)
        assertTrue(transport.opened.single().jsonBody.contains("\"tools\""))
    }

    @Test
    fun `openai sorts out-of-order indexes and keeps content plus tool calls`() = runBlocking {
        val contentAndTool = "data: " + buildJsonObject {
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "delta",
                                buildJsonObject {
                                    put("content", "我先查一下。")
                                    put(
                                        "tool_calls",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("index", 0)
                                                    put("id", "call_a")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", "get_day_plan")
                                                        },
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
        }
        val (client, _) = client(
            openAiToolDelta(index = 1, id = "call_b", name = "search_todos", arguments = "{}"),
            contentAndTool,
            openAiToolDelta(index = 0, arguments = "{}"),
            finish("tool_calls"),
            "data: [DONE]",
        )

        val result = client.streamChatWithTools(
            connection = connection(),
            apiKey = "sk",
            turns = listOf(AiChatTurn("user", "查一下")),
            tools = AiToolRegistry.nativeSpecs,
        )

        assertEquals("我先查一下。", result.text)
        assertEquals(listOf("call_a", "call_b"), result.toolCalls.map { it.id })
    }

    @Test
    fun `openai request omits tools when none are passed`() = runBlocking {
        val (client, transport) = client("data: [DONE]")

        client.streamChat(
            connection = connection(),
            apiKey = "sk",
            turns = listOf(AiChatTurn("user", "你好")),
        )

        assertFalse(transport.opened.single().jsonBody.contains("\"tools\""))
    }

    @Test
    fun `openai eof with tool call delta keeps partial as network error`() = runBlocking {
        val (client, _) = client(
            openAiToolDelta(index = 0, id = "call_1", name = "get_day_plan", arguments = """{"date":"""),
            null,
        )

        val error = runCatching {
            client.streamChatWithTools(
                connection = connection(),
                apiKey = "sk",
                turns = listOf(AiChatTurn("user", "下周三")),
                tools = AiToolRegistry.nativeSpecs,
            )
        }.exceptionOrNull()

        assertTrue(error is AiException)
        assertEquals(AiError.Network, (error as AiException).error)
    }

    // ---------- Anthropic ----------

    private fun anthropicSse(type: String, body: String): String = "data: $body"

    @Test
    fun `anthropic accumulates tool_use input json and stop reason`() = runBlocking {
        val (client, transport) = client(
            anthropicSse(
                "content_block_start",
                """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use",""" +
                    """"id":"toolu_1","name":"get_day_plan","input":{}}}""",
            ),
            anthropicSse(
                "content_block_delta",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta",""" +
                    """"partial_json":"{\"date\":"}}""",
            ),
            anthropicSse(
                "content_block_delta",
                """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta",""" +
                    """"partial_json":"\"2026-11-18\"}"}}""",
            ),
            anthropicSse("content_block_stop", """{"type":"content_block_stop","index":1}"""),
            anthropicSse(
                "message_delta",
                """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            ),
            anthropicSse("message_stop", """{"type":"message_stop"}"""),
        )

        val result = client.streamChatWithTools(
            connection = connection(AiProtocol.Anthropic),
            apiKey = "sk",
            turns = listOf(AiChatTurn("user", "下周三有空吗")),
            tools = listOf(AiToolRegistry.find(AiToolRegistry.GET_DAY_PLAN)!!),
        )

        assertEquals(1, result.toolCalls.size)
        assertEquals("toolu_1", result.toolCalls.single().id)
        assertEquals("get_day_plan", result.toolCalls.single().name)
        assertEquals("""{"date":"2026-11-18"}""", result.toolCalls.single().argumentsJson)
        assertEquals("tool_use", result.finishReason)
        assertTrue(transport.opened.single().jsonBody.contains("\"input_schema\""))
    }

    @Test
    fun `anthropic encodes tool use and tool result as paired blocks`() = runBlocking {
        val (client, transport) = client(anthropicSse("message_stop", """{"type":"message_stop"}"""))

        client.streamChatWithTools(
            connection = connection(AiProtocol.Anthropic),
            apiKey = "sk",
            turns = listOf(
                AiChatTurn("system", "你是助手"),
                AiChatTurn("user", "我下周三下午有空吗"),
                AiChatTurn(
                    role = "assistant",
                    content = "我先查一下。",
                    toolCalls = listOf(
                        AiToolCall(
                            id = "toolu_1",
                            name = "get_day_plan",
                            argumentsJson = """{"date":"2026-11-18"}""",
                        ),
                    ),
                ),
                AiChatTurn(
                    role = "tool",
                    toolResults = listOf(
                        AiToolResult(
                            callId = "toolu_1",
                            name = "get_day_plan",
                            contentJson = """{"ok":true,"data":{}}""",
                        ),
                    ),
                ),
            ),
            tools = listOf(AiToolRegistry.find(AiToolRegistry.GET_DAY_PLAN)!!),
        )

        val body = kotlinx.serialization.json.Json
            .parseToJsonElement(transport.opened.single().jsonBody)
            .jsonObject
        assertEquals("你是助手", body.getValue("system").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray
        assertEquals(listOf("user", "assistant", "user"), messages.map {
            it.jsonObject.getValue("role").jsonPrimitive.content
        })
        val assistantBlocks = messages[1].jsonObject.getValue("content").jsonArray
        assertEquals("text", assistantBlocks[0].jsonObject.getValue("type").jsonPrimitive.content)
        val toolUse = assistantBlocks[1].jsonObject
        assertEquals("tool_use", toolUse.getValue("type").jsonPrimitive.content)
        assertEquals("toolu_1", toolUse.getValue("id").jsonPrimitive.content)
        assertEquals(
            "2026-11-18",
            toolUse.getValue("input").jsonObject.getValue("date").jsonPrimitive.content,
        )
        val resultBlocks = messages[2].jsonObject.getValue("content").jsonArray
        val toolResult = resultBlocks[0].jsonObject
        assertEquals("tool_result", toolResult.getValue("type").jsonPrimitive.content)
        assertEquals("toolu_1", toolResult.getValue("tool_use_id").jsonPrimitive.content)
        assertEquals(
            """{"ok":true,"data":{}}""",
            toolResult.getValue("content").jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic legacy text-only request keeps string content`() = runBlocking {
        val (client, transport) = client(anthropicSse("message_stop", """{"type":"message_stop"}"""))

        client.streamChat(
            connection = connection(AiProtocol.Anthropic),
            apiKey = "sk",
            turns = listOf(
                AiChatTurn("system", "system"),
                AiChatTurn("user", "你好"),
                AiChatTurn("assistant", "你好呀"),
                AiChatTurn("user", "继续"),
            ),
        )

        val body = kotlinx.serialization.json.Json
            .parseToJsonElement(transport.opened.single().jsonBody)
            .jsonObject
        val messages = body.getValue("messages").jsonArray
        // 无工具轮次走原字符串路径：content 是字符串，role 严格交替
        assertEquals(listOf("user", "assistant", "user"), messages.map {
            it.jsonObject.getValue("role").jsonPrimitive.content
        })
        assertEquals("你好", messages[0].jsonObject.getValue("content").jsonPrimitive.content)
        assertFalse(transport.opened.single().jsonBody.contains("\"tools\""))
    }
}
