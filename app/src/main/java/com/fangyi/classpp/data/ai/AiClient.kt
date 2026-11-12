package com.fangyi.classpp.data.ai

import java.io.IOException
import java.util.TreeMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** 一条 AI HTTP 请求（形状由协议编解码器决定：端点、认证头、请求体；请求头显式给出，API Key 只进请求不进日志） */
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
 * 一次流式调用的完整结果：正文、思考、工具调用与结束原因。
 *
 * 工具调用按 `index` 合并后按序给出；参数原文可能不是合法 JSON（流被掐断/模型输出
 * 坏），这里不抛错，交给执行层转成 `invalid_arguments` 结果。
 */
data class AiStreamResult(
    val text: String,
    val reasoning: String,
    val toolCalls: List<AiToolCall> = emptyList(),
    val finishReason: String? = null,
)

/**
 * 流式客户端：请求编码与 SSE 载荷解析按 [AiConnection.protocol] 委托给协议编解码器
 * （[OpenAiStreamCodec] = OpenAI 兼容，[AnthropicStreamCodec] = Anthropic Messages），
 * 传输、取消与 HTTP 状态分类是协议无关的，在此收口。
 *
 * - SSE 逐行解析 `data:` 载荷交编解码器分派（增量形状与终止标记随协议），增量经
 *   onDelta 回调交给调用方（UI 逐字渲染）；推理模型的思考增量经 onReasoning 单独
 *   交付（不进正文返回值）；工具调用增量由每次调用新建的 [ToolCallAccumulator] 合并。
 *   整个流（打开 + 阻塞读）跑在专用执行线程上，回调也在该线程触发——调用方须用
 *   线程安全的方式发布状态（如 StateFlow.value 赋值），不要在回调里直接碰 UI；
 * - **取消即断流**：整个阻塞管线包在 [suspendCancellableCoroutine] 里，协程取消时
 *   invokeOnCancellation 先置本次调用的取消标志，再调 [AiStreamResponse.cancel]
 *   中断底层连接，阻塞中的读立刻解除——停止按钮即时生效，不依赖下一块数据到达；
 * - 容错：`:` 开头的 SSE 注释/心跳行、解析不了的 data 载荷一律跳过不致命；
 *   服务商把错误放进流里（`data: {...}` 携带错误对象）时由编解码器按协议分类抛 [AiException]；
 * - EOF 无终止标记（协议各自的 Done 形态未出现）：已收到过正文/思考/工具调用 = 流被掐断
 *   （[AiError.Network]，调用方保留半截），一个字都没有 = 响应不合法（[AiError.BadResponse]）。
 *
 * @param transport 传输层，默认 OkHttp 生产实现；单测注入脚本化假流
 */
class AiClient(private val transport: AiHttpTransport = OkHttpAiTransport()) {

    /**
     * 发起一次流式对话（不发送原生工具；JSON 回退路径与既有调用方使用）。
     *
     * @return 完整回复正文（全部正文增量拼接；思考与工具调用不计入）
     * @throws AiException 分类错误（UI 按 [AiError] 展示）；协程取消时在挂起点
     *   原样传播 CancellationException（半截文本已通过 onDelta 交给调用方，不随异常丢失）
     */
    suspend fun streamChat(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        temperature: Double? = null,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {},
    ): String = streamChatWithTools(
        connection = connection,
        apiKey = apiKey,
        turns = turns,
        tools = emptyList(),
        temperature = temperature,
        onDelta = onDelta,
        onReasoning = onReasoning,
    ).text

