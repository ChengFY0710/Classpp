package com.fangyi.classpp.data.xmu

import com.fangyi.classpp.data.FakeNow
import com.fangyi.classpp.data.TodoError
import com.fangyi.classpp.data.TodoRepository
import com.fangyi.classpp.data.model.isDeleted
import com.fangyi.classpp.data.store.FileTodoStore
import com.fangyi.classpp.data.store.StoreResult
import java.io.File
import java.util.GregorianCalendar
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 同步编排（指导 §11.3）：只增、幂等、删除后复活、账号切换、错误分类、去抖 */
class XmuSyncRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** 「现在」固定在 2026-11-01 12:00 北京时间（早于夹具所有截止） */
    private val now: Long = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear()
        set(2026, 10, 1, 12, 0, 0)
    }.timeInMillis

    private val clock = FakeNow(now)

    private val profileRaw = """{"id":9001,"user_no":"20260001","name":"张三"}"""
    private val otherProfileRaw = """{"id":9002,"user_no":"20260002","name":"李四"}"""

    private class FakeApi(var result: XmuFetchResult) : XmuTodoApi {
        var calls = 0
        override suspend fun fetchSession(): XmuFetchResult {
            calls++
            return result
        }
    }

    private fun todosRaw(name: String = "todos-2026-10-30.json"): String =
        javaClass.getResourceAsStream("/xmu/$name")!!.bufferedReader().use { it.readText() }

    private fun okApi(todos: String = todosRaw(), profile: String = profileRaw) =
        FakeApi(XmuFetchResult.Ok(profile, todos))

    /** 落盘失败注入：failOn(第 N 次 save) 为 true 时模拟磁盘满/权限异常 */
    private class FailingStore(
        dir: File,
        private val failOn: (Int) -> Boolean,
    ) : XmuSyncStore(dir) {
        private var saves = 0

        override fun save(file: XmuSyncFile): StoreResult {
            saves++
            return if (failOn(saves)) StoreResult.Failed("disk full") else super.save(file)
        }
    }

    private suspend fun repo(
        api: FakeApi,
        store: XmuSyncStore = XmuSyncStore(folder.root),
    ): XmuSyncRepository = XmuSyncRepository(
        store = store,
        todoRepository = TodoRepository.create(FileTodoStore(folder.root), clock),
        api = api,
        nowMillis = clock,
    )

    private fun XmuSyncRepository.activeIds(): Set<String> = todoRepository.todos.value.map { it.id }.toSet()
    private fun XmuSyncRepository.trashIds(): Set<String> = todoRepository.deletedTodos.value.map { it.id }.toSet()

    private suspend fun XmuSyncRepository.syncAll(
        api: FakeApi,
        todos: String = todosRaw(),
        profile: String = profileRaw,
    ): XmuSyncReport {
        api.result = XmuFetchResult.Ok(profile, todos)
        val plan = (prepareSync(force = true) as XmuPrepareOutcome.Ready).plan
        val selected = plan.newItems.map { it.todo.id }.toSet()
        return (commitSync(plan, selected) as XmuCommitOutcome.Done).report
    }

    @Test
    fun `首次同步导入 N 条并写入映射`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        val report = r.syncAll(api)
        assertEquals(2, report.imported)
        assertEquals(0, report.restored)
        assertEquals(setOf("xmu-925512", "xmu-1013424"), r.activeIds())
        assertEquals(2, r.state.value.entries.size)
        assertEquals("9001", r.state.value.uid)
        assertEquals("张三", r.state.value.accountName)
        assertTrue(r.state.value.lastSyncAtMillis > 0)
    }

    @Test
    fun `第二次同步新增为 0（幂等）`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)
        val second = r.syncAll(api)
        assertEquals(0, second.imported)
        assertEquals(2, r.activeIds().size)
    }

    @Test
    fun `软删后同步复活（复用原本地 id）`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)

        r.todoRepository.removeTodo("xmu-925512")
        assertTrue(r.trashIds().contains("xmu-925512"))

        api.result = XmuFetchResult.Ok(profileRaw, todosRaw())
        val plan = (r.prepareSync(force = true) as XmuPrepareOutcome.Ready).plan
        assertTrue(plan.restoreItems.map { it.todo.id }.contains("xmu-925512"))
        assertTrue(plan.newItems.isEmpty())

        val report = (r.commitSync(plan, emptySet()) as XmuCommitOutcome.Done).report
        assertEquals(1, report.restored)
        assertEquals(0, report.imported)
        assertFalse(r.todoRepository.todos.value.first { it.id == "xmu-925512" }.isDeleted)
    }

    @Test
    fun `彻底删除后同步按新增重新导入`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)

        r.todoRepository.removeTodo("xmu-925512")
        r.todoRepository.purgeTodo("xmu-925512")
        assertFalse(r.trashIds().contains("xmu-925512"))

        val report = r.syncAll(api)
        assertEquals(1, report.imported)
        assertTrue(r.activeIds().contains("xmu-925512"))
    }

    @Test
    fun `远端已不再列出时不复活也不自动完成`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)

        // 删掉 item1，并把 item2 标记完成；然后远端只返回 item1（item2 已消失）
        r.todoRepository.removeTodo("xmu-925512")
        r.todoRepository.setCompleted("xmu-1013424", true)
        val onlyFirst = """{"todo_list":[{"id":925512,"title":"第一次作业","type":"homework","course_id":106160,
                "course_code":"20262027113013001020702","course_name":"高等数学","course_type":1,
                "end_time":"2026-11-04T10:30:00Z","is_locked":false,"is_student":true}]}"""
        val report = r.syncAll(api, todos = onlyFirst)
        // item1 删除过但远端仍列出 → 复活；item2 远端消失 → 不导入、不复活
        assertEquals(0, report.imported)
        assertEquals(1, report.restored)
        assertFalse(r.trashIds().contains("xmu-925512"))

        // 远端消失的 item2：既不被自动完成/删除，也不被改动（保持用户刚设的完成态）
        val second = r.todoRepository.todos.value.first { it.id == "xmu-1013424" }
        assertTrue(second.completed)
    }

    @Test
    fun `部分条目非法 - 跳过并计数、合法条目正常导入`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        val report = r.syncAll(api, todos = todosRaw("todos-edge.json"))
        // 8 条：2001/2002/2005 合法；锁定/无截止/非学生/空标题/非法id 共 5 条跳过
        assertEquals(3, report.imported)
        assertEquals(5, report.skippedFiltered)
        assertEquals(setOf("xmu-2001", "xmu-2002", "xmu-2005"), r.activeIds())
    }

    @Test
    fun `3 秒内重复探活被去抖，超时后可重试`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api) // 已探活一次

        assertTrue(r.prepareSync() is XmuPrepareOutcome.Failed)
        assertEquals(1, api.calls) // 去抖命中，没发请求
        clock.advanceBy(3_001)
        assertTrue(r.prepareSync() is XmuPrepareOutcome.Ready)
        assertEquals(2, api.calls)
    }

    @Test
    fun `失败的探活不刷新去抖时间戳`() = runBlocking {
        val api = FakeApi(XmuFetchResult.ServiceBusy)
        val r = repo(api)
        assertTrue(r.prepareSync() is XmuPrepareOutcome.Failed)
        assertTrue(r.prepareSync() is XmuPrepareOutcome.Failed)
        assertEquals(2, api.calls)
    }

    @Test
    fun `uid 变化拒绝同步（账号切换防护）`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)

        val outcome = r.prepareFromLogin(otherProfileRaw, todosRaw())
        assertEquals(XmuSyncError.AccountChanged, (outcome as XmuPrepareOutcome.Failed).error)
    }

    @Test
    fun `prepareFromLogin 不去抖且可复用同一原文`() = runBlocking {
        val api = okApi()
        val r = repo(api)

        val first = r.prepareFromLogin(profileRaw, todosRaw())
        val plan = (first as XmuPrepareOutcome.Ready).plan
        assertEquals(2, plan.newItems.size)

        // 同一原文再 prepare（例如用户取消预览后重新进入）也允许
        assertTrue(r.prepareFromLogin(profileRaw, todosRaw()) is XmuPrepareOutcome.Ready)
        // 无头接口与登录入口互不影响（fetchSession 未被调用）
        assertEquals(0, api.calls)
    }

    @Test
    fun `错误分类 - 过期与繁忙与网络与超时`() = runBlocking {
        val r = repo(FakeApi(XmuFetchResult.SessionExpired))
        assertEquals(XmuSyncError.SessionExpired, (r.prepareSync() as XmuPrepareOutcome.Failed).error)

        val busy = repo(FakeApi(XmuFetchResult.ServiceBusy))
        assertEquals(XmuSyncError.ServiceBusy, (busy.prepareSync() as XmuPrepareOutcome.Failed).error)

        val net = repo(FakeApi(XmuFetchResult.NetworkError))
        assertEquals(XmuSyncError.NetworkError, (net.prepareSync() as XmuPrepareOutcome.Failed).error)

        val timeout = repo(FakeApi(XmuFetchResult.Timeout))
        assertEquals(XmuSyncError.Timeout, (timeout.prepareSync() as XmuPrepareOutcome.Failed).error)
    }

    @Test
    fun `响应形态变化分类 - JSON 结构变 = FormatChanged、登录页 HTML = 过期`() = runBlocking {
        val changed = repo(okApi(todos = """{"data":{"unknown_shape":true}}""", profile = """{"no_id_field":1}"""))
        assertEquals(XmuSyncError.FormatChanged, (changed.prepareSync() as XmuPrepareOutcome.Failed).error)

        val expired = repo(okApi(todos = todosRaw(), profile = "<html>统一身份认证登录</html>"))
        assertEquals(XmuSyncError.SessionExpired, (expired.prepareSync() as XmuPrepareOutcome.Failed).error)

        // profile 正常但 todos 返回 HTML（会话中途被踢）：同样按过期处理
        val midExpired = repo(okApi(todos = "<html>登录</html>"))
        assertEquals(XmuSyncError.SessionExpired, (midExpired.prepareSync() as XmuPrepareOutcome.Failed).error)
    }

    @Test
    fun `resetSyncData 仅清映射 vs 连待办一起删`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)
        r.todoRepository.removeTodo("xmu-925512") // 制造一条回收站数据

        // 仅清映射：本地待办原样保留
        r.resetSyncData(deleteSyncedTodos = false)
        assertTrue(r.state.value.uid.isEmpty())
        assertEquals(2, r.todoRepository.todos.value.size + r.todoRepository.deletedTodos.value.size)

        // 重新同步建立映射后再彻底清（含回收站的 xmu- 条目）
        r.syncAll(api)
        r.todoRepository.addTodo(com.fangyi.classpp.data.testTodo(id = "user-made"))
        r.resetSyncData(deleteSyncedTodos = true)
        assertEquals(setOf("user-made"), r.activeIds() + r.trashIds())
        // 批量 purge 后回收站不留残留
        assertTrue(r.todoRepository.deletedTodos.value.isEmpty())
    }

    @Test
    fun `todos 缺 todo_list key 按格式变化处理`() = runBlocking {
        val r = repo(okApi(todos = """{"data":{}}"""))
        assertEquals(
            XmuSyncError.FormatChanged,
            (r.prepareSync() as XmuPrepareOutcome.Failed).error,
        )
    }

    @Test
    fun `todo_list 为空数组是合法空列表`() = runBlocking {
        val r = repo(okApi(todos = """{"todo_list":[]}"""))
        val plan = (r.prepareSync() as XmuPrepareOutcome.Ready).plan
        assertTrue(plan.newItems.isEmpty())
        assertEquals(0, plan.totalRemote)
    }

    @Test
    fun `截止格式变化只计入 DeadlineUnparseable 且不阻断其他条目`() = runBlocking {
        val todos = """
            {"todo_list":[
              {"id":7001,"title":"时区偏移作业","type":"homework","course_id":1,"course_name":"课程A","end_time":"2026-11-04T18:30:00+08:00","is_student":true},
              {"id":7002,"title":"正常作业","type":"homework","course_id":1,"course_name":"课程A","end_time":"2026-11-04T10:30:00Z","is_student":true}
            ]}
        """.trimIndent()
        val plan = (repo(okApi(todos = todos)).prepareSync() as XmuPrepareOutcome.Ready).plan

        assertEquals(1, plan.skipped[SkipReason.DeadlineUnparseable])
        assertEquals(listOf("xmu-7002"), plan.newItems.map { it.todo.id })
    }

    @Test
    fun `同步状态落盘失败 - prepare 报 PersistFailed`() = runBlocking {
        val r = repo(okApi(), FailingStore(folder.root) { true })
        assertEquals(
            XmuSyncError.PersistFailed,
            (r.prepareSync() as XmuPrepareOutcome.Failed).error,
        )
        // 落盘失败时内存态不更新：连接卡不能假装已连接
        assertEquals("", r.state.value.uid)
    }

    @Test
    fun `提交时落盘失败返回 PersistFailed`() = runBlocking {
        // 第 1 次 save 是 prepare 落账号信息，第 2 次才是提交映射：让第 2 次失败
        val r = repo(okApi(), FailingStore(folder.root) { it == 2 })
        val plan = (r.prepareSync(force = true) as XmuPrepareOutcome.Ready).plan
        val selected = plan.newItems.map { it.todo.id }.toSet()

        val outcome = r.commitSync(plan, selected)
        assertTrue((outcome as XmuCommitOutcome.Failed).error is TodoError.PersistFailed)
        assertEquals(0L, r.state.value.lastSyncAtMillis)
    }

    @Test
    fun `系统时间回拨后去抖不应永久拦截`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)

        clock.advanceBy(-86_400_000L) // 系统时间被调回一天
        assertTrue(r.prepareSync() is XmuPrepareOutcome.Ready)
    }

    @Test
    fun `无新增时补写映射基线与上次同步时间`() = runBlocking {
        val api = okApi()
        val r = repo(api)
        r.syncAll(api)

        // 模拟备份恢复：todos.json 还在，同步状态丢失
        r.resetSyncData(deleteSyncedTodos = false)
        assertTrue(r.state.value.entries.isEmpty())

        val plan = (r.prepareSync(force = true) as XmuPrepareOutcome.Ready).plan
        assertTrue(plan.newItems.isEmpty())
        assertTrue(plan.restoreItems.isEmpty())
        assertEquals(2, plan.entriesForActive.size)
        assertEquals(0L, r.state.value.lastSyncAtMillis)

        assertTrue(r.markSynced(plan))
        assertEquals(2, r.state.value.entries.size)
        assertEquals(clock.millis, r.state.value.lastSyncAtMillis)
    }
}
