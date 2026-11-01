package com.fangyi.classpp.data.ai

import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * 读取是阻塞 I/O（读侧 [AiClient] 已整体搬到专用执行线程）；协程取消时
 * [AiClient] 调 [cancel] 中断底层连接，阻塞中的 [readLine] 立刻抛 [IOException]
 * 或返回 null——无论哪种形态，取消语义都由此收口。**实现契约：cancel() 必须
 * 能解除 readLine 的阻塞**（OkHttp 实现经 call.cancel() 断 socket，Fake 实现须放行）。
 * 读取循环另需观察 [AiClient] 的每次调用取消标志：`open()` 阻塞窗口内没有句柄可取消，
 * 取消只在放行后由该标志收口，且取消后不得再交付任何 `onDelta`。
 * 与 XmuHttpTransport 同一套「接口 + Fake 注入」单测惯例。
 */
interface AiStreamResponse : AutoCloseable {
    val status: Int

    /** 读取响应体一行（不含行尾符）；返回 null = 流结束。阻塞读 */
    fun readLine(): String?

    /** 读取剩余全部文本（非 2xx 时收集错误体；错误体小，一次读完即可） */
    fun readRemaining(): String

    /** 中断底层连接（流读取中途取消用）；关闭后再调无害 */
    fun cancel()

    /** 关闭并释放连接（正常读完路径；与 [cancel] 独立，先取消后关闭无害） */
    override fun close()
}

/** 传输层抽象：生产用 [OkHttpAiTransport]，JVM 单测注入脚本化假流（同 XmuHttpTransport 惯例） */
interface AiHttpTransport {
    /** 打开流式响应（阻塞到响应头就绪）；体读取按行拉取，取消由 [AiStreamResponse.cancel] 驱动 */
    fun open(request: AiHttpRequest): AiStreamResponse
}

/**
 * OkHttp 传输实现（SSE 流式）。
 *
 * - **不设整次调用上限**：LLM 长回答可持续数分钟，`callTimeout` 关闭；连接 10s /
 *   读 60s（readTimeout 是单次 read 的超时，流式期间按 chunk 到达自然续期）；
 * - **取消契约**：本类不做任何协程挂钩——取消由 [AiClient] 的 invokeOnCancellation
 *   统一驱动 [AiStreamResponse.cancel]（call.cancel() 断 socket，阻塞读立刻抛
 *   IOException）。**绝不在 open 返回时动连接**：响应体此刻才开始消费，任何
 *   「完成即取消」的钩子都会把流掐死在首帧（这是修掉过的真实缺陷）；
 * - 超时/IO 异常原样抛出，由 [AiClient] 分类为 [AiError.Network]；
 * - **日志零请求头**：本类不打印任何东西，Authorization 只进请求，不进任何日志。
 */
class OkHttpAiTransport : AiHttpTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    override fun open(request: AiHttpRequest): AiStreamResponse {
        val body = request.jsonBody.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(request.url)
            .post(body)
        for ((name, value) in request.headers) builder.header(name, value)

        val call = client.newCall(builder.build())
        return OkHttpAiStreamResponse(call, call.execute())
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
 *   onDelta 回调交给调用方（UI 逐字渲染）。整个流（打开 + 阻塞读）跑在专用
 *   执行线程上，onDelta 也在该线程触发——调用方须用线程安全的方式发布状态
 *   （如 StateFlow.value 赋值），不要在回调里直接碰 UI；
 * - **取消即断流**：整个阻塞管线包在 [suspendCancellableCoroutine] 里，协程取消时
 *   invokeOnCancellation 先置本次调用的取消标志，再调 [AiStreamResponse.cancel]
 *   中断底层连接，阻塞中的读立刻解除——停止按钮即时生效，不依赖下一块数据到达；
 *   `open()` 阻塞期间（DNS/连接/TLS/等响应头）还没有句柄可取消，线程中断对阻塞
 *   socket 读也无效，此时由取消标志在 `open()` 放行后立即收口：不读一行、不再回调；
 * - 容错：`:` 开头的 SSE 注释/心跳行、解析不了的 data 载荷一律跳过不致命；
 *   服务商把错误放进流里（`data: {"error": ...}`）时按其 code 分类抛 [AiException]；
 * - EOF 无 `[DONE]`：已收到过增量 = 流被掐断（[AiError.Network]，调用方保留半截），
 *   一个字都没有 = 响应不合法（[AiError.BadResponse]）。
 *
 * @param transport 传输层，默认 OkHttp 生产实现；单测注入脚本化假流
 */
class AiClient(private val transport: AiHttpTransport = OkHttpAiTransport()) {

