package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.defaultSlotsFor
import com.fangyi.classpp.data.store.LoadIssue
import com.fangyi.classpp.data.store.LoadOutcome
import com.fangyi.classpp.data.store.ScheduleStore
import com.fangyi.classpp.data.store.StoreResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScheduleRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock(IsoDate.parse("2026-03-02"))

    private suspend fun repo(store: ScheduleStore = com.fangyi.classpp.data.store.FileScheduleStore(folder.root)) =
        ScheduleRepository.create(store, clock)

    private suspend fun ReadResult<String>.okId(): String {
        assertTrue("expected Ok, got ${(this as? ReadResult.Err)?.error?.message}", this is ReadResult.Ok)
        return (this as ReadResult.Ok).value
    }

    private suspend fun OpResult.assertOk(): OpResult {
        assertTrue("expected Ok, got ${(this as? OpResult.Err)?.error?.message}", this is OpResult.Ok)
        return this
    }

    private suspend fun OpResult.assertErr(): ScheduleError {
        assertTrue("expected Err, got $this", this is OpResult.Err)
        return (this as OpResult.Err).error
    }

    // ---------- 创建 / 持久化 / 切换 ----------

    @Test
    fun `create persists and survives reopen`() = runBlocking {
        val id = repo().createSchedule("2026春", TestTermStart, TestTermEnd).okId()

        val reopened = repo()
        assertEquals(1, reopened.schedules.value.size)
        assertEquals(id, reopened.activeScheduleId.value)
        assertEquals(id, reopened.activeSchedule.value?.id)
        assertEquals(LoadState.Ready, reopened.loadState.value)
    }

    @Test
    fun `first schedule auto-activates later one does not`() = runBlocking {
        val r = repo()
        val a = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        assertEquals(a, r.activeScheduleId.value)

        r.createSchedule("B", TestTermStart, TestTermEnd)
        assertEquals("创建第二份不应劫持激活状态", a, r.activeScheduleId.value)
    }

    @Test
    fun `delete active falls to first remaining and delete all nulls active`() = runBlocking {
        val r = repo()
        val a = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        val b = r.createSchedule("B", TestTermStart, TestTermEnd).okId()
        r.setActiveSchedule(a).assertOk()

        r.deleteSchedule(a).assertOk()
        assertEquals(b, r.activeScheduleId.value)

        r.deleteSchedule(b).assertOk()
        assertNull(r.activeScheduleId.value)
        assertTrue(r.schedules.value.isEmpty())
        // 文件保留（可再次创建）
        val c = repo().createSchedule("C", TestTermStart, TestTermEnd).okId()
        assertNotNull(c)
    }

    @Test
    fun `unknown ids return NotFound`() = runBlocking {
        val r = repo()
        r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        assertTrue(r.setActiveSchedule("nope").assertErr() is ScheduleError.NotFound)
        assertTrue(r.renameSchedule("nope", "x").assertErr() is ScheduleError.NotFound)
        assertTrue(r.deleteSchedule("nope").assertErr() is ScheduleError.NotFound)
        assertTrue(r.removeCourse("nope", "c").assertErr() is ScheduleError.NotFound)
    }

    @Test
    fun `rename validates blank name`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.renameSchedule(id, "新名字").assertOk()
        assertEquals("新名字", r.activeSchedule.value?.name)
        assertTrue(r.renameSchedule(id, "  ").assertErr() is ScheduleError.InvalidScheduleName)
    }

    @Test
    fun `persist failure returns PersistFailed and leaves memory untouched`() = runBlocking {
        val good = com.fangyi.classpp.data.store.FileScheduleStore(folder.root)
        val r = ScheduleRepository.create(good, clock)
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()

        val failing = object : ScheduleStore {
            override fun load(): LoadOutcome = LoadOutcome.Fresh(
                com.fangyi.classpp.data.model.ScheduleFile(),
                LoadIssue.None,
            )
            override fun save(file: com.fangyi.classpp.data.model.ScheduleFile): StoreResult =
                StoreResult.Failed("disk full")
        }
        val r2 = ScheduleRepository.create(failing, clock)
        // r2 载入空库；尝试写入失败
        val err = r2.createSchedule("B", TestTermStart, TestTermEnd)
        assertTrue(err is ReadResult.Err)
        assertTrue((err as ReadResult.Err).error is ScheduleError.PersistFailed)
        assertTrue("失败后内存不得出现新课表", r2.schedules.value.isEmpty())

        // r 自身内存不受影响
        assertEquals(id, r.activeScheduleId.value)
    }

    // ---------- 设置变更拒绝策略 ----------

    @Test
    fun `setSlots shrinking with tail courses is rejected losslessly`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "c4", startSlot = 4)).assertOk()
        r.upsertCourse(id, testCourse(id = "c5", startSlot = 5)).assertOk()

        val err = r.setSlots(id, defaultSlotsFor(3)).assertErr()
        assertTrue(err is ScheduleError.CoursesOutOfRange)
        assertEquals(2, (err as ScheduleError.CoursesOutOfRange).affected.size)

        // 内存与磁盘都零变更
        assertEquals(5, r.schedules.value.single().slotCount)
        assertEquals(2, r.schedules.value.single().courses.size)
        val reopened = repo()
        assertEquals(5, reopened.schedules.value.single().slotCount)
        assertEquals(2, reopened.schedules.value.single().courses.size)
    }

    @Test
    fun `setDaysPerWeek 7 to 5 with weekend course is rejected losslessly`() = runBlocking {
        val r = repo()
        val id = r.createSchedule(
            "A", TestTermStart, TestTermEnd7,
            daysPerWeek = 7,
        ).okId()
        r.upsertCourse(id, testCourse(id = "weekend", day = 6)).assertOk()

        val err = r.setDaysPerWeek(id, 5).assertErr()
        assertTrue(err is ScheduleError.CoursesOutOfRange)
        assertEquals(1, (err as ScheduleError.CoursesOutOfRange).affected.size)
        assertEquals(7, r.schedules.value.single().daysPerWeek)

        // 无周末课时可以正常切换
        r.removeCourse(id, "weekend").assertOk()
        r.setDaysPerWeek(id, 5).assertOk()
        assertEquals(5, r.schedules.value.single().daysPerWeek)
    }

    @Test
    fun `setTerm shortening beyond course weeks is rejected losslessly`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(
            id,
            testCourse(id = "c", weeks = com.fangyi.classpp.data.model.WeekPattern(
                listOf(com.fangyi.classpp.data.model.WeekSegment(14, 16)),
            )),
        ).assertOk()

        // 缩到 10 周：2026-03-02 + 9*7+4 = 2026-05-08 周五
        val err = r.setTerm(id, TestTermStart, IsoDate.parse("2026-05-08")).assertErr()
        assertTrue(err is ScheduleError.CoursesOutOfRange)
        assertEquals(1, (err as ScheduleError.CoursesOutOfRange).affected.size)
        assertEquals(TestTermEnd, r.schedules.value.single().termEnd)
    }

    @Test
    fun `invalid term settings rejected with settings error not CoursesOutOfRange`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        // 周二起始
        val err = r.setTerm(id, IsoDate.parse("2026-03-03"), TestTermEnd).assertErr()
        assertTrue(err is ScheduleError.TermNotMonday)
    }

    @Test
    fun `setShowInactiveCourses always succeeds`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        assertEquals(true, r.schedules.value.single().showInactiveCourses)
        r.setShowInactiveCourses(id, false).assertOk()
        assertEquals(false, r.schedules.value.single().showInactiveCourses)
        assertEquals(false, repo().schedules.value.single().showInactiveCourses)
    }

    // ---------- 课程 CRUD / 校验 ----------

    @Test
    fun `upsert inserts then replaces by id`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "c1", name = "原名")).assertOk()
        r.upsertCourse(id, testCourse(id = "c1", name = "新名")).assertOk()
        assertEquals(1, r.schedules.value.single().courses.size)
        assertEquals("新名", r.schedules.value.single().courses.single().name)
    }

    @Test
    fun `upsert with blank id gets generated uuid`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "", name = "新课")).assertOk()
        val saved = r.schedules.value.single().courses.single()
        assertTrue(saved.id.isNotBlank())
    }

    @Test
    fun `grid conflict rejected on upsert`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "a", day = 3, startSlot = 1, span = 2)).assertOk()
        val err = r.upsertCourse(id, testCourse(id = "b", day = 3, startSlot = 2)).assertErr()
        assertTrue(err is ScheduleError.GridConflict)
        assertEquals(1, r.schedules.value.single().courses.size)
    }

    @Test
    fun `slot times are normalized on input`() = runBlocking {
        val r = repo()
        val id = r.createSchedule(
            "A", TestTermStart, TestTermEnd,
            slots = listOf(TimeSlotDef("08:00", "09:40")),
        ).okId()
        assertEquals("8:00", r.schedules.value.single().slots.single().startTime)
        assertEquals("9:40", r.schedules.value.single().slots.single().endTime)
    }

    @Test
    fun `invalid schedule rejected on create`() = runBlocking {
        val r = repo()
        // 周三起始
        val res = r.createSchedule("X", IsoDate.parse("2026-03-04"), TestTermEnd)
        assertTrue(res is ReadResult.Err)
        assertTrue((res as ReadResult.Err).error is ScheduleError.TermNotMonday)
        assertTrue(r.schedules.value.isEmpty())
    }

    // ---------- 按周查询 ----------

    @Test
    fun `coursesForWeek marks active by parity`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "odd", weeks = oddWeeks())).assertOk()
        r.upsertCourse(id, testCourse(id = "even", day = 4, weeks = evenWeeks())).assertOk()

        val w1 = r.coursesForWeek(id, 1).associate { it.course.id to it.active }
        assertEquals(mapOf("odd" to true, "even" to false), w1)

        val w2 = r.coursesForWeek(id, 2).associate { it.course.id to it.active }
        assertEquals(mapOf("odd" to false, "even" to true), w2)

        // 越界周宽松返回空
        assertTrue(r.coursesForWeek(id, 0).isEmpty())
        assertTrue(r.coursesForWeek(id, 17).isEmpty())
        assertTrue(r.coursesForWeek("missing", 1).isEmpty())
    }

    @Test
    fun `visibleCoursesForWeek honours showInactiveCourses switch`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "odd", weeks = oddWeeks())).assertOk()
        r.upsertCourse(id, testCourse(id = "even", day = 4, weeks = evenWeeks())).assertOk()

        // 默认显示（含不活跃）
        assertEquals(2, r.visibleCoursesForWeek(id, 1).size)

        r.setShowInactiveCourses(id, false).assertOk()
        val visible = r.visibleCoursesForWeek(id, 1)
        assertEquals(1, visible.size)
        assertEquals("odd", visible.single().course.id)
        assertTrue(visible.single().active)
    }

    @Test
    fun `courseAt finds spanning course on every covered slot`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "span2", startSlot = 2, span = 2)).assertOk()

        assertNotNull(r.courseAt(id, week = 1, day = 3, slot = 2))
        assertNotNull(r.courseAt(id, week = 1, day = 3, slot = 3))
        assertNull(r.courseAt(id, week = 1, day = 3, slot = 4))
        assertNull(r.courseAt(id, week = 1, day = 2, slot = 2))
    }

    @Test
    fun `datesForWeek returns 5 or 7 dates`() = runBlocking {
        val r = repo()
        val id5 = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        assertEquals(5, r.datesForWeek(id5, 1).size)
        assertEquals(TestTermStart, r.datesForWeek(id5, 1).first())

        val id7 = r.createSchedule("B", TestTermStart, TestTermEnd7, daysPerWeek = 7).okId()
        assertEquals(7, r.datesForWeek(id7, 1).size)
        // 第 1 周周日 = termStart + 6
        assertEquals(TestTermStart + 6, r.datesForWeek(id7, 1).last())
        assertTrue(r.datesForWeek(id5, 99).isEmpty())
    }

    // ---------- 当前周 ----------

    @Test
    fun `termPosition boundaries`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId() // 03-02 ~ 06-19, 16 周

        clock.date = IsoDate.parse("2026-03-01")
        assertEquals(TermPosition.BeforeTerm, r.termPosition(id))
        assertNull(r.currentWeek(id))

        clock.date = TestTermStart
        assertEquals(TermPosition.InTerm(1), r.termPosition(id))
        assertEquals(1, r.currentWeek(id))

        clock.date = IsoDate.parse("2026-06-16") // 第 16 周周二
        assertEquals(TermPosition.InTerm(16), r.termPosition(id))

        clock.date = TestTermEnd // 06-19 周五 = 学期最后一天
        assertEquals(TermPosition.InTerm(16), r.termPosition(id))
        assertEquals(16, r.currentWeek(id))

        clock.date = IsoDate.parse("2026-06-20") // 次日
        assertEquals(TermPosition.AfterTerm, r.termPosition(id))
        assertNull(r.currentWeek(id))

        assertNull(r.termPosition("missing"))
    }

    // ---------- 分享 ----------

    @Test
    fun `export then import creates independent copy without switching active`() = runBlocking {
        val r = repo()
        val idA = r.createSchedule("2026春", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(idA, testCourse(id = "cA", name = "微积分")).assertOk()

        val json = r.exportSchedule(idA).okId()
        assertTrue(json.contains("classpp.schedule"))

        val idB = r.importSchedule(json).okId()
        assertNotEquals(idA, idB)
        assertEquals("导入不应切换激活课表", idA, r.activeScheduleId.value)
        assertEquals(2, r.schedules.value.size)

        // 重名自动后缀
        assertEquals("2026春 (导入)", r.schedules.value.first { it.id == idB }.name)

        // 课程 id 与源无交集
        val a = r.schedules.value.first { it.id == idA }
        val b = r.schedules.value.first { it.id == idB }
        assertTrue(a.courses.map { it.id }.none { it in b.courses.map { c -> c.id } })
        assertEquals("微积分", b.courses.single().name)
    }

    @Test
    fun `import invalid json leaves library untouched`() = runBlocking {
        val r = repo()
        r.createSchedule("A", TestTermStart, TestTermEnd).okId()

        val res = r.importSchedule("{{{ nope")
        assertTrue(res is ReadResult.Err)
        assertTrue((res as ReadResult.Err).error is ScheduleError.ImportFormatInvalid)
        assertEquals(1, r.schedules.value.size)
    }

    @Test
    fun `export unknown id returns NotFound`() = runBlocking {
        val r = repo()
        val res = r.exportSchedule("missing")
        assertTrue(res is ReadResult.Err)
        assertTrue((res as ReadResult.Err).error is ScheduleError.NotFound)
    }
}
