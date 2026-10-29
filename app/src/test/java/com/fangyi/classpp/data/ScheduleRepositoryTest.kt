package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.DEFAULT_SLOTS
import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Schedule
import com.fangyi.classpp.data.model.TimeSlotDef
import com.fangyi.classpp.data.model.appendSlot
import com.fangyi.classpp.data.model.defaultSlotsFor
import com.fangyi.classpp.data.model.previewSlotMerge
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
        assertTrue(r.replaceCourses("nope", emptyList()).assertErr() is ScheduleError.NotFound)
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
            override fun load(): LoadOutcome<com.fangyi.classpp.data.model.ScheduleFile> =
                LoadOutcome.Fresh(
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
    fun `setDaysPerWeek is lossless in both directions for weekend courses`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd7, daysPerWeek = 7).okId()
        r.upsertCourse(id, testCourse(id = "weekend", day = 6)).assertOk()

        // 7 → 5：课留着（只是 5 天视图不画），天数照改
        r.setDaysPerWeek(id, 5).assertOk()
        assertEquals(5, r.schedules.value.single().daysPerWeek)
        assertEquals(1, r.schedules.value.single().courses.size)
        assertEquals(6, r.schedules.value.single().courses.single().dayOfWeek)

        // 5 → 7：原样回来
        r.setDaysPerWeek(id, 7).assertOk()
        assertEquals(7, r.schedules.value.single().daysPerWeek)
        assertEquals(1, r.schedules.value.single().courses.size)
    }

    @Test
    fun `setDaysPerWeek 7 to 5 keeps weekend courses and the term end`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd7, daysPerWeek = 7).okId()
        r.upsertCourse(id, testCourse(id = "sat", day = 6)).assertOk()
        r.upsertCourse(id, testCourse(id = "sun", day = 7)).assertOk()

        // 切 5 天：不拒绝、不删课、也不改学期结束日（只改天数）
        r.setDaysPerWeek(id, 5).assertOk()

        val five = r.schedules.value.single()
        assertEquals(5, five.daysPerWeek)
        assertEquals(TestTermEnd7, five.termEnd)
        assertEquals(2, five.courses.size)
        assertEquals(setOf(6, 7), five.courses.map { it.dayOfWeek }.toSet())

        // 再切回 7 天：周六日两门原样还在
        r.setDaysPerWeek(id, 7).assertOk()
        val seven = r.schedules.value.single()
        assertEquals(7, seven.daysPerWeek)
        assertEquals(2, seven.courses.size)
        assertEquals(setOf(6, 7), seven.courses.map { it.dayOfWeek }.toSet())
    }

    @Test
    fun `weekend course may be added while already in the five day view`() = runBlocking {
        val r = repo()
        // 通过 7 天课表加一门周日课，再切到 5 天：那门课仍可被编辑（不会被校验挡回来）
        val id = r.createSchedule("A", TestTermStart, TestTermEnd7, daysPerWeek = 7).okId()
        val sunday = testCourse(id = "sun", day = 7, name = "工程训练")
        r.upsertCourse(id, sunday).assertOk()
        r.setDaysPerWeek(id, 5).assertOk()

        val stored = r.schedules.value.single().courses.single()
        r.upsertCourse(id, stored.copy(name = "工程训练 (改)")).assertOk()
        r.removeCourse(id, "sun").assertOk()

        assertEquals(0, r.schedules.value.single().courses.size)
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
        // 结束早于开始（开学日任意星期几都合法，不再有"必须周一"的设置错误）
        val err = r.setTerm(id, TestTermEnd, TestTermStart).assertErr()
        assertTrue(err is ScheduleError.TermRangeInvalid)
    }

    @Test
    fun `setTerm to a non-monday start succeeds and shifts week grid`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()

        // 开学改到周三 03-04（结束日同步平移 2 天 = 06-21，仍 16 周）
        r.setTerm(id, IsoDate.parse("2026-03-04"), IsoDate.parse("2026-06-21")).assertOk()

        val s = r.schedules.value.single()
        assertEquals(16, s.totalWeeks)
        // 第 1 周 = 03-04 所在日历周：周一 03-02（开学前）起
        assertEquals(IsoDate.parse("2026-03-02"), r.datesForWeek(id, 1).first())
        assertEquals(IsoDate.parse("2026-03-09"), r.datesForWeek(id, 2).first())
    }

    @Test
    fun `setTerm extending weeks keeps courses and raises totalWeeks`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "c")).assertOk()

        r.setTerm(id, TestTermStart, TestTermEnd + 14 * 7).assertOk()

        val s = r.schedules.value.single()
        assertEquals(30, s.totalWeeks)
        assertEquals(TestTermStart, s.termStart)
        assertEquals(1, s.courses.size)
        // 落盘后重开一致
        assertEquals(30, repo().schedules.value.single().totalWeeks)
    }

    @Test
    fun `setSlots appending sixth period succeeds`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()

        r.setSlots(id, appendSlot(DEFAULT_SLOTS)!!).assertOk()

        val s = r.schedules.value.single()
        assertEquals(6, s.slotCount)
        assertEquals(DEFAULT_SLOTS, s.slots.take(5))
        assertEquals(TimeSlotDef("21:20", "23:00"), s.slots.last())
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

    /**
     * 逐条 upsert 的写回在"原课缩周 + 同格新增交替课"这种合法草稿上会失败：
     * 新课先写时，仓库里的原课还占着全部周数，中间态非法 —— 这正是"新建交替课程后
     * 保存报『与微积分时间冲突』"的成因（正因如此，编辑态保存改用 [replaceCourses] 整份替换）。
     */
    @Test
    fun `course-by-course write-back rejects a legal draft on illegal mid-state`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "calc", name = "微积分", day = 1, startSlot = 1)).assertOk()

        // 草稿：微积分缩成单周，同格新增一门双周的交替课（整份草稿本身合法）
        val shrunken = testCourse(id = "calc", name = "微积分", day = 1, startSlot = 1, weeks = oddWeeks())
        val alternate = testCourse(id = "alt", name = "线性代数", day = 1, startSlot = 1, weeks = evenWeeks())
        assertTrue(
            ScheduleValidator
                .validateCourses(testSchedule(id = id, courses = listOf(shrunken, alternate)))
                .isEmpty(),
        )

        val err = r.upsertCourse(id, alternate).assertErr()
        assertTrue(err is ScheduleError.GridConflict)
        assertEquals("微积分", (err as ScheduleError.GridConflict).a.name)
    }

    // ---------- 整份替换（编辑态保存） ----------

    @Test
    fun `replaceCourses applies the whole draft and persists it`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "calc", name = "微积分", day = 1, startSlot = 1)).assertOk()

        val shrunken = testCourse(id = "calc", name = "微积分", day = 1, startSlot = 1, weeks = oddWeeks())
        val alternate = testCourse(id = "alt", name = "线性代数", day = 1, startSlot = 1, weeks = evenWeeks())
        r.replaceCourses(id, listOf(shrunken, alternate)).assertOk()

        assertEquals(listOf("calc", "alt"), r.activeSchedule.value?.courses?.map { it.id })
        // 落盘后重开一致
        assertEquals(listOf("calc", "alt"), repo().activeSchedule.value?.courses?.map { it.id })
    }

    @Test
    fun `replaceCourses rejects the whole draft and keeps memory untouched`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "calc", name = "微积分", day = 1, startSlot = 1)).assertOk()

        // 两门同格、周数重合 → 整份拒绝，仓库保持原样（不会留下半份草稿）
        val conflicting = listOf(
            testCourse(id = "a", name = "课A", day = 1, startSlot = 1),
            testCourse(id = "b", name = "课B", day = 1, startSlot = 1),
        )
        assertTrue(r.replaceCourses(id, conflicting).assertErr() is ScheduleError.GridConflict)
        assertEquals(listOf("calc"), r.activeSchedule.value?.courses?.map { it.id })
    }

    @Test
    fun `replaceCourses can empty the course list`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(id = "calc")).assertOk()

        r.replaceCourses(id, emptyList()).assertOk()
        assertTrue(r.activeSchedule.value!!.courses.isEmpty())
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
        // 结束早于开始
        val res = r.createSchedule("X", TestTermEnd, TestTermStart)
        assertTrue(res is ReadResult.Err)
        assertTrue((res as ReadResult.Err).error is ScheduleError.TermRangeInvalid)
        assertTrue(r.schedules.value.isEmpty())
    }

    @Test
    fun `non-monday start accepted on create with calendar-week math`() = runBlocking {
        val r = repo()
        // 周六 03-07 开学 ~ 周五 06-19 结束：第 1 周 = 03-02~03-08（仅周日上课），
        // 共 16 个日历周
        val id = r.createSchedule("X", IsoDate.parse("2026-03-07"), TestTermEnd).okId()

        val s = r.schedules.value.single()
        assertEquals(16, s.totalWeeks)
        assertEquals(IsoDate.parse("2026-03-02"), r.datesForWeek(id, 1).first())
        assertEquals(IsoDate.parse("2026-03-06"), r.datesForWeek(id, 1).last())  // 5 天视图：周一~周五
        assertEquals(IsoDate.parse("2026-06-15"), r.datesForWeek(id, 16).first())

        // 学期外/第 1 周的当前周判定也按日历周：03-01（开学前一天，周日）在前一周 → Before；
        // 03-05（开学前、但已在开学周）→ 第 1 周
        clock.date = IsoDate.parse("2026-03-01")
        assertEquals(TermPosition.BeforeTerm, r.termPosition(id))
        clock.date = IsoDate.parse("2026-03-05")
        assertEquals(TermPosition.InTerm(1), r.termPosition(id))
        clock.date = IsoDate.parse("2026-06-19")
        assertEquals(TermPosition.InTerm(16), r.termPosition(id))
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

        // 第 0 周（开学前的当今周）：全部课程出现、一律不活跃（置灰候选）
        val week0 = r.coursesForWeek(id, 0).associate { it.course.id to it.active }
        assertEquals(mapOf("odd" to false, "even" to false), week0)

        // 越界周宽松返回空
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

    @Test
    fun `pre term weeks are greyed and date the term aligned calendar`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId() // 03-02 周一开学
        r.upsertCourse(id, testCourse(id = "c")).assertOk()

        // 第 0 周 = 开学前一个日历周（02-23~02-27），第 -1 周再往前一周：
        // 日期带按 termStart 对齐的日历周取，与「今天」无关
        assertEquals(IsoDate.parse("2026-02-23"), r.datesForWeek(id, 0).first())
        assertEquals(IsoDate.parse("2026-02-27"), r.datesForWeek(id, 0).last())
        assertEquals(IsoDate.parse("2026-02-16"), r.datesForWeek(id, -1).first())

        // 开学前的周课程一律不活跃：开关开 = 全灰显示，关 = 隐藏
        assertTrue(r.coursesForWeek(id, 0).all { !it.active })
        assertTrue(r.coursesForWeek(id, -2).all { !it.active })
        assertEquals(1, r.visibleCoursesForWeek(id, 0).size)
        r.setShowInactiveCourses(id, false).assertOk()
        assertTrue(r.visibleCoursesForWeek(id, 0).isEmpty())
    }

    @Test
    fun `calendarWeek aligns to calendar weeks including pre term`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId() // 03-02 ~ 06-19, 16 周

        clock.date = IsoDate.parse("2026-02-11") // 开学前两周的周三 → 第 -2 周
        assertEquals(-2, r.calendarWeek(id))
        clock.date = IsoDate.parse("2026-02-25") // 开学前一周的周三 → 第 0 周
        assertEquals(0, r.calendarWeek(id))
        clock.date = TestTermStart
        assertEquals(1, r.calendarWeek(id))
        clock.date = IsoDate.parse("2026-06-20") // 末周周六仍属第 16 周
        assertEquals(16, r.calendarWeek(id))
        clock.date = IsoDate.parse("2026-06-22") // 下周一 → 学期后
        assertEquals(17, r.calendarWeek(id))
        assertNull(r.calendarWeek("missing"))
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

        // 结束周还没过完：同一日历周的周六/周日仍属第 16 周
        clock.date = IsoDate.parse("2026-06-20") // 次日（周六）
        assertEquals(TermPosition.InTerm(16), r.termPosition(id))
        assertEquals(16, r.currentWeek(id))

        clock.date = IsoDate.parse("2026-06-22") // 下周一，进入新的一周
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

    // ---------- 课程时间方案库 ----------

    @Test
    fun `addTimeScheme persists and survives reopen`() = runBlocking {
        val r = repo()
        val slots = listOf(TimeSlotDef("08:00", "9:30"), TimeSlotDef("10:00", "11:30"))
        r.addTimeScheme("上午两节", slots, 90).assertOk()

        assertEquals(1, r.timeSchemes.value.size)
        val saved = r.timeSchemes.value.single()
        assertEquals("上午两节", saved.name)
        // 时间文本归一化：08:00 → 8:00
        assertEquals(listOf(TimeSlotDef("8:00", "9:30"), TimeSlotDef("10:00", "11:30")), saved.slots)
        assertEquals(90, saved.slotDurationMinutes)

        val reopened = repo()
        assertEquals(1, reopened.timeSchemes.value.size)
        assertEquals("上午两节", reopened.timeSchemes.value.single().name)
    }

    @Test
    fun `addTimeScheme rejects blank name and invalid slots`() = runBlocking {
        val r = repo()

        assertTrue(
            r.addTimeScheme("  ", DEFAULT_SLOTS, 100).assertErr() is
                ScheduleError.InvalidTimeSchemeName,
        )
        val badOrder = listOf(TimeSlotDef("10:00", "11:00"), TimeSlotDef("9:00", "9:50"))
        assertTrue(
            r.addTimeScheme("坏方案", badOrder, 60).assertErr() is
                ScheduleError.SlotOrderInvalid,
        )
        assertTrue("非法方案不应落库", r.timeSchemes.value.isEmpty())
    }

    @Test
    fun `cancel edit rollback keeps schemes saved during editing`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        val baseline = r.snapshot()

        // 编辑期间：改课表名 + 保存方案（两者都即时落库）
        r.renameSchedule(id, "B").assertOk()
        r.addTimeScheme("上午两节", DEFAULT_SLOTS, 100).assertOk()

        // 取消编辑：课表按基线回滚，方案库保留（保存方案不受编辑保存/取消控制）
        r.restoreEditBaseline(baseline).assertOk()
        assertEquals("A", r.schedules.value.single().name)
        assertEquals(listOf("上午两节"), r.timeSchemes.value.map { it.name })

        // 保留同样落盘
        assertEquals(listOf("上午两节"), repo().timeSchemes.value.map { it.name })
    }

    @Test
    fun `renameTimeScheme updates name and persists`() = runBlocking {
        val r = repo()
        r.addTimeScheme("旧名", DEFAULT_SLOTS, 100).assertOk()
        val id = r.timeSchemes.value.single().id

        // 去首尾空白；与其他方案重名也允许（列表按 id 区分）
        r.addTimeScheme("另一套", DEFAULT_SLOTS, 90).assertOk()
        r.renameTimeScheme(id, "  新名  ").assertOk()
        assertEquals(listOf("新名", "另一套"), r.timeSchemes.value.map { it.name })
        assertEquals(listOf("新名", "另一套"), repo().timeSchemes.value.map { it.name })

        // 名称未变视为成功空操作
        r.renameTimeScheme(id, "新名").assertOk()
        assertEquals(listOf("新名", "另一套"), r.timeSchemes.value.map { it.name })
    }

    @Test
    fun `renameTimeScheme rejects blank name and unknown id`() = runBlocking {
        val r = repo()
        r.addTimeScheme("方案", DEFAULT_SLOTS, 100).assertOk()
        val id = r.timeSchemes.value.single().id

        assertTrue(
            r.renameTimeScheme(id, "   ").assertErr() is ScheduleError.InvalidTimeSchemeName,
        )
        assertTrue(r.renameTimeScheme("missing", "新名").assertErr() is ScheduleError.NotFound)
        assertEquals(listOf("方案"), r.timeSchemes.value.map { it.name })
    }

    @Test
    fun `deleteTimeScheme removes scheme and persists`() = runBlocking {
        val r = repo()
        r.addTimeScheme("待删", DEFAULT_SLOTS, 100).assertOk()
        r.addTimeScheme("保留", DEFAULT_SLOTS, 90).assertOk()
        val removed = r.timeSchemes.value.first { it.name == "待删" }

        r.deleteTimeScheme(removed.id).assertOk()
        assertEquals(listOf("保留"), r.timeSchemes.value.map { it.name })
        assertEquals(listOf("保留"), repo().timeSchemes.value.map { it.name })

        // 再删同一个 id：已不存在
        assertTrue(r.deleteTimeScheme(removed.id).assertErr() is ScheduleError.NotFound)
    }

    @Test
    fun `setSlotsAndDuration applies both atomically and persists`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        val slots = listOf(TimeSlotDef("8:00", "9:30"), TimeSlotDef("10:00", "11:30"))

        r.setSlotsAndDuration(id, slots, 90).assertOk()

        val updated = r.schedules.value.first { it.id == id }
        assertEquals(slots, updated.slots)
        assertEquals(90, updated.slotDurationMinutes)

        val reopened = repo()
        val persisted = reopened.schedules.value.first { it.id == id }
        assertEquals(slots, persisted.slots)
        assertEquals(90, persisted.slotDurationMinutes)
    }

    @Test
    fun `setSlotsAndDuration rejects out of range courses losslessly`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(startSlot = 5)).assertOk()

        val error = r.setSlotsAndDuration(id, listOf(TimeSlotDef("8:00", "9:30")), 90).assertErr()
        assertTrue(error is ScheduleError.CoursesOutOfRange)

        val untouched = r.schedules.value.first { it.id == id }
        assertEquals(DEFAULT_SLOTS, untouched.slots)
        assertEquals(100, untouched.slotDurationMinutes)
    }

    @Test
    fun `setSlotsDurationAndCourses applies slots duration and courses atomically and persists`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(span = 2)).assertOk()
        val smallSlots = listOf(
            TimeSlotDef("8:00", "8:45"),
            TimeSlotDef("8:55", "9:40"),
        )
        val before = r.schedules.value.first { it.id == id }
        val plan = previewSlotMerge(smallSlots, before.courses, before.daysPerWeek)
        assertTrue(plan.canMerge)

        r.setSlotsDurationAndCourses(id, plan.newSlots, plan.newDurationMinutes, plan.newCourses)
            .assertOk()

        val updated = r.schedules.value.first { it.id == id }
        assertEquals(plan.newSlots, updated.slots)
        assertEquals(plan.newDurationMinutes, updated.slotDurationMinutes)
        assertEquals(plan.newCourses, updated.courses)
        assertEquals(1, updated.courses.single().span)

        val reopened = repo().schedules.value.first { it.id == id }
        assertEquals(plan.newSlots, reopened.slots)
        assertEquals(plan.newCourses, reopened.courses)
    }

    @Test
    fun `setSlotsDurationAndCourses rejects out of range courses losslessly`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        r.upsertCourse(id, testCourse(startSlot = 5)).assertOk()
        val before = r.schedules.value.first { it.id == id }

        val error = r.setSlotsDurationAndCourses(
            id,
            listOf(TimeSlotDef("8:00", "9:30")),
            90,
            before.courses,
        ).assertErr()
        assertTrue(error is ScheduleError.CoursesOutOfRange)

        assertEquals(before, r.schedules.value.first { it.id == id })
    }

    @Test
    fun `setSlotsDurationAndCourses judges the passed courses not the saved ones`() = runBlocking {
        val r = repo()
        val id = r.createSchedule("A", TestTermStart, TestTermEnd).okId()
        // 仓库里的课还伸到第 5 节（DEFAULT_SLOTS 末节）
        r.upsertCourse(id, testCourse(startSlot = 5)).assertOk()
        val saved = r.schedules.value.first { it.id == id }
        // 编辑草稿已把同一门课改到第 2 节：缩到 2 节应通过，不该被旧跨度误报越界
        val shortened = saved.courses.map { it.copy(startSlot = 2, span = 1) }
        val twoSlots = listOf(TimeSlotDef("8:00", "9:30"), TimeSlotDef("10:00", "11:30"))

        r.setSlotsDurationAndCourses(id, twoSlots, 90, shortened).assertOk()

        val updated = r.schedules.value.first { it.id == id }
        assertEquals(twoSlots, updated.slots)
        assertEquals(90, updated.slotDurationMinutes)
        assertEquals(shortened, updated.courses)
        // 节表与课程同批落库：重启后仍是自洽的一套
        val reopened = repo().schedules.value.first { it.id == id }
        assertEquals(twoSlots, reopened.slots)
        assertEquals(shortened, reopened.courses)
    }
}
