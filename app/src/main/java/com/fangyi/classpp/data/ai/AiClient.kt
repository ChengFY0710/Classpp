package com.fangyi.classpp.data.ai

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** 一条 AI HTTP 请求：OpenAI 兼容 POST（请求头由调用方显式给，含 Authorization） */
data class AiHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val jsonBody: String,
)

/**
 * 打开的 SSE 流式响应：HTTP 状态 + 按行读取 + 取消句柄。
 *
 * 读取是阻塞 I/O（读侧 [AiClient] 已整体搬 Dispatchers.IO）；协程取消时读侧的
 * withContext Job 触发 [cancel] 中断底层连接，阻塞中的 [readLine] 立刻抛
 * [IOException]，取消语义由此收口——与 XmuHttpTransport 同一套「接口 + Fake
 * 注入」单测惯例。
 */
interface AiStreamResponse : AutoCloseable {
    val status: Int

    /** 读取响应体一行（不含行尾符）；返回 null = 流结束。阻塞读 */
    fun readLine(): String?

    /** 读取剩余全部文本（非 2xx 时收集错误体；错误体小，一次读完即可） */
    fun readRemaining(): String

    /** 中断底层连接（流读取中途取消用）；关闭后再调无害 */
    fun cancel()
}

/** 传输层抽象：生产用 [OkHttpAiTransport]，JVM 单测注入脚本化假流（同 XmuHttpTransport 惯例） */
interface AiHttpTransport {
    suspend fun open(request: AiHttpRequest): AiStreamResponse
}

/**
 * OkHttp 传输实现（SSE 流式）。
 *
 * - **不设整次调用上限**：LLM 长回答可持续数分钟，`callTimeout` 关闭；连接 10s /
 *   读 60s（readTimeout 是单次 read 的超时，流式期间按 chunk 到达自然续期）；
 * - **取消传播**：挂起体注册 `invokeOnCompletion { call.cancel() }`——协程取消时
 *   中断 socket，阻塞中的 readLine 立刻抛 IOException；调用正常完成后 cancel()
 *   对已消费的 call 是无害 no-op；
 * - 超时/IO 异常原样抛出，由 [AiClient] 分类为 [AiError.Network]；
 * - **日志零请求头**：本类不打印任何东西，Authorization 只进请求，不进任何日志。
 */
class OkHttpAiTransport : AiHttpTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    override suspend fun open(request: AiHttpRequest): AiStreamResponse =
        withContext(Dispatchers.IO) {
            val body = request.jsonBody.toRequestBody(JSON_MEDIA_TYPE)
            val builder = Request.Builder()
                .url(request.url)
                .post(body)
            for ((name, value) in request.headers) builder.header(name, value)

            val call = client.newCall(builder.build())
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            OkHttpAiStreamResponse(call, call.execute())
        }

    private class OkHttpAiStreamResponse(
        private val call: Call,
        response: Response,
    ) : AiStreamResponse {
        private val reader = response.body.byteStream().bufferedReader(Charsets.UTF_8)

        override val status: Int = response.code

        override fun readLine(): String? = reader.readLine()

        override fun readRemaining(): String = reader.readText()

        override fun cancel() = call.cancel()

        override fun close() {
            reader.close()
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 60L
    }
}

/**
 * OpenAI 兼容 `/chat/completions` 的流式客户端（OpenAI / DeepSeek / 智谱 / 通义 /
 * Kimi / Ollama 同协议）。
 *
 * - 请求：`stream: true`；SSE 逐行解析 `data:` 载荷，`[DONE]` 终止，增量经
 *   onDelta 回调交给调用方（UI 逐字渲染）。整个流（打开 + 阻塞读）跑在
 *   Dispatchers.IO，onDelta 也在 IO 线程触发——调用方须用线程安全的方式发布状态
 *   （如 StateFlow.value 赋值），不要在回调里直接碰 UI；
 * - 容错：`:` 开头的 SSE 注释/心跳行、解析不了的 data 载荷一律跳过不致命；
 *   服务商把错误放进流里（`data: {"error": ...}`）时按其 code 分类抛 [AiException]；
 * - EOF 无 `[DONE]`：已收到过增量 = 流被掐断（[AiError.Network]，调用方保留半截），
 *   一个字都没有 = 响应不合法（[AiError.BadResponse]）；
 * - 阻塞读循环每行先 ensureActive，配合传输层的取消传播，停止即收口。
 *
 * @param transport 传输层，默认 OkHttp 生产实现；单测注入脚本化假流
 */
