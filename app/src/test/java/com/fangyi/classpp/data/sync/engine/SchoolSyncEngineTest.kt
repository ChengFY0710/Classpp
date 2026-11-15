package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.sync.CommitOutcome
import com.fangyi.classpp.data.sync.MutableSyncPreferences
import com.fangyi.classpp.data.sync.PrepareOutcome
import com.fangyi.classpp.data.sync.SyncError
import com.fangyi.classpp.data.sync.SyncPlan
import com.fangyi.classpp.data.sync.SyncPreferences
import com.fangyi.classpp.data.sync.media.AvatarCache
import com.fangyi.classpp.data.sync.media.AvatarFetcher
import java.io.File
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 通用引擎的补充单测（跨实例 plan 认领、落盘失败、头像、错误分类）：不变量契约测试
 * 覆盖两套实现共同的行为，这里只覆盖引擎特有的边界。
 */
class SchoolSyncEngineTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var nowMillis: Long = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear()
        set(2026, 10, 1, 12, 0, 0)
    }.timeInMillis

    private val remotes = listOf(
        FakeRemoteTodo(
            id = 1,
            courseId = 10,
            courseName = "课程A",
            title = "作业一",
            endTime = "2026-11-05T15:59:00Z",
        ),
        FakeRemoteTodo(
            id = 2,
            courseId = 10,
            courseName = "课程A",
            title = "作业二",
            endTime = "2026-11-06T15:59:00Z",
        ),
    )

    private class FailingStore(
        dir: File,
        private val failOn: (Int) -> Boolean,
    ) : SchoolSyncStore(dir, "fakeu") {
        private var saves = 0

        override fun save(file: SchoolSyncFile): StoreResult {
            saves++
            return if (failOn(saves)) StoreResult.Failed("disk full") else super.save(file)
        }
    }

    private class FakeAvatarFetcher(
        private val bytes: ByteArray? = byteArrayOf(1, 2, 3),
    ) : AvatarFetcher {
        var calls = 0
            private set

        override suspend fun fetch(url: String): ByteArray? {
            calls++
            return bytes
        }
    }

    private class FakeNoteEnricher(
        var notes: Map<String, String> = emptyMap(),
        var failure: Exception? = null,
    ) : SyncNoteEnricher<FakeRemoteTodo> {
        var calls = 0
            private set
        var lastRemotes: List<FakeRemoteTodo> = emptyList()
            private set

        override suspend fun notesFor(remotes: List<FakeRemoteTodo>): Map<String, String> {
            calls++
            lastRemotes = remotes
            failure?.let { throw it }
            return notes
        }
    }

    private suspend fun repo(): TodoRepository =
        TodoRepository.create(FileTodoStore(folder.root), { nowMillis })

    private suspend fun engine(
        api: SessionApi,
        todoRepository: TodoRepository? = null,
        store: SchoolSyncStore = SchoolSyncStore(folder.root, "fakeu"),
        avatar: AvatarCache? = null,
        noteEnricher: SyncNoteEnricher<FakeRemoteTodo>? = null,
        preferences: SyncPreferences = MutableSyncPreferences(),
    ): SchoolSyncEngine<FakeRemoteTodo> = SchoolSyncEngine(
        spec = fakeSchoolSpec(),
        codec = FakeSyncCodec(source = "fakeu") { nowMillis },
        store = store,
        todoRepository = todoRepository ?: repo(),
        api = api,
        groupNames = SchoolAutoGroupNames(source = "fakeu"),
        clock = { nowMillis },
        avatar = avatar,
        syncPreferences = preferences,
        noteEnricher = noteEnricher,
    )

    @Test
    fun `commit rejects a plan produced by another engine`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val first = engine(api)
        val second = engine(api)
        val plan = (first.prepareSync(force = true) as PrepareOutcome.Ready).plan

        val failure = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { second.commitSync(plan, emptySet()) }
        }
        assertTrue(failure.message.orEmpty().contains("another school session"))
    }

    @Test
    fun `prepare returns PersistFailed when connection state cannot be saved`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val engine = engine(api, store = FailingStore(folder.root) { it == 1 })

        val outcome = engine.prepareSync(force = true)

        assertTrue(outcome is PrepareOutcome.Failed)
        assertEquals(SyncError.PersistFailed, (outcome as PrepareOutcome.Failed).error)
    }

    @Test
    fun `commit reports PersistFailed after importing todos`() = runBlocking {
        // 第 1 次 save 是 prepare 的连接状态；第 2 次是 commit 的映射
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val todoRepository = repo()
        val engine = engine(
            api,
            todoRepository = todoRepository,
            store = FailingStore(folder.root) { it == 2 },
        )
        val plan = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan

        val outcome = engine.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id })

        assertTrue(outcome is CommitOutcome.Failed)
        assertTrue((outcome as CommitOutcome.Failed).error is TodoError.PersistFailed)
        // 待办已入库：下次同步按 entriesForActive 补写映射
        assertEquals(2, todoRepository.todos.value.size)
    }

    @Test
    fun `markSynced returns false when mapping cannot be saved`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val engine = engine(api, store = FailingStore(folder.root) { it == 2 })
        val plan = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan

        assertFalse(engine.markSynced(plan))
    }

    @Test
    fun `avatar is downloaded once cached and cleared on reset`() = runBlocking {
        val fetcher = FakeAvatarFetcher()
        val avatar = AvatarCache(File(folder.root, "sync/fakeu"), fetcher)
        val api = FakeSessionApi().apply {
            respond(avatarUrl = "https://cdn.test/a.png", todos = remotes)
        }
        val engine = engine(api, avatar = avatar)

        engine.prepareSync(force = true)
        assertEquals(1, fetcher.calls)
        assertEquals("https://cdn.test/a.png", engine.state.value.avatarUrl)
        assertNotNull(engine.avatarImage.value)

        // 地址没变且文件在：不重复下载
        nowMillis += 10_000
        engine.prepareSync(force = true)
        assertEquals(1, fetcher.calls)

        engine.resetSyncData(deleteSyncedTodos = false)
        assertNull(engine.avatarImage.value)
        assertEquals("", engine.state.value.avatarUrl)
    }

    @Test
    fun `avatar download failure keeps previous url and does not fail sync`() = runBlocking {
        val avatar = AvatarCache(
            File(folder.root, "sync/fakeu"),
            FakeAvatarFetcher(bytes = null),
        )
        val api = FakeSessionApi().apply {
            respond(avatarUrl = "https://cdn.test/a.png", todos = remotes)
        }
        val engine = engine(api, avatar = avatar)

        assertTrue(engine.prepareSync(force = true) is PrepareOutcome.Ready)
        assertEquals("", engine.state.value.avatarUrl)
        assertNull(engine.avatarImage.value)
    }

    @Test
    fun `codec source must equal school id`() {
        runBlocking {
            val todoRepository = repo()
            assertThrows(IllegalArgumentException::class.java) {
                SchoolSyncEngine(
                    spec = fakeSchoolSpec("fakeu"),
                    codec = FakeSyncCodec(source = "other"),
                    store = SchoolSyncStore(folder.root, "fakeu"),
                    todoRepository = todoRepository,
                    api = FakeSessionApi(),
                    groupNames = SchoolAutoGroupNames(source = "fakeu"),
                )
            }
        }
    }

    @Test
    fun `auth page html is session expired and broken json is format changed`() = runBlocking {
        val api = FakeSessionApi()
        val engine = engine(api)

        api.outcome = com.fangyi.classpp.data.sync.engine.FetchOutcome.Ok(
            com.fangyi.classpp.data.sync.LoginRaw("<html>login</html>", ""),
        )
        val html = engine.prepareSync(force = true)
        assertEquals(SyncError.SessionExpired, (html as PrepareOutcome.Failed).error)

        api.outcome = com.fangyi.classpp.data.sync.engine.FetchOutcome.Ok(
            com.fangyi.classpp.data.sync.LoginRaw("""{"unexpected":true}""", ""),
        )
        val json = engine.prepareSync(force = true)
        assertEquals(SyncError.FormatChanged, (json as PrepareOutcome.Failed).error)
    }

    @Test
    fun `plan from another engine cannot be marked synced`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val first = engine(api)
        val second = engine(api)
        val plan: SyncPlan = (first.prepareSync(force = true) as PrepareOutcome.Ready).plan

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { second.markSynced(plan) }
        }
        Unit
    }

    @Test
    fun `new items use enriched notes and entry hashes the enriched content`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val repository = repo()
        val enricher = FakeNoteEnricher(
            notes = mapOf(
                "fakeu:10:1" to "REQ-1",
                "fakeu:10:2" to "REQ-2",
            ),
        )
        val engine = engine(api, todoRepository = repository, noteEnricher = enricher)

        val plan = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan

        assertEquals(listOf("REQ-1", "REQ-2"), plan.newItems.map { it.todo.note })
        assertEquals(1, enricher.calls)
        assertTrue(engine.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id }) is CommitOutcome.Done)
        assertEquals("REQ-1", repository.todos.value.first { it.id == "fakeu-10-1" }.note)
        assertEquals("REQ-2", repository.todos.value.first { it.id == "fakeu-10-2" }.note)

        val entry = engine.fileState.value.entries.first { it.remoteKey == "fakeu:10:1" }
        val enrichedHash = localHash("作业一", "2026-11-05", 1439, "REQ-1")
        assertEquals(enrichedHash, entry.localHash)
        assertNotEquals(localHash("作业一", "2026-11-05", 1439, "课程：课程A\n类型：homework"), entry.localHash)
    }

    @Test
    fun `existing notes are refreshed on mark synced without new items`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val repository = repo()
        val enricher = FakeNoteEnricher()
        val engine = engine(api, todoRepository = repository, noteEnricher = enricher)
        val first = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        engine.commitSync(first, first.newItems.mapTo(mutableSetOf()) { it.todo.id })
        assertEquals(
            "课程：课程A\n类型：homework",
            repository.todos.value.first { it.id == "fakeu-10-1" }.note,
        )

        enricher.notes = mapOf("fakeu:10:1" to "REQ-1")
        val second = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertTrue(second.newItems.isEmpty())
        assertTrue(engine.markSynced(second))

        assertEquals("REQ-1", repository.todos.value.first { it.id == "fakeu-10-1" }.note)
        assertEquals(
            "课程：课程A\n类型：homework",
            repository.todos.value.first { it.id == "fakeu-10-2" }.note,
        )
        val entry = engine.fileState.value.entries.first { it.remoteKey == "fakeu:10:1" }
        assertEquals(localHash("作业一", "2026-11-05", 1439, "REQ-1"), entry.localHash)
    }

    @Test
    fun `commit refreshes existing notes while importing new items`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes.take(1)) }
        val repository = repo()
        val enricher = FakeNoteEnricher()
        val engine = engine(api, todoRepository = repository, noteEnricher = enricher)
        val first = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        engine.commitSync(first, first.newItems.mapTo(mutableSetOf()) { it.todo.id })

        enricher.notes = mapOf(
            "fakeu:10:1" to "REQ-1",
            "fakeu:10:2" to "REQ-2",
        )
        api.respond(todos = remotes)
        val second = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertEquals(1, second.newItems.size)

        assertTrue(engine.commitSync(second, setOf("fakeu-10-2")) is CommitOutcome.Done)
        assertEquals("REQ-1", repository.todos.value.first { it.id == "fakeu-10-1" }.note)
        assertEquals("REQ-2", repository.todos.value.first { it.id == "fakeu-10-2" }.note)
    }

    @Test
    fun `disabled preference skips enrichment entirely`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val enricher = FakeNoteEnricher(notes = mapOf("fakeu:10:1" to "REQ-1"))
        val engine = engine(
            api,
            noteEnricher = enricher,
            preferences = MutableSyncPreferences(initialSyncHomeworkNotes = false),
        )

        val plan = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan

        assertEquals(0, enricher.calls)
        assertEquals("课程：课程A\n类型：homework", plan.newItems.first().todo.note)
    }

    @Test
    fun `enricher failure keeps base notes and does not fail import`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes) }
        val repository = repo()
        val enricher = FakeNoteEnricher(failure = IllegalStateException("boom"))
        val engine = engine(api, todoRepository = repository, noteEnricher = enricher)

        val plan = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertEquals("课程：课程A\n类型：homework", plan.newItems.first().todo.note)
        assertTrue(engine.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id }) is CommitOutcome.Done)
        assertEquals("课程：课程A\n类型：homework", repository.todos.value.first().note)
    }

    @Test
    fun `restored item receives refreshed note on commit`() = runBlocking {
        val api = FakeSessionApi().apply { respond(todos = remotes.take(1)) }
        val repository = repo()
        val enricher = FakeNoteEnricher()
        val engine = engine(api, todoRepository = repository, noteEnricher = enricher)
        val first = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        engine.commitSync(first, first.newItems.mapTo(mutableSetOf()) { it.todo.id })
        assertTrue(repository.removeTodo("fakeu-10-1") is com.fangyi.classpp.data.TodoOpResult.Ok)

        enricher.notes = mapOf("fakeu:10:1" to "REQ-1")
        val second = (engine.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertEquals(1, second.restoreCount)

        assertTrue(engine.commitSync(second, emptySet()) is CommitOutcome.Done)
        val restored = repository.todos.value.first { it.id == "fakeu-10-1" }
        assertEquals("REQ-1", restored.note)
        assertNull(restored.deletedAtMillis)
    }

    /** 与引擎 localHash 同口径：name|deadlineDate|deadlineMinute|note 的 SHA-256 */
    private fun localHash(name: String, date: String, minute: Int, note: String): String {
        val text = listOf(name, date, minute.toString(), note).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
