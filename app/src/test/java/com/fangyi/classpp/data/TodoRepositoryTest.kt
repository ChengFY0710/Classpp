package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoSharePayload
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.store.TodoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TodoRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = FakeNow()

    private suspend fun repo(
        store: TodoStore = com.fangyi.classpp.data.store.FileTodoStore(folder.root),
    ) = TodoRepository.create(store, now)

    private suspend fun TodoReadResult<String>.okId(): String {
        assertTrue("expected Ok, got ${(this as? TodoReadResult.Err)?.error?.message}", this is TodoReadResult.Ok)
        return (this as TodoReadResult.Ok).value
    }

    private suspend fun TodoReadResult<Int>.okCount(): Int {
        assertTrue("expected Ok, got ${(this as? TodoReadResult.Err)?.error?.message}", this is TodoReadResult.Ok)
        return (this as TodoReadResult.Ok).value
    }

    private suspend fun TodoReadResult<String>.okText(): String {
        assertTrue("expected Ok, got ${(this as? TodoReadResult.Err)?.error?.message}", this is TodoReadResult.Ok)
        return (this as TodoReadResult.Ok).value
    }

    private suspend fun TodoOpResult.assertOk(): TodoOpResult {
        assertTrue("expected Ok, got ${(this as? TodoOpResult.Err)?.error?.message}", this is TodoOpResult.Ok)
        return this
    }

    private suspend fun TodoOpResult.assertErr(): TodoError {
        assertTrue("expected Err, got $this", this is TodoOpResult.Err)
        return (this as TodoOpResult.Err).error
    }

    // ---------- 新增 / 持久化 ----------

    @Test
    fun `addTodo generates id and createdAt then survives reopen`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "高数作业")).okId()

        assertTrue(id.isNotBlank())
        assertEquals(1_000_000L, r.todos.value.single { it.id == id }.createdAtMillis)

        val reopened = repo()
        assertEquals(listOf(id), reopened.todos.value.map { it.id })
        assertEquals(LoadState.Ready, reopened.loadState.value)
    }

    @Test
    fun `addTodo rejects blank name and leaves memory untouched`() = runBlocking {
        val r = repo()
        val err = r.addTodo(testTodo(name = "   ")).readErr()
        assertEquals(TodoError.BlankName, err)
        assertTrue(r.todos.value.isEmpty())
    }

    @Test
    fun `addTodo rejects explicit id collision`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo()).okId()
        assertTrue(r.addTodo(testTodo(id = id)).readErr() is TodoError.DuplicateId)
    }

    @Test
    fun `addTodo normalizes dates tags name and completion invariant`() = runBlocking {
        val r = repo()
        val d1 = IsoDate.parse("2026-06-20")
        val d2 = IsoDate.parse("2026-06-18")
        val id = r.addTodo(
            testTodo(
                name = "  高数作业  ",
                dates = listOf(d1, d2, d2),
                timeKind = TodoTimeKind.AllDay,
                tags = listOf("  课程 ", "课程", "", "作业"),
            ),
        ).okId()

        val saved = r.todos.value.single { it.id == id }
        assertEquals("高数作业", saved.name)
        assertEquals(listOf(d2, d1), saved.dates) // distinct + 升序
        assertEquals(listOf("课程", "作业"), saved.tags) // trim 去空去重
        assertEquals(false, saved.completed)
        assertEquals(null, saved.completedAtMillis)
    }

    @Test
    fun `addTodo marks completed instantly with completedAt`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "交表", completed = true)).okId()
        assertEquals(1_000_000L, r.todos.value.single { it.id == id }.completedAtMillis)
    }

    // ---------- 更新 / 删除 ----------

    @Test
    fun `updateTodo replaces by id and rejects unknown`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "旧名")).okId()

        r.updateTodo(testTodo(id = id, name = "新名", note = "备注")).assertOk()
        assertEquals("新名", r.todos.value.single().name)

        assertTrue(r.updateTodo(testTodo(id = "ghost", name = "无主")).assertErr() is TodoError.NotFound)
    }

    @Test
    fun `removeTodo and batch removeTodos`() = runBlocking {
        val r = repo()
        val a = r.addTodo(testTodo(name = "A")).okId()
        val b = r.addTodo(testTodo(name = "B")).okId()

        r.removeTodos(listOf(a, "不存在")).assertOk() // 未知 id 宽容忽略
        assertEquals(listOf("B"), r.todos.value.map { it.name })

        r.removeTodo(b).assertOk()
        assertTrue(r.todos.value.isEmpty())
        assertTrue(r.removeTodo(b).assertErr() is TodoError.NotFound)
    }

    // ---------- 完成状态 / 步骤 ----------

    @Test
    fun `setCompleted maintains completedAtMillis both ways`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "A")).okId()

        now.advanceBy(100)
        r.setCompleted(id, completed = true).assertOk()
        assertEquals(1_000_100L, r.todos.value.single().completedAtMillis)

        now.advanceBy(100)
        r.setCompleted(id, completed = false).assertOk()
        assertEquals(null, r.todos.value.single().completedAtMillis)
        assertEquals(false, r.todos.value.single().completed)
    }

    @Test
    fun `setStepDone toggles one step`() = runBlocking {
        val r = repo()
        val id = r.addTodo(
            testTodo(name = "作业", steps = listOf(testStep("s1"), testStep("s2"))),
        ).okId()

        r.setStepDone(id, "s2", done = true).assertOk()
        val todo = r.todos.value.single()
        assertEquals(listOf(false, true), todo.steps.map { it.done })

        assertTrue(r.setStepDone(id, "ghost", done = true).assertErr() is TodoError.NotFound)
        assertTrue(r.setStepDone("ghost", "s1", done = true).assertErr() is TodoError.NotFound)
    }

    // ---------- 导入导出 ----------

    @Test
    fun `export then import appends with fresh ids`() = runBlocking {
        val r = repo()
        val a = r.addTodo(testTodo(name = "A", createdAtMillis = 555L)).okId()
        val b = r.addTodo(
            testTodo(name = "B", steps = listOf(testStep("s1")), completed = true, deadlineDate = IsoDate.parse("2026-06-18"), deadlineMinute = 600),
        ).okId()

        val json = r.exportTodos().okText()
        now.advanceBy(1000)
        val count = r.importTodos(json).okCount()

        assertEquals(2, count)
        assertEquals(4, r.todos.value.size)
        val originals = r.todos.value.filter { it.id == a || it.id == b }.associateBy { it.name }
        val imported = (r.todos.value - originals.values.toSet()).associateBy { it.name }
        assertEquals(2, imported.size)
        // 内容一致、id 全部重铸（todo 与 step）
        assertEquals(originals.getValue("A"), imported.getValue("A").copy(id = originals.getValue("A").id))
        assertEquals("A", imported.getValue("A").name)
        assertEquals(555L, imported.getValue("A").createdAtMillis)
        assertNotEquals(a, imported.getValue("A").id)
        assertNotEquals(originals.getValue("B").steps.single().id, imported.getValue("B").steps.single().id)
    }

    @Test
    fun `import rejects wrong kind`() = runBlocking {
        val r = repo()
        val json = ScheduleJson.encodeShare(
            TodoSharePayload(exportedAtMillis = 1, kind = "classpp.schedule", todos = listOf(testTodo(name = "A"))),
        )
        assertTrue(r.importTodos(json).readErr() is TodoError.ImportKindMismatch)
    }

    @Test
    fun `import rejects future version`() = runBlocking {
        val r = repo()
        val json = ScheduleJson.encodeShare(
            TodoSharePayload(exportedAtMillis = 1, formatVersion = 99, todos = listOf(testTodo(name = "A"))),
        )
        assertTrue(r.importTodos(json).readErr() is TodoError.ImportVersionUnsupported)
    }

    @Test
    fun `import rejects malformed json`() = runBlocking {
        val r = repo()
        assertTrue(r.importTodos("{ nope").readErr() is TodoError.ImportFormatInvalid)
    }

    @Test
    fun `import rejects invalid todos wholesale`() = runBlocking {
        val r = repo()
        val json = ScheduleJson.encodeShare(
            TodoSharePayload(exportedAtMillis = 1, todos = listOf(testTodo(name = "好"), testTodo(name = "  "))),
        )
        val err = r.importTodos(json).readErr()
        assertEquals(TodoError.BlankName, err)
        assertTrue("任一条非法整体拒绝", r.todos.value.isEmpty())
    }

    // ---------- 持久化失败 ----------

    @Test
    fun `persist failure returns PersistFailed and leaves memory untouched`() = runBlocking {
        val failing = object : TodoStore {
            override fun load() = com.fangyi.classpp.data.store.LoadOutcome.Fresh(
                com.fangyi.classpp.data.model.TodoFile(),
                com.fangyi.classpp.data.store.LoadIssue.None,
            )
            override fun save(file: com.fangyi.classpp.data.model.TodoFile): StoreResult =
                StoreResult.Failed("disk full")
        }
        val r = repo(failing)

        assertTrue(r.addTodo(testTodo(name = "A")).readErr() is TodoError.PersistFailed)
        assertTrue("失败后内存不得出现新待办", r.todos.value.isEmpty())
    }

    private suspend fun TodoReadResult<*>.readErr(): TodoError {
        assertTrue("expected Err, got $this", this is TodoReadResult.Err)
        return (this as TodoReadResult.Err).error
    }
}
