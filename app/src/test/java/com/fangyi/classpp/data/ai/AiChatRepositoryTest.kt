package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.Clock
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话仓库状态机：fake store + fake settings + 脚本化假传输驱动
 * （流式落位 / 未配置拦截 / 失败保留半截 / 重试不重复追加 / 取消保留半截 / 草稿状态回写）。
 */
class AiChatRepositoryTest {

    // ---------- fakes ----------

    private class FakeAiChatStore : AiChatStore {
        var file: AiChatFile = AiChatFile()
        var saveCount = 0

        override fun load(): LoadOutcome<AiChatFile> =
            if (file.messages.isEmpty()) {
                LoadOutcome.Fresh(AiChatFile(), LoadIssue.None)
            } else {
                LoadOutcome.Loaded(file, LoadIssue.None)
            }

        override fun save(file: AiChatFile): StoreResult {
            this.file = file
            saveCount++
            return StoreResult.Ok
        }
    }

    private class FakeAiSettingsStore(
        var connection: AiConnection = AiConnection(baseUrl = "https://api.example.com/v1", model = "test"),
        var apiKey: String? = "sk-test",
    ) : AiSettingsStore {
        override val secureAvailable: Boolean get() = true
        override fun loadConnection(): AiConnection = connection
        override fun saveConnection(connection: AiConnection): Boolean {
            this.connection = connection
            return true
        }
        override fun loadApiKey(): String? = apiKey
        override fun saveApiKey(key: String?): Boolean {
            apiKey = key
            return true
        }
    }

    /** 脚本化响应：按序吐 lines，耗尽后阻塞在 gate（模拟流挂起），cancel 后放行 EOF */
    private class ScriptedResponse(
        private val lines: List<String?>,
        private val failAfterLines: Boolean = false,
    ) : AiStreamResponse {
        val gate = CountDownLatch(1)
        var cancelled = false
            private set
        private var index = 0

        override val status: Int = 200

        override fun readLine(): String? {
            if (failAfterLines && index >= lines.size) throw IOException("connection reset")
            lines.getOrNull(index++)?.let { return it }
            // 脚本耗尽：阻塞模拟慢流；cancel 放行 → EOF（与真实现 cancel 断流语义一致）
            gate.await(5, TimeUnit.SECONDS)
            return null
        }

        override fun readRemaining(): String = ""
        override fun cancel() {
            cancelled = true
            gate.countDown()
        }

        override fun close() = Unit
    }

    /** 传输假实现：open 按序取下一个脚本 */
    private class FakeAiTransport(vararg scripts: AiStreamResponse) : AiHttpTransport {
        private val queue = scripts.toMutableList()
        val opened = mutableListOf<AiHttpRequest>()

        override fun open(request: AiHttpRequest): AiStreamResponse {
            opened += request
            return queue.removeFirstOrNull() ?: error("no more scripts")
        }
    }

