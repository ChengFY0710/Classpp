package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * SSE `data:` 载荷的解析结果；空列表 = 与增量无关的载荷（心跳/元信息等，跳过）。
 *
 * 一条载荷可能同时带正文与工具调用（OpenAI 的 content + tool_calls 同包、Anthropic
 * 的 text_delta 与 input_json_delta 不同包但同一帧），所以返回列表而不是单值。
 */
internal sealed interface AiStreamEvent {

    /** 一段增量文本；空串不产生（调用侧不再判空） */
    data class Delta(val text: String) : AiStreamEvent

    /**
     * 一段思考/推理增量（推理模型的思维链）；与 [Delta] 分开交付，调用侧单独累积，
     * 不参与正文拼接，也不回放进后续请求。
     */
    data class Reasoning(val text: String) : AiStreamEvent

    /**
     * 工具调用增量：OpenAI 按 `delta.tool_calls[].index` 合并分片，Anthropic 按
     * `content_block_start` 的 `index` + `input_json_delta.partial_json` 合并。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val name: String? = null,
        val argumentsDelta: String = "",
    ) : AiStreamEvent

    /** 服务商给出的结束原因（OpenAI `finish_reason` / Anthropic `stop_reason`） */
    data class Finish(val reason: String?) : AiStreamEvent

    /** 流正常终止（OpenAI 的 `[DONE]` 行 / Anthropic 的 `message_stop` 事件） */
    data object Done : AiStreamEvent
}

/**
 * 一次流式请求的协议编解码：把 [AiChatTurn] 序列编码为一次 HTTP 请求，并把 SSE
 * `data:` 载荷解析为 [AiStreamEvent]。协议差异——端点路径、认证头、请求体形状、
 * 事件形状、流内错误分类——全部收口在此实现里；传输层、取消管线与 HTTP 状态分类
 * 是协议无关的，留在 [AiClient]。
 *
 * @param tools 本次请求可用的原生工具；空 = 不发送 `tools` 字段（JSON 回退模式）
 */
internal interface AiStreamCodec {

    fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec> = emptyList(),
    ): AiHttpRequest

    /**
     * 解析一条 `data:` 载荷；空列表 = 本载荷与增量无关（跳过）。
     * 服务商把错误放进流里时转成分类 [AiException] 抛出。
     */
    fun onData(payload: String): List<AiStreamEvent>

    companion object {
        fun of(protocol: AiProtocol): AiStreamCodec = when (protocol) {
            AiProtocol.OpenAiCompat -> OpenAiStreamCodec
            AiProtocol.Anthropic -> AnthropicStreamCodec
        }
    }
}

/**
 * OpenAI 兼容 `/chat/completions` 编解码（OpenAI / DeepSeek / 智谱 / 通义 / Kimi /
 * Ollama 同协议）。
 *
 * - 请求：Bearer 认证；体为 `{model, stream, temperature?, messages, tools?}`；
 *   assistant 带 `tool_calls` 时同时落 `tool_calls` 数组，tool 轮每个结果一条
 *   `{role:"tool", tool_call_id, content}` 消息；
 * - 解析：`data: [DONE]` 终止；`choices[0].delta.content` 为正文增量；
 *   `delta.reasoning_content`（DeepSeek 等）或 `delta.reasoning`（部分兼容网关）
 *   为思考增量；`delta.tool_calls[]` 为工具调用分片（index 可能乱序/跳号）；
 *   `finish_reason` 单独作为 [AiStreamEvent.Finish] 交付；
 *   流内 `{"error": {code, message}}` 按数值 code 分类。
 */
internal object OpenAiStreamCodec : AiStreamCodec {

