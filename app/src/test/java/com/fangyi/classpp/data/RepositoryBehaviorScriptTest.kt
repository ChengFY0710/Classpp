package com.fangyi.classpp.data

import com.fangyi.classpp.data.model.IsoDate
import com.fangyi.classpp.data.model.Parity
import com.fangyi.classpp.data.model.WeekPattern
import com.fangyi.classpp.data.model.WeekSegment
import com.fangyi.classpp.data.model.defaultSlotsFor
import com.fangyi.classpp.data.store.FileScheduleStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 端到端行为剧本：建课 → 冲突拒绝 → 设置拒绝 → 周计算 → 灰显开关 →
 * 导出/导入 → 存储损坏降级。全程走真实磁盘（临时目录）+ FakeClock。
 */
class RepositoryBehaviorScriptTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = FakeClock(IsoDate.parse("2026-03-02"))

    private suspend fun open() = ScheduleRepository.create(FileScheduleStore(folder.root), clock)

    private fun <T> ReadResult<T>.ok(): T {
        assertTrue("expected Ok, got ${(this as? ReadResult.Err)?.error?.message}", this is ReadResult.Ok)
        return (this as ReadResult.Ok).value
    }

    private fun OpResult.okOrThrow() {
        assertTrue("expected Ok, got $this", this is OpResult.Ok)
    }

    private fun OpResult.errOrThrow(): ScheduleError {
        assertTrue("expected Err, got $this", this is OpResult.Err)
        return (this as OpResult.Err).error
    }

    @Test
    fun fullJourney() = runBlocking {
        // 1. 创建 16 周、5 天制课表 → 自动激活
        val idA = open().createSchedule("2026春", TestTermStart, TestTermEnd).ok()
        var repo = open()
        assertEquals(idA, repo.activeScheduleId.value)
        assertEquals(LoadState.Ready, repo.loadState.value)

        // 2. 加课（周三 slot1-2 跨节），同格同周再加 → GridConflict
        val calculus = testCourse(
            id = "calc", name = "微积分", day = 3, startSlot = 1, span = 2,
            weeks = WeekPattern.everyWeek(16),
        )
        repo.upsertCourse(idA, calculus).okOrThrow()
        var err = repo.upsertCourse(
            idA,
            testCourse(id = "overlap", day = 3, startSlot = 2, span = 1),
        ).errOrThrow()
        assertTrue(err is ScheduleError.GridConflict)

        // 3. 单双周：同格双周课与 ALL 周课仍冲突；换到不同日则通过
        err = repo.upsertCourse(
            idA,
            testCourse(
                id = "odd-cell", day = 3, startSlot = 2, span = 1,
                weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD))),
            ),
        ).errOrThrow()
        assertTrue("ALL 与 ODD 相容仍冲突", err is ScheduleError.GridConflict)

        repo.upsertCourse(
            idA,
            testCourse(
                id = "odd-day4", day = 4, startSlot = 2, span = 1,
                weeks = WeekPattern(listOf(WeekSegment(1, 16, Parity.ODD))),
            ),
        ).okOrThrow()
        assertEquals(2, repo.schedules.value.single().courses.size)

        // 4. 尾部（第 4 节）有课时 5→3 节 → 拒绝且课程无损
        repo.upsertCourse(idA, testCourse(id = "tail", day = 5, startSlot = 4)).okOrThrow()
        err = repo.setSlots(idA, defaultSlotsFor(3)).errOrThrow()
        assertTrue(err is ScheduleError.CoursesOutOfRange)
        assertEquals(3, repo.schedules.value.single().courses.size)
        assertEquals("磁盘同样无损", 3, open().schedules.value.single().courses.size)

        // 5. 当前周边界（学期 03-02 ~ 06-19，16 周）
        clock.date = IsoDate.parse("2026-03-02")
        assertEquals(1, repo.currentWeek(idA))
        clock.date = IsoDate.parse("2026-03-01")
        assertEquals(TermPosition.BeforeTerm, repo.termPosition(idA))
        clock.date = IsoDate.parse("2026-06-19")
        assertEquals(TermPosition.InTerm(16), repo.termPosition(idA))
        clock.date = IsoDate.parse("2026-06-20")
        assertEquals(TermPosition.AfterTerm, repo.termPosition(idA))
        clock.date = IsoDate.parse("2026-03-09") // 回到第 2 周供后续断言

        // 6. 第 2 周：奇偶周 active 分布 + 开关过滤
        val week2 = repo.visibleCoursesForWeek(idA, 2).associate { it.course.id to it.active }
        assertEquals(mapOf("calc" to true, "odd-day4" to false, "tail" to true), week2)
        repo.setShowInactiveCourses(idA, false).okOrThrow()
        assertEquals(listOf("calc", "tail"), repo.visibleCoursesForWeek(idA, 2).map { it.course.id })
        repo.setShowInactiveCourses(idA, true).okOrThrow()

        // 7. 导出含版本与 kind
        val json = repo.exportSchedule(idA).ok()
        assertTrue(json.contains("\"formatVersion\": 1"))
        assertTrue(json.contains("\"kind\": \"classpp.schedule\""))

        // 8. 导入 → 独立副本，不劫持激活
        val idB = repo.importSchedule(json).ok()
        assertNotEquals(idA, idB)
        assertEquals(idA, repo.activeScheduleId.value)
        assertEquals(2, repo.schedules.value.size)
        val idsA = repo.schedules.value.first { it.id == idA }.courses.map { it.id }.toSet()
        val idsB = repo.schedules.value.first { it.id == idB }.courses.map { it.id }.toSet()
        assertTrue(idsA.intersect(idsB).isEmpty())

        // 9. 存储损坏降级
        val main = java.io.File(folder.root, FileScheduleStore.MAIN_NAME)
        val bak = java.io.File(folder.root, FileScheduleStore.BAK_NAME)
        main.writeText("corrupted manually ~~~")

        repo = open()
        assertEquals(LoadState.RestoredFromBackup, repo.loadState.value)
        assertEquals("从 bak 无损恢复最近一次保存", 2, repo.schedules.value.size)

        // 再删 bak → 双坏 → 重置 + 取证文件保留
        main.writeText("corrupted again")
        bak.delete()
        repo = open()
        assertEquals(LoadState.ResetAfterCorruption, repo.loadState.value)
        assertTrue(repo.schedules.value.isEmpty())
        val quarantined = folder.root.listFiles { f -> f.name.startsWith(FileScheduleStore.CORRUPT_PREFIX) }
        // 单次损坏恢复时主文件原样保留（不隔离）；仅双坏路径隔离取证 → 只有 1 个
        assertEquals(1, quarantined?.size)

        // 重置后库仍可正常使用
        val idC = repo.createSchedule("重建", TestTermStart, TestTermEnd).ok()
        assertEquals(idC, open().activeScheduleId.value)
    }
}