    /** SSE data 行（content 经 JSON 转义，允许内嵌围栏 JSON 的引号） */
    private fun sse(content: String): String =
        "data: " + Json.encodeToString(
            buildJsonObject {
                put(
                    "choices",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("delta", buildJsonObject { put("content", content) })
                            },
                        ),
                    ),
                )
            },
        )

    /** 请求体里的消息序列（role + content），用于结构断言 */
    private fun turnsOf(jsonBody: String): List<Pair<String, String>> =
        Json.parseToJsonElement(jsonBody).jsonObject["messages"]!!.jsonArray.map {
            it.jsonObject["role"]!!.jsonPrimitive.content to
                it.jsonObject["content"]!!.jsonPrimitive.content
        }

    private fun rolesOf(jsonBody: String): List<String> = turnsOf(jsonBody).map { it.first }

    private fun repository(
        store: FakeAiChatStore = FakeAiChatStore(),
        settings: FakeAiSettingsStore = FakeAiSettingsStore(),
        transport: FakeAiTransport,
    ): AiChatRepository {
        val repo = AiChatRepository(
            store = store,
            settings = settings,
            client = AiClient(transport),
            contextProvider = { AiChatContext() },
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        runBlocking { repo.bootstrap() }
        return repo
    }

    // ---------- 用例 ----------

    @Test
    fun `send streams assistant reply and persists`() = runBlocking {
        val store = FakeAiChatStore()
        val repo = repository(
            store = store,
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("你"), sse("好"), "data: [DONE]")),
            ),
        )

        repo.send("帮我看看下周安排")

        val messages = repo.messages.value
        assertEquals(2, messages.size)
        assertEquals(AiChatRole.User, messages[0].role)
        assertEquals("帮我看看下周安排", messages[0].content)
        assertEquals(AiChatRole.Assistant, messages[1].role)
        assertEquals("你好", messages[1].content)
        assertFalse(messages[1].interrupted)
        assertNull(messages[1].drafts)
        assertEquals(messages, store.file.messages)
        assertFalse(repo.sending.value)
    }

    @Test
    fun `send without config throws auth and appends nothing`() = runBlocking {
        val repo = repository(
            settings = FakeAiSettingsStore(apiKey = null),
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        val exception = runCatching { repo.send("hi") }.exceptionOrNull()
        assertTrue(exception is AiException)
        assertEquals(AiError.Auth, (exception as AiException).error)
        assertTrue(repo.messages.value.isEmpty())
    }

    @Test
    fun `stream failure keeps partial and publishes error`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("半截")), failAfterLines = true),
            ),
        )

        repo.send("hi")

        val last = repo.messages.value.last()
        assertEquals("半截", last.content)
        assertTrue(last.interrupted)
        assertEquals(AiError.Network, repo.lastError.value?.error)
        assertTrue(repo.messages.value.first().role == AiChatRole.User) // user 消息只追加一次
    }

    @Test
    fun `retryLast re-streams without duplicating user message`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("半截")), failAfterLines = true),
                ScriptedResponse(listOf(sse("完整"), "data: [DONE]")),
            ),
        )
        repo.send("hi")
        assertEquals(2, repo.messages.value.size)

        repo.retryLast()

        val messages = repo.messages.value
        assertEquals(2, messages.size) // user 不重复，失败的尾巴被替换
        assertEquals("hi", messages[0].content)
        assertEquals("完整", messages[1].content)
        assertFalse(messages[1].interrupted)
        assertNull(repo.lastError.value)
    }

    @Test
    fun `retryLast without interrupted tail is a no-op`() = runBlocking {
        val transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))
        val repo = repository(transport = transport)
        repo.send("hi")

        repo.retryLast()

        assertEquals(1, transport.opened.size) // 没有可重试状态，不再发请求
    }

    @Test
    fun `cancel keeps partial and marks interrupted`() = runBlocking {
        val response = ScriptedResponse(listOf(sse("前半")))
        val repo = repository(transport = FakeAiTransport(response))

        withTimeout(5000) {
            val job = launch { repo.send("hi") }
            // 等流式增量落进消息流再取消
            while (repo.messages.value.lastOrNull()?.content?.contains("前半") != true) {
                delay(10)
            }
            job.cancel()
            response.gate.countDown() // 模拟 cancel 断流：阻塞读放行
            runCatching { job.join() }
        }

        val last = repo.messages.value.last()
        assertEquals("前半", last.content)
        assertTrue(last.interrupted)
        assertFalse(repo.sending.value)
    }

    @Test
    fun `drafts extracted from fenced json and status persists`() = runBlocking {
        val store = FakeAiChatStore()
        val reply = "好的，给你起草了：\n" +
            "```json\n" +
            """{"todos":[{"name":"实验报告","dates":["2026-11-06"],"deadlineDate":"2026-11-06","deadlineTime":"23:59"}]}""" +
            "\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
        )

        repo.send("添加：周五交实验报告")

        val drafts = repo.messages.value.last().drafts
        assertNotNull(drafts)
        assertEquals(1, drafts!!.drafts.size)
        assertEquals("实验报告", drafts.drafts[0].name)
        assertEquals(AiDraftStatus.Pending, drafts.statuses[0])

        // 状态回写并落盘：用同一 store 重开一个仓库，重进对话不重复弹卡
        repo.updateDraftStatus(1, 0, AiDraftStatus.Saved)
        assertEquals(AiDraftStatus.Saved, store.file.messages[1].drafts!!.statuses[0])
    }

    @Test
    fun `bootstrap restores history and load state`() = runBlocking {
        val store = FakeAiChatStore()
        store.file = AiChatFile(
            messages = listOf(
                AiChatMessage(role = AiChatRole.User, content = "旧消息"),
            ),
        )
        val repo = AiChatRepository(
            store = store,
            settings = FakeAiSettingsStore(),
            client = AiClient(FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))),
            contextProvider = { AiChatContext() },
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        assertEquals(LoadState.Ready, repo.loadState.value)

        repo.bootstrap()

        assertEquals(LoadState.Ready, repo.loadState.value)
        assertEquals(listOf("旧消息"), repo.messages.value.map { it.content })
        assertTrue(repo.configured.value)
    }

    @Test
    fun `clearHistory empties messages and persists`() = runBlocking {
        val store = FakeAiChatStore()
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        repo.send("hi")
        assertEquals(2, store.file.messages.size)

        repo.clearHistory()

        assertTrue(repo.messages.value.isEmpty())
        assertTrue(store.file.messages.isEmpty())
    }

    @Test
    fun `system prompt carries context and user text is last turn`() = runBlocking {
        val transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))
        var context = AiChatContext()
        val repo = AiChatRepository(
            store = FakeAiChatStore(),
            settings = FakeAiSettingsStore(),
            client = AiClient(transport),
            contextProvider = { context },
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        repo.bootstrap()

        repo.send("hi")

        val request = transport.opened.single()
        assertTrue(request.jsonBody.contains("\"system\""))
        assertTrue(request.jsonBody.contains("今天是 2026-11-01"))
        // 消息序：system 在最前，user 最后
        val systemIndex = request.jsonBody.indexOf("\"system\"")
        val userIndex = request.jsonBody.lastIndexOf("\"user\"")
        assertTrue(systemIndex in 0 until userIndex)
    }

    // ---------- 请求消息序列（1.1 回归） ----------

    @Test
    fun `request has exactly one user turn and no empty assistant`() = runBlocking {
        val transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))
        val repo = repository(transport = transport)

        repo.send("帮我看看下周安排")

        // 旧实现是 [system, user, assistant(""), user]：重复本轮 user + 空 assistant
        assertEquals(
            listOf("system", "user"),
            rolesOf(transport.opened.single().jsonBody),
        )
        assertEquals(
            "帮我看看下周安排",
            turnsOf(transport.opened.single().jsonBody).last().second,
        )
    }

    @Test
    fun `retry resends user once with prior history intact`() = runBlocking {
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse("第一轮回复"), "data: [DONE]")),
            ScriptedResponse(listOf(sse("第二")), failAfterLines = true),
            ScriptedResponse(listOf(sse("重试成功"), "data: [DONE]")),
        )
        val repo = repository(transport = transport)

        repo.send("第一问")
        repo.send("第二问")
        repo.retryLast()

        assertEquals(3, transport.opened.size)
        assertEquals(listOf("system", "user"), rolesOf(transport.opened[0].jsonBody))

        // 第二轮带上一轮完整历史；重试不重复追加本轮 user，也不发送失败的半截尾巴
        val expected = listOf("system", "user", "assistant", "user")
        assertEquals(expected, rolesOf(transport.opened[1].jsonBody))
        assertEquals(expected, rolesOf(transport.opened[2].jsonBody))

        val retryTurns = turnsOf(transport.opened[2].jsonBody)
        assertEquals("第一轮回复", retryTurns[2].second)
        assertEquals("第二问", retryTurns.last().second)
    }

    @Test
    fun `empty assistant residue in history is not sent`() = runBlocking {
        val store = FakeAiChatStore()
        // 取消在首个增量前发生时的落盘形态：空 interrupted assistant 夹在历史中间
        store.file = AiChatFile(
            messages = listOf(
                AiChatMessage(role = AiChatRole.User, content = "旧问题"),
                AiChatMessage(role = AiChatRole.Assistant, content = "", interrupted = true),
                AiChatMessage(role = AiChatRole.User, content = "旧追问"),
                AiChatMessage(role = AiChatRole.Assistant, content = "旧回答"),
            ),
        )
        val transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))
        val repo = repository(store = store, transport = transport)

        repo.send("新问题")

        val turns = turnsOf(transport.opened.single().jsonBody)
        assertEquals(listOf("system", "user", "user", "assistant", "user"), turns.map { it.first })
        assertTrue(turns.none { it.first == "assistant" && it.second.isBlank() })
    }
}