    override fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec>,
    ): AiHttpRequest = AiHttpRequest(
        url = connection.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH,
        headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "text/event-stream",
        ),
        jsonBody = encodeRequest(connection.model, turns, temperature, tools),
    )

    override fun onData(payload: String): List<AiStreamEvent> {
        if (payload == DONE_MARKER) return listOf(AiStreamEvent.Done)
        val obj = parseObject(payload) ?: return emptyList() // 容忍不可解析载荷
        (obj["error"] as? JsonObject)?.let { throw classifyStreamError(it) }
        val choice = (obj["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?: return emptyList()
        val events = mutableListOf<AiStreamEvent>()
        val delta = choice["delta"] as? JsonObject
        if (delta != null) {
            val text = delta["content"].stringContentOrNull().orEmpty()
            if (text.isNotEmpty()) events += AiStreamEvent.Delta(text)
            // 推理模型的思考增量：DeepSeek 用 reasoning_content，部分兼容网关用 reasoning
            val reasoning = delta["reasoning_content"].stringContentOrNull()
                ?: delta["reasoning"].stringContentOrNull().orEmpty()
            if (reasoning.isNotEmpty()) events += AiStreamEvent.Reasoning(reasoning)
            (delta["tool_calls"] as? JsonArray)?.forEachIndexed { fallbackIndex, element ->
                val call = element as? JsonObject ?: return@forEachIndexed
                val index = call["index"].intOrNull() ?: fallbackIndex
                val function = call["function"] as? JsonObject
                val arguments = function?.get("arguments").argumentsText()
                val id = call["id"].stringContentOrNull()
                val name = function?.get("name").stringContentOrNull()
                if (id != null || name != null || arguments.isNotEmpty()) {
                    events += AiStreamEvent.ToolCallDelta(index, id, name, arguments)
                }
            }
        }
        // finish_reason 可能在 choices[0] 顶层，也可能出现在 delta 为空的数据帧
        val finish = choice["finish_reason"].stringContentOrNull()
            ?: delta?.get("finish_reason").stringContentOrNull()
        if (finish != null) events += AiStreamEvent.Finish(finish)
        return events
    }

    private fun encodeRequest(
        model: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec>,
    ): String {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            temperature?.let { put("temperature", it) }
            put("messages", JsonArray(encodeMessages(turns)))
            if (tools.isNotEmpty()) put("tools", AiToolRegistry.openAiTools(tools))
        }
        return requestJson.encodeToString(payload)
    }

    /** tool 轮一条结果一条消息；assistant 带 tool_calls 时 content 保持字符串（建议空串） */
    private fun encodeMessages(turns: List<AiChatTurn>): List<JsonElement> = buildList {
        for (turn in turns) {
            when {
                turn.role == ROLE_TOOL -> turn.toolResults.forEach { result ->
                    add(
                        buildJsonObject {
                            put("role", ROLE_TOOL)
                            put("tool_call_id", result.callId)
                            put("content", result.contentJson)
                        },
                    )
                }
                turn.toolCalls.isNotEmpty() -> add(
                    buildJsonObject {
                        put("role", turn.role)
                        put("content", turn.content)
                        put("tool_calls", toolCallsElement(turn.toolCalls))
                    },
                )
                else -> add(
                    buildJsonObject {
                        put("role", turn.role)
                        put("content", contentElement(turn))
                    },
                )
            }
        }
    }

    private fun toolCallsElement(calls: List<AiToolCall>): JsonArray = buildJsonArray {
        calls.forEach { call ->
            add(
                buildJsonObject {
                    put("id", call.id)
                    put("type", "function")
                    put(
                        "function",
                        buildJsonObject {
                            put("name", call.name)
                            put("arguments", call.argumentsJson)
                        },
                    )
                },
            )
        }
    }

    /**
     * content 形状：纯文本 turn 保持字符串（既有网关兼容面与回归测试不变）；带图 turn
     * 落成多模态数组——text 块（正文非空时）+ 每图一个 image_url 块（data URL base64）。
     * 纯图无正文（content 为空串）时数组只有图片块，两种服务商均合法。
     */
    private fun contentElement(turn: AiChatTurn): JsonElement = when {
        turn.images.isEmpty() -> JsonPrimitive(turn.content)
        else -> buildJsonArray {
            if (turn.content.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", turn.content)
                })
            }
            turn.images.forEach { image ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:${image.mediaType};base64,${image.base64}")
                    })
                })
            }
        }
    }

    /** 流内错误对象（`{"error": {...}}`）按其数值 code 分类（与 HTTP 状态语义一致） */
    private fun classifyStreamError(error: JsonObject): AiException {
        val message = error["message"].stringContentOrNull()
        val code = error["code"].stringContentOrNull()?.toIntOrNull()
        val type = when (code) {
            401 -> AiError.Auth
            402, 403 -> AiError.Quota
            404 -> AiError.NotFound
            429 -> AiError.RateLimited
            null -> AiError.BadResponse
            else -> if (code in 500..599) AiError.Server else AiError.BadResponse
        }
        return AiException(type, detail = message)
    }

    private const val CHAT_COMPLETIONS_PATH = "/chat/completions"
    private const val DONE_MARKER = "[DONE]"
    private const val ROLE_TOOL = "tool"
}

