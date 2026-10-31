package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.RepeatEndType
import com.fangyi.classpp.data.model.RepeatRule
import com.fangyi.classpp.data.model.RepeatType
import com.fangyi.classpp.data.model.TodoGroup
import com.fangyi.classpp.data.model.TodoTimeKind
import com.fangyi.classpp.data.model.TodoSharePayload
import com.fangyi.classpp.data.store.StoreResult
import com.fangyi.classpp.data.store.TodoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 一天的毫秒数（回收站保留期用例推时钟用） */
private const val DayMillis = 86_400_000L

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
    fun `removeTodo and batch removeTodos move todos to recently deleted`() = runBlocking {
        val r = repo()
        val a = r.addTodo(testTodo(name = "A")).okId()
        val b = r.addTodo(testTodo(name = "B")).okId()

        r.removeTodos(listOf(a, "不存在")).assertOk() // 未知 id 宽容忽略
        assertEquals(listOf("B"), r.todos.value.map { it.name })
        assertEquals(listOf("A"), r.deletedTodos.value.map { it.name })

        r.removeTodo(b).assertOk()
        assertTrue(r.todos.value.isEmpty())
        assertEquals(setOf("A", "B"), r.deletedTodos.value.map { it.name }.toSet())
        // 已在回收站 = 对删除路径不可见（二次删除仍报 NotFound）
        assertTrue(r.removeTodo(b).assertErr() is TodoError.NotFound)
    }

    @Test
    fun `soft deleted todos persist and stay out of the live flow`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "A")).okId()

        now.advanceBy(500)
        r.removeTodo(id).assertOk()
        assertEquals(1_000_500L, r.deletedTodos.value.single().deletedAtMillis)
        assertTrue(r.todos.value.isEmpty())

        // 软删不是物理删除：重开仓库后条目仍在回收站，活跃流依旧为空
        val reopened = repo()
        assertTrue(reopened.todos.value.isEmpty())
        assertEquals(listOf(id), reopened.deletedTodos.value.map { it.id })
        assertEquals(1_000_500L, reopened.deletedTodos.value.single().deletedAtMillis)
    }

    @Test
    fun `restoreTodo returns the todo to the live list`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业"))).assertOk()
        val id = r.addTodo(testTodo(name = "A", group = "作业")).okId()
        r.removeTodo(id).assertOk()

        r.restoreTodo(id).assertOk()
        val restored = r.todos.value.single { it.id == id }
        assertEquals("A", restored.name)
        assertEquals("作业", restored.group) // 恢复保留原分组
        assertNull(restored.deletedAtMillis)
        assertTrue(r.deletedTodos.value.isEmpty())

        // 活跃条目不在回收站，恢复同样报 NotFound（与二次删除对称）
        assertTrue(r.restoreTodo(id).assertErr() is TodoError.NotFound)
    }

    @Test
    fun `purgeTodo only removes recently deleted todos`() = runBlocking {
        val r = repo()
        val live = r.addTodo(testTodo(name = "A")).okId()
        val trashed = r.addTodo(testTodo(name = "B")).okId()
        r.removeTodo(trashed).assertOk()

        // 活跃条目不允许绕过软删直接彻底删除
        assertTrue(r.purgeTodo(live).assertErr() is TodoError.NotFound)

        r.purgeTodo(trashed).assertOk()
        assertTrue(r.deletedTodos.value.isEmpty())
        assertEquals(listOf("A"), r.todos.value.map { it.name })
        assertTrue(r.purgeTodo(trashed).assertErr() is TodoError.NotFound)
    }

    @Test
    fun `purgeTodos removes only trashed ids in one save`() = runBlocking {
        var saves = 0
        val inner = com.fangyi.classpp.data.store.FileTodoStore(folder.root)
        val counting = object : TodoStore {
            override fun load() = inner.load()
            override fun save(file: com.fangyi.classpp.data.model.TodoFile): StoreResult {
                saves++
                return inner.save(file)
            }
        }
        val r = repo(counting)
        val a = r.addTodo(testTodo(name = "A")).okId()
        val b = r.addTodo(testTodo(name = "B")).okId()
        val c = r.addTodo(testTodo(name = "C")).okId()
        r.removeTodos(listOf(b, c)).assertOk()

        // 活跃 id（a）与未知 id 宽容忽略：批量语义只删回收站里的 b
        saves = 0
        r.purgeTodos(listOf(a, b, "unknown")).assertOk()
        assertEquals("批量彻底删除只落盘一次", 1, saves)
        assertEquals(listOf("A"), r.todos.value.map { it.name })
        assertEquals(listOf(c), r.deletedTodos.value.map { it.id })

        // 没有可删条目时不写盘
        r.purgeTodos(listOf(a, "unknown")).assertOk()
        assertEquals(1, saves)

        val reopened = repo()
        assertEquals(listOf("A"), reopened.todos.value.map { it.name })
        assertEquals(listOf(c), reopened.deletedTodos.value.map { it.id })
    }

    @Test
    fun `purgeExpiredDeleted only removes trash at or past retention`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "A")).okId()
        r.removeTodo(id).assertOk()

        // 未满 30 天：原样保留
        now.advanceBy(TRASH_RETENTION_DAYS * DayMillis - 1)
        r.purgeExpiredDeleted().assertOk()
        assertEquals(listOf(id), r.deletedTodos.value.map { it.id })

        // 满 30 天：物理删除，重开仓库也不回来
        now.advanceBy(1)
        r.purgeExpiredDeleted().assertOk()
        assertTrue(r.deletedTodos.value.isEmpty())
        assertTrue(r.purgeTodo(id).assertErr() is TodoError.NotFound)
        assertTrue(repo().deletedTodos.value.isEmpty())
    }

    @Test
    fun `bootstrap purges trash that expired while app was closed`() = runBlocking {
        val r = repo()
        val expired = r.addTodo(testTodo(name = "过期")).okId()
        val kept = r.addTodo(testTodo(name = "未过期")).okId()
        r.removeTodo(expired).assertOk()
        now.advanceBy(DayMillis)
        r.removeTodo(kept).assertOk()

        // 重开时按各自的删除时刻分别判定：先删的满期、后删的还差一天
        now.advanceBy((TRASH_RETENTION_DAYS - 1) * DayMillis)
        val reopened = repo()
        assertEquals(listOf(kept), reopened.deletedTodos.value.map { it.id })
    }

    @Test
    fun `exportTodos excludes recently deleted`() = runBlocking {
        val r = repo()
        val a = r.addTodo(testTodo(name = "已删除待办")).okId()
        r.addTodo(testTodo(name = "活跃待办")).okId()
        r.removeTodo(a).assertOk()

        val json = r.exportTodos().okText()
        assertTrue(json.contains("活跃待办"))
        assertFalse(json.contains("已删除待办"))
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
    fun `setOccurrenceCompleted maintains per-occurrence set`() = runBlocking {
        now.millis = millisOf(IsoDate.parse("2026-03-20"))
        val r = repo()
        val id = r.addTodo(
            testTodo(
                name = "周报",
                dates = listOf(IsoDate.parse("2026-03-02")),
                repeat = RepeatRule(type = RepeatType.Weekly),
            ),
        ).okId()

        r.setOccurrenceCompleted(id, IsoDate.parse("2026-03-09"), true).assertOk()
        r.setOccurrenceCompleted(id, IsoDate.parse("2026-03-16"), true).assertOk()
        assertEquals(
            listOf("2026-03-09", "2026-03-16"),
            r.todos.value.single { it.id == id }.completedOccurrences.map { it.toString() },
        )

        r.setOccurrenceCompleted(id, IsoDate.parse("2026-03-09"), false).assertOk()
        assertEquals(
            listOf("2026-03-16"),
            r.todos.value.single { it.id == id }.completedOccurrences.map { it.toString() },
        )
    }

    @Test
    fun `overdue occurrences settle into set on write`() = runBlocking {
        // 时钟推到 2026-03-20：锚 3/2（周一）的每周待办已错过 3/2、3/9、3/16 三期
        now.millis = millisOf(IsoDate.parse("2026-03-20"))
        val r = repo()
        val id = r.addTodo(
            testTodo(
                name = "周报",
                dates = listOf(IsoDate.parse("2026-03-02")),
                repeat = RepeatRule(type = RepeatType.Weekly),
            ),
        ).okId()

        assertEquals(
            listOf("2026-03-02", "2026-03-09", "2026-03-16"),
            r.todos.value.single { it.id == id }.overdueOccurrences.map { it.toString() },
        )

        // 再写入（改名）不重复计：集合按日期去重
        r.updateTodo(
            testTodo(
                id = id,
                name = "周报v2",
                dates = listOf(IsoDate.parse("2026-03-02")),
                repeat = RepeatRule(type = RepeatType.Weekly),
            ),
        ).assertOk()
        assertEquals(3, r.todos.value.single { it.id == id }.overdueOccurrences.size)
    }

    @Test
    fun `late completion keeps overdue and uncomplete never double counts`() = runBlocking {
        now.millis = millisOf(IsoDate.parse("2026-03-20"))
        val r = repo()
        val id = r.addTodo(
            testTodo(
                name = "周报",
                dates = listOf(IsoDate.parse("2026-03-02")),
                repeat = RepeatRule(type = RepeatType.Weekly),
            ),
        ).okId()
        assertEquals(3, r.todos.value.single { it.id == id }.overdueOccurrences.size)

        // 3/24 迟到补完成 3/16 这期：拖欠不消失（3/2、3/9、3/16 留在逾期集合），新错的 3/23 入集
        now.millis = millisOf(IsoDate.parse("2026-03-24"))
        r.setOccurrenceCompleted(id, IsoDate.parse("2026-03-16"), true).assertOk()
        assertEquals(
            listOf("2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23"),
            r.todos.value.single { it.id == id }.overdueOccurrences.map { it.toString() },
        )

        // 3/29 准点前完成 3/30 这期（提前勾掉未来期）
        now.millis = millisOf(IsoDate.parse("2026-03-29"))
        r.setOccurrenceCompleted(id, IsoDate.parse("2026-03-30"), true).assertOk()

        // 4/1 取消 3/16 的完成：该期早已在逾期集合，不重复计账
        now.millis = millisOf(IsoDate.parse("2026-04-01"))
        r.setOccurrenceCompleted(id, IsoDate.parse("2026-03-16"), false).assertOk()
        assertEquals(
            listOf("2026-03-02", "2026-03-09", "2026-03-16", "2026-03-23"),
            r.todos.value.single { it.id == id }.overdueOccurrences.map { it.toString() },
        )
        assertEquals(
            listOf("2026-03-30"),
            r.todos.value.single { it.id == id }.completedOccurrences.map { it.toString() },
        )
    }

    @Test
    fun `legacy whole-todo completion of repeat migrates to occurrence set`() = runBlocking {
        now.millis = millisOf(IsoDate.parse("2026-03-20"))
        val r = repo()
        val id = r.addTodo(
            testTodo(
                name = "周报",
                dates = listOf(IsoDate.parse("2026-03-02")),
                repeat = RepeatRule(type = RepeatType.Weekly),
                completed = true,
            ),
        ).okId()

        val todo = r.todos.value.single { it.id == id }
        assertEquals(false, todo.completed)
        assertEquals(listOf("2026-03-02"), todo.completedOccurrences.map { it.toString() })
        assertNull(todo.completedAtMillis)
    }

    @Test
    fun `repeat end condition sanitizes dangling and out of range values`() = runBlocking {
        val r = repo()
        val anchor = IsoDate.parse("2026-03-02")
        val end = IsoDate.parse("2026-06-01")

        // 无重复：结束条件不参与语义，强制归「永不」并清空两参
        val noneId = r.addTodo(
            testTodo(
                name = "无重复",
                dates = listOf(anchor),
                repeat = RepeatRule(
                    type = RepeatType.None,
                    endType = RepeatEndType.Date,
                    endDate = end,
                    endCount = 3,
                ),
            ),
        ).okId()

        // 日期档缺日期：悬挂态回退「永不」
        val danglingId = r.addTodo(
            testTodo(
                name = "悬挂日期",
                dates = listOf(anchor),
                repeat = RepeatRule(type = RepeatType.Daily, endType = RepeatEndType.Date),
            ),
        ).okId()

        // 次数档：越界夹紧 1..99，缺值兜底默认 5；日期/次数两参随档位互斥清空
        val countId = r.addTodo(
            testTodo(
                name = "次数夹紧",
                dates = listOf(anchor),
                repeat = RepeatRule(
                    type = RepeatType.Weekly,
                    endType = RepeatEndType.Count,
                    endCount = 500,
                    endDate = end,
                ),
            ),
        ).okId()

        // 预设档位保留合法的日期结束条件（结束条件随任一重复档位存在）
        val dateEndId = r.addTodo(
            testTodo(
                name = "预设带日期结束",
                dates = listOf(anchor),
                repeat = RepeatRule(type = RepeatType.Weekly, endType = RepeatEndType.Date, endDate = end),
            ),
        ).okId()

        val savedNone = r.todos.value.single { it.id == noneId }
        assertEquals(RepeatEndType.Never, savedNone.repeat.endType)
        assertNull(savedNone.repeat.endDate)
        assertNull(savedNone.repeat.endCount)

        assertEquals(RepeatEndType.Never, r.todos.value.single { it.id == danglingId }.repeat.endType)

        val savedCount = r.todos.value.single { it.id == countId }
        assertEquals(RepeatEndType.Count, savedCount.repeat.endType)
        assertEquals(99, savedCount.repeat.endCount)
        assertNull(savedCount.repeat.endDate)

        val savedDateEnd = r.todos.value.single { it.id == dateEndId }
        assertEquals(RepeatEndType.Date, savedDateEnd.repeat.endType)
        assertEquals(end, savedDateEnd.repeat.endDate)
        assertNull(savedDateEnd.repeat.endCount)
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

    @Test
    fun `addStep trims title, mints id, rejects blank and survives reopen`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "作业", steps = listOf(testStep("s1")))).okId()

        r.addStep(id, "  交材料  ").assertOk()
        val steps = r.todos.value.single().steps
        assertEquals(listOf("做第 1 题", "交材料"), steps.map { it.title })
        // 现铸 id：非空且与既有步骤不撞
        assertEquals(2, steps.map { it.id }.toSet().size)
        assertTrue(steps[1].id.isNotBlank())

        // 空白标题拒绝且不落库（同校验器的 BlankTitle 规则；索引 = 追加位置）
        val blankErr = r.addStep(id, "   ").assertErr()
        assertEquals(TodoError.StepInvalid(2, TodoStepReason.BlankTitle), blankErr)
        assertEquals(2, r.todos.value.single().steps.size)

        assertTrue(r.addStep("ghost", "x").assertErr() is TodoError.NotFound)

        assertEquals(2, repo().todos.value.single().steps.size)
    }

    @Test
    fun `setCountdown persists and is cleared when deadline disappears`() = runBlocking {
        val r = repo()
        val deadline = IsoDate.of(2026, 6, 18)
        val id = r.addTodo(testTodo(name = "作业", deadlineDate = deadline, deadlineMinute = 480)).okId()

        r.setCountdown(id, enabled = true).assertOk()
        assertEquals(true, r.todos.value.single().countdown)
        assertEquals(true, repo().todos.value.single().countdown) // 落盘可重开

        // 清掉截止日期：倒计时不变式回 false（不留「无截止日期却开着倒计时」的悬挂态）
        val noDeadline = r.todos.value.single().copy(deadlineDate = null, deadlineMinute = null)
        r.updateTodo(noDeadline).assertOk()
        assertEquals(false, r.todos.value.single().countdown)

        // 无截止日期时开启：被归一化挡回 false
        r.setCountdown(id, enabled = true).assertOk()
        assertEquals(false, r.todos.value.single().countdown)

        assertTrue(r.setCountdown("ghost", enabled = true).assertErr() is TodoError.NotFound)
    }

    // ---------- 分组 ----------

    @Test
    fun `setGroups persists and survives reopen`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业", pinned = true), TodoGroup("日常琐事"))).assertOk()

        val reopened = repo()
        assertEquals(listOf("作业", "日常琐事"), reopened.groups.value.map { it.name })
        assertEquals(listOf(true, false), reopened.groups.value.map { it.pinned })
    }

    @Test
    fun `setGroups normalizes blank trim dedupe and pinned prefix`() = runBlocking {
        val r = repo()
        r.setGroups(
            listOf(
                TodoGroup("作业", pinned = true),
                TodoGroup("   "),
                TodoGroup("  日常  "),
                TodoGroup("日常"), // trim 后与上一条重名 → 保留首个
                TodoGroup("灵感", pinned = true),
            ),
        ).assertOk()

        // 空白丢弃、trim、去重；置顶连续前缀由稳定划分维持（置顶项保持原相对序在前）
        assertEquals(listOf("作业", "灵感", "日常"), r.groups.value.map { it.name })
        assertEquals(listOf(true, true, false), r.groups.value.map { it.pinned })
    }

    @Test
    fun `removing group cascades its todos to ungrouped in same commit`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业"), TodoGroup("日常琐事"))).assertOk()
        val a = r.addTodo(testTodo(name = "A", group = "作业")).okId()
        val b = r.addTodo(testTodo(name = "B", group = "日常琐事")).okId()
        r.addTodo(testTodo(name = "C")).okId()

        r.setGroups(listOf(TodoGroup("日常琐事"))).assertOk()

        val byName = r.todos.value.associateBy { it.name }
        assertNull(byName.getValue("A").group)
        assertEquals("日常琐事", byName.getValue("B").group)
        assertNull(byName.getValue("C").group)

        // 原子性：级联结果与分组表同一份文件落盘，重载后一致
        val reopened = repo()
        assertEquals(listOf("日常琐事"), reopened.groups.value.map { it.name })
        assertNull(reopened.todos.value.single { it.id == a }.group)
        assertEquals("日常琐事", reopened.todos.value.single { it.id == b }.group)
    }

    @Test
    fun `renaming group keeps its todos following the renames mapping`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业"), TodoGroup("日常琐事"))).assertOk()
        val a = r.addTodo(testTodo(name = "A", group = "作业")).okId()
        val b = r.addTodo(testTodo(name = "B", group = "日常琐事")).okId()

        // 整表替换 + 重命名映射：该组待办的归属同一次提交跟随到新名，不因旧名不在新表
        // 被级联成未分组（改名丢归属的回归防线）
        r.setGroups(
            listOf(TodoGroup("日常作业"), TodoGroup("日常琐事")),
            renames = mapOf("作业" to "日常作业"),
        ).assertOk()

        assertEquals(listOf("日常作业", "日常琐事"), r.groups.value.map { it.name })
        assertEquals("日常作业", r.todos.value.single { it.id == a }.group)
        assertEquals("日常琐事", r.todos.value.single { it.id == b }.group)

        val reopened = repo()
        assertEquals(listOf("日常作业", "日常琐事"), reopened.groups.value.map { it.name })
        assertEquals("日常作业", reopened.todos.value.single { it.id == a }.group)
    }

    @Test
    fun `rename mapping to a missing new group still cascades to ungrouped`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业"))).assertOk()
        val id = r.addTodo(testTodo(name = "A", group = "作业")).okId()

        // 防御：映射目标不在新表（调用方 bug）时按级联规则兜底回未分组，不留悬挂引用
        r.setGroups(emptyList(), renames = mapOf("作业" to "新组")).assertOk()

        assertTrue(r.groups.value.isEmpty())
        assertNull(r.todos.value.single { it.id == id }.group)
    }

    @Test
    fun `todo writes do not lose groups`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业"))).assertOk()
        val id = r.addTodo(testTodo(name = "A", group = "作业")).okId()

        // 任意待办写路径都不得清空分组表（TodoFile copy 改造的回归防线）
        r.setCompleted(id, completed = true).assertOk()
        assertEquals(listOf("作业"), r.groups.value.map { it.name })
        r.updateTodo(testTodo(id = id, name = "A2", group = "作业")).assertOk()
        assertEquals(listOf("作业"), r.groups.value.map { it.name })
        r.removeTodo(id).assertOk()
        assertEquals(listOf("作业"), r.groups.value.map { it.name })
    }

    @Test
    fun `renameTagInTodos rewrites active and deleted todos with dedupe`() = runBlocking {
        val r = repo()
        r.addTodo(testTodo(name = "无标签")).okId()
        val a = r.addTodo(testTodo(name = "A", tags = listOf("紧急", "作业"))).okId()
        val b = r.addTodo(testTodo(name = "B", tags = listOf("紧急", "本周"))).okId()
        val c = r.addTodo(testTodo(name = "C", tags = listOf("紧急", "作业"))).okId()
        r.removeTodo(c).assertOk()

        r.renameTagInTodos("紧急", "作业").assertOk()

        val live = r.todos.value.associateBy { it.name }
        assertEquals(emptyList<String>(), live.getValue("无标签").tags)
        // 与既有「作业」叠加时 distinct 去重，不产生重复标签
        assertEquals(listOf("作业"), live.getValue("A").tags)
        assertEquals(listOf("作业", "本周"), live.getValue("B").tags)
        // 回收站条目同批回写（恢复后引用不悬空），重载后一致
        val reopened = repo()
        assertEquals(listOf("作业"), reopened.deletedTodos.value.single { it.id == c }.tags)
        reopened.restoreTodo(c).assertOk()
        assertEquals(listOf("作业"), reopened.todos.value.single { it.id == c }.tags)
    }

    @Test
    fun `renameTagInTodos is lenient on blank and unknown tags`() = runBlocking {
        val r = repo()
        val id = r.addTodo(testTodo(name = "A", tags = listOf("紧急"))).okId()

        // 批量语义宽容：空名/未知标签都是无操作（调用方 UI 已校验非空 + 不重名）
        r.renameTagInTodos("紧急", "   ").assertOk()
        r.renameTagInTodos("不存在", "新名").assertOk()
        assertEquals(listOf("紧急"), r.todos.value.single { it.id == id }.tags)
    }

    @Test
    fun `removeTagsFromTodos unbinds tags and keeps todos`() = runBlocking {
        val r = repo()
        val a = r.addTodo(testTodo(name = "A", tags = listOf("紧急", "作业"))).okId()
        val b = r.addTodo(testTodo(name = "B", tags = listOf("紧急"))).okId()
        val c = r.addTodo(testTodo(name = "C", tags = listOf("本周"))).okId()
        r.removeTodo(b).assertOk()

        r.removeTagsFromTodos(setOf("紧急", "本周")).assertOk()

        // 只解绑标签，待办本身（活跃 + 回收站条目）保留
        val live = r.todos.value.associateBy { it.name }
        assertEquals(listOf("作业"), live.getValue("A").tags)
        assertTrue(r.deletedTodos.value.single { it.id == b }.tags.isEmpty())
        assertEquals(emptyList<String>(), live.getValue("C").tags)
        // 未知标签宽容无操作：报 Ok 且现有标签不受影响
        r.removeTagsFromTodos(setOf("不存在")).assertOk()
        assertEquals(listOf("作业"), r.todos.value.single { it.id == a }.tags)
    }

    @Test
    fun `normalize clears unknown blank or stale group reference`() = runBlocking {
        val r = repo()
        r.setGroups(listOf(TodoGroup("作业"))).assertOk()

        // 引用不存在的组 → 未分组
        val a = r.addTodo(testTodo(name = "A", group = "不存在")).okId()
        assertNull(r.todos.value.single { it.id == a }.group)
        // 合法引用保留
        val b = r.addTodo(testTodo(name = "B", group = "作业")).okId()
        assertEquals("作业", r.todos.value.single { it.id == b }.group)
        // 更新时携带已删除/不存在的组名 → 清回未分组
        r.updateTodo(testTodo(id = b, name = "B2", group = "已删组")).assertOk()
        assertNull(r.todos.value.single { it.id == b }.group)
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
