package com.fangyi.classpp.data.ai

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSE 流式客户端：脚本化假流覆盖 正常流/错误码/流内错误/EOF 无 DONE/IO 中断/容错跳过
 * （与 XmuNativeTodoApiTest 同一套 Fake 传输注入惯例）。
 */
class AiClientTest {

    private fun connection(
        baseUrl: String = "https://api.example.com/v1",
        model: String = "test-model",
    ) = AiConnection(providerId = "custom", baseUrl = baseUrl, model = model)

    private val turns = listOf(AiChatTurn("user", "帮我看看下周安排"))

    private fun sseData(content: String) =
        """data: {"choices":[{"delta":{"content":"$content"}}]}"""

    // ---------- 脚本化假流 ----------

    private class FakeAiStreamResponse(
        override val status: Int,
        private val lines: List<String?>,
        private val failAfter: Int = -1,
    ) : AiStreamResponse {
        var cancelled = false
            private set
        var closed = false
            private set
        private var reads = 0
        private var index = 0

        override fun readLine(): String? {
            if (failAfter in 0..reads) throw IOException("connection reset")
            reads++
            return lines.getOrNull(index++)
        }

        override fun readRemaining(): String = ""
        override fun cancel() {
            cancelled = true
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeAiTransport(private val response: AiStreamResponse) : AiHttpTransport {
        var lastRequest: AiHttpRequest? = null
            private set

        override fun open(request: AiHttpRequest): AiStreamResponse {
            lastRequest = request
            return response
        }
    }

    private fun client(vararg lines: String?): AiClient =
        AiClient(FakeAiTransport(FakeAiStreamResponse(200, lines.toList())))

    private fun clientWithStatus(status: Int) =
        AiClient(FakeAiTransport(FakeAiStreamResponse(status, listOf<String?>(null))))

    /** 记录读取次数与 cancel 信号；用于断言取消后一行都没读 */
    private class RecordingResponse(private val lines: List<String?>) : AiStreamResponse {
        override val status: Int = 200
        val reads = AtomicInteger(0)
        val cancelCalled = CountDownLatch(1)
        private var index = 0

        override fun readLine(): String? {
            reads.incrementAndGet()
            return lines.getOrNull(index++)
        }

        override fun readRemaining(): String = ""

        override fun cancel() {
            cancelCalled.countDown()
        }

        override fun close() = Unit
    }

    /**
     * 首次 open 阻塞（模拟 DNS/连接/TLS/等响应头）。真实 OkHttp 的阻塞 socket 读
     * 不响应线程中断，因此这里必须吞掉 InterruptedException——否则
     * `future.cancel(true)` 会让测试短路，测不到「open 放行后由取消标志断流」。
     */
    private class BlockingFirstOpenTransport(
        private val first: AiStreamResponse,
        private val second: AiStreamResponse,
    ) : AiHttpTransport {
        val firstOpenEntered = CountDownLatch(1)
        val releaseFirstOpen = CountDownLatch(1)
        private val opens = AtomicInteger(0)

        override fun open(request: AiHttpRequest): AiStreamResponse {
            if (opens.incrementAndGet() == 1) {
                firstOpenEntered.countDown()
                while (true) {
                    try {
                        releaseFirstOpen.await()
                        break
                    } catch (_: InterruptedException) {
                        // 与真实 OkHttp 一致：阻塞在 socket I/O 时不因中断返回
                    }
                }
                return first
            }
            return second
        }
    }

    // ---------- 请求形态 ----------

    @Test
    fun `request targets chat completions with bearer and stream body`() = runBlocking {
        val transport = FakeAiTransport(FakeAiStreamResponse(200, listOf("data: [DONE]", null)))
        AiClient(transport).streamChat(
            connection(baseUrl = "https://api.example.com/v1/"),
            apiKey = "sk-secret",
            turns = turns,
        )

        val request = transport.lastRequest!!
        assertEquals("https://api.example.com/v1/chat/completions", request.url)
        assertEquals("Bearer sk-secret", request.headers["Authorization"])

        val body = Json.parseToJsonElement(request.jsonBody).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    // ---------- 正常流 ----------

    @Test
    fun `deltas accumulate and return full text`() = runBlocking {
        val deltas = mutableListOf<String>()
        val result = client(
            ": keep-alive",
            "",
            sseData("下"),
            sseData("周三"),
            "event: ping",
            """data: {"choices":[{"delta":{}}]}""", // 无 content 增量：忽略
            """data: {"choices":[{"delta":{"content":null}}]}""", // content null：忽略
            "data: not-json", // 不可解析载荷：跳过
            sseData("有数学课"),
            "data: [DONE]",
            null,
        ).streamChat(connection(), "sk", turns, onDelta = { deltas += it })

        assertEquals("下周三有数学课", result)
        assertEquals(listOf("下", "周三", "有数学课"), deltas)
    }

    @Test
    fun `reasoning deltas are delivered separately and excluded from returned text`() = runBlocking {
        val deltas = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        val result = client(
            // DeepSeek 等用 reasoning_content，部分兼容网关用 reasoning：两种字段都要认
            """data: {"choices":[{"delta":{"reasoning_content":"先想"}}]}""",
            """data: {"choices":[{"delta":{"reasoning":"再答"}}]}""",
            sseData("答案"),
            "data: [DONE]",
            null,
        ).streamChat(
            connection(),
            "sk",
            turns,
            onDelta = { deltas += it },
            onReasoning = { reasoning += it },
        )

        assertEquals("答案", result)
        assertEquals(listOf("先想", "再答"), reasoning)
        assertEquals(listOf("答案"), deltas)
    }

    // ---------- HTTP 错误分类 ----------

    @Test
    fun `http status maps to error classes`() {
        val cases = mapOf(
            401 to AiError.Auth,
            402 to AiError.Quota,
            403 to AiError.Quota,
            404 to AiError.NotFound,
            429 to AiError.RateLimited,
            500 to AiError.Server,
            418 to AiError.BadResponse,
        )
        for ((status, expected) in cases) {
            val exception = runCatching {
                runBlocking { clientWithStatus(status).streamChat(connection(), "sk", turns) }
            }.exceptionOrNull()
            assertTrue("status $status should throw", exception is AiException)
            assertEquals("status $status", expected, (exception as AiException).error)
        }
    }

    @Test
    fun `error body is truncated into detail`() = runBlocking {
        val longBody = "x".repeat(1000)
        val transport = FakeAiTransport(
            object : AiStreamResponse {
                override val status = 401
                override fun readLine(): String? = null
                override fun readRemaining(): String = longBody
                override fun cancel() = Unit
                override fun close() = Unit
            },
        )
        val exception = runCatching {
            AiClient(transport).streamChat(connection(), "sk", turns)
        }.exceptionOrNull() as AiException

        assertEquals(AiError.Auth, exception.error)
        assertTrue(exception.detail!!.startsWith("HTTP 401: "))
        assertTrue(exception.detail!!.length <= "HTTP 401: ".length + 300)
    }

    // ---------- 流内错误对象 ----------

    @Test
    fun `in-stream error object with code classifies`() {
        val exception = runCatching {
            runBlocking {
                client(
                    """data: {"error":{"code":429,"message":"rate limited"}}""",
                    null,
                ).streamChat(connection(), "sk", turns)
            }
        }.exceptionOrNull() as AiException
        assertEquals(AiError.RateLimited, exception.error)
        assertEquals("rate limited", exception.detail)
    }

    @Test
    fun `in-stream error object without code is bad response`() {
        val exception = runCatching {
            runBlocking {
                client("""data: {"error":{"message":"weird failure"}}""", null)
                    .streamChat(connection(), "sk", turns)
            }
        }.exceptionOrNull() as AiException
        assertEquals(AiError.BadResponse, exception.error)
    }

    // ---------- EOF / IO 中断 ----------

    @Test
    fun `eof without done with content is network and deltas survive`() = runBlocking {
        val deltas = mutableListOf<String>()
        val exception = runCatching {
            client(sseData("半截"), null)
                .streamChat(connection(), "sk", turns, onDelta = { deltas += it })
        }.exceptionOrNull() as AiException

        assertEquals(AiError.Network, exception.error)
        assertEquals(listOf("半截"), deltas)
    }

    @Test
    fun `eof without done and without content is bad response`() {
        val exception = runCatching {
            runBlocking { client(null).streamChat(connection(), "sk", turns) }
        }.exceptionOrNull() as AiException
        assertEquals(AiError.BadResponse, exception.error)
    }

    @Test
    fun `eof without done but with only reasoning is network`() {
        val exception = runCatching {
            runBlocking {
                client(
                    """data: {"choices":[{"delta":{"reasoning_content":"想了一半"}}]}""",
                    null,
                ).streamChat(connection(), "sk", turns)
            }
        }.exceptionOrNull() as AiException
        // 只收到思考也是「已有半截内容」：网络中断而非空响应
        assertEquals(AiError.Network, exception.error)
    }

    @Test
    fun `io exception mid stream is network error`() {
        val exception = runCatching {
            runBlocking {
                val transport = FakeAiTransport(
                    FakeAiStreamResponse(200, listOf(sseData("前半"), null), failAfter = 1),
                )
                AiClient(transport).streamChat(connection(), "sk", turns)
            }
        }.exceptionOrNull() as AiException
        assertEquals(AiError.Network, exception.error)
    }

    // ---------- open 阻塞窗口内的取消（1.2 回归） ----------

    @Test
    fun `cancel during blocked open delivers no deltas and does not leak into next stream`() = runBlocking {
        val blocked = RecordingResponse(listOf(sseData("旧"), null))
        val next = RecordingResponse(listOf(sseData("新"), "data: [DONE]", null))
        val transport = BlockingFirstOpenTransport(blocked, next)
        val client = AiClient(transport)
        val oldDeltas = mutableListOf<String>()

        val job = launch {
            try {
                client.streamChat(connection(), "sk", turns, onDelta = { oldDeltas += it })
            } catch (_: CancellationException) {
                // 预期：取消在挂起点传播
            }
        }
        // 挂起式轮询等 open 进入阻塞（不占住 runBlocking 的事件循环线程）
        withTimeout(5000) {
            while (transport.firstOpenEntered.count > 0L) delay(10)
        }
        job.cancel()
        job.join()

        // 放行 open：执行线程应立刻断流——不读一行、零回调、响应被取消
        transport.releaseFirstOpen.countDown()
        withTimeout(5000) {
            while (blocked.cancelCalled.count > 0L) delay(10)
        }
        assertEquals(0, blocked.reads.get())
        assertTrue(oldDeltas.isEmpty())

        // 后续新的流只收到自己的增量，不与被取消的孤儿流串台
        val newDeltas = mutableListOf<String>()
        val text = client.streamChat(connection(), "sk", turns, onDelta = { newDeltas += it })
        assertEquals("新", text)
        assertEquals(listOf("新"), newDeltas)
    }
}
