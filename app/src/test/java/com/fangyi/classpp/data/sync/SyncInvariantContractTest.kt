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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * 跨校退出隔离、状态丢失后的映射重建、远端消失标记完成与重新出现回滚。
 * 两套实现必须给出完全一致的可观察行为——
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
        // 偏好默认关闭；契约用例以既有「自动完成」行为为基线，这里显式开启
        service.preferences.setAutoCompleteOnVanish(true)
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

    // ---------- 远端消失 / 回滚 ----------

    @Test
    fun `remote disappearance marks active synced todo completed`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val goneId = expectedLocalId(110846, 1013424)
        val keptId = expectedLocalId(106160, 925512)

        setRemote(RemoteProfile(), listOf(defaultRemotes().first()))
        val plan = ready(service.prepareSync(force = true))
        assertEquals(listOf(goneId), plan.vanishedItems.map { it.todo.id })
        assertTrue(plan.newItems.isEmpty())

        val report = done(service.commitSync(plan, emptySet()))
        assertEquals(1, report.completed)
        assertEquals(0, report.reopened)
        assertTrue(todo(goneId).completed)
        assertEquals(nowMillis, todo(goneId).completedAtMillis)
        assertFalse(todo(keptId).completed)
    }

    @Test
    fun `auto complete preference off keeps vanished todo uncompleted`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val goneId = expectedLocalId(110846, 1013424)

        service.preferences.setAutoCompleteOnVanish(false)
        setRemote(RemoteProfile(), listOf(defaultRemotes().first()))
        val offPlan = ready(service.prepareSync(force = true))
        assertTrue(offPlan.vanishedItems.isEmpty())
        done(service.commitSync(offPlan, emptySet()))
        assertFalse(todo(goneId).completed)

        // 重新开启：下一次同步仍能扫到并标记完成
        service.preferences.setAutoCompleteOnVanish(true)
        val onPlan = ready(service.prepareSync(force = true))
        assertEquals(listOf(goneId), onPlan.vanishedItems.map { it.todo.id })
        done(service.commitSync(onPlan, emptySet()))
        assertTrue(todo(goneId).completed)
    }

    @Test
    fun `overdue vanished todo is not marked completed`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        // 第一条截止 2026-11-04 18:30（北京时间），第二条截止 2026-11-05 23:59
        val overdueId = expectedLocalId(106160, 925512)
        val activeId = expectedLocalId(110846, 1013424)

        // 推进到第一项截止之后、第二项截止之前
        nowMillis = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear()
            set(2026, 10, 5, 12, 0, 0)
        }.timeInMillis

        // 两条都已从远端消失；用一条新条目保持远端列表非空
        val extra = RemoteTodo(
            id = 3,
            courseId = 12,
            courseName = "大学物理",
            title = "新作业",
        )
        setRemote(RemoteProfile(), listOf(extra))
        val plan = ready(service.prepareSync(force = true))

        // 逾期的第一条不标记；未逾期的第二条照常标记
        assertEquals(listOf(activeId), plan.vanishedItems.map { it.todo.id })
        val report = done(service.commitSync(plan, emptySet()))
        assertEquals(1, report.completed)
        assertFalse(todo(overdueId).completed)
        assertTrue(todo(activeId).completed)
    }

    @Test
    fun `empty remote list does not complete synced todos`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()

        setRemote(RemoteProfile(), emptyList())
        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.vanishedItems.isEmpty())
        assertTrue(service.markSynced(plan))
        assertTrue(todoRepository.todos.value.none { it.completed })
    }

    @Test
    fun `reappearing remote reopens auto completed todo`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val goneId = expectedLocalId(110846, 1013424)

        setRemote(RemoteProfile(), listOf(defaultRemotes().first()))
        done(service.commitSync(ready(service.prepareSync(force = true)), emptySet()))
        assertTrue(todo(goneId).completed)

        setRemote(RemoteProfile(), defaultRemotes())
        val plan = ready(service.prepareSync(force = true))
        assertEquals(listOf(goneId), plan.reactivatedItems.map { it.todo.id })
        val report = done(service.commitSync(plan, emptySet()))
        assertEquals(1, report.reopened)
        assertFalse(todo(goneId).completed)
        assertNull(todo(goneId).completedAtMillis)
    }

    @Test
    fun `user uncompleted auto completed todo is detected again`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val goneId = expectedLocalId(110846, 1013424)

        setRemote(RemoteProfile(), listOf(defaultRemotes().first()))
        done(service.commitSync(ready(service.prepareSync(force = true)), emptySet()))
        assertTrue(todo(goneId).completed)
        assertEquals(TodoOpResult.Ok, todoRepository.setCompleted(goneId, false))

        // 远端仍未返回：手动取消不会让条目永久脱离同步，下次仍应扫到并标记完成
        val plan = ready(service.prepareSync(force = true))
        assertEquals(listOf(goneId), plan.vanishedItems.map { it.todo.id })
        val report = done(service.commitSync(plan, emptySet()))
        assertEquals(1, report.completed)
        assertTrue(todo(goneId).completed)
    }

    @Test
    fun `user uncompleted manually completed vanished todo is detected again`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val goneId = expectedLocalId(110846, 1013424)

        // 用户先手动完成，随后远端不再返回：已完成状态不参与消失标记
        assertEquals(TodoOpResult.Ok, todoRepository.setCompleted(goneId, true))
        setRemote(RemoteProfile(), listOf(defaultRemotes().first()))
        val first = ready(service.prepareSync(force = true))
        assertTrue(first.vanishedItems.isEmpty())
        done(service.commitSync(first, emptySet()))

        // 用户取消完成：远端仍未返回，下次同步重新扫到并标记完成
        assertEquals(TodoOpResult.Ok, todoRepository.setCompleted(goneId, false))
        val second = ready(service.prepareSync(force = true))
        assertEquals(listOf(goneId), second.vanishedItems.map { it.todo.id })
        done(service.commitSync(second, emptySet()))
        assertTrue(todo(goneId).completed)
    }

    @Test
    fun `manual re-completion after auto completion is not reopened`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val goneId = expectedLocalId(110846, 1013424)

        setRemote(RemoteProfile(), listOf(defaultRemotes().first()))
        done(service.commitSync(ready(service.prepareSync(force = true)), emptySet()))
        assertEquals(TodoOpResult.Ok, todoRepository.setCompleted(goneId, false))
        // 让手动重新勾选的时间戳与自动完成基线不同
        nowMillis += 1_000
        assertEquals(TodoOpResult.Ok, todoRepository.setCompleted(goneId, true))

        setRemote(RemoteProfile(), defaultRemotes())
        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.reactivatedItems.isEmpty())
        done(service.commitSync(plan, emptySet()))
        assertTrue(todo(goneId).completed)
    }

    @Test
    fun `vanished detection skips archived trashed and manual todos`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()
        val archivedId = expectedLocalId(106160, 925512)
        val trashedId = expectedLocalId(110846, 1013424)
        assertEquals(TodoOpResult.Ok, todoRepository.archiveTodo(archivedId))
        assertEquals(TodoOpResult.Ok, todoRepository.removeTodos(listOf(trashedId)))
        assertTrue(
            todoRepository.addTodo(Todo(id = "manual-1", name = "手动待办")) is TodoReadResult.Ok,
        )

        // 保留一条新远端条目让列表非空，同时让两条已映射条目都不在列表里
        val extra = RemoteTodo(
            id = 3,
            courseId = 12,
            courseName = "大学物理",
            title = "新作业",
        )
        setRemote(RemoteProfile(), listOf(extra))
        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.vanishedItems.isEmpty())
        done(service.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id }))

        assertFalse(todoRepository.archivedTodos.value.single().completed)
        assertFalse(todoRepository.deletedTodos.value.single().completed)
        assertFalse(todoRepository.todos.value.first { it.id == "manual-1" }.completed)
    }

    @Test
    fun `filtered remote items are still present for vanish detection`() = runBlocking {
        setRemote(RemoteProfile(), defaultRemotes())
        syncAll()

        // locked/overdue 会被映射层跳过，但仍在远端原始列表里：不算消失
        setRemote(RemoteProfile(), defaultRemotes().map { it.copy(locked = true) })
        val plan = ready(service.prepareSync(force = true))
        assertTrue(plan.vanishedItems.isEmpty())
        assertTrue(plan.reactivatedItems.isEmpty())
        done(service.commitSync(plan, emptySet()))
        assertTrue(todoRepository.todos.value.none { it.completed })
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
