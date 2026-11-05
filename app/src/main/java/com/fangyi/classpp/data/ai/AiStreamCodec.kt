package com.fangyi.classpp.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** SSE `data:` 载荷的解析结果；null = 与增量无关的载荷（心跳/元信息等，跳过） */
internal sealed interface AiStreamEvent {

    /** 一段增量文本；空串不产生（调用侧不再判空） */
    data class Delta(val text: String) : AiStreamEvent

    /**
     * 一段思考/推理增量（推理模型的思维链）；与 [Delta] 分开交付，调用侧单独累积，
     * 不参与正文拼接，也不回放进后续请求。
     */
    data class Reasoning(val text: String) : AiStreamEvent

    /** 流正常终止（OpenAI 的 `[DONE]` 行 / Anthropic 的 `message_stop` 事件） */
    data object Done : AiStreamEvent
}

/**
 * 一次流式请求的协议编解码：把 [AiChatTurn] 序列编码为一次 HTTP 请求，并把 SSE
 * `data:` 载荷解析为 [AiStreamEvent]。协议差异——端点路径、认证头、请求体形状、
 * 事件形状、流内错误分类——全部收口在此实现里；传输层、取消管线与 HTTP 状态分类
 * 是协议无关的，留在 [AiClient]。
 */
internal interface AiStreamCodec {

    fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
    ): AiHttpRequest

    /**
     * 解析一条 `data:` 载荷；返回 null = 本载荷与增量无关（跳过）。
     * 服务商把错误放进流里时转成分类 [AiException] 抛出。
     */
    fun onData(payload: String): AiStreamEvent?

    companion object {
        fun of(protocol: AiProtocol): AiStreamCodec = when (protocol) {
            AiProtocol.OpenAiCompat -> OpenAiStreamCodec
            AiProtocol.Anthropic -> AnthropicStreamCodec
        }
    }
}

/**
 * OpenAI 兼容 `/chat/completions` 编解码（OpenAI / DeepSeek / 智谱 / 通义 / Kimi /
 * Ollama 同协议）——逻辑自原 AiClient 原样迁入，行为零变化。
 *
 * - 请求：Bearer 认证；体为 `{model, stream, temperature?, messages[{role,content}]}`；
 * - 解析：`data: [DONE]` 终止；`choices[0].delta.content` 为正文增量；
 *   `delta.reasoning_content`（DeepSeek 等）或 `delta.reasoning`（部分兼容网关）
 *   为思考增量，作为 [AiStreamEvent.Reasoning] 单独交付；
 *   流内 `{"error": {code, message}}` 按数值 code 分类。
 */
internal object OpenAiStreamCodec : AiStreamCodec {

    override fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
    ): AiHttpRequest = AiHttpRequest(
        url = connection.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH,
        headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Accept" to "text/event-stream",
        ),
        jsonBody = encodeRequest(connection.model, turns, temperature),
    )

    override fun onData(payload: String): AiStreamEvent? {
        if (payload == DONE_MARKER) return AiStreamEvent.Done
        val obj = parseObject(payload) ?: return null // 容忍不可解析载荷
        (obj["error"] as? JsonObject)?.let { throw classifyStreamError(it) }
        val delta = (obj["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("delta") as? JsonObject
            ?: return null
        val text = delta["content"].stringContentOrNull().orEmpty()
        if (text.isNotEmpty()) return AiStreamEvent.Delta(text)
        // 推理模型的思考增量：DeepSeek 用 reasoning_content，部分兼容网关用 reasoning
        val reasoning = delta["reasoning_content"].stringContentOrNull()
            ?: delta["reasoning"].stringContentOrNull().orEmpty()
        return if (reasoning.isEmpty()) null else AiStreamEvent.Reasoning(reasoning)
    }

    private fun encodeRequest(model: String, turns: List<AiChatTurn>, temperature: Double?): String {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            temperature?.let { put("temperature", it) }
            put("messages", JsonArray(turns.map { turn ->
                buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                }
            }))
        }
        return requestJson.encodeToString(payload)
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
}