/**
 * Anthropic Messages 协议编解码：`POST {base}/messages`（预设端点已含 `/v1`）。
 *
 * - **认证**：`x-api-key` 头 + `anthropic-version` 头（不是 Bearer）；
 * - **system 是顶层字段**：role=system 的 turn 不进 messages，拼接为顶层 `system`；
 * - **max_tokens 必填**：取 [DEFAULT_MAX_TOKENS]；
 * - **messages 须以 user 开头且严格交替**：无工具轮次走原字符串拼接路径（既有行为
 *   与回归测试不变）；出现工具轮次时走 block-aware 路径——assistant 的 `tool_use`
 *   编码为 content block，tool 结果编码为紧随其后的 user `tool_result` 块；
 * - **事件按 `type` 分派**：`content_block_start(tool_use)` + `input_json_delta`
 *   累积工具参数；`message_delta.delta.stop_reason` 作为 [AiStreamEvent.Finish]；
 *   `message_stop` 终止；其余非文本增量忽略。
 */
internal object AnthropicStreamCodec : AiStreamCodec {

    override fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec>,
    ): AiHttpRequest = AiHttpRequest(
        url = connection.baseUrl.trimEnd('/') + MESSAGES_PATH,
        headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to API_VERSION,
            "Accept" to "text/event-stream",
        ),
        jsonBody = encodeRequest(connection.model, turns, temperature, tools),
    )

    override fun onData(payload: String): List<AiStreamEvent> {
        val obj = parseObject(payload) ?: return emptyList() // 容忍不可解析载荷
        (obj["error"] as? JsonObject)?.let { throw classifyStreamError(it) }
        return when (obj["type"].stringContentOrNull()) {
            TYPE_CONTENT_BLOCK_START -> {
                val block = obj["content_block"] as? JsonObject ?: return emptyList()
                when (block["type"].stringContentOrNull()) {
                    TYPE_TOOL_USE -> {
                        val index = obj["index"].intOrNull() ?: 0
                        val input = block["input"]
                        // 绝大多数网关的 input 是空对象，参数靠 input_json_delta 累积；
                        // 少数实现直接把完整 input 放在 start 里，此时作为初始参数收下。
                        val arguments = if (input is JsonObject && input.isNotEmpty()) input.toString() else ""
                        listOf(
                            AiStreamEvent.ToolCallDelta(
                                index = index,
                                id = block["id"].stringContentOrNull(),
                                name = block["name"].stringContentOrNull(),
                                argumentsDelta = arguments,
                            ),
                        )
                    }
                    TYPE_TEXT -> {
                        val text = block["text"].stringContentOrNull().orEmpty()
                        if (text.isEmpty()) emptyList() else listOf(AiStreamEvent.Delta(text))
                    }
                    else -> emptyList()
                }
            }
            TYPE_CONTENT_BLOCK_DELTA -> {
                val delta = obj["delta"] as? JsonObject ?: return emptyList()
                when (delta["type"].stringContentOrNull()) {
                    TYPE_TEXT_DELTA -> {
                        val text = delta["text"].stringContentOrNull().orEmpty()
                        if (text.isEmpty()) emptyList() else listOf(AiStreamEvent.Delta(text))
                    }
                    TYPE_THINKING_DELTA -> {
                        val thinking = delta["thinking"].stringContentOrNull().orEmpty()
                        if (thinking.isEmpty()) emptyList() else listOf(AiStreamEvent.Reasoning(thinking))
                    }
                    TYPE_INPUT_JSON_DELTA -> {
                        val partial = delta["partial_json"].stringContentOrNull().orEmpty()
                        if (partial.isEmpty()) {
                            emptyList()
                        } else {
                            listOf(
                                AiStreamEvent.ToolCallDelta(
                                    index = obj["index"].intOrNull() ?: 0,
                                    argumentsDelta = partial,
                                ),
                            )
                        }
                    }
                    else -> emptyList()
                }
            }
            TYPE_MESSAGE_DELTA -> {
                val stop = (obj["delta"] as? JsonObject)?.get("stop_reason").stringContentOrNull()
                if (stop == null) emptyList() else listOf(AiStreamEvent.Finish(stop))
            }
            TYPE_MESSAGE_STOP -> listOf(AiStreamEvent.Done)
            else -> emptyList()
        }
    }

    private fun encodeRequest(
        model: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec>,
    ): String {
        val system = turns.filter { it.role == ROLE_SYSTEM }
            .joinToString(separator = SYSTEM_JOIN_SEPARATOR) { it.content.trim() }
            .trim()
        val nonSystem = turns.filterNot { it.role == ROLE_SYSTEM }
        val hasToolTurns = nonSystem.any { it.toolCalls.isNotEmpty() || it.toolResults.isNotEmpty() }
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", DEFAULT_MAX_TOKENS)
            put("stream", true)
            temperature?.let { put("temperature", it) }
            if (system.isNotEmpty()) put("system", system)
            if (tools.isNotEmpty()) put("tools", AiToolRegistry.anthropicTools(tools))
            put(
                "messages",
                if (hasToolTurns) {
                    JsonArray(sanitizeBlockMessages(nonSystem).map { it.toJson() })
                } else {
                    JsonArray(
                        sanitizeMessages(nonSystem).map { turn ->
                            buildJsonObject {
                                put("role", turn.role)
                                put("content", contentElement(turn))
                            }
                        },
                    )
                },
            )
        }
        return requestJson.encodeToString(payload)
    }

    /**
     * content 形状：纯文本 turn 保持字符串（既有行为与回归测试不变）；带图 turn 落成
     * 多模态数组——text 块（正文非空时）+ 每图一个 image 块（base64 source）。
     * 纯图无正文（content 为空串）时数组只有图片块，Anthropic 合法。
     */
    private fun contentElement(turn: AiChatTurn): JsonElement = when {
        turn.images.isEmpty() -> JsonPrimitive(turn.content)
        else -> buildJsonArray {
            if (turn.content.isNotEmpty()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", turn.content)
                })
            }
            turn.images.forEach { image ->
                add(buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", image.mediaType)
                        put("data", image.base64)
                    })
                })
            }
        }
    }

    /**
     * Anthropic 要求 messages 以 user 开头且严格交替：丢弃开头 assistant，
     * 连续同角色合并（content 以空行连接，图片顺次拼接）。前置条件：剩余序列非空
     * （调用方每次请求都以一条 user turn 收尾）。
     *
     * **仅用于没有工具轮次的请求**；带工具轮次走 [sanitizeBlockMessages]。
     */
    private fun sanitizeMessages(turns: List<AiChatTurn>): List<AiChatTurn> {
        val firstUser = turns.indexOfFirst { it.role != ROLE_ASSISTANT }
        if (firstUser < 0) return emptyList()
        val merged = mutableListOf<AiChatTurn>()
        for (turn in turns.drop(firstUser)) {
            val last = merged.lastOrNull()
            if (last != null && last.role == turn.role) {
                merged[merged.size - 1] = last.copy(
                    content = "${last.content}$SYSTEM_JOIN_SEPARATOR${turn.content}",
                    images = last.images + turn.images,
                )
            } else {
                merged.add(turn)
            }
        }
        return merged
    }

    /** block-aware 路径的中间形态：role + content blocks（tool 轮已折算为 user） */
    private data class BlockMessage(val role: String, val blocks: List<JsonObject>) {
        fun toJson(): JsonObject = buildJsonObject {
            put("role", role)
            put("content", JsonArray(blocks))
        }
    }

    /**
     * 带工具轮次的 messages 清洗：
     * - 丢弃首个「纯 user 文本」之前的 assistant / tool_result（窗口不能从配对中间切）；
     * - tool 轮折成 user 角色；
     * - 连续 user 合并时 `tool_result` 块排在前面（Anthropic 的硬性要求）；
     * - 带 `tool_use` 的 assistant 不与相邻 assistant 合并，避免破坏配对。
     */
    private fun sanitizeBlockMessages(turns: List<AiChatTurn>): List<BlockMessage> {
        val messages = turns.map { turn ->
            BlockMessage(
                role = if (turn.role == ROLE_TOOL) ROLE_USER else turn.role,
                blocks = turnBlocks(turn),
            )
        }.filter { it.blocks.isNotEmpty() }
        val firstUser = messages.indexOfFirst { it.role == ROLE_USER && !it.hasToolResult() }
        val scoped = if (firstUser >= 0) messages.drop(firstUser) else messages.dropWhile { it.role != ROLE_USER }
        val merged = mutableListOf<BlockMessage>()
        for (message in scoped) {
            val last = merged.lastOrNull()
            when {
                last == null -> merged += message
                last.role != message.role -> merged += message
                message.role == ROLE_USER -> {
                    merged[merged.size - 1] = BlockMessage(
                        role = ROLE_USER,
                        // tool_result 必须在最前；合并两个纯文本 user（草稿修复）时顺序不变
                        blocks = last.blocks.filter(::isToolResultBlock) +
                            message.blocks.filter(::isToolResultBlock) +
                            last.blocks.filterNot(::isToolResultBlock) +
                            message.blocks.filterNot(::isToolResultBlock),
                    )
                }
                last.hasToolUse() || message.hasToolUse() -> merged += message
                else -> merged[merged.size - 1] = BlockMessage(
                    role = ROLE_ASSISTANT,
                    blocks = last.blocks + message.blocks,
                )
            }
        }
        return merged.dropWhile { it.role != ROLE_USER }
    }

    /** turn → content blocks：tool 结果 / assistant tool_use / 文本+图片 */
    private fun turnBlocks(turn: AiChatTurn): List<JsonObject> = when {
        turn.toolResults.isNotEmpty() -> buildList {
            turn.toolResults.forEach { result ->
                add(
                    buildJsonObject {
                        put("type", TYPE_TOOL_RESULT)
                        put("tool_use_id", result.callId)
                        put("content", result.contentJson)
                        if (result.isError) put("is_error", true)
                    },
                )
            }
            if (turn.content.isNotEmpty()) add(textBlock(turn.content))
        }
        turn.toolCalls.isNotEmpty() -> buildList {
            if (turn.content.isNotEmpty()) add(textBlock(turn.content))
            turn.toolCalls.forEach { call ->
                add(
                    buildJsonObject {
                        put("type", TYPE_TOOL_USE)
                        put("id", call.id)
                        put("name", call.name)
                        put("input", parseObject(call.argumentsJson) ?: buildJsonObject {})
                    },
                )
            }
        }
        else -> buildList {
            if (turn.content.isNotEmpty()) add(textBlock(turn.content))
            turn.images.forEach { image ->
                add(
                    buildJsonObject {
                        put("type", TYPE_IMAGE)
                        put("source", buildJsonObject {
                            put("type", "base64")
                            put("media_type", image.mediaType)
                            put("data", image.base64)
                        })
                    },
                )
            }
        }
    }

    private fun textBlock(text: String): JsonObject = buildJsonObject {
        put("type", TYPE_TEXT)
        put("text", text)
    }

    private fun BlockMessage.hasToolResult(): Boolean = blocks.any(::isToolResultBlock)

    private fun BlockMessage.hasToolUse(): Boolean = blocks.any(::isToolUseBlock)

    /** 流内错误对象按 Anthropic 错误类型串分类（语义对齐 OpenAI 侧的 HTTP 状态映射） */
    private fun classifyStreamError(error: JsonObject): AiException {
        val type = error["type"].stringContentOrNull()
        val classified = when (type) {
            "authentication_error" -> AiError.Auth
            "permission_error" -> AiError.Quota
            "not_found_error" -> AiError.NotFound
            "rate_limit_error" -> AiError.RateLimited
            "api_error", "overloaded_error" -> AiError.Server
            else -> AiError.BadResponse
        }
        return AiException(classified, detail = error["message"].stringContentOrNull())
    }

    private const val MESSAGES_PATH = "/messages"
    private const val API_VERSION = "2023-06-01"

    /** 各在售 Claude 模型输出上限的公共下限（claude-3-haiku = 4096），避免旧模型 400 */
    private const val DEFAULT_MAX_TOKENS = 4096

    private const val ROLE_SYSTEM = "system"
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_TOOL = "tool"
    private const val SYSTEM_JOIN_SEPARATOR = "\n\n"
    private const val TYPE_CONTENT_BLOCK_START = "content_block_start"
    private const val TYPE_CONTENT_BLOCK_DELTA = "content_block_delta"
    private const val TYPE_MESSAGE_DELTA = "message_delta"
    private const val TYPE_MESSAGE_STOP = "message_stop"
    private const val TYPE_TEXT = "text"
    private const val TYPE_TEXT_DELTA = "text_delta"
    private const val TYPE_THINKING_DELTA = "thinking_delta"
    private const val TYPE_INPUT_JSON_DELTA = "input_json_delta"
    private const val TYPE_TOOL_USE = "tool_use"
    private const val TYPE_TOOL_RESULT = "tool_result"
    private const val TYPE_IMAGE = "image"
}