class AiClient(private val transport: AiHttpTransport = OkHttpAiTransport()) {

    /**
     * 发起一次流式对话。
     *
     * @param onDelta 每收到一段增量文本回调一次（在 IO 线程，须轻量）
     * @return 完整回复文本（全部增量拼接）
     * @throws AiException 分类错误（UI 按 [AiError] 展示）；协程取消时原样传播
     *   CancellationException（半截文本已通过 onDelta 交给调用方，不随异常丢失）
     */
    suspend fun streamChat(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double? = null,
        onDelta: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        val request = AiHttpRequest(
            url = connection.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Accept" to "text/event-stream",
            ),
            jsonBody = encodeRequest(connection.model, turns, temperature),
        )

        try {
            transport.open(request).use { response ->
                if (response.status !in 200..299) {
                    // 错误体一次性读完再关；detail 截短供排查，不含敏感请求头
                    val body = response.readRemaining()
                    throw classifyStatus(response.status, body)
                }
                readEventStream(response, onDelta)
            }
        } catch (e: IOException) {
            // 连接失败/流中途掐断都归 Network；先让取消语义胜出（取消会以
            // IOException 形态从阻塞读里冒出来，不能误报成网络错误）
            coroutineContext.ensureActive()
            throw AiException(AiError.Network, detail = e.message)
        }
    }

    // ---------- SSE 解析 ----------

    private suspend fun readEventStream(response: AiStreamResponse, onDelta: (String) -> Unit): String {
        val full = StringBuilder()
        var sawDone = false
        while (true) {
            coroutineContext.ensureActive()
            val line = response.readLine() ?: break
            if (line.isBlank() || line.startsWith(":")) continue // 空行与 SSE 注释/心跳
            if (!line.startsWith(DATA_PREFIX)) continue // event: 等无关字段
            val payload = line.removePrefix(DATA_PREFIX).trim()
            if (payload == DONE_MARKER) {
                sawDone = true
                break
            }
            val delta = parseDelta(payload) ?: continue // 容忍不可解析载荷
            if (delta.isNotEmpty()) {
                full.append(delta)
                onDelta(delta)
            }
        }
        coroutineContext.ensureActive() // EOF/取消竞态：先让取消语义胜出
        if (!sawDone) {
            throw if (full.isEmpty()) {
                AiException(AiError.BadResponse, detail = "stream ended without [DONE], no content")
            } else {
                AiException(AiError.Network, detail = "stream ended without [DONE], partial content kept")
            }
        }
        return full.toString()
    }

    /**
     * 解析一条 data 载荷的增量文本；返回 null = 本行不可解析（跳过）。
     * 服务商流内错误对象（`{"error": {...}}`）转成分类异常抛出。
     */
    private fun parseDelta(payload: String): String? {
        val obj = try {
            JSON.parseToJsonElement(payload).let { it as? JsonObject } ?: return null
        } catch (_: Exception) {
            return null
        }
        (obj["error"] as? JsonObject)?.let { throw classifyStreamError(it) }
        val delta = (obj["choices"] as? JsonArray)
            ?.firstOrNull()
            ?.let { it as? JsonObject }
            ?.get("delta") as? JsonObject
            ?: return null
        return delta["content"].stringContentOrNull().orEmpty()
    }

    // ---------- 错误分类 ----------

    private fun classifyStatus(status: Int, body: String): AiException {
        val error = when (status) {
            401 -> AiError.Auth
            402, 403 -> AiError.Quota // 余额不足 / 无模型权限（各家 403 语义归并此处）
            404 -> AiError.NotFound
            429 -> AiError.RateLimited
            in 500..599 -> AiError.Server
            else -> AiError.BadResponse
        }
        return AiException(error, detail = "HTTP $status: ${body.take(DETAIL_MAX)}")
    }

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

    // ---------- 请求编码 ----------

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

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true } // 流内载荷宽容读取；结构错误由逐字段判空兜住
        val requestJson = Json { encodeDefaults = false }
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val DETAIL_MAX = 300
    }
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
