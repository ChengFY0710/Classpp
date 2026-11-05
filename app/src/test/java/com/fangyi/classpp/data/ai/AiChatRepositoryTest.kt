package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.Clock
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
        @Volatile
        var file: AiChatFile = AiChatFile()

        @Volatile
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

    /** 落盘阻塞到放行：用于「取消/关闭时落盘仍要完成」的用例 */
    private class BlockingSaveStore : AiChatStore {
        private val release = CountDownLatch(1)
        val saveStarted = CountDownLatch(1)

        @Volatile
        var file: AiChatFile = AiChatFile()

        override fun load(): LoadOutcome<AiChatFile> = LoadOutcome.Loaded(file, LoadIssue.None)

        override fun save(file: AiChatFile): StoreResult {
            saveStarted.countDown()
            release.await(5, TimeUnit.SECONDS)
            this.file = file
            return StoreResult.Ok
        }

        fun releaseSave() = release.countDown()
    }

    /** 内存方案库：connection / apiKey 字段继续承载「自定义」槽位，既有用例语义不变 */
    private class FakeAiSettingsStore(
        var connection: AiConnection = AiConnection(baseUrl = "https://api.example.com/v1", model = "test"),
        var apiKey: String? = "sk-test",
        override var secureAvailable: Boolean = true,
    ) : AiSettingsStore {
        val profiles = mutableListOf<AiProfile>()
        val profileKeys = mutableMapOf<String, String>()
        var activeId: String? = null

        override fun loadProfiles(): List<AiProfile> = profiles.toList()

        override fun loadActiveProfileId(): String? =
            activeId?.takeIf { id -> profiles.any { it.id == id } }

        override fun saveProfile(profile: AiProfile, activate: Boolean): Boolean {
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index >= 0) profiles[index] = profile else profiles += profile
            if (activate) activeId = profile.id
            return true
        }

        override fun renameProfile(id: String, name: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return false
            val index = profiles.indexOfFirst { it.id == id }
            if (index < 0) return false
            profiles[index] = profiles[index].copy(name = trimmed)
            return true
        }

        override fun deleteProfile(id: String): Boolean {
            if (profiles.none { it.id == id }) return false
            profiles.removeAll { it.id == id }
            profileKeys.remove(id)
            if (activeId == id) activeId = null
            return true
        }

        override fun setActiveProfile(id: String?): Boolean {
            activeId = id
            return true
        }

        override fun loadCustomConnection(): AiConnection = connection

        override fun loadCustomKey(): String? = apiKey

        override fun saveCustomConnection(connection: AiConnection): Boolean {
            this.connection = connection
            activeId = null
            return true
        }

        override fun saveCustomKey(key: String?): Boolean {
            apiKey = key
            return true
        }

        override fun loadProfileKey(profileId: String): String? = profileKeys[profileId]

        override fun saveProfileKey(profileId: String, key: String?): Boolean {
            if (key == null) profileKeys.remove(profileId) else profileKeys[profileId] = key
            return true
        }

        override fun loadConnection(): AiConnection {
            val id = activeId
            if (id != null) {
                profiles.firstOrNull { it.id == id }?.let { return it.connection }
            }
            return connection
        }

        override fun loadApiKey(): String? {
            val id = activeId
            // 活动方案没有 Key 时不能回落自定义槽位的 Key（两个槽位彼此独立）
            return if (id != null) profileKeys[id] else apiKey
        }
    }

    /** 待办落库假实现：记录每批 addTodos 入参，result 控制成功/失败 */
    private class FakeTodoPersister : AiTodoPersister {
        val batches = mutableListOf<List<Todo>>()
        var result = true

        override suspend fun addAll(todos: List<Todo>): Boolean {
            batches += todos
            return result
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

    /** SSE data 行（思考增量：reasoning_content 字段） */
    private fun reasoningSse(reasoning: String): String =
        "data: " + Json.encodeToString(
            buildJsonObject {
                put(
                    "choices",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("delta", buildJsonObject { put("reasoning_content", reasoning) })
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
        persister: FakeTodoPersister = FakeTodoPersister(),
        nowMillis: () -> Long = System::currentTimeMillis,
    ): AiChatRepository {
        val repo = AiChatRepository(
            store = store,
            settings = settings,
            client = AiClient(transport),
            contextProvider = { AiChatContext() },
            persister = persister,
            clock = Clock { IsoDate.of(2026, 11, 1) },
            nowMillis = nowMillis,
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
        assertTrue(messages[1].todoIds.isEmpty())
        assertFalse(messages[1].todoSaveFailed)
        assertEquals(messages, store.file.messages)
        assertFalse(repo.sending.value)
    }

    @Test
    fun `send records reasoning separately and persists it`() = runBlocking {
        val store = FakeAiChatStore()
        val repo = repository(
            store = store,
            transport = FakeAiTransport(
                ScriptedResponse(
                    listOf(
                        reasoningSse("先看课表，"),
                        reasoningSse("再排时间。"),
                        sse("明天没课，可以复习。"),
                        "data: [DONE]",
                    ),
                ),
            ),
        )

        repo.send("明天有课吗")

        val last = repo.messages.value.last()
        assertEquals("明天没课，可以复习。", last.content)
        assertEquals("先看课表，再排时间。", last.reasoning)
        // 思考过程随对话落盘，重进浮层/重启后折叠块仍可展开查看
        assertEquals("先看课表，再排时间。", store.file.messages.last().reasoning)
    }

    @Test
    fun `send without config appends nothing and leaves guidance state`() = runBlocking {
        val repo = repository(
            settings = FakeAiSettingsStore(apiKey = null),
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        // 未配置：静默返回（UI 渲染引导态），不再向调用方抛 AiException
        repo.send("hi")

        assertFalse(repo.configured.value)
        assertTrue(repo.messages.value.isEmpty())
        assertFalse(repo.sending.value)
    }

    @Test
    fun `send with anthropic connection posts messages wire format`() = runBlocking {
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好"}}""",
                    """data: {"type":"message_stop"}""",
                ),
            ),
        )
        val repo = repository(
            settings = FakeAiSettingsStore(
                connection = AiConnection(
                    providerId = "anthropic",
                    baseUrl = "https://api.anthropic.com/v1",
                    model = "claude-sonnet-4-5",
                    protocol = AiProtocol.Anthropic,
                ),
            ),
            transport = transport,
        )

        repo.send("帮我看看下周安排")

        val request = transport.opened.single()
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-test", request.headers["x-api-key"])
        val body = Json.parseToJsonElement(request.jsonBody).jsonObject
        // system 提升为顶层字段，messages 仅剩交替的 user/assistant
        assertTrue(body.containsKey("system"))
        assertTrue(body.containsKey("max_tokens"))
        assertEquals(
            listOf("user"),
            body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content },
        )
        assertEquals("你好", repo.messages.value.last().content)
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
    fun `fenced todos are persisted directly and ids recorded`() = runBlocking {
        val store = FakeAiChatStore()
        val persister = FakeTodoPersister()
        val reply = "好的，已经创建：\n" +
            "```json\n" +
            """{"todos":[{"name":"实验报告","dates":["2026-11-06"],"deadlineDate":"2026-11-06","deadlineTime":"23:59",""" +
            """"pinned":true,"repeat":{"type":"Weekly","endType":"Count","endCount":3}}]}""" +
            "\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            persister = persister,
        )

        repo.send("添加：每周五交实验报告，共三次")

        // 无需确认：仓库解析后直接整批落库（一次 addTodos）
        assertEquals(1, persister.batches.size)
        val created = persister.batches.single().single()
        assertEquals("实验报告", created.name)
        assertEquals(23 * 60 + 59, created.deadlineMinute)
        assertTrue(created.pinned)
        assertEquals(com.fangyi.classpp.data.model.RepeatType.Weekly, created.repeat.type)
        assertEquals(3, created.repeat.endCount)

        // 消息只记录成功创建的 id，落盘后可恢复
        val last = repo.messages.value.last()
        assertEquals(listOf(created.id), last.todoIds)
        assertFalse(last.todoSaveFailed)
        assertEquals(listOf(created.id), store.file.messages.last().todoIds)
    }

    @Test
    fun `blank name drafts are filtered before batch persist`() = runBlocking {
        val persister = FakeTodoPersister()
        val reply = "```json\n" +
            """{"todos":[{"name":"  ","dates":["2026-11-06"]},{"name":"有效待办"}]}""" +
            "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            persister = persister,
        )

        repo.send("添加")

        // 缺名称行会让 addTodos 整批失败：必须在落库前滤掉，只保存合法行
        assertEquals(listOf("有效待办"), persister.batches.single().map { it.name })
        assertEquals(1, repo.messages.value.last().todoIds.size)
        assertFalse(repo.messages.value.last().todoSaveFailed)
    }

    @Test
    fun `persist failure marks message and keeps reply`() = runBlocking {
        val store = FakeAiChatStore()
        val persister = FakeTodoPersister().apply { result = false }
        val reply = "```json\n{\"todos\":[{\"name\":\"实验报告\"}]}\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            persister = persister,
        )

        repo.send("添加")

        val last = repo.messages.value.last()
        assertEquals(reply, last.content)
        assertTrue(last.todoIds.isEmpty())
        assertTrue(last.todoSaveFailed)
        assertTrue(store.file.messages.last().todoSaveFailed)
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
            persister = FakeTodoPersister(),
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
            persister = FakeTodoPersister(),
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

    // ---------- 草稿修复阶段的取消（2.1 回归） ----------

    @Test
    fun `cancel during draft repair keeps full body and does not mark interrupted`() = runBlocking {
        val store = FakeAiChatStore()
        // 有围栏块但结构解析不了 → 主回复结束后会发起一次修复请求
        val reply = "草稿如下：\n```json\n{\"x\":1}\n```"
        val repair = ScriptedResponse(emptyList()) // 修复请求挂起，等取消断流
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse(reply), "data: [DONE]")),
            repair,
        )
        val repo = repository(store = store, transport = transport)

        withTimeout(5000) {
            val job = launch { repo.send("添加待办") }
            // 主回复已结束、修复请求已在途（第二次 open 返回）后再取消
            while (transport.opened.size < 2) delay(10)
            job.cancel()
            repair.gate.countDown()
            runCatching { job.join() }
        }

        val persisted = store.file.messages.last()
        assertEquals(reply, persisted.content) // 正文完整落盘（取消不丢）
        assertFalse(persisted.interrupted) // 正文完整，不应标中断
        assertTrue(persisted.todoIds.isEmpty()) // 修复没有结果，不创建待办
        assertFalse(persisted.todoSaveFailed)
        assertFalse(repo.sending.value)

        // 修复请求自带格式声明：原文只说「遵循之前声明的格式」，模型手里并没有声明
        assertEquals(listOf("system", "user", "user"), rolesOf(transport.opened[1].jsonBody))
        assertTrue(turnsOf(transport.opened[1].jsonBody).first().second.contains("\"todos\""))
    }

    // ---------- 批次 3：后台续流 / checkpoint / 批量状态 / 健壮性 ----------

    @Test
    fun `background send keeps streaming after caller scope is cancelled`() = runBlocking {
        val store = FakeAiChatStore()
        val repo = repository(
            store = store,
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("后台"), sse("完成"), "data: [DONE]")),
            ),
        )
        val uiScope = CoroutineScope(SupervisorJob())

        repo.sendInBackground("hi")
        uiScope.cancel() // 模拟收起浮层 / Activity 重建

        withTimeout(5000) {
            while (repo.sending.value || repo.messages.value.size < 2) delay(10)
        }

        val last = repo.messages.value.last()
        assertEquals("后台完成", last.content)
        assertFalse(last.interrupted)
        assertEquals("后台完成", store.file.messages.last().content)
    }

    @Test
    fun `cancelSending stops background stream and keeps partial`() = runBlocking {
        val response = ScriptedResponse(listOf(sse("前半")))
        val store = FakeAiChatStore()
        val repo = repository(store = store, transport = FakeAiTransport(response))

        repo.sendInBackground("hi")
        withTimeout(5000) {
            while (repo.messages.value.lastOrNull()?.content?.contains("前半") != true) delay(10)
            repo.cancelSending()
            response.gate.countDown()
            while (repo.sending.value) delay(10)
        }

        val last = repo.messages.value.last()
        assertEquals("前半", last.content)
        assertTrue(last.interrupted)
        assertEquals("前半", store.file.messages.last().content)
    }

    @Test
    fun `multiple todos are persisted in a single batch`() = runBlocking {
        val store = FakeAiChatStore()
        val persister = FakeTodoPersister()
        val reply = "已创建：\n```json\n" +
            """{"todos":[{"name":"甲"},{"name":"乙"}]}""" +
            "\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            persister = persister,
        )
        repo.send("添加两个待办")

        // 两条同批提交（一次 addTodos），消息记录两个 id
        assertEquals(1, persister.batches.size)
        assertEquals(listOf("甲", "乙"), persister.batches.single().map { it.name })
        assertEquals(2, repo.messages.value.last().todoIds.size)
        assertEquals(repo.messages.value.last().todoIds, store.file.messages[1].todoIds)
    }

    @Test
    fun `long stream checkpoints partial reply before it finishes`() = runBlocking {
        var tick = 0L
        val response = ScriptedResponse(listOf(sse("部分"), sse("内容"), sse("片段")))
        val store = FakeAiChatStore()
        val repo = repository(
            store = store,
            transport = FakeAiTransport(response),
            nowMillis = { tick += 1_000; tick },
        )

        repo.sendInBackground("hi")
        withTimeout(5000) {
            // 流仍阻塞在 gate（未收到 [DONE]），store 里已由 checkpoint 留下半截
            while (store.file.messages.lastOrNull()?.content?.contains("部分内容") != true) delay(10)
        }
        assertTrue(repo.sending.value)

        response.gate.countDown()
        withTimeout(5000) { while (repo.sending.value) delay(10) }
    }

    @Test
    fun `secureAvailable state follows store availability`() = runBlocking {
        val repo = repository(
            settings = FakeAiSettingsStore(secureAvailable = false),
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        assertFalse(repo.secureAvailable.value)
    }

    @Test
    fun `clearHistory persists even when caller is cancelled mid-save`() = runBlocking {
        val store = BlockingSaveStore()
        val repo = AiChatRepository(
            store = store,
            settings = FakeAiSettingsStore(),
            client = AiClient(FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))),
            contextProvider = { AiChatContext() },
            persister = FakeTodoPersister(),
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        repo.bootstrap()

        val job = launch { repo.clearHistory() }
        withTimeout(5000) { while (store.saveStarted.count != 0L) delay(10) }
        job.cancel()
        store.releaseSave()
        runCatching { job.join() }

        assertTrue(repo.messages.value.isEmpty())
        assertTrue(store.file.messages.isEmpty())
    }

    @Test
    fun `clearHistory during active send persists empty after in-flight checkpoint`() = runBlocking {
        val store = BlockingSaveStore()
        store.file = AiChatFile(
            messages = listOf(
                AiChatMessage(role = AiChatRole.User, content = "旧问题"),
                AiChatMessage(role = AiChatRole.Assistant, content = "旧回答"),
            ),
        )
        var tick = 0L
        // 两个增量后流挂起：节流时钟让第二个增量触发 checkpoint，其落盘阻塞在 store 里
        val response = ScriptedResponse(listOf(sse("部分"), sse("内容")))
        val repo = AiChatRepository(
            store = store,
            settings = FakeAiSettingsStore(),
            client = AiClient(FakeAiTransport(response)),
            contextProvider = { AiChatContext() },
            persister = FakeTodoPersister(),
            clock = Clock { IsoDate.of(2026, 11, 1) },
            nowMillis = { tick += 1_000; tick },
        )
        repo.bootstrap()

        repo.sendInBackground("新问题")
        withTimeout(5000) { store.saveStarted.await() } // checkpoint 的旧快照落盘已在途并阻塞

        // send 全程持有主 mutex：清空必须排队，落盘顺序 = checkpoint → 失败收口 → 清空
        val clearJob = launch { repo.clearHistory() }
        store.releaseSave()
        response.gate.countDown() // EOF 无 [DONE]：send 按失败收口（保留半截 + persist）
        withTimeout(5000) {
            clearJob.join()
            while (repo.sending.value) delay(10)
        }

        // 空文件最后落盘：在途/排队的落盘（含 checkpoint 旧快照）不会把刚清空的旧历史写回磁盘
        assertTrue(repo.messages.value.isEmpty())
        assertTrue(store.file.messages.isEmpty())
    }

    // ---------- 配置方案 ----------

    @Test
    fun `createProfile saves without activating`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        val profile = repo.createProfile(
            name = "  DeepSeek  ",
            connection = AiConnection(
                providerId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
            ),
            apiKey = "sk-profile",
        )

        assertNotNull(profile)
        val saved = profile!!
        assertEquals("DeepSeek", saved.name)
        assertEquals(listOf(saved), repo.profiles.value)
        // 保存为新方案不激活：活动配置仍是自定义槽位
        assertNull(repo.activeProfileId.value)
        assertEquals("https://api.example.com/v1", repo.activeConnection.value.baseUrl)
        assertEquals("sk-profile", settings.profileKeys[saved.id])
    }

    @Test
    fun `updateProfile activates profile and uses its own key`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        val deepseek = repo.createProfile(
            name = "DeepSeek",
            connection = AiConnection(
                providerId = "deepseek",
                baseUrl = "https://api.deepseek.com/v1",
                model = "deepseek-chat",
            ),
            apiKey = "sk-deepseek",
        )!!
        val openai = repo.createProfile(
            name = "OpenAI",
            connection = AiConnection(
                providerId = "openai",
                baseUrl = "https://api.openai.com/v1",
                model = "gpt-4o-mini",
            ),
            apiKey = "sk-openai",
        )!!

        repo.updateProfile(deepseek.id, deepseek.connection, apiKey = null)
        assertEquals(deepseek.id, repo.activeProfileId.value)
        assertEquals("deepseek-chat", repo.activeConnection.value.model)
        assertEquals("sk-deepseek", settings.loadApiKey())
        assertTrue(repo.configured.value)

        repo.updateProfile(openai.id, openai.connection, apiKey = "sk-openai-new")
        assertEquals(openai.id, repo.activeProfileId.value)
        assertEquals("sk-openai-new", settings.loadApiKey())
        assertTrue(repo.configured.value)
    }

    @Test
    fun `saveCustom switches active config back to custom slot`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        val profile = repo.createProfile(
            name = "P",
            connection = AiConnection(baseUrl = "https://p.example/v1", model = "p"),
            apiKey = "sk-p",
        )!!
        repo.updateProfile(profile.id, profile.connection, apiKey = null)
        assertEquals(profile.id, repo.activeProfileId.value)

        val custom = AiConnection(baseUrl = "https://custom.example/v1", model = "custom")
        val outcome = repo.saveCustom(custom, apiKey = "sk-custom")

        assertTrue(outcome.configSaved)
        assertTrue(outcome.keySaved)
        assertNull(repo.activeProfileId.value)
        assertEquals(custom, repo.activeConnection.value)
        assertEquals("sk-custom", settings.loadApiKey())
        assertTrue(repo.configured.value)
    }

    @Test
    fun `delete active profile falls back to custom slot`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        val profile = repo.createProfile(
            name = "P",
            connection = AiConnection(baseUrl = "https://p.example/v1", model = "p"),
            apiKey = "sk-p",
        )!!
        repo.updateProfile(profile.id, profile.connection, apiKey = null)

        assertTrue(repo.deleteProfile(profile.id))

        assertTrue(repo.profiles.value.isEmpty())
        assertNull(repo.activeProfileId.value)
        assertEquals("https://api.example.com/v1", repo.activeConnection.value.baseUrl)
        assertEquals("sk-test", settings.loadApiKey())
        assertTrue(repo.configured.value)
    }

    @Test
    fun `delete non active profile keeps active config`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        val active = repo.createProfile(
            name = "A",
            connection = AiConnection(baseUrl = "https://a.example/v1", model = "a"),
            apiKey = "sk-a",
        )!!
        val other = repo.createProfile(
            name = "B",
            connection = AiConnection(baseUrl = "https://b.example/v1", model = "b"),
            apiKey = "sk-b",
        )!!
        repo.updateProfile(active.id, active.connection, apiKey = null)

        assertTrue(repo.deleteProfile(other.id))

        assertEquals(active.id, repo.activeProfileId.value)
        assertEquals("a", repo.activeConnection.value.model)
        assertEquals("sk-a", settings.loadApiKey())
    }

    @Test
    fun `renameProfile trims and rejects blank or unknown`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        val profile = repo.createProfile(
            name = "A",
            connection = AiConnection(baseUrl = "https://a.example/v1", model = "a"),
            apiKey = null,
        )!!

        assertTrue(repo.renameProfile(profile.id, "  B  "))
        assertEquals("B", repo.profiles.value.single().name)
        assertFalse(repo.renameProfile(profile.id, "   "))
        assertEquals("B", repo.profiles.value.single().name)
        assertFalse(repo.renameProfile("missing", "C"))
    }

    @Test
    fun `updateProfile reports missing key and unconfigures active profile`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        val profile = repo.createProfile(
            name = "A",
            connection = AiConnection(baseUrl = "https://a.example/v1", model = "a"),
            apiKey = null,
        )!!

        val outcome = repo.updateProfile(profile.id, profile.connection, apiKey = null)

        assertTrue(outcome.configSaved)
        assertFalse(outcome.keySaved)
        assertEquals(profile.id, repo.activeProfileId.value)
        assertFalse(repo.configured.value)
    }

    @Test
    fun `custom config exposes connection and key presence`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val repo = repository(
            settings = settings,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        val (connection, hasKey) = repo.loadCustomConfig()

        assertEquals(settings.connection, connection)
        assertTrue(hasKey)
        assertFalse(repo.loadProfileKeyPresent("missing"))
    }
}
