package com.fangyi.classpp.data.sync

import com.fangyi.classpp.data.TodoOpResult
import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.store.FileTodoStore
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 通用同步不变量契约测试（指导 §四 Phase 4 验收）：**同一组用例同时跑两套编排**——
 * 既有厦大实现（经归一化包装）与通用 [com.fangyi.classpp.data.sync.engine.SchoolSyncEngine]。
 *
 * 覆盖：首次导入、幂等去重、软删复活、账号切换、探活去抖、过滤计数、自动分组回填、
 * 跨校退出隔离、状态丢失后的映射重建。两套实现必须给出完全一致的可观察行为——
 * 任何一边改了 §2.4 不变量，这里都会红。
 *
 * 子类只负责装配（fake api / 编码器）与远端夹具的序列化；断言全部写在本类。
 */
abstract class SyncInvariantContractTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 「现在」固定在 2026-11-01 12:00 北京时间（早于夹具所有截止） */
    protected var nowMillis: Long = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear()
        set(2026, 10, 1, 12, 0, 0)
    }.timeInMillis

    /** 被测同步服务（两套实现都经 data.sync 归一化契约暴露） */
    protected lateinit var service: SyncService
        private set

    protected lateinit var todoRepository: TodoRepository
        private set

    /** 子类装配自己的实现（仓库先建好，便于观察落库结果） */
    protected abstract suspend fun createService(todoRepository: TodoRepository): SyncService

    @Before
    fun setUp() = runBlocking {
        todoRepository = TodoRepository.create(FileTodoStore(folder.root), { nowMillis })
        service = createService(todoRepository)
    }

    /** 配下一次取数的账号与远端条目 */
    protected abstract fun setRemote(profile: RemoteProfile, todos: List<RemoteTodo>)

    /** 该实现下 (courseId, remoteId) 对应的确定性本地 id */
    protected abstract fun expectedLocalId(courseId: Long, remoteId: Long): String

    /** 自动分组键（各校命名空间不同，断言按 key 定位而不是名称字面量） */
    protected abstract val homeworkGroupKey: String
    protected abstract val quizGroupKey: String

    protected data class RemoteProfile(val uid: String = "9001", val name: String = "张三")

    protected data class RemoteTodo(
        val id: Long,
        val courseId: Long,
        val courseName: String,
        val title: String,
        val type: String = "homework",
        val endTime: String? = "2026-11-05T15:59:00Z",
        val locked: Boolean = false,
        val student: Boolean = true,
    )

    protected fun defaultRemotes(): List<RemoteTodo> = listOf(
        RemoteTodo(
            id = 925512,
            courseId = 106160,
            courseName = "高等数学",
            title = "第一次作业",
            type = "homework",
            endTime = "2026-11-04T10:30:00Z",
        ),
        RemoteTodo(
            id = 1013424,
            courseId = 110846,
            courseName = "线性代数",
            title = "第二次作业",
            type = "quiz",
            endTime = "2026-11-05T15:59:00Z",
        ),
    )

    // ---------- 用例 ----------

    @Test
    fun `first sync imports all importable items and records account`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())

        val plan = ready(service.prepareSync(force = true))
        assertEquals(2, plan.newItems.size)
        assertEquals(0, plan.restoreCount)
        assertEquals(0, plan.skippedTotal)

        val report = done(service.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id }))
        assertEquals(2, report.imported)
        assertEquals(0, report.restored)

        assertEquals(
            setOf(expectedLocalId(106160, 925512), expectedLocalId(110846, 1013424)),
            todoRepository.todos.value.map { it.id }.toSet(),
        )
        assertEquals("9001", service.state.value.uid)
        assertEquals("张三", service.state.value.accountName)
        assertTrue(service.state.value.lastSyncAtMillis > 0)
    }

    @Test
    fun `second sync is idempotent and markSynced keeps single copy`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()

        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.newItems.isEmpty())
        assertEquals(0, plan.restoreCount)
        assertTrue(service.markSynced(plan))

        assertEquals(2, todoRepository.todos.value.size)
    }

    @Test
    fun `soft deleted todo is restored with the same local id`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val localId = expectedLocalId(106160, 925512)
        assertTrue(todoRepository.removeTodos(listOf(localId)) is TodoOpResult.Ok)
        assertTrue(localId in todoRepository.deletedTodos.value.map { it.id })

        val plan = ready(service.prepareSync(force = true))
        assertEquals(1, plan.restoreCount)
        assertTrue(plan.newItems.isEmpty())

        val report = done(service.commitSync(plan, emptySet()))
        assertEquals(1, report.restored)
        assertEquals(0, report.imported)
        assertTrue(localId in todoRepository.todos.value.map { it.id })
    }

    @Test
    fun `account change is refused until the user resets sync data`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()

        setRemote(RemoteProfile(uid = "9002", name = "李四"), defaultRemotes())
        val failed = service.prepareSync(force = true)
        assertTrue(failed is PrepareOutcome.Failed)
        assertEquals(SyncError.AccountChanged, (failed as PrepareOutcome.Failed).error)
        assertEquals("9001", service.state.value.uid)

        service.resetSyncData(deleteSyncedTodos = false)
        setRemote(RemoteProfile(uid = "9002", name = "李四"), defaultRemotes())
        val plan = ready(service.prepareSync(force = true))
        done(service.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id }))
        assertEquals("9002", service.state.value.uid)
        assertEquals("李四", service.state.value.accountName)
    }

    @Test
    fun `probe debounce rejects immediate second prepare unless forced`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        ready(service.prepareSync(force = true))

        val tooSoon = service.prepareSync(force = false)
        assertTrue(tooSoon is PrepareOutcome.Failed)
        assertEquals(SyncError.TooSoon, (tooSoon as PrepareOutcome.Failed).error)

        nowMillis += 3_000
        assertTrue(service.prepareSync(force = false) is PrepareOutcome.Ready)
    }

    @Test
    fun `filtered remote items are counted and not imported`() = runBlocking {
        setRemote(
            RemoteProfile(),
            listOf(
                RemoteTodo(
                    id = 1,
                    courseId = 10,
                    courseName = "课程A",
                    title = "正常作业",
                    endTime = "2026-11-05T15:59:00Z",
                ),
                RemoteTodo(
                    id = 2,
                    courseId = 10,
                    courseName = "课程A",
                    title = "已锁定",
                    locked = true,
                ),
                RemoteTodo(
                    id = 3,
                    courseId = 11,
                    courseName = "课程B",
                    title = "已过期",
                    endTime = "2026-10-01T00:00:00Z",
                ),
                RemoteTodo(
                    id = 4,
                    courseId = 11,
                    courseName = "课程B",
                    title = "截止格式坏了",
                    endTime = "not-a-timestamp",
                ),
            ),
        )

        val plan = ready(service.prepareSync(force = true))
        assertEquals(1, plan.newItems.size)
        assertEquals(expectedLocalId(10, 1), plan.newItems.single().todo.id)
        assertEquals(3, plan.skippedTotal)
        assertEquals(1, plan.deadlineUnparseableCount)
    }

    @Test
    fun `group backfill fills only ungrouped owned todos and keeps manual groups`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val homeworkId = expectedLocalId(106160, 925512)
        val quizId = expectedLocalId(110846, 1013424)
        assertNotNull(todo(homeworkId).group)
        assertNotNull(todo(quizId).group)

        // 模拟用户清掉一个自动分组（存量未分组），并把另一个改成手动分组
        assertEquals(TodoOpResult.Ok, todoRepository.updateTodo(todo(homeworkId).copy(group = null)))
        assertEquals(
            TodoOpResult.Ok,
            todoRepository.applyGroupAssignments(listOf(TodoGroup(name = "手动分组")), emptyMap()),
        )
        assertEquals(
            TodoOpResult.Ok,
            todoRepository.updateTodo(todo(quizId).copy(group = "手动分组")),
        )

        setRemote(RemoteProfile(), defaultRemotes())
        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.newItems.isEmpty())
        assertTrue(service.markSynced(plan))

        val homeworkGroup = todoRepository.groups.value.first { it.autoKey == homeworkGroupKey }
        assertEquals(homeworkGroup.name, todo(homeworkId).group)
        assertEquals("手动分组", todo(quizId).group)
        // 测验的自动分组在首次导入时已创建，但用户手动改写后不被回填覆盖
        assertTrue(todoRepository.groups.value.any { it.autoKey == quizGroupKey })
    }

    @Test
    fun `reset removes only own todos and clears connection state`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        assertTrue(
            todoRepository.addTodo(
                Todo(id = "foreign-1", name = "手动待办", source = "other"),
            ) is TodoReadResult.Ok,
        )

        service.resetSyncData(deleteSyncedTodos = true)

        assertEquals(setOf("foreign-1"), todoRepository.todos.value.map { it.id }.toSet())
        assertEquals("", service.state.value.uid)
        assertTrue(service.state.value.lastSyncAtMillis == 0L)
    }

    @Test
    fun `markSynced rebuilds entries after state loss without duplicating todos`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val ids = todoRepository.todos.value.map { it.id }.toSet()

        // 模拟「备份恢复了 todos.json 但没有同步状态」：清状态、保留待办
        service.resetSyncData(deleteSyncedTodos = false)
        assertEquals(ids, todoRepository.todos.value.map { it.id }.toSet())

        setRemote(RemoteProfile(), defaultRemotes())
        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.newItems.isEmpty())
        assertTrue(service.markSynced(plan))
        assertEquals("9001", service.state.value.uid)

        // 映射已重建：再一次同步仍是「无新增」
        val again = ready(service.prepareSync(force = true))
        assertTrue(again.newItems.isEmpty())
        assertEquals(ids, todoRepository.todos.value.map { it.id }.toSet())
    }

    // ---------- 工具 ----------

    protected suspend fun syncAll() {
        val plan = ready(service.prepareSync(force = true))
        done(service.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id }))
    }

    protected fun ready(outcome: PrepareOutcome): SyncPlan {
        assertTrue("expected Ready, got $outcome", outcome is PrepareOutcome.Ready)
        return (outcome as PrepareOutcome.Ready).plan
    }

    protected fun done(outcome: CommitOutcome): SyncReport {
        assertTrue("expected Done, got $outcome", outcome is CommitOutcome.Done)
        return (outcome as CommitOutcome.Done).report
    }

    protected fun todo(id: String): Todo =
        todoRepository.todos.value.firstOrNull { it.id == id }
            ?: error("todo not found: $id")
}