/**
 * Anthropic Messages 协议编解码：`POST {base}/messages`（预设端点已含 `/v1`）。
 * 与 OpenAI 协议的关键差异，全部在此收口：
 *
 * - **认证**：`x-api-key` 头 + `anthropic-version` 头（不是 Bearer）；
 * - **system 是顶层字段**：role=system 的 turn 不进 messages，拼接为顶层 `system`
 *   （多个以空行连接；没有则省略字段）；
 * - **max_tokens 必填**：取 [DEFAULT_MAX_TOKENS]——各在售 Claude 模型输出上限的
 *   公共下限，选旧模型也不会 400；
 * - **messages 须以 user 开头且严格交替**：丢弃开头 assistant（HISTORY_WINDOW 截断
 *   可能让窗口以 assistant 开头），连续同角色合并（覆盖草稿修复的连续 user 场景）；
 * - **事件按 `type` 分派**：`content_block_delta` 的 `text_delta` 为正文增量、
 *   `thinking_delta` 为思考增量（服务商返回扩展思考时，单独作为
 *   [AiStreamEvent.Reasoning] 交付），`message_stop` 终止，
 *   `ping`/`message_start`/`content_block_*`/`message_delta`/`signature_delta`
 *   等非文本增量一律忽略；流内 `{"type":"error","error":{...}}`
 *   按错误类型串分类（无数值 code，语义对齐 HTTP 状态映射）。
 *
 * temperature 取值须在 0..1（Anthropic 约束；当前无调用方设置）。
 */
internal object AnthropicStreamCodec : AiStreamCodec {

    override fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
    ): AiHttpRequest = AiHttpRequest(
        url = connection.baseUrl.trimEnd('/') + MESSAGES_PATH,
        headers = mapOf(
            "x-api-key" to apiKey,
            "anthropic-version" to API_VERSION,
            "Accept" to "text/event-stream",
        ),
        jsonBody = encodeRequest(connection.model, turns, temperature),
    )

    override fun onData(payload: String): AiStreamEvent? {
        val obj = parseObject(payload) ?: return null // 容忍不可解析载荷
        (obj["error"] as? JsonObject)?.let { throw classifyStreamError(it) }
        return when (obj["type"].stringContentOrNull()) {
            TYPE_CONTENT_BLOCK_DELTA -> {
                val delta = obj["delta"] as? JsonObject ?: return null
                when (delta["type"].stringContentOrNull()) {
                    TYPE_TEXT_DELTA -> {
                        val text = delta["text"].stringContentOrNull().orEmpty()
                        if (text.isEmpty()) null else AiStreamEvent.Delta(text)
                    }
                    TYPE_THINKING_DELTA -> {
                        val thinking = delta["thinking"].stringContentOrNull().orEmpty()
                        if (thinking.isEmpty()) null else AiStreamEvent.Reasoning(thinking)
                    }
                    else -> null
                }
            }
            TYPE_MESSAGE_STOP -> AiStreamEvent.Done
            else -> null
        }
    }

    private fun encodeRequest(model: String, turns: List<AiChatTurn>, temperature: Double?): String {
        val system = turns.filter { it.role == ROLE_SYSTEM }
            .joinToString(separator = SYSTEM_JOIN_SEPARATOR) { it.content.trim() }
            .trim()
        val messages = sanitizeMessages(turns.filterNot { it.role == ROLE_SYSTEM })
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", DEFAULT_MAX_TOKENS)
            put("stream", true)
            temperature?.let { put("temperature", it) }
            if (system.isNotEmpty()) put("system", system)
            put("messages", JsonArray(messages.map { turn ->
                buildJsonObject {
                    put("role", turn.role)
                    put("content", turn.content)
                }
            }))
        }
        return requestJson.encodeToString(payload)
    }

    /**
     * Anthropic 要求 messages 以 user 开头且严格交替：丢弃开头 assistant，
     * 连续同角色合并（content 以空行连接）。前置条件：剩余序列非空（调用方
     * 每次请求都以一条 user turn 收尾）。
     */
    private fun sanitizeMessages(turns: List<AiChatTurn>): List<AiChatTurn> {
        val firstUser = turns.indexOfFirst { it.role != ROLE_ASSISTANT }
        if (firstUser < 0) return emptyList()
        val merged = mutableListOf<AiChatTurn>()
        for (turn in turns.drop(firstUser)) {
            val last = merged.lastOrNull()
            if (last != null && last.role == turn.role) {
                merged[merged.size - 1] = last.copy(content = "${last.content}$SYSTEM_JOIN_SEPARATOR${turn.content}")
            } else {
                merged.add(turn)
            }
        }
        return merged
    }

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
    private const val ROLE_ASSISTANT = "assistant"
    private const val SYSTEM_JOIN_SEPARATOR = "\n\n"
    private const val TYPE_CONTENT_BLOCK_DELTA = "content_block_delta"
    private const val TYPE_TEXT_DELTA = "text_delta"
    private const val TYPE_THINKING_DELTA = "thinking_delta"
    private const val TYPE_MESSAGE_STOP = "message_stop"
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
