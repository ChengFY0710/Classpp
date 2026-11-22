package com.fangyi.classpp.data.ai

import android.util.Log
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

    /**
     * @param sessionId 会话标识：预设声明了会话路由头（[AiProviderPreset.sessionHeader]）
     *   时随请求下发（OpenCode Go 缺它直接 400）；对话场景传对话 id，一次性任务可省略，
     *   由编解码器生成稳定回退值
     */
    fun buildRequest(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec> = emptyList(),
        sessionId: String? = null,
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
 * - 请求：Bearer 认证；体为 `{model, stream, temperature?, reasoning_effort?,
 *   thinking? / enable_thinking?, messages, tools?}`（`reasoning_effort` 来自当前模型的
 *   推理等级配置，按控制形态原样透传或省略；思考开关按服务商 + 模型族下发：
 *   DeepSeek 用 `thinking.type`，MiMo / GLM 开关族 / Kimi K2.6 用 `thinking.type`
 *   且不发 effort，千问 / 百炼用 `enable_thinking`，Kimi K3 / GLM-5.3 只发 effort
 *   且没有「关」档；未声明控制形态的服务商不发开关，避免不认识该字段的网关 400）；
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
        sessionId: String?,
    ): AiHttpRequest {
        val config = connection.modelConfig(connection.model)
        val thinkingControl = AiProviderPresets.thinkingControlFor(
            connection.providerId,
            connection.model,
        )
        // 始终思考的模型族没有「关」档：首档是最低强度，必须照常发送
        val reasoningOff = thinkingControl.supportsOff && config.isReasoningOff()
        return AiHttpRequest(
            url = connection.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH,
            headers = buildMap {
                // 本地 Ollama 等无 Key 服务商不发认证头：空 Bearer 没有意义，
                // 部分自建网关还会把空值当成非法凭据
                if (apiKey.isNotBlank()) put("Authorization", "Bearer $apiKey")
                put("Accept", "text/event-stream")
                putAll(sessionHeaders(connection, sessionId))
            },
            jsonBody = encodeRequest(
                model = connection.model,
                reasoningLevel = if (reasoningOff) {
                    null
                } else {
                    config.reasoningLevel.trim().takeIf { it.isNotEmpty() }
                },
                reasoningOff = reasoningOff,
                thinkingControl = thinkingControl,
                turns = turns,
                // o1/o3 等推理模型与部分网关不接受 temperature：配置关闭时不发送
                temperature = temperature.takeIf { config.supportsTemperature },
                tools = tools,
            ),
        )
    }

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
                // 部分兼容网关后续分片会带 "id": "" / "name": ""：按缺失处理，否则
                // 累积器会把首片的真实值覆盖成空串（真机 MiMo 上表现为 unknown_tool）
                val id = call["id"].stringContentOrNull()?.takeIf { it.isNotBlank() }
                val name = function?.get("name").stringContentOrNull()?.takeIf { it.isNotBlank() }
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
        reasoningLevel: String?,
        reasoningOff: Boolean,
        thinkingControl: AiThinkingControl,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec>,
    ): String {
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            temperature?.let { put("temperature", it) }
            // 推理等级按控制形态发送：纯开关型档位（MiMo / GLM 开关族 / Kimi K2.6 /
            // 千问 hybrid）不把档位字符串当 reasoning_effort，避免服务商 400；effort 型
            // 也只发送白名单内的标准档位（自定义文案透传同样会 400）
            if (thinkingControl.sendsEffort) {
                reasoningLevel
                    ?.takeIf { AiReasoningProfiles.isKnownEffort(it) }
                    ?.let { put("reasoning_effort", it) }
            }
            when (thinkingControl) {
                AiThinkingControl.None,
                AiThinkingControl.ReasoningEffortOnly -> Unit
                AiThinkingControl.ThinkingType,
                AiThinkingControl.ThinkingTypeOnly -> when {
                    // 非关档位显式 enabled（DeepSeek 等默认开启思考，省略等于继续思考）
                    reasoningLevel != null -> put("thinking", thinkingElement(THINKING_TYPE_ENABLED))
                    // 「关」档位必须显式 disabled 才真正关闭
                    reasoningOff -> put("thinking", thinkingElement(THINKING_TYPE_DISABLED))
                }
                AiThinkingControl.EnableThinking,
                AiThinkingControl.EnableThinkingOnly -> when {
                    reasoningLevel != null -> put("enable_thinking", true)
                    reasoningOff -> put("enable_thinking", false)
                }
            }
            put("messages", JsonArray(encodeMessages(turns)))
            if (tools.isNotEmpty()) put("tools", AiToolRegistry.openAiTools(tools))
        }
        return requestJson.encodeToString(payload)
    }

    /** thinking 开关对象：`{"type": "enabled"|"disabled"}`（DeepSeek 的 OpenAI 兼容形态） */
    private fun thinkingElement(type: String): JsonObject = buildJsonObject { put("type", type) }

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

    /** 思考开关取值（`thinking.type`，DeepSeek 的 OpenAI 兼容形态） */
    private const val THINKING_TYPE_ENABLED = "enabled"
    private const val THINKING_TYPE_DISABLED = "disabled"
}

