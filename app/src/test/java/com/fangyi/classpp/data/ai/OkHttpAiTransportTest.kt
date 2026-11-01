package com.fangyi.classpp.data.ai

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实 OkHttp 路径回归（本地 ServerSocket，纯 JVM，无 Android）：
 * Fake 传输测不到「open 返回时连接被误掐」这类真实传输层缺陷——曾因在 open 的
 * withContext 完成钩子里 call.cancel()，把刚收到响应头、还没读的流掐死，
 * 全部服务商都表现为「网络连接失败」。本测试锁死该回归。
 */
class OkHttpAiTransportTest {

    private fun sse(content: String) =
        "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\n"

    /** 起一个本地 HTTP 服务：脚本化响应（完整 SSE / 半截后挂起），返回端口给测试 */
    private fun withServer(script: (socketOutput: java.io.OutputStream) -> Unit, block: suspend (ServerSocket) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val serverThread = Thread {
            try {
                val socket = server.accept()
                socket.use {
                    val input = it.getInputStream().bufferedReader()
                    while (true) { // 读完请求头
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    script(it.getOutputStream())
                }
            } catch (_: Exception) {
                // 测试结束关 server 时 accept/write 失败是常态，静默
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
        try {
            runBlocking { block(server) }
        } finally {
            server.close()
        }
    }

    private fun writeResponseHeaders(out: java.io.OutputStream, declaredLength: Int) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Content-Length: $declaredLength\r\n" +
                "Connection: close\r\n\r\n").toByteArray(),
        )
        out.flush()
    }

    private fun connection(port: Int) = AiConnection(
        providerId = "custom",
        baseUrl = "http://127.0.0.1:$port",
        model = "test",
    )

    @Test
    fun `real socket streams sse end to end without premature cancel`() = runBlocking {
        val fullBody = sse("你") + sse("好") + "data: [DONE]\n\n"
        withServer(
            script = { out ->
                writeResponseHeaders(out, fullBody.toByteArray(Charsets.UTF_8).size)
                out.write(fullBody.toByteArray(Charsets.UTF_8))
                out.flush()
            },
        ) { server ->
            val client = AiClient(OkHttpAiTransport())
            val deltas = mutableListOf<String>()
            val text = client.streamChat(
                connection = connection(server.localPort),
                apiKey = "sk-test",
                turns = listOf(AiChatTurn("user", "hi")),
                onDelta = { deltas += it },
            )
            assertEquals("你好", text)
            assertEquals(listOf("你", "好"), deltas)
        }
    }

    @Test
    fun `cancellation aborts blocked read promptly`() = runBlocking {
        val firstChunk = sse("前半")
        val release = CountDownLatch(1)
        withServer(
            script = { out ->
                // 声明大 Content-Length，写完首块后挂起不再发送（模拟流中途卡住）
                writeResponseHeaders(out, 4096)
                out.write(firstChunk.toByteArray(Charsets.UTF_8))
                out.flush()
                release.await(10, TimeUnit.SECONDS)
            },
        ) { server ->
            val client = AiClient(OkHttpAiTransport())
            var sawFirstDelta = false
            val job: Job = launch {
                try {
                    client.streamChat(
                        connection = connection(server.localPort),
                        apiKey = "sk-test",
                        turns = listOf(AiChatTurn("user", "hi")),
                        onDelta = { sawFirstDelta = true },
                    )
                } catch (_: CancellationException) {
                    // 预期：取消在挂起点传播
                } catch (_: com.fangyi.classpp.data.ai.AiException) {
                    // 取消与断流竞态时也可能以 Network 形态到达（半截已保留）
                }
            }
            withTimeout(10_000) {
                // 挂起式轮询：runBlocking 的事件循环线程不能被 latch 阻塞，
                // 否则 launch 的协程没机会启动（delay 让出线程，事件循环照常泵任务）
                while (!sawFirstDelta) delay(20)
                val start = System.nanoTime()
                job.cancel()
                job.join()
                val elapsedMillis = (System.nanoTime() - start) / 1_000_000
                // 取消必须即时解除阻塞读（invokeOnCancellation 断流），不能等 readTimeout
                assertTrue("cancel took ${elapsedMillis}ms", elapsedMillis < 3000)
                release.countDown()
            }
        }
    }

    @Test
    fun `error status maps to classified exception over real socket`() = runBlocking {
        withServer(
            script = { out ->
                val body = """{"error":{"message":"bad key"}}"""
                out.write(
                    ("HTTP/1.1 401 Unauthorized\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray() + body.toByteArray(),
                )
                out.flush()
            },
        ) { server ->
            val client = AiClient(OkHttpAiTransport())
            val exception = runCatching {
                client.streamChat(connection(server.localPort), "sk-wrong", listOf(AiChatTurn("user", "hi")))
            }.exceptionOrNull()
            assertTrue(exception is AiException)
            assertEquals(AiError.Auth, (exception as AiException).error)
        }
    }
}