/** 流内载荷宽容读取（同原 AiClient.JSON）；结构错误由逐字段判空兜住 */
private val payloadJson = Json { ignoreUnknownKeys = true }

/** 请求体编码（同原 AiClient.requestJson：不落默认值） */
private val requestJson = Json { encodeDefaults = false }

/** 解析一条 data 载荷为 JSON 对象；null = 非对象或不可解析（调用方跳过，不致命） */
private fun parseObject(payload: String): JsonObject? = try {
    payloadJson.parseToJsonElement(payload) as? JsonObject
} catch (_: Exception) {
    null
}

/** 取字符串内容：JSON null / 非字符串（对象、数组）一律 null，不让畸形载荷炸掉整条流 */
private fun JsonElement?.stringContentOrNull(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> try {
        content
    } catch (_: Exception) {
        null
    }
    else -> null
}

/** 取整数值（OpenAI/Anthropic 的 index）：非整数/缺省返回 null */
private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * 工具参数增量：正常是字符串分片；少数网关直接给对象/数组时取其 JSON 文本，
 * 仍交给累积器拼接（完整参数最终由执行层校验）。
 */
private fun JsonElement?.argumentsText(): String = when (this) {
    null, is JsonNull -> ""
    is JsonObject, is JsonArray -> toString()
    is JsonPrimitive -> content
    else -> ""
}

private fun isToolResultBlock(block: JsonObject): Boolean =
    block["type"].stringContentOrNull() == "tool_result"

private fun isToolUseBlock(block: JsonObject): Boolean =
    block["type"].stringContentOrNull() == "tool_use"
