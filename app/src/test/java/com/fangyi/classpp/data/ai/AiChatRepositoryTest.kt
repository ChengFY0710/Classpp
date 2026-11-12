package com.fangyi.classpp.data.ai

import com.fangyi.classpp.data.Clock
import com.fangyi.classpp.data.LoadState
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.testSchedule
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
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
import kotlinx.serialization.json.JsonObject
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

    /** 每个用例独立的图片附件临时目录（真实 AiImageStore，只测文件名到编码的通路） */
    private fun tempImageDir(): File = Files.createTempDirectory("ai_images").toFile()

    private class FakeAiChatStore : AiChatStore {
        @Volatile
        var file: AiChatFile = AiChatFile()

        @Volatile
        var saveCount = 0

        override fun load(): LoadOutcome<AiChatFile> =
            if (file.messages.isEmpty() && file.conversations.isEmpty()) {
                LoadOutcome.Fresh(AiChatFile(), LoadIssue.None)
            } else {
                LoadOutcome.Loaded(file, LoadIssue.None)
            }

        override fun save(file: AiChatFile): StoreResult {
            this.file = file
            saveCount++
            return StoreResult.Ok
        }

        /** 落盘消息（v2 在 conversations 里；v1 顶层字段兜底，断言迁移前的种子用） */
        fun persistedMessages(): List<AiChatMessage> =
            file.conversations.singleOrNull()?.messages ?: file.messages
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

        /** 落盘消息（v2 在 conversations 里；与 FakeAiChatStore 同一口径的断言辅助） */
        fun persistedMessages(): List<AiChatMessage> =
            file.conversations.singleOrNull()?.messages ?: file.messages
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
        var toolsEnabled: Boolean = true
        val toolCapabilities = mutableMapOf<String, AiToolCapability>()

        override fun loadToolsEnabled(): Boolean = toolsEnabled

        override fun saveToolsEnabled(enabled: Boolean): Boolean {
            toolsEnabled = enabled
            return true
        }

        override fun loadToolCapability(key: String): AiToolCapability =
            toolCapabilities[key] ?: AiToolCapability.Unknown

        override fun saveToolCapability(key: String, capability: AiToolCapability) {
            toolCapabilities[key] = capability
        }

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

    /** 修改/删除/完成写入口假实现：记录调用入参，result 控制成功/失败 */
    private class FakeTodoChangeWriter : AiTodoChangeWriter {
        data class CompletionCall(val id: String, val date: IsoDate?, val completed: Boolean)

        val updates = mutableListOf<Todo>()
        val deletions = mutableListOf<List<String>>()
        val completionCalls = mutableListOf<CompletionCall>()
        val archives = mutableListOf<String>()
        val unarchives = mutableListOf<String>()
        val restores = mutableListOf<String>()
        var updateResult = true
        var deleteResult = true
        var completionResult = true
        var lifecycleResult = true

        override suspend fun update(todo: Todo): Boolean {
            updates += todo
            return updateResult
        }

        override suspend fun deleteAll(ids: List<String>): Boolean {
            deletions += ids
            return deleteResult
        }

        override suspend fun setCompleted(id: String, completed: Boolean): Boolean {
            completionCalls += CompletionCall(id, date = null, completed = completed)
            return completionResult
        }

        override suspend fun setOccurrenceCompleted(
            id: String,
            date: IsoDate,
            completed: Boolean,
        ): Boolean {
            completionCalls += CompletionCall(id, date, completed)
            return completionResult
        }

        override suspend fun archive(id: String): Boolean {
            archives += id
            return lifecycleResult
        }

        override suspend fun unarchive(id: String): Boolean {
            unarchives += id
            return lifecycleResult
        }

        override suspend fun restore(id: String): Boolean {
            restores += id
            return lifecycleResult
        }
    }

    /** 脚本化响应：按序吐 lines，耗尽后阻塞在 gate（模拟流挂起），cancel 后放行 EOF */
    private class ScriptedResponse(
        private val lines: List<String?>,
        private val failAfterLines: Boolean = false,
        override val status: Int = 200,
    ) : AiStreamResponse {
        val gate = CountDownLatch(1)
        var cancelled = false
            private set
        private var index = 0

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

    /** SSE data 行（工具调用分片：OpenAI delta.tool_calls[]） */
    private fun toolCallSse(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ): String = "data: " + Json.encodeToString(
        buildJsonObject {
            put(
                "choices",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put(
                                "delta",
                                buildJsonObject {
                                    put(
                                        "tool_calls",
                                        JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("index", index)
                                                    id?.let { put("id", it) }
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            name?.let { put("name", it) }
                                                            arguments?.let { put("arguments", it) }
                                                        },
                                                    )
                                                },
                                            ),
                                        ),
                                    )
                                },
                            )
                        },
                    ),
                ),
            )
        },
    )

    private fun finishSse(reason: String): String =
        """data: {"choices":[{"delta":{},"finish_reason":"$reason"}]}"""

    /** 请求体里的 assistant tool_calls 与 tool 消息（用于配对断言） */
    private fun toolMessagesOf(jsonBody: String): List<JsonObject> =
        Json.parseToJsonElement(jsonBody).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["tool_calls"] != null || it["role"]!!.jsonPrimitive.content == "tool" }

    private fun requestHasTools(jsonBody: String): Boolean =
        Json.parseToJsonElement(jsonBody).jsonObject["tools"] != null

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
        changeWriter: FakeTodoChangeWriter = FakeTodoChangeWriter(),
        contextProvider: () -> AiChatContext = { AiChatContext() },
        nowMillis: () -> Long = System::currentTimeMillis,
    ): AiChatRepository {
        val repo = AiChatRepository(
            store = store,
            settings = settings,
            client = AiClient(transport),
            contextProvider = contextProvider,
            persister = persister,
            changeWriter = changeWriter,
            imageStore = AiImageStore(tempImageDir()),
            clock = Clock { IsoDate.of(2026, 11, 1) },
            nowMillis = nowMillis,
        )
        runBlocking { repo.bootstrap() }
        return repo
    }

    /** 识别导入用例：预置一张已导入的图片副本，仓库走真实 AiImageStore 编码 */
    private fun repositoryWithImage(
        transport: FakeAiTransport,
        settings: FakeAiSettingsStore = FakeAiSettingsStore(),
        imageBytes: ByteArray = byteArrayOf(1),
    ): AiChatRepository {
        val imageStore = AiImageStore(tempImageDir())
        imageStore.file("page.jpg").parentFile?.mkdirs()
        imageStore.file("page.jpg").writeBytes(imageBytes)
        val repo = AiChatRepository(
            store = FakeAiChatStore(),
            settings = settings,
            client = AiClient(transport),
            contextProvider = { AiChatContext() },
            persister = FakeTodoPersister(),
            changeWriter = FakeTodoChangeWriter(),
            imageStore = imageStore,
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
        assertTrue(messages[1].todoIds.isEmpty())
        assertFalse(messages[1].todoSaveFailed)
        assertEquals(messages, store.persistedMessages())
        assertFalse(repo.sending.value)
    }

    @Test
    fun `send with images persists filenames and encodes them into wire turns`() = runBlocking {
        val imageStore = AiImageStore(tempImageDir())
        // 预置两张副本文件（模拟 UI 已导入完毕，文件名来自导入返回值）
        val payloads = mapOf("a.jpg" to byteArrayOf(1), "b.jpg" to byteArrayOf(2, 3))
        payloads.forEach { (name, bytes) ->
            imageStore.file(name).parentFile.mkdirs()
            imageStore.file(name).writeBytes(bytes)
        }
        val store = FakeAiChatStore()
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse("收到"), "data: [DONE]")),
        )
        val repo = AiChatRepository(
            store = store,
            settings = FakeAiSettingsStore(),
            client = AiClient(transport),
            contextProvider = { AiChatContext() },
            persister = FakeTodoPersister(),
            changeWriter = FakeTodoChangeWriter(),
            imageStore = imageStore,
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        repo.bootstrap()

        repo.send("这两张图里有什么", listOf("a.jpg", "b.jpg"))

        // user 消息随文件名落库（图片是消息的一部分，历史回放/重试都依赖它）
        val user = repo.messages.value.first()
        assertEquals(AiChatRole.User, user.role)
        assertEquals(listOf("a.jpg", "b.jpg"), user.images)

        // wire 请求：user turn 的 content 是多模态数组（text 块 + 每图一个 data URL 块）
        val body = Json.parseToJsonElement(transport.opened.single().jsonBody).jsonObject
        val userTurn = body["messages"]!!.jsonArray
            .last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }
        val content = userTurn.jsonObject["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("这两张图里有什么", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        val imageBlocks = content.drop(1)
        assertEquals(2, imageBlocks.size)
        assertEquals(
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1))}",
            imageBlocks[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        // 已清理的文件不阻断请求：b.jpg 仍在，编码正常
        assertEquals(
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(byteArrayOf(2, 3))}",
            imageBlocks[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )

        assertEquals("收到", repo.messages.value.last().content)
    }

    // ---------- AI 识别导入 ----------

    @Test
    fun `recognizeSchedule sends import prompt with image and parses schedule`() = runBlocking {
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    sse("```json\n$AI_SCHEDULE_IMPORT_VALID_JSON\n```"),
                    "data: [DONE]",
                ),
            ),
        )
        val repo = repositoryWithImage(transport, imageBytes = byteArrayOf(42))

        val result = repo.recognizeSchedule("page.jpg")

        val ok = result as AiScheduleRecognition.Ok
        assertEquals("2026-2027 第1学期", ok.schedule.name)
        assertEquals(1, ok.schedule.courses.size)
        // 识别请求不进对话：不产生消息、不翻转发送态
        assertTrue(repo.messages.value.isEmpty())
        assertFalse(repo.sending.value)

        // wire 请求：system 是课表解析提示词，user 是文字 + 图片块
        val body = Json.parseToJsonElement(transport.opened.single().jsonBody).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages.first().jsonObject["role"]!!.jsonPrimitive.content)
        assertTrue(
            messages.first().jsonObject["content"]!!.jsonPrimitive.content
                .contains("课表截图解析器"),
        )
        val userTurn = messages.last().jsonObject
        assertEquals("user", userTurn["role"]!!.jsonPrimitive.content)
        val content = userTurn["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(byteArrayOf(42))}",
            content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `recognizeSchedule maps http failure`() = runBlocking {
        val repo = repositoryWithImage(
            FakeAiTransport(ScriptedResponse(lines = emptyList(), status = 401)),
        )

        val result = repo.recognizeSchedule("page.jpg")

        assertEquals(AiError.Auth, (result as AiScheduleRecognition.AiFailed).error)
    }

    @Test
    fun `recognizeSchedule reports no json and empty courses`() = runBlocking {
        val noJson = repositoryWithImage(
            FakeAiTransport(ScriptedResponse(listOf(sse("抱歉，无法识别"), "data: [DONE]"))),
        )
        assertEquals(AiScheduleRecognition.NoJson, noJson.recognizeSchedule("page.jpg"))

        val noCourses = repositoryWithImage(
            FakeAiTransport(
                ScriptedResponse(
                    listOf(sse("```json\n$AI_SCHEDULE_IMPORT_EMPTY_JSON\n```"), "data: [DONE]"),
                ),
            ),
        )
        assertEquals(AiScheduleRecognition.NoCourses, noCourses.recognizeSchedule("page.jpg"))
    }

    @Test
    fun `recognizeSchedule requires configured provider`() = runBlocking {
        val transport = FakeAiTransport()
        val repo = repositoryWithImage(
            transport,
            settings = FakeAiSettingsStore(apiKey = null),
        )

        assertEquals(AiScheduleRecognition.NotConfigured, repo.recognizeSchedule("page.jpg"))
        assertTrue(transport.opened.isEmpty())
    }

    @Test
    fun `send supports image only message with missing file skipped`() = runBlocking {
        val imageStore = AiImageStore(tempImageDir())
        imageStore.file("kept.jpg").parentFile.mkdirs()
        imageStore.file("kept.jpg").writeBytes(byteArrayOf(9))
        // gone.jpg 从未落盘：编码侧应跳过而不是让整条请求失败
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse("收到"), "data: [DONE]")),
        )
        val repo = AiChatRepository(
            store = FakeAiChatStore(),
            settings = FakeAiSettingsStore(),
            client = AiClient(transport),
            contextProvider = { AiChatContext() },
            persister = FakeTodoPersister(),
            changeWriter = FakeTodoChangeWriter(),
            imageStore = imageStore,
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        repo.bootstrap()

        repo.send("", listOf("kept.jpg", "gone.jpg"))

        val user = repo.messages.value.first()
        assertEquals("", user.content)
        assertEquals(listOf("kept.jpg", "gone.jpg"), user.images)

        // content 数组只有图片块（无 text 块），缺失的 gone.jpg 被跳过
        val body = Json.parseToJsonElement(transport.opened.single().jsonBody).jsonObject
        val userTurn = body["messages"]!!.jsonArray
            .last { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }
        val content = userTurn.jsonObject["content"]!!.jsonArray
        assertEquals(1, content.size)
        assertEquals(
            "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(byteArrayOf(9))}",
            content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
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
        assertEquals("先看课表，再排时间。", store.persistedMessages().last().reasoning)
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
        assertEquals(listOf(created.id), store.persistedMessages().last().todoIds)
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
        assertTrue(store.persistedMessages().last().todoSaveFailed)
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
            changeWriter = FakeTodoChangeWriter(),
            imageStore = AiImageStore(tempImageDir()),
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
        assertEquals(2, store.persistedMessages().size)

        repo.clearHistory()

        assertTrue(repo.messages.value.isEmpty())
        assertTrue(store.persistedMessages().isEmpty())
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
            changeWriter = FakeTodoChangeWriter(),
            imageStore = AiImageStore(tempImageDir()),
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

        val persisted = store.persistedMessages().last()
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
        assertEquals("后台完成", store.persistedMessages().last().content)
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
        assertEquals("前半", store.persistedMessages().last().content)
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
        assertEquals(repo.messages.value.last().todoIds, store.persistedMessages()[1].todoIds)
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
            while (store.persistedMessages().lastOrNull()?.content?.contains("部分内容") != true) delay(10)
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
            changeWriter = FakeTodoChangeWriter(),
            imageStore = AiImageStore(tempImageDir()),
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )
        repo.bootstrap()

        val job = launch { repo.clearHistory() }
        withTimeout(5000) { while (store.saveStarted.count != 0L) delay(10) }
        job.cancel()
        store.releaseSave()
        runCatching { job.join() }

        assertTrue(repo.messages.value.isEmpty())
        assertTrue(store.persistedMessages().isEmpty())
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
            changeWriter = FakeTodoChangeWriter(),
            imageStore = AiImageStore(tempImageDir()),
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
        assertTrue(store.persistedMessages().isEmpty())
    }

    // ---------- 多对话管理（「所有对话」浮层的数据底座） ----------

    @Test
    fun `send with no conversation creates one implicitly and names it`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse("回复"), "data: [DONE]"))),
        )
        assertTrue(repo.conversations.value.isEmpty())
        assertNull(repo.activeConversationId.value)

        repo.send("帮我看看下周安排")

        // 首次发言隐式建对话：未命名状态借首条用户消息命名，消息落在其中
        val conversation = repo.conversations.value.single()
        assertEquals(conversation.id, repo.activeConversationId.value)
        assertEquals("帮我看看下周安排", conversation.title)
        assertEquals(2, repo.messages.value.size)
    }

    @Test
    fun `conversation title is flattened and truncated from first user message`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        repo.send("第一行\n第二行 " + "很长".repeat(20))

        val title = repo.conversations.value.single().title
        assertFalse("换行要压平", title.contains('\n'))
        assertEquals(AiChatRepository.TITLE_MAX_LENGTH, title.length)
    }

    @Test
    fun `createConversation switches to empty active conversation`() = runBlocking {
        var tick = 0L
        val repo = repository(
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("甲"), "data: [DONE]")),
                ScriptedResponse(listOf(sse("乙"), "data: [DONE]")),
            ),
            nowMillis = { tick += 1_000; tick },
        )
        repo.send("第一场")
        val first = repo.activeConversationId.value!!

        val created = repo.createConversation()

        assertEquals(created, repo.activeConversationId.value)
        assertEquals(2, repo.conversations.value.size)
        assertTrue(repo.messages.value.isEmpty())

        // 切回旧对话：镜像随之切换，旧对话消息完整
        repo.switchConversation(first)
        assertEquals(first, repo.activeConversationId.value)
        assertEquals(2, repo.messages.value.size)
    }

    @Test
    fun `createConversation reuses active empty conversation`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        val created = repo.createConversation()
        val again = repo.createConversation()

        // 连点「新建」不堆积空对话
        assertEquals(created, again)
        assertEquals(1, repo.conversations.value.size)
    }

    @Test
    fun `switchConversation ignores unknown and current ids`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse("甲"), "data: [DONE]"))),
        )
        repo.send("hi")
        val active = repo.activeConversationId.value!!

        repo.switchConversation("missing")
        repo.switchConversation(active)

        assertEquals(active, repo.activeConversationId.value)
        assertEquals(2, repo.messages.value.size)
    }

    @Test
    fun `delete active conversation falls back to most recent remaining`() = runBlocking {
        var tick = 0L
        val repo = repository(
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("甲"), "data: [DONE]")),
                ScriptedResponse(listOf(sse("乙"), "data: [DONE]")),
            ),
            nowMillis = { tick += 1_000; tick },
        )
        repo.send("第一场")
        val first = repo.activeConversationId.value!!
        repo.createConversation()
        repo.send("第二场")
        val second = repo.activeConversationId.value!!

        repo.deleteConversation(second)

        // 删的是活跃对话：回落「最近更新」的剩余对话（这里只剩第一场）
        assertEquals(listOf(first), repo.conversations.value.map { it.id })
        assertEquals(first, repo.activeConversationId.value)
        assertEquals(listOf("第一场", "甲"), repo.messages.value.map { it.content })
    }

    @Test
    fun `delete non-active conversation keeps current chat`() = runBlocking {
        var tick = 0L
        val repo = repository(
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("甲"), "data: [DONE]")),
                ScriptedResponse(listOf(sse("乙"), "data: [DONE]")),
            ),
            nowMillis = { tick += 1_000; tick },
        )
        repo.send("第一场")
        repo.createConversation()
        repo.send("第二场")
        val second = repo.activeConversationId.value!!

        repo.deleteConversation("missing")
        repo.deleteConversation(repo.conversations.value.first { it.id != second }.id)

        // 删的是非活跃对话：当前对话与消息镜像不受影响；不存在的 id 静默返回
        assertEquals(listOf(second), repo.conversations.value.map { it.id })
        assertEquals(second, repo.activeConversationId.value)
        assertEquals(listOf("第二场", "乙"), repo.messages.value.map { it.content })
    }

    @Test
    fun `deleting last conversation leaves zero-conversation state`() = runBlocking {
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse("甲"), "data: [DONE]"))),
        )
        repo.send("hi")
        val only = repo.activeConversationId.value!!

        repo.deleteConversation(only)

        // 删光：活跃置空、对话浮层走空态（入口按钮点击无反应，再发言隐式新建）
        assertTrue(repo.conversations.value.isEmpty())
        assertNull(repo.activeConversationId.value)
        assertTrue(repo.messages.value.isEmpty())
    }

    @Test
    fun `clearHistory deletes all conversations`() = runBlocking {
        var tick = 0L
        val repo = repository(
            transport = FakeAiTransport(
                ScriptedResponse(listOf(sse("甲"), "data: [DONE]")),
                ScriptedResponse(listOf(sse("乙"), "data: [DONE]")),
            ),
            nowMillis = { tick += 1_000; tick },
        )
        repo.send("第一场")
        repo.createConversation()
        repo.send("第二场")
        assertEquals(2, repo.conversations.value.size)

        repo.clearHistory()

        assertTrue(repo.conversations.value.isEmpty())
        assertNull(repo.activeConversationId.value)
        assertTrue(repo.messages.value.isEmpty())
    }

    @Test
    fun `legacy bootstrap migrates flat history into one named conversation`() = runBlocking {
        val store = FakeAiChatStore()
        store.file = AiChatFile(
            messages = listOf(
                AiChatMessage(role = AiChatRole.User, content = "帮我看看下周安排", createdAtMillis = 5L),
                AiChatMessage(role = AiChatRole.Assistant, content = "好的", createdAtMillis = 9L),
            ),
        )
        val repo = AiChatRepository(
            store = store,
            settings = FakeAiSettingsStore(),
            client = AiClient(FakeAiTransport(ScriptedResponse(listOf("data: [DONE]")))),
            contextProvider = { AiChatContext() },
            persister = FakeTodoPersister(),
            changeWriter = FakeTodoChangeWriter(),
            imageStore = AiImageStore(tempImageDir()),
            clock = Clock { IsoDate.of(2026, 11, 1) },
        )

        repo.bootstrap()

        // 顶层 messages 包装成一场对话：标题取首条用户消息、活跃指针指向它
        val conversation = repo.conversations.value.single()
        assertEquals("帮我看看下周安排", conversation.title)
        assertEquals(conversation.id, repo.activeConversationId.value)
        assertEquals(listOf("帮我看看下周安排", "好的"), repo.messages.value.map { it.content })
        assertEquals(5L, conversation.createdAtMillis)
        assertEquals(9L, conversation.updatedAtMillis)
        // 迁移立即以 v2 形态重写落盘（幂等：失败下次装载会再迁移）
        assertEquals(AI_CHAT_FORMAT_VERSION, store.file.formatVersion)
        assertEquals(1, store.file.conversations.size)
        assertTrue(store.file.messages.isEmpty())
    }

    @Test
    fun `stream keeps landing in bound conversation after switching away`() = runBlocking {
        // 首个增量后流挂起：切走再放行，回复应完整落在发起时的对话里
        val response = ScriptedResponse(listOf(sse("前半")))
        val repo = repository(
            transport = FakeAiTransport(
                response,
                ScriptedResponse(listOf("data: [DONE]")),
            ),
        )

        repo.sendInBackground("hi")
        withTimeout(5000) {
            while (repo.messages.value.lastOrNull()?.content?.contains("前半") != true) delay(10)
        }
        val bound = repo.activeConversationId.value!!

        // 在途发送未结束时新建并切换（管理操作不取主 mutex，立即生效）
        repo.createConversation()
        assertTrue(repo.messages.value.isEmpty())

        response.gate.countDown() // EOF 无 [DONE]：按失败收口（保留半截 + interrupted）
        withTimeout(5000) { while (repo.sending.value) delay(10) }

        // 回复落在绑定的旧对话（interrupted 半截），当前对话不受打扰
        assertEquals(2, repo.conversations.value.size)
        val boundConversation = repo.conversations.value.first { it.id == bound }
        assertEquals(2, boundConversation.messages.size)
        assertEquals("前半", boundConversation.messages.last().content)
        assertTrue(boundConversation.messages.last().interrupted)
        assertTrue(repo.messages.value.isEmpty())
        assertEquals(0, repo.conversations.value.first { it.id != bound }.messages.size)
    }

    @Test
    fun `stream failure while switched away hides error until switching back`() = runBlocking {
        // 在途流失败时用户已切走：错误条不串场；切回发起对话自动重现，关闭后不再复活
        val response = ScriptedResponse(listOf(sse("前半")))
        val repo = repository(
            transport = FakeAiTransport(
                response,
                ScriptedResponse(listOf("data: [DONE]")),
            ),
        )

        repo.sendInBackground("hi")
        withTimeout(5000) {
            while (repo.messages.value.lastOrNull()?.content?.contains("前半") != true) delay(10)
        }
        val bound = repo.activeConversationId.value!!
        repo.createConversation()

        response.gate.countDown() // EOF 无 [DONE]：按失败收口
        withTimeout(5000) { while (repo.sending.value) delay(10) }

        assertNull(repo.lastError.value) // 失败的对话不在当前显示，错误条不出现

        repo.switchConversation(bound)
        assertNotNull(repo.lastError.value) // 切回发起对话：错误条重现（含重试入口）

        repo.dismissError()
        repo.switchConversation(repo.conversations.value.first { it.id != bound }.id)
        repo.switchConversation(bound)
        assertNull(repo.lastError.value) // 用户已关闭：切走再切回也不复活
    }

    @Test
    fun `stream failure error persists with conversation and survives reload`() = runBlocking {
        // 错误条是对话的一部分：退出浮层 / 进程重启后仍应出现，用户关闭后才消失
        val store = FakeAiChatStore()
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse("半截")), failAfterLines = true)),
        )

        repo.send("hi")
        assertEquals(AiError.Network, repo.lastError.value?.error)
        assertNotNull(store.file.conversations.single().lastError)

        // 模拟进程重启：同一份落盘重新装配，错误条随对话恢复
        val restarted = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        assertNotNull(restarted.lastError.value)
        assertEquals(AiError.Network, restarted.lastError.value?.error)

        // 用户关闭：落盘清空，再次装载不再出现
        restarted.dismissError()
        withTimeout(5000) {
            while (store.file.conversations.single().lastError != null) delay(10)
        }
        val reloaded = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )
        assertNull(reloaded.lastError.value)
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

    // ---------- 修改/删除待办（确认后生效） ----------

    private fun existingTodo(id: String, name: String) = Todo(
        id = id,
        name = name,
        dates = listOf(IsoDate.of(2026, 11, 5)),
    )

    /** 等确认卡的应用协程收口：消息里不再有 Pending 条目即视为完成 */
    private suspend fun awaitChangesSettled(repo: AiChatRepository, messageId: String) {
        withTimeout(5000) {
            while (
                repo.messages.value.firstOrNull { it.id == messageId }
                    ?.todoChanges?.any { it.status == AiTodoChangeStatus.Pending } == true
            ) {
                delay(10)
            }
        }
    }

    @Test
    fun `updates and deletes become pending changes without writing`() = runBlocking {
        val store = FakeAiChatStore()
        val writer = FakeTodoChangeWriter()
        val todos = listOf(existingTodo("todo-1", "实验报告"), existingTodo("todo-2", "英语作业"))
        val reply = "已生成方案，请确认：\n```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"实验报告（终稿）"}}],""" +
            """"deletes":[{"id":"todo-2"}]}""" + "\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = todos) },
        )

        repo.send("把实验报告改个名，再删掉英语作业")

        val last = repo.messages.value.last()
        assertFalse(last.todoSaveFailed)
        assertEquals(2, last.todoChanges.size)
        assertTrue(last.todoChanges.all { it.status == AiTodoChangeStatus.Pending })
        assertEquals(AiTodoChangeKind.Update, last.todoChanges[0].kind)
        assertEquals("实验报告", last.todoChanges[0].todoName)
        assertEquals(AiTodoChangeKind.Delete, last.todoChanges[1].kind)
        assertEquals("英语作业", last.todoChanges[1].todoName)
        assertFalse(last.todoChangeDropped)
        // 确认前不写库
        assertTrue(writer.updates.isEmpty())
        assertTrue(writer.deletions.isEmpty())
        // 待确认状态随消息落盘
        assertEquals(2, store.persistedMessages().last().todoChanges.size)
    }

    @Test
    fun `unknown ids and invalid patches are dropped and flagged`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"updates":[{"id":"missing","patch":{"name":"x"}},""" +
            """{"id":"todo-1","patch":{"name":"   "}}],""" +
            """"deletes":[{"id":"missing-2"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("随便改改")

        val last = repo.messages.value.last()
        assertTrue(last.todoChanges.isEmpty())
        assertTrue(last.todoChangeDropped)
        assertTrue(writer.updates.isEmpty())
        assertTrue(writer.deletions.isEmpty())
    }

    @Test
    fun `apply update writes against latest todo and records applied`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"新名字","pinned":true}}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )
        repo.send("改名并置顶")
        val messageId = repo.messages.value.last().id
        assertTrue(messageId.isNotBlank())

        repo.applyTodoChangesInBackground(messageId)
        awaitChangesSettled(repo, messageId)

        val change = repo.messages.value.last().todoChanges.single()
        assertEquals(AiTodoChangeStatus.Applied, change.status)
        assertEquals(AiTodoChangeFailure.None, change.failure)
        val updated = writer.updates.single()
        assertEquals("todo-1", updated.id)
        assertEquals("新名字", updated.name)
        assertTrue(updated.pinned)
        assertNull(repo.todoChangesApplyingMessageId.value)
    }

    @Test
    fun `multiple updates to same todo compose sequentially`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"新名字"}},""" +
            """{"id":"todo-1","patch":{"pinned":true}}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )
        repo.send("改名并置顶")
        val messageId = repo.messages.value.last().id

        repo.applyTodoChangesInBackground(messageId)
        awaitChangesSettled(repo, messageId)

        assertEquals(2, writer.updates.size)
        // 第二条基于第一条的结果继续套补丁，不能拿旧快照把名字覆盖回去
        assertEquals("新名字", writer.updates[1].name)
        assertTrue(writer.updates[1].pinned)
    }

    @Test
    fun `apply delete batches existing targets`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val todos = listOf(existingTodo("todo-1", "实验报告"), existingTodo("todo-2", "英语作业"))
        val reply = "```json\n" +
            """{"deletes":[{"id":"todo-1"},{"id":"todo-2"},{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = todos) },
        )

        repo.send("删掉这两个")
        val messageId = repo.messages.value.last().id
        repo.applyTodoChangesInBackground(messageId)
        awaitChangesSettled(repo, messageId)

        // 同一 id 的重复删除只保留一条确认项，且批量删除只调用一次
        assertEquals(2, repo.messages.value.last().todoChanges.size)
        assertEquals(listOf(listOf("todo-1", "todo-2")), writer.deletions)
        assertTrue(
            repo.messages.value.last().todoChanges.all {
                it.status == AiTodoChangeStatus.Applied
            },
        )
    }

    @Test
    fun `apply marks vanished target as not found`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        var context = AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告")))
        val reply = "```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"新名字"}}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { context },
        )
        repo.send("改名")
        val messageId = repo.messages.value.last().id
        // 确认前目标在别处被删除/归档：应用时按最新活跃待办反查不到
        context = AiChatContext()

        repo.applyTodoChangesInBackground(messageId)
        awaitChangesSettled(repo, messageId)

        val change = repo.messages.value.last().todoChanges.single()
        assertEquals(AiTodoChangeStatus.Failed, change.status)
        assertEquals(AiTodoChangeFailure.NotFound, change.failure)
        assertTrue(writer.updates.isEmpty())
    }

    @Test
    fun `apply marks writer failure as save failed`() = runBlocking {
        val writer = FakeTodoChangeWriter().apply { updateResult = false }
        val reply = "```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"新名字"}}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )
        repo.send("改名")
        val messageId = repo.messages.value.last().id

        repo.applyTodoChangesInBackground(messageId)
        awaitChangesSettled(repo, messageId)

        val change = repo.messages.value.last().todoChanges.single()
        assertEquals(AiTodoChangeStatus.Failed, change.status)
        assertEquals(AiTodoChangeFailure.SaveFailed, change.failure)
    }

    @Test
    fun `dismiss marks pending changes dismissed without writing`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"新名字"}}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )
        repo.send("改名")
        val messageId = repo.messages.value.last().id

        repo.dismissTodoChanges(messageId)

        assertEquals(
            AiTodoChangeStatus.Dismissed,
            repo.messages.value.last().todoChanges.single().status,
        )
        assertTrue(writer.updates.isEmpty())
    }

    @Test
    fun `updates only reply repairs malformed json once`() = runBlocking {
        val malformed = "```json\n{\"updates\":[{\"id\":\"todo-1\",\"patch\":\n```"
        val repaired = "```json\n" +
            """{"updates":[{"id":"todo-1","patch":{"name":"修复后"}}]}""" + "\n```"
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse(malformed), "data: [DONE]")),
            ScriptedResponse(listOf(sse(repaired), "data: [DONE]")),
        )
        val repo = repository(
            transport = transport,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("改名")

        // 主请求 + 一次修复重试
        assertEquals(2, transport.opened.size)
        val change = repo.messages.value.last().todoChanges.single()
        assertEquals("修复后", change.patch?.name)
        assertFalse(repo.messages.value.last().todoSaveFailed)
    }

    // ---------- 完成 / 取消完成（直接生效，可撤销） ----------

    private fun repeatingTodo(id: String, name: String) = Todo(
        id = id,
        name = name,
        dates = listOf(IsoDate.of(2026, 11, 2)), // 周一；测试时钟 2026-11-01
        repeat = RepeatRule(type = RepeatType.Weekly),
    )

    /** 等撤销协程收口：该条记录不再是 Applied 即视为完成 */
    private suspend fun awaitCompletionSettled(
        repo: AiChatRepository,
        messageId: String,
        index: Int,
    ) {
        withTimeout(5000) {
            while (
                repo.messages.value.firstOrNull { it.id == messageId }
                    ?.todoCompletions?.getOrNull(index)?.status == AiTodoCompletionStatus.Applied
            ) {
                delay(10)
            }
        }
    }

    @Test
    fun `completes non repeating todo immediately`() = runBlocking {
        val store = FakeAiChatStore()
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"completes":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("把实验报告标记完成")

        val last = repo.messages.value.last()
        assertFalse(last.todoSaveFailed)
        val completion = last.todoCompletions.single()
        assertEquals(AiTodoCompletionStatus.Applied, completion.status)
        assertTrue(completion.completed)
        assertNull(completion.date)
        assertEquals(
            FakeTodoChangeWriter.CompletionCall("todo-1", null, true),
            writer.completionCalls.single(),
        )
        // 结果随消息落盘
        assertEquals(1, store.persistedMessages().last().todoCompletions.size)
    }

    @Test
    fun `reopens non repeating todo immediately`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"reopens":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(
                    todos = listOf(existingTodo("todo-1", "实验报告").copy(completed = true)),
                )
            },
        )

        repo.send("取消完成实验报告")

        assertEquals(
            FakeTodoChangeWriter.CompletionCall("todo-1", null, false),
            writer.completionCalls.single(),
        )
        assertEquals(
            AiTodoCompletionStatus.Applied,
            repo.messages.value.last().todoCompletions.single().status,
        )
    }

    @Test
    fun `completes repeating occurrence with injected date`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"completes":[{"id":"repeat-1","date":"2026-11-09"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(repeatingTodo("repeat-1", "跑步"))) },
        )

        repo.send("这周一的跑步完成了")

        assertEquals(
            FakeTodoChangeWriter.CompletionCall("repeat-1", IsoDate.of(2026, 11, 9), true),
            writer.completionCalls.single(),
        )
        assertEquals(
            IsoDate.of(2026, 11, 9),
            repo.messages.value.last().todoCompletions.single().date,
        )
    }

    @Test
    fun `repeating completion without valid occurrence date fails without writing`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"completes":[{"id":"repeat-1"},{"id":"repeat-1","date":"2026-11-03"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(repeatingTodo("repeat-1", "跑步"))) },
        )

        repo.send("完成跑步")

        val completions = repo.messages.value.last().todoCompletions
        assertEquals(2, completions.size)
        assertTrue(completions.all { it.status == AiTodoCompletionStatus.Failed })
        assertTrue(completions.all { it.failure == AiTodoChangeFailure.InvalidDate })
        assertTrue(writer.completionCalls.isEmpty())
    }

    @Test
    fun `unknown completion target is recorded as failed`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"completes":[{"id":"missing"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
        )

        repo.send("完成它")

        val completion = repo.messages.value.last().todoCompletions.single()
        assertEquals(AiTodoCompletionStatus.Failed, completion.status)
        assertEquals(AiTodoChangeFailure.NotFound, completion.failure)
        assertTrue(writer.completionCalls.isEmpty())
    }

    @Test
    fun `completing an already completed todo is a no-op`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"completes":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(
                    todos = listOf(existingTodo("todo-1", "实验报告").copy(completed = true)),
                )
            },
        )

        repo.send("完成实验报告")

        val completion = repo.messages.value.last().todoCompletions.single()
        assertEquals(AiTodoCompletionStatus.Applied, completion.status)
        assertFalse(completion.changed)
        assertTrue(writer.completionCalls.isEmpty())
    }

    @Test
    fun `completing an already completed occurrence is a no-op`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" +
            """{"completes":[{"id":"repeat-1","date":"2026-11-09"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(
                    todos = listOf(
                        repeatingTodo("repeat-1", "跑步").copy(
                            completedOccurrences = listOf(IsoDate.of(2026, 11, 9)),
                        ),
                    ),
                )
            },
        )

        repo.send("完成这周一的跑步")

        val completion = repo.messages.value.last().todoCompletions.single()
        assertEquals(AiTodoCompletionStatus.Applied, completion.status)
        assertFalse(completion.changed)
        assertTrue(writer.completionCalls.isEmpty())
    }

    @Test
    fun `undo reverses applied completion`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        var context = AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告")))
        val reply = "```json\n" + """{"completes":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { context },
        )
        repo.send("完成实验报告")
        val messageId = repo.messages.value.last().id
        // 模拟完成态已落库：撤销时基于最新待办判断当前状态
        context = AiChatContext(
            todos = listOf(existingTodo("todo-1", "实验报告").copy(completed = true)),
        )

        repo.undoTodoCompletionInBackground(messageId, 0)
        awaitCompletionSettled(repo, messageId, 0)

        assertEquals(
            AiTodoCompletionStatus.Undone,
            repo.messages.value.last().todoCompletions.single().status,
        )
        assertEquals(
            listOf(
                FakeTodoChangeWriter.CompletionCall("todo-1", null, true),
                FakeTodoChangeWriter.CompletionCall("todo-1", null, false),
            ),
            writer.completionCalls,
        )
    }

    @Test
    fun `undo is a no-op when target already reversed elsewhere`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        // 上下文始终是未完成态：模拟用户在别处已手动取消完成
        val reply = "```json\n" + """{"completes":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )
        repo.send("完成实验报告")
        val messageId = repo.messages.value.last().id

        repo.undoTodoCompletionInBackground(messageId, 0)
        awaitCompletionSettled(repo, messageId, 0)

        assertEquals(
            AiTodoCompletionStatus.Undone,
            repo.messages.value.last().todoCompletions.single().status,
        )
        // 只发生一次「完成」写入；撤销发现已处于目标状态，不再写库
        assertEquals(1, writer.completionCalls.size)
        assertTrue(writer.completionCalls.single().completed)
    }

    @Test
    fun `undo failure keeps completion applied and marks undo failed`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        var context = AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告")))
        val reply = "```json\n" + """{"completes":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { context },
        )
        repo.send("完成实验报告")
        val messageId = repo.messages.value.last().id
        context = AiChatContext(
            todos = listOf(existingTodo("todo-1", "实验报告").copy(completed = true)),
        )
        writer.completionResult = false

        repo.undoTodoCompletionInBackground(messageId, 0)
        awaitCompletionSettled(repo, messageId, 0)

        val completion = repo.messages.value.last().todoCompletions.single()
        assertEquals(AiTodoCompletionStatus.UndoFailed, completion.status)
        assertEquals(AiTodoChangeFailure.SaveFailed, completion.failure)
    }

    // ---------- 归档 / 取消归档 / 恢复（直接生效，可撤销） ----------

    private fun archivedTodo(id: String, name: String) =
        existingTodo(id, name).copy(archivedAtMillis = 1L)

    private fun deletedTodo(id: String, name: String) =
        existingTodo(id, name).copy(deletedAtMillis = 1L)

    private suspend fun awaitLifecycleSettled(
        repo: AiChatRepository,
        messageId: String,
        index: Int,
    ) {
        withTimeout(5000) {
            while (
                repo.messages.value.firstOrNull { it.id == messageId }
                    ?.todoLifecycles?.getOrNull(index)?.status == AiTodoLifecycleStatus.Applied
            ) {
                delay(10)
            }
        }
    }

    @Test
    fun `archives active todo immediately`() = runBlocking {
        val store = FakeAiChatStore()
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"archives":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("把实验报告归档")

        val lifecycle = repo.messages.value.last().todoLifecycles.single()
        assertEquals(AiTodoLifecycleAction.Archive, lifecycle.action)
        assertEquals(AiTodoLifecycleStatus.Applied, lifecycle.status)
        assertEquals(listOf("todo-1"), writer.archives)
        assertEquals(1, store.persistedMessages().last().todoLifecycles.size)
    }

    @Test
    fun `archiving an already archived todo is a no-op`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"archives":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(archivedTodos = listOf(archivedTodo("todo-1", "实验报告")))
            },
        )

        repo.send("归档实验报告")

        val lifecycle = repo.messages.value.last().todoLifecycles.single()
        assertEquals(AiTodoLifecycleStatus.Applied, lifecycle.status)
        assertFalse(lifecycle.changed)
        assertTrue(writer.archives.isEmpty())
    }

    @Test
    fun `archiving a deleted todo fails with wrong state`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"archives":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(deletedTodos = listOf(deletedTodo("todo-1", "实验报告")))
            },
        )

        repo.send("归档实验报告")

        val lifecycle = repo.messages.value.last().todoLifecycles.single()
        assertEquals(AiTodoLifecycleStatus.Failed, lifecycle.status)
        assertEquals(AiTodoChangeFailure.WrongState, lifecycle.failure)
        assertTrue(writer.archives.isEmpty())
    }

    @Test
    fun `unarchives archived todo immediately`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"unarchives":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(archivedTodos = listOf(archivedTodo("todo-1", "实验报告")))
            },
        )

        repo.send("取消归档实验报告")

        assertEquals(listOf("todo-1"), writer.unarchives)
        assertEquals(
            AiTodoLifecycleStatus.Applied,
            repo.messages.value.last().todoLifecycles.single().status,
        )
    }

    @Test
    fun `restores deleted todo immediately`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"restores":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = {
                AiChatContext(deletedTodos = listOf(deletedTodo("todo-1", "实验报告")))
            },
        )

        repo.send("恢复实验报告")

        assertEquals(listOf("todo-1"), writer.restores)
        assertEquals(
            AiTodoLifecycleStatus.Applied,
            repo.messages.value.last().todoLifecycles.single().status,
        )
    }

    @Test
    fun `restoring a todo already out of trash is a no-op`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"restores":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("恢复实验报告")

        val lifecycle = repo.messages.value.last().todoLifecycles.single()
        assertFalse(lifecycle.changed)
        assertTrue(writer.restores.isEmpty())
    }

    @Test
    fun `unknown lifecycle target fails`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val reply = "```json\n" + """{"unarchives":[{"id":"missing"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
        )

        repo.send("取消归档它")

        val lifecycle = repo.messages.value.last().todoLifecycles.single()
        assertEquals(AiTodoLifecycleStatus.Failed, lifecycle.status)
        assertEquals(AiTodoChangeFailure.NotFound, lifecycle.failure)
        assertTrue(writer.unarchives.isEmpty())
    }

    @Test
    fun `undo archive calls unarchive`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        var context = AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告")))
        val reply = "```json\n" + """{"archives":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { context },
        )
        repo.send("归档实验报告")
        val messageId = repo.messages.value.last().id
        // 模拟归档已落库
        context = AiChatContext(archivedTodos = listOf(archivedTodo("todo-1", "实验报告")))

        repo.undoTodoLifecycleInBackground(messageId, 0)
        awaitLifecycleSettled(repo, messageId, 0)

        assertEquals(listOf("todo-1"), writer.unarchives)
        assertEquals(
            AiTodoLifecycleStatus.Undone,
            repo.messages.value.last().todoLifecycles.single().status,
        )
    }

    @Test
    fun `undo restore deletes the todo again`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        var context = AiChatContext(deletedTodos = listOf(deletedTodo("todo-1", "实验报告")))
        val reply = "```json\n" + """{"restores":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { context },
        )
        repo.send("恢复实验报告")
        val messageId = repo.messages.value.last().id
        // 模拟恢复已落库
        context = AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告")))

        repo.undoTodoLifecycleInBackground(messageId, 0)
        awaitLifecycleSettled(repo, messageId, 0)

        assertEquals(listOf(listOf("todo-1")), writer.deletions)
        assertEquals(
            AiTodoLifecycleStatus.Undone,
            repo.messages.value.last().todoLifecycles.single().status,
        )
    }

    @Test
    fun `undo lifecycle failure marks undo failed`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        var context = AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告")))
        val reply = "```json\n" + """{"archives":[{"id":"todo-1"}]}""" + "\n```"
        val repo = repository(
            transport = FakeAiTransport(ScriptedResponse(listOf(sse(reply), "data: [DONE]"))),
            changeWriter = writer,
            contextProvider = { context },
        )
        repo.send("归档实验报告")
        val messageId = repo.messages.value.last().id
        context = AiChatContext(archivedTodos = listOf(archivedTodo("todo-1", "实验报告")))
        writer.lifecycleResult = false

        repo.undoTodoLifecycleInBackground(messageId, 0)
        awaitLifecycleSettled(repo, messageId, 0)

        val lifecycle = repo.messages.value.last().todoLifecycles.single()
        assertEquals(AiTodoLifecycleStatus.UndoFailed, lifecycle.status)
        assertEquals(AiTodoChangeFailure.SaveFailed, lifecycle.failure)
    }

    // ---------- 原生只读工具与多轮循环 ----------

    @Test
    fun `send executes read tool then streams final answer`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.GET_DAY_PLAN,
                        arguments = """{"date":"2026-11-01"}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(listOf(sse("那天下午有空。"), "data: [DONE]")),
        )
        val context = AiChatContext(schedule = testSchedule(), currentWeek = 12)
        val repo = repository(
            settings = settings,
            transport = transport,
            contextProvider = { context },
        )

        repo.send("我 11 月 1 日下午有空吗")

        assertEquals(2, transport.opened.size)
        assertTrue(requestHasTools(transport.opened[0].jsonBody))
        assertTrue(requestHasTools(transport.opened[1].jsonBody))
        // 第二次请求必须把 assistant.tool_calls 与 tool 结果成对带回去
        val paired = toolMessagesOf(transport.opened[1].jsonBody)
        assertEquals(2, paired.size)
        assertEquals("assistant", paired[0].getValue("role").jsonPrimitive.content)
        assertNotNull(paired[0]["tool_calls"])
        assertEquals("tool", paired[1].getValue("role").jsonPrimitive.content)
        assertEquals("call_1", paired[1].getValue("tool_call_id").jsonPrimitive.content)

        val message = repo.messages.value.last()
        assertEquals("那天下午有空。", message.content)
        assertFalse(message.interrupted)
        assertEquals(1, message.toolRounds.size)
        val round = message.toolRounds.single()
        assertEquals(AiToolRoundStatus.Completed, round.status)
        assertEquals(1, round.results.size)
        assertEquals("get_day_plan", round.results.single().name)
        assertFalse(round.results.single().isError)
        assertTrue(round.results.single().contentJson.contains("\"ok\":true"))
        assertEquals(
            AiToolCapability.Supported,
            settings.toolCapabilities[settings.connection.toolCapabilityKey()],
        )
    }

    @Test
    fun `send caps tool rounds and keeps partial text`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val toolRound = listOf(
            toolCallSse(
                index = 0,
                id = "call_1",
                name = AiToolRegistry.SEARCH_TODOS,
                arguments = "{}",
            ),
            finishSse("tool_calls"),
            "data: [DONE]",
        )
        val transport = FakeAiTransport(
            ScriptedResponse(toolRound),
            ScriptedResponse(toolRound),
            ScriptedResponse(toolRound),
            ScriptedResponse(toolRound),
        )
        val repo = repository(settings = settings, transport = transport)

        repo.send("一直查")

        // 第 4 次请求仍有 tool_calls：预算停止，不再发第 5 次
        assertEquals(AiChatRepository.MAX_TOOL_ROUNDS, transport.opened.size)
        val message = repo.messages.value.last()
        assertTrue(message.toolLoopCapped)
        assertEquals(AiChatRepository.MAX_TOOL_ROUNDS, message.toolRounds.size)
        val lastRound = message.toolRounds.last()
        assertEquals(AiToolRoundStatus.Failed, lastRound.status)
        assertTrue(lastRound.results.single().isError)
        assertTrue(lastRound.results.single().contentJson.contains("limit_exceeded"))
    }

    @Test
    fun `send limits calls per round and still continues`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val calls = (0..4).flatMap { index ->
            listOf(
                toolCallSse(
                    index = index,
                    id = "call_$index",
                    name = AiToolRegistry.SEARCH_TODOS,
                    arguments = "{}",
                ),
            )
        } + listOf(finishSse("tool_calls"), "data: [DONE]")
        val transport = FakeAiTransport(
            ScriptedResponse(calls),
            ScriptedResponse(listOf(sse("查完了。"), "data: [DONE]")),
        )
        val repo = repository(settings = settings, transport = transport)

        repo.send("查五条")

        assertEquals(2, transport.opened.size)
        val round = repo.messages.value.last().toolRounds.single()
        assertEquals(5, round.calls.size)
        assertEquals(5, round.results.size)
        assertEquals(4, round.results.count { !it.isError })
        assertEquals(1, round.results.count { it.contentJson.contains("limit_exceeded") })
    }

    @Test
    fun `send falls back when provider rejects tools`() = runBlocking {
        val settings = FakeAiSettingsStore()
        val transport = FakeAiTransport(
            ScriptedResponse(lines = emptyList(), status = 400),
            ScriptedResponse(listOf(sse("回退回答"), "data: [DONE]")),
        )
        val context = AiChatContext()
        val repo = repository(
            settings = settings,
            transport = transport,
            contextProvider = { context },
        )

        repo.send("你好")

        assertEquals(2, transport.opened.size)
        assertTrue(requestHasTools(transport.opened[0].jsonBody))
        assertFalse(requestHasTools(transport.opened[1].jsonBody))
        assertEquals(
            AiToolCapability.Unsupported,
            settings.toolCapabilities[settings.connection.toolCapabilityKey()],
        )
        val message = repo.messages.value.last()
        assertEquals("回退回答", message.content)
        assertTrue(message.toolRounds.isEmpty())
    }

    @Test
    fun `tools disabled skips native wire and prompt`() = runBlocking {
        val settings = FakeAiSettingsStore().apply { toolsEnabled = false }
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse("普通回答"), "data: [DONE]")),
        )
        val repo = repository(settings = settings, transport = transport)

        repo.send("你好")

        val request = transport.opened.single()
        assertFalse(requestHasTools(request.jsonBody))
        assertFalse(request.jsonBody.contains("已启用只读查询工具"))
        assertEquals("普通回答", repo.messages.value.last().content)
        assertTrue(repo.messages.value.last().toolRounds.isEmpty())
    }

    @Test
    fun `native prompt carries today summary and trace records the turn`() = runBlocking {
        AiDebugTrace.init(debuggable = true)
        AiDebugTrace.reset()
        try {
            var tick = 0L
            val transport = FakeAiTransport(
                ScriptedResponse(
                    listOf(
                        toolCallSse(
                            index = 0,
                            id = "call_1",
                            name = AiToolRegistry.GET_TODO,
                            arguments = """{"id":"todo-today"}""",
                        ),
                        finishSse("tool_calls"),
                        "data: [DONE]",
                    ),
                ),
                ScriptedResponse(listOf(sse("看完了。"), "data: [DONE]")),
            )
            val repo = repository(
                transport = transport,
                contextProvider = {
                    AiChatContext(
                        todos = listOf(
                            existingTodo("todo-today", "今天的作业")
                                .copy(dates = listOf(IsoDate.of(2026, 11, 1))),
                            existingTodo("todo-far", "很远的事")
                                .copy(dates = listOf(IsoDate.of(2026, 12, 20))),
                            existingTodo("todo-archived", "归档的事").copy(archivedAtMillis = 1L),
                        ),
                    )
                },
                nowMillis = {
                    tick += 100
                    tick
                },
            )

            repo.send("今天的作业还有什么")

            // P3：上下文只给今日摘要，其余待办（含归档）不再整段注入
            val system = turnsOf(transport.opened.first().jsonBody).first().second
            assertTrue(system.contains("[今日课程]"))
            assertTrue(system.contains("[todo-today] 今天的作业"))
            assertTrue(system.contains("[待办概况]"))
            assertFalse(system.contains("很远的事"))
            assertFalse(system.contains("归档的事"))

            // P3：一轮的规模/延迟/工具调用进入诊断缓冲（仅 debug）
            val trace = AiDebugTrace.recent().single()
            assertTrue(trace.toolsAttached)
            assertTrue(trace.promptChars > 0)
            assertTrue(trace.promptTokens > 0)
            assertEquals(2, trace.requestCount)
            assertEquals(1, trace.toolCallCount)
            assertEquals(AiToolRegistry.GET_TODO, trace.calls.single().name)
            assertTrue(trace.calls.single().ok)
            assertNotNull(trace.firstDeltaMillis)
            assertNull(trace.error)
        } finally {
            AiDebugTrace.reset()
            AiDebugTrace.init(debuggable = false)
        }
    }

    @Test
    fun `history replays recent tool pairing exactly`() = runBlocking {
        val call = AiToolCall(
            id = "call_1",
            name = AiToolRegistry.GET_DAY_PLAN,
            argumentsJson = """{"date":"2026-11-01"}""",
        )
        val result = AiToolResult(
            callId = "call_1",
            name = AiToolRegistry.GET_DAY_PLAN,
            contentJson = """{"ok":true,"data":{}}""",
        )
        val store = FakeAiChatStore()
        store.file = AiChatFile(
            conversations = listOf(
                AiConversation(
                    id = "c1",
                    title = "旧对话",
                    messages = listOf(
                        AiChatMessage(role = AiChatRole.User, content = "查一下"),
                        AiChatMessage(
                            role = AiChatRole.Assistant,
                            content = "有空。",
                            id = "m1",
                            toolRounds = listOf(
                                AiToolRound(
                                    text = "",
                                    calls = listOf(call),
                                    results = listOf(result),
                                    status = AiToolRoundStatus.Completed,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            activeConversationId = "c1",
        )
        val transport = FakeAiTransport(
            ScriptedResponse(listOf(sse("继续回答"), "data: [DONE]")),
        )
        val repo = repository(store = store, transport = transport)

        repo.send("继续")

        val paired = toolMessagesOf(transport.opened.single().jsonBody)
        assertEquals(2, paired.size)
        assertEquals("assistant", paired[0].getValue("role").jsonPrimitive.content)
        assertEquals(
            "call_1",
            paired[0].getValue("tool_calls").jsonArray.single().jsonObject
                .getValue("id").jsonPrimitive.content,
        )
        assertEquals("tool", paired[1].getValue("role").jsonPrimitive.content)
        assertEquals("call_1", paired[1].getValue("tool_call_id").jsonPrimitive.content)
    }

    @Test
    fun `bootstrap normalizes unfinished tool rounds to cancelled`() = runBlocking {
        val store = FakeAiChatStore()
        store.file = AiChatFile(
            conversations = listOf(
                AiConversation(
                    id = "c1",
                    messages = listOf(
                        AiChatMessage(
                            role = AiChatRole.Assistant,
                            content = "半截",
                            id = "m1",
                            toolRounds = listOf(
                                AiToolRound(
                                    calls = listOf(AiToolCall("c1", AiToolRegistry.GET_DAY_PLAN)),
                                    status = AiToolRoundStatus.Running,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            activeConversationId = "c1",
        )
        val repo = repository(
            store = store,
            transport = FakeAiTransport(ScriptedResponse(listOf("data: [DONE]"))),
        )

        assertEquals(
            AiToolRoundStatus.Cancelled,
            repo.messages.value.single().toolRounds.single().status,
        )
    }

    // ---------- 原生写工具与确认流（P2） ----------

    @Test
    fun `create tool writes todos and attaches ids`() = runBlocking {
        val persister = FakeTodoPersister()
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.CREATE_TODOS,
                        arguments = """{"todos":[{"name":"交实验报告","dates":["2026-11-18"],""" +
                            """"urgency":"High"}]}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(listOf(sse("已创建，记得按时交。"), "data: [DONE]")),
        )
        val repo = repository(transport = transport, persister = persister)

        repo.send("11 月 18 日交实验报告，很急")

        assertEquals(1, persister.batches.size)
        assertEquals("交实验报告", persister.batches.single().single().name)
        val message = repo.messages.value.last()
        assertEquals(1, message.todoIds.size)
        assertFalse(message.todoSaveFailed)
        assertEquals("已创建，记得按时交。", message.content)
        val round = message.toolRounds.single()
        assertEquals(AiToolRoundStatus.Completed, round.status)
        val result = round.results.single()
        assertFalse(result.isError)
        assertTrue(result.contentJson.contains("\"ok\":true"))
        assertTrue(result.contentJson.contains("可在消息下方撤销"))
        // 写工具已执行，正文不再解析出任何待确认变更
        assertTrue(message.todoChanges.isEmpty())
    }

    @Test
    fun `complete tool applies completion and records undo entry`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.COMPLETE_TODOS,
                        arguments = """{"completes":[{"id":"todo-1"}]}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(listOf(sse("已标记完成。"), "data: [DONE]")),
        )
        val repo = repository(
            transport = transport,
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("实验报告交了")

        assertEquals(listOf("todo-1"), writer.completionCalls.map { it.id })
        assertTrue(writer.completionCalls.single().completed)
        val completion = repo.messages.value.last().todoCompletions.single()
        assertEquals(AiTodoCompletionStatus.Applied, completion.status)
        assertEquals("实验报告", completion.todoName)
        assertTrue(repo.messages.value.last().toolRounds.single().results.single().contentJson.contains("已生效"))
    }

    @Test
    fun `update tool only records pending change`() = runBlocking {
        val writer = FakeTodoChangeWriter()
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.UPDATE_TODOS,
                        arguments = """{"updates":[{"id":"todo-1","patch":{"name":"新实验报告"}}]}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(listOf(sse("已生成确认卡。"), "data: [DONE]")),
        )
        val repo = repository(
            transport = transport,
            changeWriter = writer,
            contextProvider = { AiChatContext(todos = listOf(existingTodo("todo-1", "实验报告"))) },
        )

        repo.send("把实验报告改名为新实验报告")

        // 确认前不写库：写入只由确认卡的 changeWriter 完成
        assertTrue(writer.updates.isEmpty())
        val change = repo.messages.value.last().todoChanges.single()
        assertEquals(AiTodoChangeStatus.Pending, change.status)
        assertEquals("新实验报告", change.patch?.name)
        val result = repo.messages.value.last().toolRounds.single().results.single()
        assertFalse(result.isError)
        assertTrue(result.contentJson.contains("pending_user_confirmation"))
    }

    @Test
    fun `delete tool with unknown id returns failure result`() = runBlocking {
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.DELETE_TODOS,
                        arguments = """{"deletes":[{"id":"missing"}]}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(listOf(sse("没找到这条待办。"), "data: [DONE]")),
        )
        val repo = repository(transport = transport)

        repo.send("删掉没有的那条")

        val message = repo.messages.value.last()
        assertTrue(message.todoChanges.isEmpty())
        val result = message.toolRounds.single().results.single()
        assertTrue(result.isError)
        assertTrue(result.contentJson.contains("not_found"))
    }

    @Test
    fun `retry after native write reminds model not to repeat`() = runBlocking {
        val persister = FakeTodoPersister()
        val transport = FakeAiTransport(
            // 第一轮工具成功落库，随后最终回答的流断开：尾部消息 interrupted
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.CREATE_TODOS,
                        arguments = """{"todos":[{"name":"工具创建"}]}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(listOf(sse("半截")), failAfterLines = true),
            ScriptedResponse(listOf(sse("完整回答"), "data: [DONE]")),
        )
        val repo = repository(transport = transport, persister = persister)

        repo.send("记一下")
        assertTrue(repo.messages.value.last().interrupted)
        assertEquals(1, persister.batches.size)

        repo.retryLast()

        // 工具轮次随消息被摘掉：靠一句提醒挡住重复创建（脚本本身不会再调工具）
        assertEquals(1, persister.batches.size)
        assertEquals("完整回答", repo.messages.value.last().content)
        assertTrue(transport.opened[2].jsonBody.contains("不要重复创建、完成或归档"))
    }

    @Test
    fun `native tool round ignores body json blocks`() = runBlocking {        val persister = FakeTodoPersister()
        val transport = FakeAiTransport(
            ScriptedResponse(
                listOf(
                    toolCallSse(
                        index = 0,
                        id = "call_1",
                        name = AiToolRegistry.CREATE_TODOS,
                        arguments = """{"todos":[{"name":"工具创建"}]}""",
                    ),
                    finishSse("tool_calls"),
                    "data: [DONE]",
                ),
            ),
            ScriptedResponse(
                listOf(
                    sse(
                        "已创建。\n```json\n" +
                            """{"todos":[{"name":"正文创建","dates":["2026-11-18"]}]}""" +
                            "\n```",
                    ),
                    "data: [DONE]",
                ),
            ),
        )
        val repo = repository(transport = transport, persister = persister)

        repo.send("记一下")

        // 原生模式只认工具：正文里的 JSON 块不再执行，避免同一动作落库两次
        assertEquals(1, persister.batches.size)
        assertEquals("工具创建", persister.batches.single().single().name)
        assertEquals(1, repo.messages.value.last().todoIds.size)
    }
}
