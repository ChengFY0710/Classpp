package com.fangyi.classpp.data.sync.engine

import com.fangyi.classpp.data.TodoReadResult
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.model.Todo
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.sync.AutoRenewPolicy
import com.fangyi.classpp.data.sync.CommitOutcome
import com.fangyi.classpp.data.sync.EnableOutcome
import com.fangyi.classpp.data.sync.PrepareOutcome
import com.fangyi.classpp.data.sync.SchoolRegistry
import com.fangyi.classpp.data.sync.SessionSlot
import com.fangyi.classpp.data.sync.webview.WebViewPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 假学校端到端契约（指导 §四 Phase 4 验收）：**完全不依赖真实学校**，只用通用工具包
 * （引擎 + 自动续期 + 声明式 WebView）装配出一所学校，走通
 * prepare → 预览 → commit → 去重 → 复活 → 退出清理 → 自动续期登录 → 注册表装配。
 */
class FakeSchoolProviderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var todoRepository: TodoRepository
    private lateinit var provider: FakeSchoolProvider

    /** 「现在」固定在 2026-11-01 12:00 北京时间（早于夹具所有截止） */
    private val now: Long = java.util.GregorianCalendar(
        java.util.TimeZone.getTimeZone("Asia/Shanghai"),
    ).apply {
        clear()
        set(2026, 10, 1, 12, 0, 0)
    }.timeInMillis

    @Before
    fun setUp() = runBlocking {
        todoRepository = TodoRepository.create(FileTodoStore(folder.root))
        provider = FakeSchoolProvider { now }
        provider.api.respond(todos = remotes())
    }

    private fun remotes() = listOf(
        FakeRemoteTodo(
            id = 1,
            courseId = 10,
            courseName = "高等数学",
            title = "作业一",
            type = "homework",
        ),
        FakeRemoteTodo(
            id = 2,
            courseId = 10,
            courseName = "高等数学",
            title = "测验一",
            type = "quiz",
        ),
    )

    private suspend fun assemble() = provider.assemble(todoRepository, folder.root)

    @Test
    fun `session matches declared capabilities and valid web view spec`() = runBlocking {
        val session = assemble()

        assertEquals("fakeu", session.spec.id)
        assertNotNull(session.autoRenew)
        assertNotNull(session.webViewLogin)
        assertNull(session.scheduleImport)
        assertEquals(emptyList<String>(), WebViewPolicy.validate(session.webViewLogin!!))
        // 登录态确认口径由 provider 注入：HTML 登录页不算已登录
        assertTrue(session.webViewProfileValidator!!("9001\t张三"))
        assertFalse(session.webViewProfileValidator!!("<html>login</html>"))
    }

    @Test
    fun `end to end prepare commit restore and reset`() = runBlocking {
        val session = assemble()
        val service = session.sync

        val plan = (service.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertEquals(2, plan.newItems.size)
        val report = (
            service.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id })
                as CommitOutcome.Done
            ).report
        assertEquals(2, report.imported)
        assertEquals(
            setOf("fakeu-10-1", "fakeu-10-2"),
            todoRepository.todos.value.map { it.id }.toSet(),
        )

        // 去重：第二次同步无新增
        val second = (service.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertTrue(second.newItems.isEmpty())

        // 复活：软删后同步恢复同一本地 id
        todoRepository.removeTodos(listOf("fakeu-10-1"))
        val restored = (service.prepareSync(force = true) as PrepareOutcome.Ready).plan
        assertEquals(1, restored.restoreCount)
        val restoredReport = (service.commitSync(restored, emptySet()) as CommitOutcome.Done).report
        assertEquals(1, restoredReport.restored)
        assertTrue("fakeu-10-1" in todoRepository.todos.value.map { it.id })

        // 退出清理只删本校数据
        todoRepository.addTodo(Todo(id = "manual-1", name = "手建", source = "manual"))
        service.resetSyncData(deleteSyncedTodos = true)
        assertEquals(setOf("manual-1"), todoRepository.todos.value.map { it.id }.toSet())
        assertEquals("", service.state.value.uid)
    }

    @Test
    fun `auto renew enable produces login raw and engine syncs from it`() = runBlocking {
        val session = assemble()
        val autoRenew = requireNotNull(session.autoRenew)

        val enabled = autoRenew.enable("20260001", "secret")
        assertTrue("expected Ready, got $enabled", enabled is EnableOutcome.Ready)
        val raw = (enabled as EnableOutcome.Ready).raw
        assertEquals(AutoRenewPolicy.ENABLED, provider.secureStore.policy)
        assertEquals("20260001", provider.secureStore.credentials?.username)
        assertTrue(provider.api.calls > 0)
        assertTrue(provider.secureStore.cookie!!.isNotBlank())
        assertEquals("lms.example.test", provider.mirror.host)

        // 登录返回的原文直接进入预览-确认链路（不再重复 fetch）
        val plan = (session.sync.prepareFromLogin(raw) as PrepareOutcome.Ready).plan
        assertEquals(2, plan.newItems.size)
        val report = (
            session.sync.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id })
                as CommitOutcome.Done
            ).report
        assertEquals(2, report.imported)

        // 自动续期开启后，手动同步走原生会话（探活 + 取数），不再依赖 WebView
        val routed = session.sync.prepareSync(force = true)
        assertTrue(routed is PrepareOutcome.Ready)

        autoRenew.disable()
        assertEquals(AutoRenewPolicy.USER_LOGGED_OUT, provider.secureStore.policy)
    }

    @Test
    fun `registry accepts the fake provider and shares the daily toggle`() = runBlocking {
        val registry = SchoolRegistry(
            providers = listOf(provider),
            assembler = { p ->
                val fake = p as FakeSchoolProvider
                fake.assemble(todoRepository, folder.root)
            },
            toggleOf = { p -> (p as FakeSchoolProvider).toggle },
        )

        registry.session()

        val slot = registry.activeSession.value
        assertTrue(slot is SessionSlot.Ready)
        val session = (slot as SessionSlot.Ready).session
        assertEquals("fakeu", session.spec.id)
        assertSame(provider.toggle, registry.dailySyncToggle())
        assertSame(session.dailySync, registry.dailySyncToggle())

        provider.toggle.setEnabled(true)
        assertTrue(registry.dailySyncToggle().enabled.value)
    }

    @Test
    fun `manual todo added between syncs is not touched by dedupe or reset of own data`() =
        runBlocking {
            val session = assemble()
            assertTrue(
                todoRepository.addTodo(Todo(id = "manual-1", name = "手建")) is TodoReadResult.Ok,
            )
            val plan = (session.sync.prepareSync(force = true) as PrepareOutcome.Ready).plan
            session.sync.commitSync(plan, plan.newItems.mapTo(mutableSetOf()) { it.todo.id })

            assertEquals(3, todoRepository.todos.value.size)
            session.sync.resetSyncData(deleteSyncedTodos = true)
            assertEquals(setOf("manual-1"), todoRepository.todos.value.map { it.id }.toSet())
        }
}