    /**
     * 发起一次流式对话。
     *
     * @param onDelta 每收到一段增量文本回调一次（在 AI 执行线程，须轻量）；协程取消后
     *   保证不再回调（读取循环与交付前各检查一次本次调用的取消标志）
     * @return 完整回复文本（全部增量拼接）
     * @throws AiException 分类错误（UI 按 [AiError] 展示）；协程取消时在挂起点
     *   原样传播 CancellationException（半截文本已通过 onDelta 交给调用方，不随异常丢失）
     */
    suspend fun streamChat(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double? = null,
        onDelta: (String) -> Unit = {},
    ): String = suspendCancellableCoroutine { cont ->
        // 每次调用一份取消标志：open() 阻塞期间 inFlight 还是 null，取消只能靠它在
        // 放行后立即断流（OkHttp 的阻塞 socket 读不响应线程中断）
        val cancelled = AtomicBoolean(false)
        // 在途响应句柄：取消钩子用它断开底层连接（在执行线程里 open 后写入）
        val inFlight = AtomicReference<AiStreamResponse?>(null)
        val future = EXECUTOR.submit {
            try {
                val request = buildRequest(connection, apiKey, turns, temperature)
                val text = executeAndRead(request, cancelled, inFlight, onDelta)
                cont.resume(text, onCancellation = null)
            } catch (_: StreamCancelled) {
                // 协程已取消：没有等待者，无需 resume；use 已释放连接
            } catch (e: Throwable) {
                when (e) {
                    is AiException -> cont.resumeWith(Result.failure(e))
                    is IOException -> cont.resumeWith(
                        Result.failure(AiException(AiError.Network, detail = e.message)),
                    )
                    else -> cont.resumeWith(
                        Result.failure(AiException(AiError.BadResponse, detail = e.message)),
                    )
                }
            }
        }
        cont.invokeOnCancellation {
            cancelled.set(true)
            inFlight.get()?.cancel()
            future.cancel(true) // 仅对尚未开始的任务有效；已开始的任务靠取消标志退出
        }
    }

    // ---------- 阻塞管线（专用执行线程上运行） ----------

    private fun executeAndRead(
        request: AiHttpRequest,
        cancelled: AtomicBoolean,
        inFlight: AtomicReference<AiStreamResponse?>,
        onDelta: (String) -> Unit,
    ): String {
        val response = transport.open(request)
        inFlight.set(response)
        return response.use {
            if (cancelled.get()) {
                // open 期间被取消：立即断流，一行都不读（cancel 解除阻塞读）
                response.cancel()
                throw StreamCancelled
            }
            if (response.status !in 200..299) {
                // 错误体一次性读完再关；detail 截短供排查，不含敏感请求头
                val body = response.readRemaining()
                throw classifyStatus(response.status, body)
            }
            readEventStream(response, cancelled, onDelta)
        }
    }

    // ---------- SSE 解析 ----------

    private fun readEventStream(
        response: AiStreamResponse,
        cancelled: AtomicBoolean,
        onDelta: (String) -> Unit,
    ): String {
        val full = StringBuilder()
        var sawDone = false
        while (true) {
            if (cancelled.get()) throw StreamCancelled
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
                if (cancelled.get()) throw StreamCancelled // 取消后不再交付副作用
                full.append(delta)
                onDelta(delta)
            }
        }
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

    private fun buildRequest(
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

    /**
     * 内部提前退出信号：协程已取消，阻塞管线不再读取/回调。不是值得 resume 的错误
     * （取消侧已把 continuation 恢复为 CancellationException），任务侧静默收尾即可。
     */
    private object StreamCancelled : Exception()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true } // 流内载荷宽容读取；结构错误由逐字段判空兜住
        val requestJson = Json { encodeDefaults = false }
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val DATA_PREFIX = "data:"
        const val DONE_MARKER = "[DONE]"
        const val DETAIL_MAX = 300

        /**
         * 流式阻塞管线的专用线程池（daemon，进程级共享）：执行线程独立于协程调度器，
         * 取消经 invokeOnCancellation 驱动 response.cancel() 断流，而非依赖线程中断
         * （socket 读对中断无响应）。竞态说明：任务完成与取消同时发生时，取消侧已
         * 把 continuation 恢复为 CancellationException，任务侧迟到的 resume/
         * resumeWithException 会被 CancellableContinuation 静默忽略，无需加锁。
         */
        val EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "classpp-ai-stream").apply { isDaemon = true }
        }
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