/**
 * Anthropic Messages 协议编解码：`POST {base}/v1/messages`。
 *
 * 服务地址按 Anthropic 官方与各家兼容网关的公布口径理解——**不含版本段**（官方
 * `https://api.anthropic.com`、阿里云 MaaS `https://{host}/apps/anthropic`），`/v1`
 * 属于端点路径。旧配置若已手动带上 `/v1`（如已下架预设的 `api.anthropic.com/v1`）
 * 则不重复拼接，两种写法兼容。
 *
 * - **认证**：`x-api-key` 头 + `anthropic-version` 头（不是 Bearer）；
 * - **system 是顶层字段**：role=system 的 turn 不进 messages，拼接为顶层 `system`；
 * - **max_tokens 必填**：模型配置了 `maxOutputTokens` 时以它为上限（思考预算夹到
 *   `max_tokens - 1024`，放不下最小预算时不启用 thinking）；未配置时保持默认
 *   [DEFAULT_MAX_TOKENS]，启用思考时取 `budget_tokens + [DEFAULT_MAX_TOKENS]`（答案
 *   余量与无思考时持平，且满足 Anthropic 对 max_tokens > budget_tokens 的硬性要求）；
 * - **thinking**：当前模型的推理等级经 [AnthropicStreamCodec.reasoningBudget] 映射为
 *   预算后发送 `{type: "enabled", budget_tokens}`；无法映射的等级不发送（无思考的原行为）。
 *   Anthropic 要求 thinking 启用时 temperature 只能为 1，启用思考即**不发送 temperature**
 *   （课表识别固定传 0.0，混在一起会 400）；
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
        sessionId: String?,
    ): AiHttpRequest = AiHttpRequest(
        url = messagesUrl(connection.baseUrl),
        headers = buildMap {
            put("x-api-key", apiKey)
            put("anthropic-version", API_VERSION)
            put("Accept", "text/event-stream")
            putAll(sessionHeaders(connection, sessionId))
        },
        jsonBody = encodeRequest(
            model = connection.model,
            reasoningLevel = connection.activeReasoningLevel(),
            maxOutputTokens = connection.modelConfig(connection.model).maxOutputTokens,
            turns = turns,
            temperature = temperature,
            tools = tools,
        ),
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
        reasoningLevel: String?,
        maxOutputTokens: Int,
        turns: List<AiChatTurn>,
        temperature: Double?,
        tools: List<AiToolSpec>,
    ): String {
        val system = turns.filter { it.role == ROLE_SYSTEM }
            .joinToString(separator = SYSTEM_JOIN_SEPARATOR) { it.content.trim() }
            .trim()
        val nonSystem = turns.filterNot { it.role == ROLE_SYSTEM }
        val hasToolTurns = nonSystem.any { it.toolCalls.isNotEmpty() || it.toolResults.isNotEmpty() }
        val requestedMax = maxOutputTokens.takeIf { it > 0 }
        val rawBudget = reasoningLevel?.let(::reasoningBudget)
        // max_tokens 与 thinking 预算的关系：
        // - 未配置 maxOutputTokens（旧配置）保持原行为：无思考 4096，有思考 budget+4096；
        // - 配置后以配置值为上限，思考预算夹到 max_tokens-1024（必须 max_tokens > budget），
        //   max_tokens 小到放不下最小预算时不启用 thinking。
        val maxTokens = if (requestedMax == null) {
            rawBudget?.plus(DEFAULT_MAX_TOKENS) ?: DEFAULT_MAX_TOKENS
        } else {
            requestedMax
        }
        val thinkingBudget = when {
            rawBudget == null -> null
            requestedMax == null -> rawBudget
            maxTokens > MIN_THINKING_BUDGET * 2 ->
                rawBudget.coerceIn(MIN_THINKING_BUDGET, maxTokens - MIN_THINKING_BUDGET)
            else -> null
        }
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("stream", true)
            // Anthropic 要求 thinking 启用时 temperature 只能为 1：启用思考即不发送
            //（课表识别固定传 0.0，混在一起会 400）
            if (thinkingBudget == null) temperature?.let { put("temperature", it) }
            if (thinkingBudget != null) {
                put(
                    "thinking",
                    buildJsonObject {
                        put("type", THINKING_TYPE_ENABLED)
                        put("budget_tokens", thinkingBudget)
                    },
                )
            }
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

    /**
     * Messages 端点：服务地址未带版本段时补 `/v1`（官方与兼容网关的公布口径）；
     * 已带 `/v1` 的旧配置只拼 `/messages`；直接粘贴完整端点的也原样使用。
     * 三种写法都兼容，避免出现 `/v1/v1/messages` 这类 404 路径。
     */
    private fun messagesUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return when {
            base.endsWith(MESSAGES_ENDPOINT_SUFFIX) -> base
            base.endsWith(VERSION_PATH) -> base + MESSAGES_PATH
            else -> base + MESSAGES_ENDPOINT_SUFFIX
        }
    }

    /**
     * 推理等级 → thinking 预算；null = 无法映射（不发 thinking，保持无思考的原行为）。
     * 命名等级忽略大小写按 [THINKING_BUDGET_BY_LEVEL] 预设映射；纯数字等级直接作为
     * 预算并夹到 [MIN_THINKING_BUDGET]..[MAX_NUMERIC_THINKING_BUDGET]（上下限都是
     * Anthropic 的硬性约束：过小 400，过大必然超过模型输出上限）；其余写法不猜——
     * 错发预算比不发更糟，仅 debug 记一条供排查。
     */
    private fun reasoningBudget(level: String): Int? {
        THINKING_BUDGET_BY_LEVEL[level.lowercase()]?.let { return it }
        val budget = level.toIntOrNull()
            ?.coerceIn(MIN_THINKING_BUDGET, MAX_NUMERIC_THINKING_BUDGET)
        if (budget == null && AiDebugTrace.enabled) {
            runCatching {
                Log.w(REASONING_LOG_TAG, "anthropic reasoning level not mapped, ignored: $level")
            }
        }
        return budget
    }

    private const val MESSAGES_PATH = "/messages"
    private const val VERSION_PATH = "/v1"
    private const val MESSAGES_ENDPOINT_SUFFIX = VERSION_PATH + MESSAGES_PATH
    private const val API_VERSION = "2023-06-01"

    /** 各在售 Claude 模型输出上限的公共下限（claude-3-haiku = 4096），避免旧模型 400 */
    private const val DEFAULT_MAX_TOKENS = 4096

    private const val THINKING_TYPE_ENABLED = "enabled"

    /** Anthropic 对 thinking.budget_tokens 的硬性下限 */
    private const val MIN_THINKING_BUDGET = 1024

    /** 纯数字推理等级的预算上限：再大必然超过常见模型输出上限（xhigh 预设为 32768） */
    private const val MAX_NUMERIC_THINKING_BUDGET = 32_000

    /** 推理等级 → thinking 预算的命名预设（忽略大小写；与「配置模型」浮层的快捷预设一致） */
    private val THINKING_BUDGET_BY_LEVEL = mapOf(
        "minimal" to 1024,
        "low" to 2048,
        "medium" to 8192,
        "high" to 16384,
        "xhigh" to 32768,
    )

    /** 无法映射的推理等级仅在 debug 记一条（与 AiClient 的 HTTP 排查日志同 tag） */
    private const val REASONING_LOG_TAG = "AiHttp"

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