    /**
     * 发起一次带原生工具的流式对话。
     *
     * @param tools 本次可用的工具；空 = 请求体不发送 `tools` 字段（回退/普通对话）
     * @param onDelta 每收到一段正文增量文本回调一次（在 AI 执行线程，须轻量）；协程取消后
     *   保证不再回调（读取循环与交付前各检查一次本次调用的取消标志）
     * @param onReasoning 每收到一段思考增量回调一次（推理模型才有；同样在执行线程、
     *   取消后不再回调）。思考内容不参与返回值，由调用方单独累积与展示
     * @throws AiException 分类错误；协程取消时原样传播 CancellationException
     */
    suspend fun streamChatWithTools(
        connection: AiConnection,
        apiKey: String,
        turns: List<AiChatTurn>,
        tools: List<AiToolSpec>,
        temperature: Double? = null,
        onDelta: (String) -> Unit = {},
        onReasoning: (String) -> Unit = {},
    ): AiStreamResult = suspendCancellableCoroutine { cont ->
        // 每次调用一份取消标志：open() 阻塞期间 inFlight 还是 null，取消只能靠它在
        // 放行后立即断流（OkHttp 的阻塞 socket 读不响应线程中断）
        val cancelled = AtomicBoolean(false)
        // 在途响应句柄：取消钩子用它断开底层连接（在执行线程里 open 后写入）
        val inFlight = AtomicReference<AiStreamResponse?>(null)
        val future = EXECUTOR.submit {
            try {
                val codec = AiStreamCodec.of(connection.protocol)
                val request = codec.buildRequest(connection, apiKey, turns, temperature, tools)
                val result = executeAndRead(codec, request, cancelled, inFlight, onDelta, onReasoning)
                cont.resume(result, onCancellation = null)
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
        codec: AiStreamCodec,
        request: AiHttpRequest,
        cancelled: AtomicBoolean,
        inFlight: AtomicReference<AiStreamResponse?>,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): AiStreamResult {
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
            readEventStream(codec, response, cancelled, onDelta, onReasoning)
        }
    }

    // ---------- SSE 解析 ----------

    private fun readEventStream(
        codec: AiStreamCodec,
        response: AiStreamResponse,
        cancelled: AtomicBoolean,
        onDelta: (String) -> Unit,
        onReasoning: (String) -> Unit,
    ): AiStreamResult {
        val full = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = ToolCallAccumulator()
        var finishReason: String? = null
        var sawDone = false
        loop@ while (true) {
            if (cancelled.get()) throw StreamCancelled
            val line = response.readLine() ?: break
            if (line.isBlank() || line.startsWith(":")) continue // 空行与 SSE 注释/心跳
            if (!line.startsWith(DATA_PREFIX)) continue // event: 等无关字段
            for (event in codec.onData(line.removePrefix(DATA_PREFIX).trim())) {
                when (event) {
                    is AiStreamEvent.Delta -> {
                        if (cancelled.get()) throw StreamCancelled // 取消后不再交付副作用
                        full.append(event.text)
                        onDelta(event.text)
                    }
                    is AiStreamEvent.Reasoning -> {
                        if (cancelled.get()) throw StreamCancelled
                        reasoning.append(event.text)
                        onReasoning(event.text)
                    }
                    is AiStreamEvent.ToolCallDelta -> {
                        if (cancelled.get()) throw StreamCancelled
                        toolCalls.accept(event)
                    }
                    is AiStreamEvent.Finish -> finishReason = event.reason
                    AiStreamEvent.Done -> {
                        sawDone = true
                        break@loop
                    }
                }
            }
        }
        if (!sawDone) {
            // 只收到思考/工具调用也算「已有半截内容」：按网络中断保留，而不是当作空响应
            throw if (full.isEmpty() && reasoning.isEmpty() && toolCalls.isEmpty()) {
                AiException(AiError.BadResponse, detail = "stream ended without completion marker, no content")
            } else {
                AiException(AiError.Network, detail = "stream ended without completion marker, partial content kept")
            }
        }
        return AiStreamResult(
            text = full.toString(),
            reasoning = reasoning.toString(),
            toolCalls = toolCalls.build(),
            finishReason = finishReason,
        )
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

    /**
     * 内部提前退出信号：协程已取消，阻塞管线不再读取/回调。不是值得 resume 的错误
     * （取消侧已把 continuation 恢复为 CancellationException），任务侧静默收尾即可。
     */
    private object StreamCancelled : Exception()

    private companion object {
        const val DATA_PREFIX = "data:"
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

/**
 * 工具调用增量累积器（每次 [AiClient.streamChatWithTools] 新建，绝不放进 codec 单例）。
 *
 * - OpenAI：index 来自 `delta.tool_calls[].index`，id/name 通常只在首片出现，
 *   arguments 是字符串分片；
 * - Anthropic：index 来自 `content_block_start.index`，参数来自 `input_json_delta`；
 * - index 可能乱序/跳号，用 [TreeMap] 保证输出按 index 升序；
 * - 参数可能不是合法 JSON（流被掐断/模型输出坏），原样保留，由执行层转
 *   `invalid_arguments` 结果交给模型。
 */
private class ToolCallAccumulator {

    private val calls = TreeMap<Int, MutableCall>()

    fun accept(delta: AiStreamEvent.ToolCallDelta) {
        val call = calls.getOrPut(delta.index) { MutableCall() }
        delta.id?.let { call.id = it }
        delta.name?.let { call.name = it }
        call.arguments.append(delta.argumentsDelta)
    }

    fun isEmpty(): Boolean = calls.isEmpty()

    fun build(): List<AiToolCall> = calls.entries.map { (index, call) ->
        AiToolCall(
            id = call.id ?: "tool_call_$index",
            name = call.name.orEmpty(),
            argumentsJson = call.arguments.toString().ifBlank { "{}" },
        )
    }

    private class MutableCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )
}