/**
 * 会话路由头（[AiProviderPreset.sessionHeader]）：预设声明了头名时下发稳定会话标识。
 * 对话请求传入对话 id；一次性识别 / 修复请求没有对话上下文，按「端点 + 模型」生成
 * 稳定回退值——同一配置的多次请求保持一致，满足网关对“稳定会话”的要求。
 */
private fun sessionHeaders(connection: AiConnection, sessionId: String?): Map<String, String> {
    val header = AiProviderPresets.sessionHeaderFor(connection.providerId) ?: return emptyMap()
    val value = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        ?: "classpp-" + (connection.baseUrl.trim() + "|" + connection.model.trim())
            .hashCode().toUInt().toString(16)
    return mapOf(header to value)
}

/** 流内载荷宽容读取（同原 AiClient.JSON）；结构错误由逐字段判空兜住 */
private val payloadJson = Json { ignoreUnknownKeys = true }

/** 请求体编码（同原 AiClient.requestJson：不落默认值） */
private val requestJson = Json { encodeDefaults = false }

/**
 * 当前生效模型的推理等级（Anthropic 侧解析口）：配置随模型存在
 * [AiConnection.modelConfigs]，等级为空或「关」档位（[AiModelConfig.isReasoningOff]）
 * 均返回 null——Anthropic 的 thinking 默认关闭，不发送即关闭。
 * （OpenAI 兼容侧在编解码器内自行解析，以区分「关」与「未配置」并下发 thinking 开关。）
 */
private fun AiConnection.activeReasoningLevel(): String? {
    val config = modelConfig(model)
    if (config.isReasoningOff()) return null
    return config.reasoningLevel.trim().takeIf { it.isNotEmpty() }
}

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
